package com.cffplanb.backend.service

import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.gtfs.GtfsMatch
import com.cffplanb.backend.gtfs.GtfsSnapshot
import com.cffplanb.backend.domain.JourneyLeg
import com.cffplanb.backend.domain.LegMode
import com.cffplanb.backend.provider.StopEvent
import com.cffplanb.backend.provider.StopEventsResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime

/** SUPPRESSED_STOP: the trip runs but does not call at the boarding stop (GTFS-RT SKIPPED). */
enum class DisruptionBasis { REALTIME_ESTIMATE, CANCELLED_FLAG, MISSING_FROM_REALTIME, SUPPRESSED_STOP }

/** [legIndex] indexes the checked journey's legs; [leg] is that leg as scheduled. */
data class Disruption(
    val legIndex: Int, val leg: JourneyLeg, val delayMinutes: Int?, val cancelled: Boolean,
    val basis: DisruptionBasis, val confidence: String, val estimatedDeparture: OffsetDateTime?, val cause: String? = null,
    /** True when the verdict came from the GTFS-RT feed rather than OJP stop events. */
    val fromGtfs: Boolean = false,
    /** False for a cancelled train that is not part of the checked journey (the planner already dropped it) but would have served the same hop. */
    val affectsJourney: Boolean = true
)

sealed class LegCheck {
    /** [delayMinutes] is the measured boarding delay (< 3 min, may be 0); null when the source did not give a number. */
    data class Clear(val delayMinutes: Int? = null, val fromGtfs: Boolean = false) : LegCheck()
    object Unknown : LegCheck()
    data class Hit(val disruption: Disruption) : LegCheck()
}

/** [worst] is the single most severe disruption; [count] how many legs are disrupted; [unknown] if any leg could not be verified; [legs] the verdict per transit leg index. */
data class JourneyCheck(val worst: Disruption?, val count: Int, val unknown: Boolean, val legs: Map<Int, LegCheck> = emptyMap())

/** Smaller delays are not a disruption: nothing is reported and the normal recommendation stands. */
const val MIN_DELAY_MINUTES = 3

private fun norm(s: String?) = s?.uppercase()?.filter { it.isLetterOrDigit() }.orEmpty()

/** The timetable-board departure that is this leg (same line label, closest scheduled time within 5 min). */
fun plannedFor(leg: JourneyLeg, plan: List<StopEvent>): StopEvent? {
    val key = norm(leg.serviceLabel).ifEmpty { return null }
    return plan.filter { (norm(it.label) == key || norm(it.lineRef) == key) && Math.abs(Duration.between(it.scheduled, leg.departure).toMinutes()) <= 5 }
        .minByOrNull { Math.abs(Duration.between(it.scheduled, leg.departure).toMinutes()) }
}

/**
 * Pure, primary path: GTFS-RT verdict for [leg] given its timetable-board departure [planned], or null when no confident GTFS match exists.
 * Order: trip CANCELED (matched by route + start time only: cancelled trips carry no stop_time_updates), boarding stop SKIPPED, boarding-stop delay >= 3 min, otherwise Clear.
 */
fun classifyLegGtfs(legIndex: Int, leg: JourneyLeg, planned: StopEvent, snap: GtfsSnapshot): LegCheck? {
    val trip = GtfsMatch.find(snap, planned.lineRef, planned.originDeparture?.toInstant()) ?: return null
    fun hit(delay: Int?, cancelled: Boolean, basis: DisruptionBasis, est: OffsetDateTime? = null) = LegCheck.Hit(Disruption(legIndex, leg, delay, cancelled, basis, "high", est, fromGtfs = true))
    if (trip.cancelled) return hit(null, true, DisruptionBasis.CANCELLED_FLAG)
    val stu = trip.stops.firstOrNull { GtfsMatch.sameStop(it.stopId, planned.stopRef) } ?: return null
    if (stu.skipped) return hit(null, false, DisruptionBasis.SUPPRESSED_STOP)
    val d = stu.delaySeconds ?: return null
    return if (d / 60 >= MIN_DELAY_MINUTES) hit(d / 60, false, DisruptionBasis.REALTIME_ESTIMATE, leg.departure.plusSeconds(d.toLong())) else LegCheck.Clear(d / 60, true)
}

/**
 * Pure: classify one transit leg by diffing the timetable board [plan] against the live board [live].
 * OJP drops cancelled trips from the live board, so "planned but not live" means cancelled.
 */
fun classifyLeg(legIndex: Int, leg: JourneyLeg, plan: List<StopEvent>, live: List<StopEvent>): LegCheck {
    val planned = plannedFor(leg, plan) ?: return LegCheck.Unknown
    fun sameLine(a: StopEvent, label: String?, ref: String?) = (norm(a.label) == norm(label) && norm(label).isNotEmpty()) || (!ref.isNullOrBlank() && a.lineRef == ref)
    fun gap(a: OffsetDateTime, b: OffsetDateTime) = Math.abs(Duration.between(a, b).toMinutes())
    if (live.isEmpty()) return LegCheck.Unknown
    fun hit(delay: Int?, cancelled: Boolean, basis: DisruptionBasis, conf: String, est: OffsetDateTime? = null) =
        LegCheck.Hit(Disruption(legIndex, leg, delay, cancelled, basis, conf, est))
    val counterpart = live.filter { sameLine(it, planned.label, planned.lineRef) && gap(it.scheduled, planned.scheduled) <= 2 }.minByOrNull { gap(it.scheduled, planned.scheduled) }
        ?: return hit(null, true, DisruptionBasis.CANCELLED_FLAG, "high")
    if (counterpart.cancelled) return hit(null, true, DisruptionBasis.CANCELLED_FLAG, "high")
    val est = counterpart.estimated ?: return LegCheck.Unknown // no estimate is no evidence of a problem, and not proof of health either
    val delay = Duration.between(counterpart.scheduled, est).toMinutes().toInt()
    return if (delay >= MIN_DELAY_MINUTES) hit(delay, false, DisruptionBasis.REALTIME_ESTIMATE, "high", est) else LegCheck.Clear(delay)
}

/**
 * Pure: the latest cancelled departure between [floor] and this leg that calls at the leg's (or the journey's final) destination. OJP omits cancelled trips
 * from journey results, so this is the only way to notice that the train the traveller wanted is gone.
 */
fun cancelledEarlier(legIndex: Int, leg: JourneyLeg, floor: OffsetDateTime, plan: List<StopEvent>, live: List<StopEvent>, gtfsCancelled: (StopEvent) -> Boolean = { false }, finalDestination: String = leg.destination): Disruption? {
    // "Herisau, Wiesental" (a bus stop) still names the place "Herisau"
    val dest = norm(leg.destination.substringBefore(',')); val fin = norm(finalDestination.substringBefore(','))
    val cut = leg.departure.minusMinutes(2)
    val gone = plan.filter { !it.scheduled.isBefore(floor) && it.scheduled.isBefore(cut) && (dest in it.onward || fin in it.onward) }.filter { p ->
        gtfsCancelled(p) || (live.isNotEmpty() && live.none { l -> Math.abs(Duration.between(l.scheduled, p.scheduled).toMinutes()) <= 2 && norm(l.label) == norm(p.label) && !l.cancelled })
    }.maxByOrNull { it.scheduled } ?: return null
    return Disruption(legIndex, leg.copy(serviceLabel = gone.label, departure = gone.scheduled, destination = if (dest in gone.onward) leg.destination else finalDestination), null, true, DisruptionBasis.CANCELLED_FLAG, "high", null, affectsJourney = false)
}

/** Cancelled beats delay, a larger delay beats a smaller one, an unknown delay ranks last. */
private val severity = compareBy<Disruption>({ if (it.affectsJourney) 1 else 0 }, { if (it.cancelled) 2 else if (it.basis == DisruptionBasis.REALTIME_ESTIMATE) 1 else 0 }, { it.delayMinutes ?: 0 }, { -it.legIndex })

class DisruptionDetector(
    private val fetch: suspend (String, OffsetDateTime, Boolean) -> StopEventsResult,
    private val deadlineMs: Long = 6_000,
    private val maxQueries: Int = 12,
    private val gtfs: (suspend () -> GtfsSnapshot?)? = null
) {
    private val log = LoggerFactory.getLogger(DisruptionDetector::class.java)

    /** One check per journey (same order), or null when the global deadline or a provider error made detection impossible. */
    suspend fun detect(journeys: List<Journey>, floor: OffsetDateTime): List<JourneyCheck>? {
        val out = try {
            withTimeoutOrNull(deadlineMs) { run(journeys, floor) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Disruption detection failed: {}", e.javaClass.simpleName); return null
        }
        if (out == null) log.warn("Disruption detection exceeded {} ms; situation UNKNOWN", deadlineMs)
        else if (out.all { it.unknown && it.worst == null }) log.warn("Disruption detection got no usable realtime data; situation UNKNOWN")
        return out
    }

    private suspend fun run(journeys: List<Journey>, floor: OffsetDateTime): List<JourneyCheck> = coroutineScope {
        val jobs = HashMap<Triple<String, Long, Boolean>, Deferred<StopEventsResult>>()
        fun job(leg: JourneyLeg, realtime: Boolean): Deferred<StopEventsResult>? {
            // 5-minute grid so legs at the same stop share one query; window starts 20 min before departure.
            val from = leg.departure.minusMinutes(20).let { it.minusMinutes((it.minute % 5).toLong()).withSecond(0).withNano(0) }
            val k = Triple(leg.origin.trim().lowercase(), from.toEpochSecond(), realtime)
            return jobs[k] ?: if (jobs.size < maxQueries) async { fetch(leg.origin, from, realtime) }.also { jobs[k] = it } else null
        }
        // GTFS-RT is the primary source; bounded so a cold first fetch cannot eat the whole budget.
        val snapJob = gtfs?.let { g -> async { try { withTimeoutOrNull(3_000) { g() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null } } }
        val planned = journeys.map { j -> j.legs.mapIndexedNotNull { i, l -> if (l.mode == LegMode.TRANSIT) Triple(i, l, job(l, false) to job(l, true)) else null } }
        planned.mapIndexed { ji, legs ->
            val extra = mutableListOf<Disruption>()
            val checks = legs.map { (i, l, d) ->
                val plan = d.first?.await(); val live = d.second?.await()
                val snap = snapJob?.await()
                if (plan is StopEventsResult.Success) {
                    val liveEvents = (live as? StopEventsResult.Success)?.events.orEmpty()
                    fun gtfsCancelled(p: StopEvent) = snap != null && GtfsMatch.find(snap, p.lineRef, p.originDeparture?.toInstant())?.cancelled == true
                    extra += listOfNotNull(cancelledEarlier(i, l, if (i == 0) floor else journeys[ji].legs[i - 1].arrival, plan.events, liveEvents, ::gtfsCancelled, journeys[ji].legs.last().destination))
                    val planned = plannedFor(l, plan.events)
                    (if (snap != null && planned != null) classifyLegGtfs(i, l, planned, snap) else null)
                        ?: if (live is StopEventsResult.Success) classifyLeg(i, l, plan.events, liveEvents) else LegCheck.Unknown
                } else LegCheck.Unknown
            }
            val hits = checks.filterIsInstance<LegCheck.Hit>().map { it.disruption } + extra
            JourneyCheck(hits.maxWithOrNull(severity), hits.size, checks.any { it is LegCheck.Unknown }, legs.map { it.first }.zip(checks).toMap())
        }
    }
}
