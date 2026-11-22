package com.cffplanb.backend.service

import com.cffplanb.backend.provider.FareProvider
import com.cffplanb.backend.provider.FareQuote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.OffsetDateTime

/**
 * Quota guard: at most one upstream fare request per "origin|destination" per TTL.
 * Concurrent callers for the same key share one in-flight request. Failures are cached for [negativeTtlMs].
 */
class FareCache(
    private val provider: FareProvider,
    private val ttlMs: Long = 6 * 3_600_000L,
    private val negativeTtlMs: Long = 300_000L,
    private val maxEntries: Int = 500,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private class Entry(var expires: Long, val value: CompletableDeferred<FareQuote?>)
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>() // insertion order = eviction order
    @Volatile private var failStreak = 0

    /** From the last lookups only: no failures OK, 1-2 in a row DEGRADED, 3+ DOWN. */
    fun status() = when { failStreak == 0 -> "OK"; failStreak < 3 -> "DEGRADED"; else -> "DOWN" }

    suspend fun quote(origin: String, destination: String, departure: OffsetDateTime): FareQuote? {
        val key = "${origin.trim().lowercase()}|${destination.trim().lowercase()}"
        var owner = false
        val entry = mutex.withLock {
            val now = nowMs()
            entries[key]?.takeIf { it.expires > now } ?: run {
                entries.remove(key)
                if (entries.size >= maxEntries) entries.values.removeIf { it.expires <= now }
                while (entries.size >= maxEntries) entries.remove(entries.keys.first())
                owner = true
                Entry(now + ttlMs, CompletableDeferred()).also { entries[key] = it }
            }
        }
        if (owner) {
            var q: FareQuote? = null
            try { q = provider.quote(origin, destination, departure) } finally {
                entry.value.complete(q) // waiters never hang, even if this caller is cancelled
                failStreak = if (q == null) failStreak + 1 else 0
                if (q == null) withContext(NonCancellable) { mutex.withLock { entry.expires = minOf(entry.expires, nowMs() + negativeTtlMs) } }
            }
        }
        return entry.value.await()
    }
}
