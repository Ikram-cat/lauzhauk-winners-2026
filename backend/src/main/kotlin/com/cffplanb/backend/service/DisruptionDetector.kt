package com.cffplanb.backend.service

import com.cffplanb.backend.domain.Journey
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

enum class DisruptionBasis { REALTIME_ESTIMATE, CANCELLED_FLAG, MISSING_ESTIMATE }

/** [legIndex] indexes the checked journey's legs; [leg] is that leg as scheduled. */
data class Disruption(
    val legIndex: Int, val leg: JourneyLeg, val delayMinutes: Int?, val cancelled: Boolean,
    val basis: DisruptionBasis, val confidence: String, val estimatedDeparture: OffsetDateTime?, val cause: String? = null,
    /** False for a cancelled train that is not part of the checked journey (the planner already dropped it) but would have served the same hop. */
    val affectsJourney: Boolean = true
)

sealed class LegCheck {
    object Clear : LegCheck()
    object Unknown : LegCheck()
    data class Hit(val disruption: Disruption) : LegCheck()
}

/** [worst] is the single most severe disruption; [count] how many legs are disrupted; [unknown] if any leg could not be verified. */
data class JourneyCheck(val worst: Disruption?, val count: Int, val unknown: Boolean)

private fun norm(s: String?) = s?.uppercase()?.filter { it.isLetterOrDigit() }.orEmpty()

/**
 * Pure: classify one transit leg by diffing the timetable board [plan] against the live board [live].
 * OJP drops cancelled trips from the live board, so "planned but not live" means cancelled.
 */
fun classifyLeg(legIndex: Int, leg: JourneyLeg, plan: List<StopEvent>, live: List<StopEvent>): LegCheck {
    val key = norm(leg.serviceLabel).ifEmpty { return LegCheck.Unknown }
    fun sameLine(a: StopEvent, label: String?, ref: String?) = (norm(a.label) == norm(label) && norm(label).isNotEmpty()) || (!ref.isNullOrBlank() && a.lineRef == ref)
    fun gap(a: OffsetDateTime, b: OffsetDateTime) = Math.abs(Duration.between(a, b).toMinutes())
    val planned = plan.filter { (norm(it.label) == key || norm(it.lineRef) == key) && gap(it.scheduled, leg.departure) <= 5 }.minByOrNull { gap(it.scheduled, leg.departure) }
        ?: return LegCheck.Unknown
    if (live.isEmpty()) return LegCheck.Unknown
    fun hit(delay: Int?, cancelled: Boolean, basis: DisruptionBasis, conf: String, est: OffsetDateTime? = null) =
        LegCheck.Hit(Disruption(legIndex, leg, delay, cancelled, basis, conf, est))
    val counterpart = live.filter { sameLine(it, planned.label, planned.lineRef) && gap(it.scheduled, planned.scheduled) <= 2 }.minByOrNull { gap(it.scheduled, planned.scheduled) }
        ?: return hit(null, true, DisruptionBasis.CANCELLED_FLAG, "high")
    if (counterpart.cancelled) return hit(null, true, DisruptionBasis.CANCELLED_FLAG, "high")
    val est = counterpart.estimated ?: return hit(null, false, DisruptionBasis.MISSING_ESTIMATE, "medium")
    val delay = Duration.between(counterpart.scheduled, est).toMinutes().toInt()
    return if (delay >= 1) hit(delay, false, DisruptionBasis.REALTIME_ESTIMATE, "high", est) else LegCheck.Clear
}

/**
 * Pure: the latest cancelled departure between [floor] and this leg that calls at the leg's destination. OJP omits cancelled trips
 * from journey results, so this is the only way to notice that the train the traveller wanted is gone.
 */
fun cancelledEarlier(legIndex: Int, leg: JourneyLeg, floor: OffsetDateTime, plan: List<StopEvent>, live: List<StopEvent>): Disruption? {
    if (live.isEmpty()) return null
    val dest = norm(leg.destination)
    val cut = leg.departure.minusMinutes(2)
    val gone = plan.filter { !it.scheduled.isBefore(floor) && it.scheduled.isBefore(cut) && dest in it.onward }.filter { p ->
        live.none { l -> Math.abs(Duration.between(l.scheduled, p.scheduled).toMinutes()) <= 2 && norm(l.label) == norm(p.label) && !l.cancelled }
    }.maxByOrNull { it.scheduled } ?: return null
    return Disruption(legIndex, leg.copy(serviceLabel = gone.label, departure = gone.scheduled), null, true, DisruptionBasis.CANCELLED_FLAG, "high", null, affectsJourney = false)
}

/** Cancelled beats delay, a larger delay beats a smaller one, an unknown delay ranks last. */
private val severity = compareBy<Disruption>({ if (it.affectsJourney) 1 else 0 }, { if (it.cancelled) 2 else if (it.basis == DisruptionBasis.REALTIME_ESTIMATE) 1 else 0 }, { it.delayMinutes ?: 0 }, { -it.legIndex })

class DisruptionDetector(
    private val fetch: suspend (String, OffsetDateTime, Boolean) -> StopEventsResult,
    private val deadlineMs: Long = 6_000,
    private val maxQueries: Int = 12
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
        val planned = journeys.map { j -> j.legs.mapIndexedNotNull { i, l -> if (l.mode == LegMode.TRANSIT) Triple(i, l, job(l, false) to job(l, true)) else null } }
        planned.mapIndexed { ji, legs ->
            val extra = mutableListOf<Disruption>()
            val checks = legs.map { (i, l, d) ->
                val plan = d.first?.await(); val live = d.second?.await()
                if (plan is StopEventsResult.Success && live is StopEventsResult.Success) {
                    val earlier = cancelledEarlier(i, l, if (i == 0) floor else journeys[ji].legs[i - 1].arrival, plan.events, live.events)
                    val own = classifyLeg(i, l, plan.events, live.events)
                    extra += listOfNotNull(earlier)
                    own
                } else LegCheck.Unknown
            }
            val hits = checks.filterIsInstance<LegCheck.Hit>().map { it.disruption } + extra
            JourneyCheck(hits.maxWithOrNull(severity), hits.size, checks.any { it is LegCheck.Unknown })
        }
    }
}
