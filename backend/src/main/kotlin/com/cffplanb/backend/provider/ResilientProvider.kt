package com.cffplanb.backend.provider

import com.cffplanb.backend.domain.SearchRequest
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** In-memory TTL cache (successes only) plus a consecutive-failure circuit breaker around any provider. */
class ResilientProvider(
    private val delegate: TransportProvider,
    private val cacheEnabled: Boolean = true,
    private val searchTtlMs: Long = 60_000,
    private val locationTtlMs: Long = 600_000,
    private val failureThreshold: Int = 5,
    private val failureWindowMs: Long = 60_000,
    private val openMs: Long = 30_000,
    private val nowMs: () -> Long = System::currentTimeMillis
) : TransportProvider {
    private val log = LoggerFactory.getLogger(ResilientProvider::class.java)
    private class Entry(val expires: Long, val value: Any)
    private val cache = ConcurrentHashMap<Any, Entry>()

    private var failures = 0
    private var firstFailureAt = 0L
    private var openUntil = 0L

    private inline fun <R : Any> cached(key: Any, ttl: Long, call: () -> R, ok: (R) -> Boolean): R {
        val now = nowMs()
        if (cacheEnabled) {
            @Suppress("UNCHECKED_CAST")
            cache[key]?.takeIf { it.expires > now }?.let { return it.value as R }
        }
        val r = call()
        if (cacheEnabled && ok(r)) {
            // shortcut: crude size cap, evict everything when full; fine for a demo-sized key space
            if (cache.size >= 500) cache.entries.removeIf { it.value.expires <= now }.also { if (cache.size >= 500) cache.clear() }
            cache[key] = Entry(now + ttl, r)
        }
        return r
    }

    /** True when calls should fail fast. Also closes an expired open state. */
    @Synchronized private fun isOpen(): Boolean {
        if (openUntil == 0L) return false
        if (nowMs() < openUntil) return true
        openUntil = 0L; failures = 0
        log.info("Upstream circuit breaker closed")
        return false
    }

    @Synchronized private fun record(failed: Boolean) {
        if (!failed) { if (openUntil == 0L) failures = 0; return }
        val now = nowMs()
        if (failures == 0 || now - firstFailureAt > failureWindowMs) { failures = 0; firstFailureAt = now }
        if (++failures >= failureThreshold && openUntil == 0L) {
            openUntil = now + openMs
            log.warn("Upstream circuit breaker opened for {} s after {} consecutive failures", openMs / 1000, failures)
        }
    }

    override suspend fun search(request: SearchRequest): ProviderResult {
        // minute resolution so "now" defaults from separate requests share a key
        val key = Triple(request.origin.trim().lowercase(), request.destination.trim().lowercase(), request.earliestDeparture.toInstant().epochSecond / 60)
        return cached(key, searchTtlMs, {
            if (isOpen()) ProviderResult.Failure(BREAKER_MESSAGE)
            else delegate.search(request).also { record(it.isFailure()) }
        }, { it is ProviderResult.Success })
    }

    override suspend fun searchLocations(query: String): LocationsResult =
        cached("loc:" + query.trim().lowercase(), locationTtlMs, {
            if (isOpen()) LocationsResult.Failure(BREAKER_MESSAGE)
            else delegate.searchLocations(query).also { record(it !is LocationsResult.Success) }
        }, { it is LocationsResult.Success })

    // Cached per (stop, window start minute) for searchTtlMs. Failures never trip the breaker: detection is best-effort and must not take /api/journeys down.
    override suspend fun stopEvents(stop: String, from: java.time.OffsetDateTime, realtime: Boolean): StopEventsResult =
        cached(listOf("ev", realtime, stop.trim().lowercase(), from.toEpochSecond() / 60), searchTtlMs, {
            if (isOpen()) StopEventsResult.Failure(BREAKER_MESSAGE) else delegate.stopEvents(stop, from)
        }, { it is StopEventsResult.Success })

    // Bad user input (unresolvable stop) is not an upstream health signal.
    private fun ProviderResult.isFailure() = this !is ProviderResult.Success && this !is ProviderResult.LocationResolutionError

    companion object { const val BREAKER_MESSAGE = "Transport provider temporarily unavailable" }
}
