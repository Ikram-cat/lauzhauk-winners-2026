package com.alt.backend.gtfs

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

/**
 * GTFS-RT TripUpdates feed, shared by all requests. One network fetch per [ttlMs] at most (the feed allows 5/min and we use 1/min),
 * single in-flight fetch, a snapshot stays usable for [staleMs] when refreshes fail. The key is only ever sent as a header.
 */
class GtfsRealtimeSource(
    private val fetch: suspend () -> ByteArray,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ttlMs: Long = 60_000,
    private val staleMs: Long = 300_000
) {
    private val log = LoggerFactory.getLogger(GtfsRealtimeSource::class.java)
    private val mutex = Mutex()
    @Volatile private var snapshot: GtfsSnapshot? = null
    @Volatile private var lastAttemptMs = Long.MIN_VALUE / 2
    @Volatile private var inflight: Deferred<Unit>? = null

    /** Latest usable snapshot (at most [staleMs] old) or null. Fresh -> returned; expired -> refreshed in the background; none -> waits for the first fetch. */
    suspend fun current(): GtfsSnapshot? {
        val s = snapshot
        val age = s?.let { nowMs() - it.fetchedAtMs }
        if (s != null && age!! < ttlMs) return s
        if (s != null && age!! < staleMs) { refresh(); return s }
        refresh().await()
        return snapshot?.takeIf { nowMs() - it.fetchedAtMs < staleMs }
    }

    /** Cached state for /api/model; never triggers a fetch. */
    fun peek(): GtfsSnapshot? = snapshot

    fun ageSeconds(): Long? = snapshot?.let { (nowMs() - it.fetchedAtMs) / 1000 }

    /** DOWN without any snapshot, DEGRADED once it is older than two refresh intervals, else OK. Never fetches. */
    fun health(): String { val a = ageSeconds() ?: return "DOWN"; return if (a * 1000 <= 2 * ttlMs) "OK" else "DEGRADED" }

    /** Wait for a running refresh (tests). */
    internal suspend fun settle() { inflight?.await() }

    private suspend fun refresh(): Deferred<Unit> = mutex.withLock {
        inflight?.takeIf { it.isActive } ?: scope.async { doFetch() }.also { inflight = it }
    }

    private suspend fun doFetch() {
        val now = nowMs()
        if (now - lastAttemptMs < ttlMs) return   // rate-limit guard: never a second network call within the TTL, success or not
        lastAttemptMs = now
        try {
            snapshot = GtfsDecoder.parse(fetch(), now)
            snapshot!!.let { log.info("GTFS-RT refreshed: {} trip updates, {} cancelled, {} added, {} skipped stops", it.trips.size, it.cancelled, it.added, it.skippedStops) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("GTFS-RT refresh failed ({}); keeping previous snapshot for up to {} s", e.javaClass.simpleName, staleMs / 1000)
        }
    }

    companion object {
        /** HTTP fetch following redirects, 20 s timeouts. Failure messages never include the key. */
        fun httpFetch(url: String, apiKey: String): suspend () -> ByteArray {
            val client = HttpClient(CIO) {
                followRedirects = true
                install(HttpTimeout) { connectTimeoutMillis = 20_000; requestTimeoutMillis = 20_000; socketTimeoutMillis = 20_000 }
            }
            return {
                val r = client.get(url) { header(HttpHeaders.Authorization, "Bearer $apiKey") }
                if (r.status.value != 200) error("GTFS-RT HTTP ${r.status.value}")
                r.readBytes()
            }
        }
    }
}
