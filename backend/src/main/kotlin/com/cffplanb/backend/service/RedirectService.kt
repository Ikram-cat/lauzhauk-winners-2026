package com.cffplanb.backend.service

import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.domain.LegMode
import com.cffplanb.backend.domain.SearchRequest
import com.cffplanb.backend.provider.FareQuote
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Situation { NO_DISRUPTION, DISRUPTION_DETECTED, UNKNOWN }

/**
 * [disruption].legIndex indexes the legs of the originally planned journey, which is only part of [recommendation]
 * when the recommendation is to stay on it. [fares] covers both the original and the replacement searches.
 */
data class RedirectPlan(
    val situation: Situation, val disruption: Disruption?, val disruptedLegs: Int,
    val recommendation: Journey?, val why: String?, val alternatives: List<Journey>,
    val fares: Map<String, FareQuote>?, val message: String
)

private val ZURICH = ZoneId.of("Europe/Zurich")
private val HM = DateTimeFormatter.ofPattern("HH:mm")
private fun OffsetDateTime.hm() = atZoneSameInstant(ZURICH).format(HM)
private fun mins(a: OffsetDateTime, b: OffsetDateTime) = Duration.between(a, b).toMinutes()
private fun norm(s: String?) = s?.uppercase()?.filter { it.isLetterOrDigit() }.orEmpty()

class RedirectService(
    private val search: JourneySearchService,
    private val detector: DisruptionDetector,
    private val clock: Clock
) {
    suspend fun plan(req: SearchRequest, pref: Preference, deadline: OffsetDateTime?, first: SearchResult.Success): RedirectPlan {
        val price = { q: FareQuote -> q.price(req.fareClass, req.discount) }
        var fares = first.fares
        fun fareOf(j: Journey) = fares?.get(j.id)?.let(price)
        val cmp = RecommendationService.comparator(pref, ::fareOf)
        fun meets(j: Journey) = deadline == null || !j.arrival.toInstant().isAfter(deadline.toInstant())

        val ranked = first.journeys.sortedWith(cmp)
        val ordered = ranked.filter(::meets) + ranked.filterNot(::meets)
        if (ordered.isEmpty()) return RedirectPlan(Situation.UNKNOWN, null, 0, null, null, emptyList(), fares, "No journeys found for this request.")

        val orig = ordered[0]
        val checkList = ordered.take(2)
        val checks = detector.detect(checkList, req.earliestDeparture)
        val checkOf = if (checks == null) emptyMap() else checkList.map { it.id }.zip(checks).toMap()
        val disruption = checkOf[orig.id]?.worst
        // [own] disrupts the planned journey itself; a [disruption] that is not own only informs ("the train you wanted is cancelled").
        val own = disruption?.takeIf { it.affectsJourney }
        val situation = when {
            disruption != null -> Situation.DISRUPTION_DETECTED
            checks == null || checkOf[orig.id]!!.unknown -> Situation.UNKNOWN
            else -> Situation.NO_DISRUPTION
        }

        fun usesAffected(j: Journey) = own != null && j.legs.any {
            it.mode == LegMode.TRANSIT && norm(it.serviceLabel) == norm(own!!.leg.serviceLabel) &&
                Math.abs(mins(it.departure, own.leg.departure)) <= 2
        }
        // Cancelled / missing journeys are dropped; delayed ones keep their journey-level arrival shifted by the delay.
        val pool = first.journeys.mapNotNull { j ->
            val d = checkOf[j.id]?.worst?.takeIf { it.affectsJourney }
            when {
                j.id != orig.id && usesAffected(j) -> null
                d == null -> j
                d.cancelled || d.basis == DisruptionBasis.SUPPRESSED_STOP -> null
                else -> (d.delayMinutes ?: 0).let { j.copy(arrival = j.arrival.plusMinutes(it.toLong()), durationMinutes = j.durationMinutes + it) }
            }
        }.toMutableList()

        if (own != null) {
            // Floor = when the traveller can be at the boarding station: now, or the previous leg's arrival.
            val atStation = if (own.legIndex == 0) req.earliestDeparture else orig.legs[own.legIndex - 1].arrival
            val now = OffsetDateTime.now(clock)
            val notBefore = if (atStation.isAfter(now)) atStation else now
            val repl = search.search(SearchRequest(own.leg.origin, req.destination, notBefore, req.fareClass, req.discount))
            if (repl is SearchResult.Success) {
                pool += repl.journeys.filterNot(::usesAffected)
                if (repl.fares != null) fares = (fares ?: emptyMap()) + repl.fares
            }
        }

        // The replacement search overlaps the original one; same legs under a different id are the same trip.
        val unique = pool.distinctBy { j -> j.legs.map { Triple(it.serviceLabel, it.departure.toInstant(), it.arrival.toInstant()) } }
        val eligible = unique.filter(::meets)
        val noFeasible = deadline != null && eligible.isEmpty()
        var final = if (noFeasible) unique.sortedBy { it.arrival.toInstant() } else eligible.sortedWith(cmp)
        // Stay-vs-reroute: a delayed (not cancelled) original is kept unless it misses the deadline or an alternative gains >= 5 min
        // (and, for delays <= 10 min, strictly more than 5 min). Cancelled originals are not in the pool, so they always reroute.
        val waitJ = if (own != null && !own.cancelled) unique.firstOrNull { it.id == orig.id } else null
        if (waitJ != null && !noFeasible && final.first().id != waitJ.id) {
            val gain = mins(final.first().arrival, waitJ.arrival)
            val delay = own!!.delayMinutes ?: 0
            if (meets(waitJ) && !(gain >= 5 && !(delay <= 10 && gain <= 5))) final = listOf(waitJ) + final.filter { it.id != waitJ.id }
        }
        val rec = final.firstOrNull()
        val alts = final.drop(1).take(3)
        val stay = own != null && rec != null && rec.id == orig.id
        val waitJourney = unique.firstOrNull { it.id == orig.id }

        val why = rec?.let { whyFor(it, orig, disruption, stay, alts.firstOrNull(), waitJourney, pref, deadline, noFeasible, ::fareOf) }
        val msg = messageFor(situation, disruption, checkOf[orig.id]?.count ?: 0, rec, stay, deadline, noFeasible)
        return RedirectPlan(situation, disruption, checkOf[orig.id]?.count ?: 0, rec, why, alts, fares, msg)
    }

    private fun whyFor(
        rec: Journey, orig: Journey, d: Disruption?, stay: Boolean, nextBest: Journey?, wait: Journey?,
        pref: Preference, deadline: OffsetDateTime?, noFeasible: Boolean, fareOf: (Journey) -> Double?
    ): String {
        if (noFeasible) return "closest to your deadline: arrives ${mins(deadline!!, rec.arrival)} min late"
        if (d == null) return when (pref) { Preference.FASTEST -> "fastest"; Preference.SIMPLEST -> "fewest changes"; Preference.CHEAPEST -> "best price" }
        val lab = d.leg.serviceLabel ?: "service"
        if (!d.affectsJourney) return "next departure after the cancelled $lab, ${mins(d.leg.departure, rec.departure)} min later"
        if (stay) {
            val delay = d.delayMinutes
            val longer = nextBest?.let { mins(orig.arrival, it.arrival) }
            return when {
                delay != null && longer != null && longer >= delay -> "waiting is faster: $delay min delay vs $longer min longer route"
                delay != null -> "$delay min late, no meaningful gain from changing"
                else -> "no live delay estimate for $lab, no meaningful gain from changing"
            }
        }
        val skips = if (d.basis == DisruptionBasis.SUPPRESSED_STOP) "skips $lab, which does not stop at ${d.leg.origin}" else "skips the ${if (d.cancelled) "cancelled" else "disrupted"} $lab"
        return when (pref) {
            Preference.FASTEST -> {
                val early = deadline?.let { mins(rec.arrival, it) }
                val faster = wait?.let { mins(rec.arrival, it.arrival) }
                when {
                    early != null && early > 0 -> "arrives $early min before your deadline"
                    early == 0L -> "arrives exactly at your deadline"
                    faster != null && faster > 0 -> "$faster min faster than waiting"
                    else -> skips
                }
            }
            Preference.SIMPLEST -> (orig.transfers - rec.transfers).let { if (it > 0) "$it fewer ${if (it == 1) "change" else "changes"}" else skips }
            Preference.CHEAPEST -> {
                val a = fareOf(rec); val b = fareOf(orig)
                when {
                    a != null && b != null && a < b -> String.format(Locale.ROOT, "CHF %.2f cheaper than the original", b - a)
                    a != null -> String.format(Locale.ROOT, "best price: CHF %.2f", a)
                    else -> skips
                }
            }
        }
    }

    private fun messageFor(s: Situation, d: Disruption?, count: Int, rec: Journey?, stay: Boolean, deadline: OffsetDateTime?, noFeasible: Boolean): String {
        val t = rec?.legs?.firstOrNull { it.mode == LegMode.TRANSIT }
        val hop = if (t == null) "leave at ${rec?.departure?.hm()}" else "${t.serviceLabel ?: "the next connection"} at ${t.departure.hm()}"
        val plat = t?.departurePlatform?.let { " from platform $it" } ?: ""
        val tail = when {
            rec == null -> null
            noFeasible -> "Nothing reaches your ${deadline!!.hm()} deadline — ${if (stay) "staying on it" else "the ${rec.departure.hm()} ${t?.serviceLabel ?: "connection"}"} arrives ${mins(deadline, rec.arrival)} min late."
            else -> null
        }
        val by = deadline?.let { mins(rec?.arrival ?: it, it) }
        val options: List<String> = when {
            rec == null -> listOf(if (d == null) "No journeys found for this request." else "${head(d)} and no alternative was found.")
            s != Situation.DISRUPTION_DETECTED && tail != null -> listOf(tail)
            s != Situation.DISRUPTION_DETECTED -> {
                val pre = if (s == Situation.UNKNOWN) "Live disruption data is unavailable — the planned best route is" else "No disruption detected — take"
                val early = if (by != null && by > 0) ", $by min before your ${deadline!!.hm()} deadline" else ""
                listOf("$pre $hop$plat, arriving ${rec.arrival.hm()}$early.", "$pre $hop, arriving ${rec.arrival.hm()}.")
            }
            else -> {
                val more = if (count > 1) " (+${count - 1} more disrupted)" else ""
                val h = head(d!!)
                if (stay && tail == null) listOf("$h$more — stay on it, it is still your best option, arriving ${rec.arrival.hm()}.", "$h — stay on it, arriving ${rec.arrival.hm()}.")
                else if (tail != null) listOf("$h$more. $tail", tail)
                else {
                    val end = if (by != null && by > 0) "you arrive $by min early" else if (by == 0L) "you arrive just in time" else "arriving ${rec.arrival.hm()}"
                    listOf("$h$more — take $hop$plat, $end.", "$h — take $hop, $end.", "$h — take $hop.")
                }
            }
        }
        return options.firstOrNull { it.length <= 200 } ?: options.last().take(199) + "…"
    }

    private fun head(d: Disruption): String {
        val lab = d.leg.serviceLabel ?: "service"
        val at = d.leg.departure.hm()
        return when {
            d.cancelled -> "The $at $lab is cancelled"
            d.basis == DisruptionBasis.SUPPRESSED_STOP -> "The $at $lab does not stop at ${d.leg.origin}"
            d.delayMinutes == null -> "$lab at $at has no live delay estimate"
            else -> "$lab at $at is running ${d.delayMinutes} min late"
        }
    }
}
