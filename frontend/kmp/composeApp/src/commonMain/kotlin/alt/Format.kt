@file:OptIn(kotlin.time.ExperimentalTime::class)

package alt

import kotlin.math.abs
import kotlin.math.roundToInt

internal fun p2(n: Int): String = if (n < 10) "0$n" else "$n"

// Platform-specific Europe/Zurich conversions (kotlinx-datetime has no tz database on Wasm;
// there we use the browser's Intl API instead).
expect fun fmtZurich(iso: String, withDate: Boolean): String?
expect fun localToInstantIso(localIso: String): String?
expect fun todayZurichDate(): String

fun fmtTime(iso: String?): String = (iso?.let { fmtZurich(it, false) }) ?: "—"

fun fmtDateTime(iso: String?): String = (iso?.let { fmtZurich(it, true) }) ?: "—"

fun fmtDuration(minutes: Int?): String {
    if (minutes == null) return "—"
    val h = minutes / 60
    val m = minutes % 60
    return if (h == 0) "${m} min" else "${h}h${p2(m)}"
}

/** Signed delay badge, rounded sensibly. */
fun fmtDelay(d: Double?, roundUp: Boolean = false): String? {
    if (d == null) return null
    val v = if (roundUp) kotlin.math.ceil(d) else kotlin.math.floor(d)
    if (abs(v) < 0.5) return null
    val sign = if (v > 0) "+" else "−"
    return "$sign${abs(v).roundToInt()}"
}

/** Small one-decimal estimate badge, e.g. "+1.4" / "−0.3". */
fun fmtEstimate(d: Double?): String? {
    if (d == null) return null
    if (abs(d) < 0.05) return null
    val rounded = (abs(d) * 10).roundToInt() / 10.0
    if (rounded == 0.0) return null
    val sign = if (d > 0) "+" else "−"
    return "$sign$rounded"
}

fun fmtChf(v: Double?): String? {
    if (v == null) return null
    val cents = (v * 100).roundToInt()
    val whole = cents / 100
    val frac = cents % 100
    return "CHF $whole.${p2(frac)}"
}

fun fmtPct(v: Double?): String? {
    if (v == null) return null
    return "${(v * 100).roundToInt()}%"
}

fun fmtRate(v: Double?): String? {
    if (v == null) return null
    val p = v * 100
    return if (p >= 1) "${p.roundToInt()}%" else "<1%"
}

// Platform hooks for the animations/welcome copy.
expect fun zurichSecondsOfDay(): Double
expect fun prefersReducedMotion(): Boolean
expect fun kvGet(key: String, session: Boolean): String
expect fun kvSet(key: String, value: String, session: Boolean)
