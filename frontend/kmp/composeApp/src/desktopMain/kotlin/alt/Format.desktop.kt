@file:OptIn(kotlin.time.ExperimentalTime::class)

package alt

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

private val ZURICH = TimeZone.of("Europe/Zurich")

actual fun fmtZurich(iso: String, withDate: Boolean): String? {
    val inst = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    val t = inst.toLocalDateTime(ZURICH)
    return if (withDate) {
        "${p2(t.day)}.${p2(t.month.ordinal + 1)} ${p2(t.hour)}:${p2(t.minute)}"
    } else {
        "${p2(t.hour)}:${p2(t.minute)}"
    }
}

actual fun localToInstantIso(localIso: String): String? =
    runCatching { LocalDateTime.parse(localIso).toInstant(ZURICH).toString() }.getOrNull()

actual fun todayZurichDate(): String {
    val now = Clock.System.now().toLocalDateTime(ZURICH)
    val mn = now.month.ordinal + 1
    return "${now.year}-${p2(mn)}-${p2(now.day)}"
}
