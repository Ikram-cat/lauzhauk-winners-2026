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

enum class DisruptionBasis { REALTIME_ESTIMATE, CANCELLED_FLAG, MISSING_FROM_REALTIME }

/** [legIndex] indexes the checked journey's legs; [leg] is that leg as scheduled. */
data class Disruption(
    val legIndex: Int, val leg: JourneyLeg, val delayMinutes: Int, val cancelled: Boolean,
    val basis: DisruptionBasis, val confidence: String, val estimatedDeparture: OffsetDateTime?, val cause: String? = null
)

sealed class LegCheck {
    object Clear : LegCheck()
    object Unknown : LegCheck()
    data class Hit(val disruption: Disruption) : LegCheck()
}

/** [worst] is the single most severe disruption; [count] how many legs are disrupted; [unknown] if any leg could not be verified. */
data class JourneyCheck(val worst: Disruption?, val count: Int, val unknown: Boolean)

private fun norm(s: String?) = s?.uppercase()?.filter { it.isLetterOrDigit() }.orEmpty()

/** Pure: classify one transit leg against the stop's realtime departures. */
fun classifyLeg(legIndex: Int, leg: JourneyLeg, events: List<StopEvent>): LegCheck {
    val key = norm(leg.serviceLabel).ifEmpty { return LegCheck.Unknown }
    fun gap(e: StopEvent) = Math.abs(Duration.between(leg.departure, e.scheduled).toMinutes())
    val match = events.filter { (norm(it.label) == key || norm(it.lineRef) == key) && gap(it) <= 5 }.minByOrNull { gap(it) }
    if (match == null) {
        // Only claim "missing" when the realtime list demonstrably reaches past the leg's departure.
        val covered = events.any { it.scheduled.toInstant().isAfter(leg.departure.plusMinutes(5).toInstant()) }
        return if (covered) LegCheck.Hit(Disruption(legIndex, leg, 0, false, DisruptionBasis.MISSING_FROM_REALTIME, "low", null)) else LegCheck.Unknown
    }
    if (match.cancelled) return LegCheck.Hit(Disruption(legIndex, leg, 0, true, DisruptionBasis.CANCELLED_FLAG, "high", null))
    val delay = match.estimated?.let { Duration.between(match.scheduled, it).toMinutes().toInt() } ?: 0
    return if (delay >= 1) LegCheck.Hit(Disruption(legIndex, leg, delay, false, DisruptionBasis.REALTIME_ESTIMATE, "high", match.estimated)) else LegCheck.Clear
}

/** Cancelled beats delay, a larger delay beats a smaller one, low-confidence "missing" ranks last. */
private val severity = compareBy<Disruption>({ if (it.cancelled) 2 else if (it.basis == DisruptionBasis.REALTIME_ESTIMATE) 1 else 0 }, { it.delayMinutes }, { -it.legIndex })

class DisruptionDetector(
    private val fetch: suspend (String, OffsetDateTime) -> StopEventsResult,
    private val deadlineMs: Long = 6_000,
    private val maxQueries: Int = 6
) {
    private val log = LoggerFactory.getLogger(DisruptionDetector::class.java)

    /** One check per journey (same order), or null when the global deadline or a provider error made detection impossible. */
    suspend fun detect(journeys: List<Journey>): List<JourneyCheck>? {
        val out = try {
            withTimeoutOrNull(deadlineMs) { run(journeys) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Disruption detection failed: {}", e.javaClass.simpleName); return null
        }
        if (out == null) log.warn("Disruption detection exceeded {} ms; situation UNKNOWN", deadlineMs)
        else if (out.all { it.unknown && it.worst == null }) log.warn("Disruption detection got no usable realtime data; situation UNKNOWN")
        return out
    }

    private suspend fun run(journeys: List<Journey>): List<JourneyCheck> = coroutineScope {
        val jobs = HashMap<Pair<String, Long>, Deferred<StopEventsResult>>()
        fun job(leg: JourneyLeg): Deferred<StopEventsResult>? {
            // 5-minute grid so legs at the same stop share one query; window starts 20 min before departure.
            val from = leg.departure.minusMinutes(20).let { it.minusMinutes((it.minute % 5).toLong()).withSecond(0).withNano(0) }
            val k = leg.origin.trim().lowercase() to from.toEpochSecond()
            return jobs[k] ?: if (jobs.size < maxQueries) async { fetch(leg.origin, from) }.also { jobs[k] = it } else null
        }
        val planned = journeys.map { j -> j.legs.mapIndexedNotNull { i, l -> if (l.mode == LegMode.TRANSIT) Triple(i, l, job(l)) else null } }
        planned.map { legs ->
            val checks = legs.map { (i, l, d) ->
                when (val r = d?.await()) {
                    is StopEventsResult.Success -> classifyLeg(i, l, r.events)
                    else -> LegCheck.Unknown
                }
            }
            val hits = checks.filterIsInstance<LegCheck.Hit>().map { it.disruption }
            JourneyCheck(hits.maxWithOrNull(severity), hits.size, checks.any { it is LegCheck.Unknown })
        }
    }
}
