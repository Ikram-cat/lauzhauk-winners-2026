@file:OptIn(kotlin.time.ExperimentalTime::class)

package alt

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import kotlin.time.Clock
import kotlin.time.Instant

// Same rules as the desktop actuals: kotlinx-datetime carries the Europe/Zurich rules on Apple platforms.
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
    return "${now.year}-${p2(now.month.ordinal + 1)}-${p2(now.day)}"
}

actual fun zurichSecondsOfDay(): Double {
    val t = Clock.System.now().toLocalDateTime(ZURICH)
    return t.hour * 3600.0 + t.minute * 60 + t.second + t.nanosecond / 1e9
}

actual fun prefersReducedMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()

// "session" values live for the app process (like sessionStorage); the others persist in NSUserDefaults.
private val sessionStore = HashMap<String, String>()

actual fun kvGet(key: String, session: Boolean): String =
    if (session) sessionStore[key] ?: "" else NSUserDefaults.standardUserDefaults.stringForKey(key) ?: ""

actual fun kvSet(key: String, value: String, session: Boolean) {
    if (session) sessionStore[key] = value else NSUserDefaults.standardUserDefaults.setObject(value, key)
}

actual val canLoadSvgResources: Boolean = true
