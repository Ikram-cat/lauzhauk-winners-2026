package alt

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Swiss editorial tokens — mirrors frontend/DESIGN.md exactly.
 * Helvetica is not bundled with Compose/Wasm, so the canvas uses the platform
 * sans fallback (Helvetica → Arial → sans-serif on the desktop; the DOM shell
 * in index.html declares Helvetica/Arial for HTML chrome). Times use a
 * monospace face so figures stay tabular.
 */
@Immutable
data class AltPalette(
    val canvas: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val rule: Color,
    val red: Color,
    val action: Color,
    val actionInk: Color,
    val orange: Color,
    /** Estimated (statistical) delay — blue per lead's design update (2026-11-22). */
    val estimate: Color,
    val featureBg: Color,
    val featureInk: Color,
    val featureMuted: Color,
    val featureTimeBg: Color,
    val featureRule: Color,
    val clearBg: Color,
)

val LightAlt = AltPalette(
    canvas = Color(0xFFFFFFFF),
    surface = Color(0xFFF2F2F2),
    ink = Color(0xFF101010),
    muted = Color(0xFF555555),
    rule = Color(0xFFC7C7C7),
    red = Color(0xFFE53935),
    action = Color(0xFFB71C1C),
    actionInk = Color(0xFFFFFFFF),
    orange = Color(0xFFFF8B56),
    estimate = Color(0xFF1565C0),
    featureBg = Color(0xFFFFFFFF),
    featureInk = Color(0xFF101010),
    featureMuted = Color(0xFF555555),
    featureTimeBg = Color(0xFFF2F2F2),
    featureRule = Color(0xFFC7C7C7),
    clearBg = Color(0xFFF2F2F2),
)

val DarkAlt = AltPalette(
    canvas = Color(0xFF101010),
    surface = Color(0xFF242424),
    ink = Color(0xFFFFFFFF),
    muted = Color(0xFFC7C7C7),
    rule = Color(0xFF555555),
    red = Color(0xFFE53935),
    action = Color(0xFFE53935),
    actionInk = Color(0xFF101010),
    orange = Color(0xFFFF8B56),
    estimate = Color(0xFF5C9DFF),
    featureBg = Color(0xFF242424),
    featureInk = Color(0xFFFFFFFF),
    featureMuted = Color(0xFFC7C7C7),
    featureTimeBg = Color(0xFF101010),
    featureRule = Color(0xFF555555),
    clearBg = Color(0xFF242424),
)

val AltSans = FontFamily.SansSerif
val AltMono = FontFamily.Monospace

fun timeStyle(size: Int, color: Color, weight: FontWeight = FontWeight.Bold) = TextStyle(
    fontFamily = AltMono,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = (-1).sp,
    color = color,
)
