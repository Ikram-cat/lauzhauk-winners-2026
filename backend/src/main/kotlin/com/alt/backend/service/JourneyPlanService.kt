package com.alt.backend.service

import com.alt.backend.domain.Journey
import com.alt.backend.domain.LegMode
import com.alt.backend.domain.SearchRequest
import com.alt.backend.provider.FareQuote
import com.alt.backend.service.estimate.BASIS_NONE
import com.alt.backend.service.estimate.DelayEstimateModel
import com.alt.backend.service.estimate.IncidentContext
import com.alt.backend.service.estimate.LegEstimate
import com.alt.backend.service.estimate.OVERLAP_KNOWN_WINS
import com.alt.backend.service.estimate.SOURCE_NONE
import com.alt.backend.service.estimate.SideEstimate
import com.alt.backend.service.estimate.weekdayClassOf
import com.alt.backend.service.impact.DisruptionSource
import com.alt.backend.service.impact.EmptyDisruptionSource
import com.alt.backend.service.impact.JourneyDisruptionMatcher
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId

enum class Situation { NO_DISRUPTION, DISRUPTION_DETECTED, UNKNOWN }
enum class Action { REROUTE, STAY, NONE }

/** A structured reason; only the numbers that justify [code] are set. */
data class Reason(
    val code: String, val serviceLabel: String? = null, val delayMinutes: Int? = null,
    val savedMinutes: Int? = null, val savedTransfers: Int? = null, val savedChf: Double? = null, val marginMinutes: Int? = null
)

/**
 * [departureDelayMinutes] / [arrivalDelayMinutes] stay the KNOWN live delay only. The estimated layer is separate:
 * [estimatedDepartureDelayMinutes] / [estimatedArrivalDelayMinutes] are the historical statistical median (clamped
 * to -10..+30), [estimatedSource] is HISTORICAL_ACTUALS | LIVE_SAMPLE | NONE, and the projected times add
 * scheduled + known + estimated (default additive; ESTIMATE_OVERLAP=known_wins drops the estimate where live is known).
 */
data class LegRealtime(
    val expectedDeparture: OffsetDateTime?, val departureDelayMinutes: Int?, val expectedArrival: OffsetDateTime?, val arrivalDelayMinutes: Int?,
    val estimatedDepartureDelayMinutes: Double? = null, val estimatedArrivalDelayMinutes: Double? = null,
    val estimatedSource: String = SOURCE_NONE, val estimatedSamples: Int? = null,
    val projectedDeparture: OffsetDateTime? = null, val projectedArrival: OffsetDateTime? = null,
    /** Which ladder answered: LINE | STATION | LINE_INCIDENT | STATION_INCIDENT | NONE. */
    val estimatedBasis: String = BASIS_NONE,
    /** Incident-conditioned layer: present only while a live disruption touches this leg. */
    val incidentExtraMinutes: Double? = null, val incidentCancellationRisk: Double? = null,
    val incidentSuppressedStopRisk: Double? = null, val incidentSamples: Int? = null, val incidentCount: Int? = null
)

/** [source]: GTFS_RT | OJP | DELAY_MODEL | NONE. Journey values come from the first / last transit leg or the worst leg; [sampleCount] only for DELAY_MODEL. */
data class JourneyRealtime(
    val legs: List<LegRealtime>, val expectedDeparture: OffsetDateTime?, val departureDelayMinutes: Int?,
    val expectedArrival: OffsetDateTime?, val arrivalDelayMinutes: Int?, val source: String, val sampleCount: Int?,
    val estimatedDepartureDelayMinutes: Double? = null, val estimatedArrivalDelayMinutes: Double? = null,
    val estimatedSource: String = SOURCE_NONE, val estimatedSamples: Int? = null,
    val projectedDeparture: OffsetDateTime? = null, val projectedArrival: OffsetDateTime? = null,
    val estimatedBasis: String = BASIS_NONE,
    val incidentExtraMinutes: Double? = null, val incidentCancellationRisk: Double? = null,
    val incidentSuppressedStopRisk: Double? = null, val incidentSamples: Int? = null, val incidentCount: Int? = null
)

/**
 * [disruption].legIndex indexes the legs of [primary]. [alternatives] is every other candidate, sorted by preference (the route caps it).
 * [realtime] covers every journey of the plan by id; [fares] covers the original and the replacement searches.
 */
data class JourneyPlan(
    val status: Situation, val action: Action, val reasons: List<Reason>, val disruption: Disruption?,
    val primary: Journey?, val recommended: Journey?, val alternatives: List<Journey>,
    val fares: Map<String, FareQuote>?, val realtime: Map<String, JourneyRealtime>,
    /** Latest scheduled departure among candidates that arrive by the deadline; null without a deadline or when none does. */
    val latestFeasibleDeparture: OffsetDateTime?,
    /** Ids the detector actually verified against live data; the rest are ranked on expectations. */
    val checked: Set<String> = emptySet()
)

private fun mins(a: OffsetDateTime, b: OffsetDateTime) = Duration.between(a, b).toMinutes().toInt()
private val estimateZone = ZoneId.of("Europe/Zurich")
private fun norm(s: String?) = s?.uppercase()?.filter { it.isLetterOrDigit() }.orEmpty()
private const val MIN_REROUTE_GAIN_MIN = 5

class JourneyPlanService(
    private val search: JourneySearchService,
    private val detector: DisruptionDetector,
    private val clock: Clock,
    private val delay: RealtimeDelayModel = RealtimeDelayModel.NONE,
    private val estimates: DelayEstimateModel = DelayEstimateModel.NONE,
    /** ESTIMATE_OVERLAP: additive (default) or known_wins. */
    private val estimateOverlap: String = System.getenv("ESTIMATE_OVERLAP") ?: "additive",
    /** ACTIVE disruptions (SIRI/GTFS alert feed, replayed timelines): a second source of incident context. */
    private val disruptions: DisruptionSource = EmptyDisruptionSource
) {
    suspend fun plan(req: SearchRequest, pref: Preference, deadline: OffsetDateTime?, first: SearchResult.Success): JourneyPlan {
        val price = { q: FareQuote -> q.price(req.fareClass, req.discount) }
        var fares = first.fares
        fun fareOf(j: Journey) = fares?.get(j.id)?.let(price)
        if (first.journeys.isEmpty()) return JourneyPlan(Situation.UNKNOWN, Action.NONE, emptyList(), null, null, null, emptyList(), fares, emptyMap(), null)

        fun meetsAt(j: Journey, arrival: OffsetDateTime) = deadline == null || !arrival.toInstant().isAfter(deadline.toInstant())
        val ranked = first.journeys.sortedWith(RecommendationService.comparator(pref, ::fareOf))
        val ordered = ranked.filter { meetsAt(it, it.arrival) } + ranked.filterNot { meetsAt(it, it.arrival) }
        val primary = ordered[0]

        val checkList = ordered.take(4)
        val checks = detector.detect(checkList, req.earliestDeparture)
        val checkOf = if (checks == null) emptyMap() else checkList.map { it.id }.zip(checks).toMap()
        // ACTIVE alert/replay disruptions feed the conditioned estimate layer alongside the per-leg detection.
        val active = try { disruptions.active(OffsetDateTime.now(clock)) } catch (e: Exception) { emptyList() }
        val disruption = checkOf[primary.id]?.worst
        val own = disruption?.takeIf { it.affectsJourney }
        val status = when {
            disruption != null -> Situation.DISRUPTION_DETECTED
            checks == null || checkOf.getValue(primary.id).unknown -> Situation.UNKNOWN
            else -> Situation.NO_DISRUPTION
        }

        fun usesAffected(j: Journey) = own != null && j.legs.any {
            it.mode == LegMode.TRANSIT && norm(it.serviceLabel) == norm(own.leg.serviceLabel) && Math.abs(mins(it.departure, own.leg.departure)) <= 2
        }
        fun unusable(j: Journey) = checkOf[j.id]?.worst?.let { it.affectsJourney && (it.cancelled || it.basis == DisruptionBasis.SUPPRESSED_STOP) } == true
        val pool = first.journeys.filter { j -> !unusable(j) && (j.id == primary.id || !usesAffected(j)) }.toMutableList()
        val rt = HashMap<String, JourneyRealtime>()
        fun realtimeOf(j: Journey) = rt.getOrPut(j.id) { realtime(j, checkOf[j.id], checks != null, active) }

        if (own != null) {
            // Floor = when the traveller can be at the boarding station: now, or the previous leg's arrival.
            val atStation = if (own.legIndex == 0) req.earliestDeparture else primary.legs[own.legIndex - 1].arrival
            val now = OffsetDateTime.now(clock)
            val repl = search.search(SearchRequest(own.leg.origin, req.destination, if (atStation.isAfter(now)) atStation else now, req.fareClass, req.discount))
            if (repl is SearchResult.Success) {
                val used = first.journeys.mapTo(HashSet()) { it.id }
                for (j in repl.journeys.filterNot(::usesAffected)) {
                    // OJP trip ids repeat across searches; keep ids unique so fares and realtime stay per journey
                    val id = if (j.id in used) "${j.id}~r" else j.id
                    repl.fares?.get(j.id)?.let { fares = (fares ?: emptyMap()) + (id to it) }
                    pool += j.copy(id = id)
                }
            }
        }
        // The replacement search overlaps the original one; the same legs under a different id are the same trip.
        val unique = pool.distinctBy { j -> j.legs.map { Triple(it.serviceLabel, it.departure.toInstant(), it.arrival.toInstant()) } }
        fun arrivalOf(j: Journey) = realtimeOf(j).projectedArrival ?: realtimeOf(j).expectedArrival ?: j.arrival
        val cmp = RecommendationService.comparator(pref, ::fareOf) { arrivalOf(it).toInstant() }
        fun sorted(js: List<Journey>) = js.sortedWith(cmp).let { r -> r.filter { meetsAt(it, arrivalOf(it)) } + r.filterNot { meetsAt(it, arrivalOf(it)) } }

        val reasons = mutableListOf<Reason>()
        if (status == Situation.UNKNOWN) reasons += Reason("REALTIME_UNAVAILABLE")
        val recommended: Journey?
        val action: Action
        val label = own?.leg?.serviceLabel
        when {
            own != null && (own.cancelled || own.basis == DisruptionBasis.SUPPRESSED_STOP) -> {
                reasons += Reason(if (own.cancelled) "PRIMARY_LEG_CANCELLED" else "SUPPRESSED_STOP", serviceLabel = label)
                action = Action.REROUTE
                recommended = sorted(unique.filter { it.id != primary.id }).firstOrNull()
            }
            own != null -> {
                reasons += Reason("PRIMARY_LEG_DELAYED", serviceLabel = label, delayMinutes = own.delayMinutes)
                val expected = arrivalOf(primary)
                val better = unique.filter { it.id != primary.id && mins(arrivalOf(it), expected) >= MIN_REROUTE_GAIN_MIN }
                recommended = sorted(better).firstOrNull() ?: primary
                action = if (recommended.id == primary.id) Action.STAY else Action.REROUTE
                if (action == Action.REROUTE) reasons += Reason("EARLIER_ARRIVAL", savedMinutes = mins(arrivalOf(recommended), expected))
            }
            else -> {
                if (disruption != null) reasons += Reason("EARLIER_DEPARTURE_CANCELLED", serviceLabel = disruption.leg.serviceLabel)
                // Nothing live is broken; only when the traveller asked for FASTEST can a candidate that is expected
                // (live estimate or measured chronic delay) to arrive 5+ min earlier take the recommendation.
                val expectedPrimary = arrivalOf(primary)
                val robust = if (pref == Preference.FASTEST) sorted(unique.filter { it.id != primary.id && mins(arrivalOf(it), expectedPrimary) >= MIN_REROUTE_GAIN_MIN }).firstOrNull() else null
                recommended = robust ?: primary; action = Action.STAY
                if (robust != null) reasons += Reason("EXPECTED_FASTER", savedMinutes = mins(arrivalOf(robust), expectedPrimary))
            }
        }
        if (recommended != null && recommended.id != primary.id) {
            if (recommended.transfers < primary.transfers) reasons += Reason("FEWER_TRANSFERS", savedTransfers = primary.transfers - recommended.transfers)
            val a = fareOf(recommended); val b = fareOf(primary)
            if (a != null && b != null && a < b) reasons += Reason("CHEAPER", savedChf = Math.round((b - a) * 100) / 100.0)
        }
        if (action == Action.STAY && unique.none { arrivalOf(it).toInstant() < arrivalOf(primary).toInstant() }) reasons += Reason("STAY_FASTEST")
        if (deadline != null && recommended != null) {
            val m = mins(arrivalOf(recommended), deadline)
            reasons += if (m >= 0) Reason("DEADLINE_MET", marginMinutes = m) else Reason("DEADLINE_MISSED", marginMinutes = -m)
        }

        val alternatives = unique.filter { it.id != recommended?.id }.sortedWith(cmp)
        val latest = deadline?.let { unique.filter { meetsAt(it, arrivalOf(it)) }.maxByOrNull { it.departure.toInstant() }?.departure }
        (unique + listOfNotNull(primary)).forEach { realtimeOf(it) }
        return JourneyPlan(status, action, reasons, disruption, primary, recommended, alternatives, fares, rt, latest, checkOf.keys)
    }

    /** Historical estimate for a transit leg: departure looked up at its origin, arrival at its destination.
     *  [incident] is set only when live detection found an ACTIVE disruption on this leg's line/corridor. */
    private fun estimateFor(l: com.alt.backend.domain.JourneyLeg, incident: IncidentContext? = null): LegEstimate {
        val depLocal = l.departure.atZoneSameInstant(estimateZone)
        val arrLocal = l.arrival.atZoneSameInstant(estimateZone)
        val depEst = estimates.estimate(l.serviceLabel, l.origin, weekdayClassOf(depLocal.toLocalDate()), depLocal.hour, incident)
        val arrEst = estimates.estimate(l.serviceLabel, l.destination, weekdayClassOf(arrLocal.toLocalDate()), arrLocal.hour, incident)
        return LegEstimate(depEst.departure, arrEst.arrival, depEst.incident ?: arrEst.incident)
    }

    /** scheduled + known + estimated (the estimate is dropped when ESTIMATE_OVERLAP=known_wins and live is known).
     *  Composed in seconds so the decimal estimate survives into the projected timestamps. */
    private fun project(scheduled: OffsetDateTime, known: OffsetDateTime?, est: SideEstimate?): OffsetDateTime? {
        val knownSec = known?.let { Duration.between(scheduled, it).seconds }
        if (knownSec == null && est == null) return null
        val appliedSec = when {
            est == null -> 0L
            estimateOverlap.equals(OVERLAP_KNOWN_WINS, ignoreCase = true) && knownSec != null -> 0L
            else -> Math.round(est.median * 60)
        }
        return scheduled.plusSeconds((knownSec ?: 0L) + appliedSec)
    }

    /**
     * Per leg: provider estimates (OJP) overlaid by the detector's verdict for that leg (GTFS-RT or OJP stop events),
     * plus the SEPARATE historical estimate layer (estimated* / projected* fields). Journey level: first transit leg
     * for departure, last transit leg (else the worst leg delay) for arrival; the statistical delay model fills in
     * only when nothing live is known. [live] false (detection failed) leaves everything null.
     */
    private fun realtime(j: Journey, check: JourneyCheck?, live: Boolean, active: List<com.alt.backend.domain.ActiveDisruption> = emptyList()): JourneyRealtime {
        if (!live) return JourneyRealtime(j.legs.map { LegRealtime(null, null, null, null) }, null, null, null, null, "NONE", null)
        var gtfs = false; var ojp = false
        // Legs an ACTIVE alert/replay disruption touches: a second, feed-level source of incident context.
        val alertLegs = if (active.isEmpty()) emptySet() else
            active.flatMap { d -> JourneyDisruptionMatcher.match(j, d)?.stations?.map { it.legIndex } ?: emptyList() }.toSet()
        val legs = j.legs.mapIndexed { i, l ->
            if (l.mode != LegMode.TRANSIT) return@mapIndexed LegRealtime(null, null, null, null)
            var dep = l.expectedDeparture; var arr = l.expectedArrival
            if (dep != null || arr != null) ojp = true
            when (val c = check?.legs?.get(i)) {
                is LegCheck.Hit -> c.disruption.takeIf { it.affectsJourney && it.delayMinutes != null }?.let { d ->
                    dep = d.estimatedDeparture ?: l.departure.plusMinutes(d.delayMinutes!!.toLong())
                    if (d.fromGtfs) gtfs = true else ojp = true
                }
                is LegCheck.Clear -> c.delayMinutes?.let { dep = l.departure.plusMinutes(it.toLong()); if (c.fromGtfs) gtfs = true else ojp = true }
                else -> {}
            }
            val hit = (check?.legs?.get(i) as? LegCheck.Hit)?.disruption?.takeIf { it.affectsJourney }
            val incCtx = hit?.let { IncidentContext(hit.leg.serviceLabel ?: l.serviceLabel) }
                ?: if (i in alertLegs) IncidentContext(l.serviceLabel) else null
            val est = estimateFor(l, incCtx)
            LegRealtime(dep, dep?.let { mins(l.departure, it) }, arr, arr?.let { mins(l.arrival, it) },
                estimatedDepartureDelayMinutes = est.departure?.let { Math.round(it.median * 10) / 10.0 },
                estimatedArrivalDelayMinutes = est.arrival?.let { Math.round(it.median * 10) / 10.0 },
                estimatedSource = est.source, estimatedSamples = est.samples,
                projectedDeparture = project(l.departure, dep, est.departure),
                projectedArrival = project(l.arrival, arr, est.arrival),
                estimatedBasis = est.basis,
                incidentExtraMinutes = est.incident?.extraMinutes,
                incidentCancellationRisk = est.incident?.cancellationRisk,
                incidentSuppressedStopRisk = est.incident?.suppressedStopRisk,
                incidentSamples = est.incident?.samples,
                incidentCount = est.incident?.incidentCount)
        }
        val transit = j.legs.indices.filter { j.legs[it].mode == LegMode.TRANSIT }
        val firstLeg = transit.firstOrNull()?.let { legs[it] }
        val lastLeg = transit.lastOrNull()?.let { legs[it] }
        val lastDelay = lastLeg?.arrivalDelayMinutes
        val worst = transit.mapNotNull { legs[it].arrivalDelayMinutes ?: legs[it].departureDelayMinutes }.maxOrNull()
        val arrDelay = lastDelay ?: worst
        val estSource = firstLeg?.estimatedSource?.takeIf { it != SOURCE_NONE } ?: lastLeg?.estimatedSource ?: SOURCE_NONE
        val estSamples = firstLeg?.estimatedSamples ?: lastLeg?.estimatedSamples
        val estBasis = firstLeg?.estimatedBasis ?: BASIS_NONE
        val incExtra = firstLeg?.incidentExtraMinutes ?: lastLeg?.incidentExtraMinutes
        val incSamples = firstLeg?.incidentSamples ?: lastLeg?.incidentSamples
        val incCount = firstLeg?.incidentCount ?: lastLeg?.incidentCount
        val incCancelled = transit.mapNotNull { legs[it].incidentCancellationRisk }.maxOrNull()
        val incSuppressed = transit.mapNotNull { legs[it].incidentSuppressedStopRisk }.maxOrNull()
        val projDep = firstLeg?.projectedDeparture
        val projArr = lastLeg?.projectedArrival
        if (gtfs || ojp) {
            val src = if (gtfs) "GTFS_RT" else "OJP"
            return JourneyRealtime(legs, firstLeg?.expectedDeparture, firstLeg?.departureDelayMinutes, arrDelay?.let { j.arrival.plusMinutes(it.toLong()) }, arrDelay, src, null,
                firstLeg?.estimatedDepartureDelayMinutes, lastLeg?.estimatedArrivalDelayMinutes, estSource, estSamples, projDep, projArr,
                estBasis, incExtra, incCancelled, incSuppressed, incSamples, incCount)
        }
        val m = delay.forJourney(j) ?: return JourneyRealtime(legs, null, null, null, null, "NONE", null,
            firstLeg?.estimatedDepartureDelayMinutes, lastLeg?.estimatedArrivalDelayMinutes, estSource, estSamples, projDep, projArr,
            estBasis, incExtra, incCancelled, incSuppressed, incSamples, incCount)
        return JourneyRealtime(legs, null, null, j.arrival.plusMinutes(m.minutes.toLong()), m.minutes, "DELAY_MODEL", m.samples,
            firstLeg?.estimatedDepartureDelayMinutes, lastLeg?.estimatedArrivalDelayMinutes, estSource, estSamples, projDep, projArr,
            estBasis, incExtra, incCancelled, incSuppressed, incSamples, incCount)
    }
}
