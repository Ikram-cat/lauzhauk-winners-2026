package com.alt.backend.service.impact

import com.alt.backend.domain.Journey
import com.alt.backend.domain.LegMode
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Instant

/** One GTFS-RT Service Alert (la/gtfs-sa). Periods are [start, end) instants; null = open. */
data class ServiceAlert(
    val id: String,
    val periods: List<Pair<Instant?, Instant?>>,
    val stopIds: List<String>,
    val agencyIds: List<String>,
    val cause: String?,
    val effect: String?,
    val header: Map<String, String>,
    val description: Map<String, String>
) {
    fun activeAt(t: Instant): Boolean =
        periods.isEmpty() || periods.any { (s, e) -> (s == null || !t.isBefore(s)) && (e == null || t.isBefore(e)) }
}

data class AlertsSnapshot(val fetchedAt: Instant, val feedTimestamp: Instant?, val alerts: List<ServiceAlert>)

/**
 * Minimal protobuf decoder for Service Alerts, separate from the team's TripUpdates decoder.
 * Official gtfs-realtime.proto field numbers: FeedMessage{1 header, 2 entity}, FeedHeader{3 timestamp},
 * FeedEntity{1 id, 2 is_deleted, 5 alert}, Alert{1 active_period, 5 informed_entity, 6 cause, 7 effect,
 * 10 header_text, 11 description_text}, TimeRange{1 start, 2 end}, EntitySelector{1 agency_id, 5 stop_id},
 * TranslatedString{1 translation}, Translation{1 text, 2 language}. Mirrors analytics/sbb_duration/gtfs_alerts.py.
 */
object ServiceAlertsDecoder {
    private val CAUSES = mapOf(1 to "UNKNOWN_CAUSE", 2 to "OTHER_CAUSE", 3 to "TECHNICAL_PROBLEM", 4 to "STRIKE",
        5 to "DEMONSTRATION", 6 to "ACCIDENT", 7 to "HOLIDAY", 8 to "WEATHER", 9 to "MAINTENANCE", 10 to "CONSTRUCTION",
        11 to "POLICE_ACTIVITY", 12 to "MEDICAL_EMERGENCY")
    private val EFFECTS = mapOf(1 to "NO_SERVICE", 2 to "REDUCED_SERVICE", 3 to "SIGNIFICANT_DELAYS", 4 to "DETOUR",
        5 to "ADDITIONAL_SERVICE", 6 to "MODIFIED_SERVICE", 7 to "OTHER_EFFECT", 8 to "UNKNOWN_EFFECT", 9 to "STOP_MOVED",
        10 to "NO_EFFECT", 11 to "ACCESSIBILITY_ISSUE")

    private class Field(val number: Int, val varint: Long, val bytes: ByteArray?)

    private fun fields(b: ByteArray): List<Field> {
        val out = mutableListOf<Field>()
        var i = 0
        fun varint(): Long {
            var shift = 0; var v = 0L
            while (true) {
                val byte = b[i++].toInt() and 0xFF
                v = v or ((byte and 0x7F).toLong() shl shift)
                if (byte < 0x80) return v
                shift += 7
            }
        }
        while (i < b.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += Field(number, varint(), null)
                2 -> { val n = varint().toInt(); out += Field(number, 0, b.copyOfRange(i, i + n)); i += n }
                1 -> i += 8
                5 -> i += 4
                else -> error("unsupported wire type in Service Alerts")
            }
        }
        return out
    }

    private fun translations(b: ByteArray): Map<String, String> = fields(b).filter { it.number == 1 }.associate { t ->
        val parts = fields(t.bytes!!)
        val text = parts.firstOrNull { it.number == 1 }?.bytes?.decodeToString() ?: ""
        val lang = parts.firstOrNull { it.number == 2 }?.bytes?.decodeToString()?.ifBlank { null } ?: "und"
        lang to text
    }

    fun parse(feed: ByteArray, fetchedAt: Instant = Instant.now()): AlertsSnapshot {
        var feedTs: Instant? = null
        val alerts = mutableListOf<ServiceAlert>()
        for (f in fields(feed)) {
            when (f.number) {
                1 -> feedTs = fields(f.bytes!!).firstOrNull { it.number == 3 }?.let { Instant.ofEpochSecond(it.varint) }
                2 -> {
                    val e = fields(f.bytes!!)
                    if (e.any { it.number == 2 && it.varint != 0L }) continue
                    val raw = e.firstOrNull { it.number == 5 }?.bytes ?: continue
                    val id = e.firstOrNull { it.number == 1 }?.bytes?.decodeToString() ?: continue
                    val periods = mutableListOf<Pair<Instant?, Instant?>>()
                    val stops = mutableListOf<String>(); val agencies = mutableListOf<String>()
                    var cause: String? = null; var effect: String? = null
                    var header = emptyMap<String, String>(); var description = emptyMap<String, String>()
                    for (a in fields(raw)) when (a.number) {
                        1 -> fields(a.bytes!!).let { p ->
                            periods += p.firstOrNull { it.number == 1 }?.let { Instant.ofEpochSecond(it.varint) } to
                                p.firstOrNull { it.number == 2 }?.let { Instant.ofEpochSecond(it.varint) }
                        }
                        5 -> fields(a.bytes!!).forEach { s ->
                            if (s.number == 5) stops += s.bytes!!.decodeToString()
                            if (s.number == 1) agencies += s.bytes!!.decodeToString()
                        }
                        6 -> cause = CAUSES[a.varint.toInt()] ?: a.varint.toString()
                        7 -> effect = EFFECTS[a.varint.toInt()] ?: a.varint.toString()
                        10 -> header = translations(a.bytes!!)
                        11 -> description = translations(a.bytes!!)
                    }
                    alerts += ServiceAlert(id, periods, stops, agencies, cause, effect, header, description)
                }
            }
        }
        return AlertsSnapshot(fetchedAt, feedTs, alerts)
    }
}

data class AlertMatch(val alert: ServiceAlert, val stops: List<Pair<Int, String>>)  // (legIndex, stop-place sloid)

/** Alerts active at [at] whose informed stops (stop places) are boarded, passed or alighted by a transit leg. */
object ServiceAlertMatcher {
    fun stopPlace(id: String): String? = Regex("^ch:1:sloid:(\\d+)").find(id)?.groupValues?.get(1)?.let { "ch:1:sloid:$it" }

    fun match(journey: Journey, snapshot: AlertsSnapshot, at: Instant): List<AlertMatch> {
        val legStops = journey.legs.withIndex().filter { it.value.mode == LegMode.TRANSIT }.flatMap { (i, leg) ->
            (listOfNotNull(leg.originSloid) + leg.intermediateSloids + listOfNotNull(leg.destinationSloid))
                .mapNotNull { stopPlace(it) }.map { i to it }
        }
        if (legStops.isEmpty()) return emptyList()
        return snapshot.alerts.filter { it.activeAt(at) }.mapNotNull { alert ->
            val places = alert.stopIds.mapNotNull { stopPlace(it) }.toSet()
            val hits = legStops.filter { it.second in places }.distinct()
            if (hits.isEmpty()) null else AlertMatch(alert, hits)
        }
    }
}

/** Alerts with the same title (direction ignored) and the same matched stations, merged. */
data class AlertGroup(
    val alerts: List<ServiceAlert>,
    val stops: List<Pair<Int, String>>,
    val operators: List<String>,
    /** "A - B" route parts of the English (else any) titles, in feed order: one entry per direction seen. */
    val directions: List<String>
)

object ServiceAlertGrouper {
    private fun title(a: ServiceAlert) = a.header["en"] ?: a.header.values.firstOrNull() ?: ""

    /** "Interrupted service: Allaman - Renens VD" and "...: Renens VD - Allaman" share one key. */
    fun key(a: ServiceAlert, stops: List<Pair<Int, String>>): String {
        val t = title(a)
        val (kind, route) = if (": " in t) t.substringBefore(": ") to t.substringAfter(": ") else "" to t
        val ends = route.split(" - ").map { it.trim() }.sorted().joinToString(" | ")
        return "$kind#$ends#" + stops.map { it.second }.toSortedSet().joinToString(",")
    }

    fun group(matches: List<AlertMatch>): List<AlertGroup> =
        matches.groupBy { key(it.alert, it.stops) }.values.map { same ->
            AlertGroup(
                alerts = same.map { it.alert },
                stops = same.flatMap { it.stops }.distinct(),
                operators = same.flatMap { it.alert.agencyIds }.distinct(),
                directions = same.map { title(it.alert).substringAfter(": ") }.distinct()
            )
        }
}

/**
 * la/gtfs-sa, fetched at most once per [ttlMs]; a snapshot stays usable for [staleMs]. The key is only a header.
 * Stale-while-revalidate like GtfsRealtimeSource: an expired snapshot is returned at once while one background fetch
 * refreshes it; only the very first call (no snapshot yet) waits. No lock is held across the network call.
 */
class ServiceAlertsSource(
    private val fetch: suspend () -> ByteArray,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 60_000,
    private val staleMs: Long = 300_000,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val log = LoggerFactory.getLogger(ServiceAlertsSource::class.java)
    private val mutex = Mutex()
    @Volatile private var snapshot: AlertsSnapshot? = null
    @Volatile private var lastAttemptMs = Long.MIN_VALUE / 2
    @Volatile private var inflight: Deferred<Unit>? = null

    private fun usable(): AlertsSnapshot? = snapshot?.takeIf { nowMs() - it.fetchedAt.toEpochMilli() < staleMs }

    suspend fun current(): AlertsSnapshot? {
        val s = snapshot
        val age = s?.let { nowMs() - it.fetchedAt.toEpochMilli() }
        if (s != null && age!! < ttlMs) return s
        val refresh = refresh()
        if (s != null && age!! < staleMs) return s
        refresh.await()
        return usable()
    }

    /** Wait for a running refresh (tests). */
    internal suspend fun settle() { inflight?.await() }

    // The mutex only guards starting the single in-flight job, never the fetch itself.
    private suspend fun refresh(): Deferred<Unit> = mutex.withLock {
        inflight?.takeIf { it.isActive } ?: scope.async { doFetch() }.also { inflight = it }
    }

    private suspend fun doFetch() {
        val now = nowMs()
        if (now - lastAttemptMs < ttlMs) return // rate-limit guard: never a second network call within the TTL, success or not
        lastAttemptMs = now
        try {
            val parsed = ServiceAlertsDecoder.parse(fetch(), Instant.ofEpochMilli(now))
            snapshot = parsed
            log.info("Service Alerts refreshed: {} alerts", parsed.alerts.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Service Alerts refresh failed ({}); keeping previous snapshot for up to {} s",
                e.javaClass.simpleName, staleMs / 1000)
        }
    }

    companion object {
        fun httpFetch(url: String, apiKey: String): suspend () -> ByteArray {
            val client = HttpClient(CIO) {
                followRedirects = true
                install(HttpTimeout) { connectTimeoutMillis = 20_000; requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
            }
            return {
                val r = client.get(url) { header(HttpHeaders.Authorization, "Bearer $apiKey") }
                if (r.status.value != 200) error("Service Alerts HTTP ${r.status.value}")
                r.readRawBytes()
            }
        }
    }
}
