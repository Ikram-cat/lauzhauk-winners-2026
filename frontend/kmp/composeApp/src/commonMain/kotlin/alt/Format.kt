@file:OptIn(kotlin.time.ExperimentalTime::class)

package alt

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

val ZURICH = TimeZone.of("Europe/Zurich")

private fun p2(n: Int): String = if (n < 10) "0$n" else "$n"

@OptIn(ExperimentalTime::class)
fun parseInstant(iso: String?): Instant? =
    if (iso.isNullOrBlank()) null else runCatching { Instant.parse(iso) }.getOrNull()

@OptIn(ExperimentalTime::class)
fun LocalDateTime.toApiInstant(): String? = runCatching { toInstant(ZURICH).toString() }.getOrNull()

fun fmtTime(iso: String?): String {
    val inst = parseInstant(iso) ?: return "—"
    val t = inst.toLocalDateTime(ZURICH)
    return "${p2(t.hour)}:${p2(t.minute)}"
}

fun fmtDateTime(iso: String?): String {
    val inst = parseInstant(iso) ?: return "—"
    val t = inst.toLocalDateTime(ZURICH)
    return "${p2(t.day)}.${p2(t.month.ordinal + 1)} ${p2(t.hour)}:${p2(t.minute)}"
}

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
