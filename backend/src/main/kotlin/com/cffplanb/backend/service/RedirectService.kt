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
    private val clock: Clock,
    private val delay: RealtimeDelayModel = RealtimeDelayModel.NONE,
    private val gtfs: (suspend () -> com.cffplanb.backend.gtfs.GtfsSnapshot?)? = null
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
        // A journey already shifted by a live delay is not shifted again by the statistical model.
        val shifted = HashSet<String>()
        // Cancelled / missing journeys are dropped; delayed ones keep their journey-level arrival shifted by the delay.
        val pool = first.journeys.mapNotNull { j ->
            val d = checkOf[j.id]?.worst?.takeIf { it.affectsJourney }
            when {
                j.id != orig.id && usesAffected(j) -> null
                d == null -> j
                d.cancelled || d.basis == DisruptionBasis.SUPPRESSED_STOP -> null
                else -> (d.delayMinutes ?: 0).also { shifted += j.id }.let { j.copy(arrival = j.arrival.plusMinutes(it.toLong()), durationMinutes = j.durationMinutes + it) }
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
        fun delayOf(j: Journey) = if (j.id in shifted) null else delay.forJourney(j)
        fun expected(j: Journey) = j.arrival.toInstant().plusSeconds(60L * (delayOf(j)?.minutes ?: 0))
        fun prone(j: Journey) = if (j.id in shifted) 0 else delay.legDelays(j).count { (it.minutes ?: 0.0) >= MIN_DELAY_MINUTES }
        val known = !noFeasible && eligible.any { delayOf(it) != null }
        val onPaper = eligible.sortedWith(cmp)
        var final = when {
            noFeasible -> unique.sortedBy { it.arrival.toInstant() }
            !known -> onPaper
            else -> eligible.sortedWith(RecommendationService.comparator(pref, ::fareOf, ::expected)).let { byExp ->
                // FASTEST: expected arrivals within 2 min of the best count as a tie, which the journey with fewer delay-prone legs wins.
                val pick = if (pref != Preference.FASTEST) null else byExp.filter { Duration.between(expected(byExp[0]), expected(it)).toMinutes() <= 2 }.minByOrNull(::prone)
                if (pick == null) byExp else listOf(pick) + byExp.filter { it.id != pick.id }
            }
        }
        val modelFirst = final.firstOrNull()
        // Stay-vs-reroute: a delayed (not cancelled) original is kept unless it misses the deadline or an alternative gains >= 5 min
        // (and, for delays <= 10 min, strictly more than 5 min). Cancelled originals are not in the pool, so they always reroute.
        val waitJ = if (own != null && !own.cancelled) unique.firstOrNull { it.id == orig.id } else null
        if (waitJ != null && !noFeasible && final.first().id != waitJ.id) {
            val gain = mins(final.first().arrival, waitJ.arrival)
            val delay = own!!.delayMinutes ?: 0
            if (meets(waitJ) && !(gain >= 5 && !(delay <= 10 && gain <= 5))) final = listOf(waitJ) + final.filter { it.id != waitJ.id }
        }
        val rec = final.firstOrNull()
        val alts = final.drop(1).take(9)
        val stay = own != null && rec != null && rec.id == orig.id
        val waitJourney = unique.firstOrNull { it.id == orig.id }

        val base = rec?.let { whyFor(it, orig, disruption, stay, alts.firstOrNull(), waitJourney, pref, deadline, noFeasible, ::fareOf) }
        // Only when the expected-arrival ranking, not the schedule, produced this answer.
        val changed = if (known && rec != null && rec.id == modelFirst?.id && rec.id != onPaper.first().id) delayWhy(rec, onPaper.first(), delayOf(rec), delayOf(onPaper.first())) else null
        val caution = rec?.let { upstreamCaution(it) }
        val why = base?.let { b -> listOfNotNull(if (changed != null && disruption == null) null else b, changed, caution).joinToString("; ") }
        val msg = messageFor(situation, disruption, checkOf[orig.id]?.count ?: 0, rec, stay, deadline, noFeasible)
        return RedirectPlan(situation, disruption, checkOf[orig.id]?.count ?: 0, rec, why, alts, fares, msg)
    }

    private fun delayWhy(rec: Journey, schedFirst: Journey, dr: JourneyDelay?, ds: JourneyDelay?): String {
        val paper = mins(schedFirst.arrival, rec.arrival)
        val late = ds?.minutes ?: 0
        return when {
            paper > 0 && late > 0 -> "arrives $paper min later on paper but ${if ((dr?.minutes ?: 0) < 1) "is usually on time" else "usually runs ~${dr!!.minutes} min late"}, while the earlier one runs ~$late min late here"
            late > 0 -> "the other option usually runs ~$late min late here"
            else -> "fewer legs that usually run late"
        }
    }

    /** One-line warning if the GTFS-RT feed shows an earlier trip of the same line and direction cancelled or late; null when it cannot be established. */
    private suspend fun upstreamCaution(rec: Journey): String? {
        val g = gtfs ?: return null
        val leg = rec.legs.firstOrNull { it.mode == LegMode.TRANSIT } ?: return null
        return try {
            kotlinx.coroutines.withTimeoutOrNull(2_500) {
                val snap = g() ?: return@withTimeoutOrNull null
                val plan = (search.stopEvents(leg.origin, leg.departure.minusMinutes(20), false) as? com.cffplanb.backend.provider.StopEventsResult.Success)?.events ?: return@withTimeoutOrNull null
                plannedFor(leg, plan)?.let { upstreamIncident(snap, it) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
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

/**
 * Pure: an earlier trip (up to 90 min before [planned]'s first-stop departure) on the same GTFS route key, which includes the direction letter,
 * that is cancelled or runs >= [MIN_DELAY_MINUTES] late. Routes without a direction letter ("91-57") give null: direction cannot be established.
 */
fun upstreamIncident(snap: com.cffplanb.backend.gtfs.GtfsSnapshot, planned: com.cffplanb.backend.provider.StopEvent): String? {
    val key = com.cffplanb.backend.gtfs.GtfsMatch.routeKey(planned.lineRef)?.takeIf { it.count { c -> c == '-' } == 2 } ?: return null
    val t0 = planned.originDeparture?.toInstant() ?: return null
    return (snap.byRoute[key] ?: return null).filter { !it.added && it.startInstant?.let { s -> s.isBefore(t0) && !s.isBefore(t0.minusSeconds(90 * 60)) } == true }
        .sortedByDescending { it.startInstant }
        .firstNotNullOfOrNull { t ->
            val late = (t.stops.mapNotNull { it.delaySeconds }.maxOrNull() ?: 0) / 60
            val what = if (t.cancelled) "cancelled" else if (late >= MIN_DELAY_MINUTES) "running $late min late" else return@firstNotNullOfOrNull null
            "Caution: the ${t.startInstant!!.atZone(ZURICH).format(HM)} departure of this line in the same direction is $what, which can ripple onto this train"
        }
}
