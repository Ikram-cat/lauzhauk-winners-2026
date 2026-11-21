@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private val Ink = Color(0xFF17191C)
private val Red = Color(0xFFD71920)

@Composable
internal fun StationClock(language: Language) {
    val copy = language::text
    val colors = MaterialTheme.colorScheme
    var now by remember { mutableStateOf(Clock.System.now()) }
    var digital by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Clock.System.now()
            delay(1_000)
        }
    }
    val local = now.toLocalDateTime(Zurich)
    val display = "${two(local.hour)}:${two(local.minute)}:${two(local.second)}"
    Surface(color = colors.surfaceVariant, contentColor = colors.onSurface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(copy(Copy.ZurichTime), color = colors.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.sp)
            TextButton(onClick = { digital = !digital }, modifier = Modifier.semantics {
                contentDescription = copy(if (digital) Copy.AnalogClock else Copy.DigitalClock)
            }) {
                Box(Modifier.size(206.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val unit = size.width / 240f
                        drawCircle(Color.White, size.width / 2)
                        if (!digital) {
                            repeat(60) { index ->
                                val major = index % 5 == 0
                                rotate(index * 6f) { drawLine(Ink, Offset(120 * unit, 12 * unit),
                                    Offset(120 * unit, (if (major) 36 else 20) * unit), (if (major) 6 else 2.5f).toFloat() * unit) }
                            }
                            rotate((local.hour % 12 + local.minute / 60f) * 30f) {
                                drawLine(Ink, Offset(120 * unit, 141 * unit), Offset(120 * unit, 66 * unit), 14 * unit)
                            }
                            rotate((local.minute + local.second / 60f) * 6f) {
                                drawLine(Ink, Offset(120 * unit, 139 * unit), Offset(120 * unit, 35 * unit), 10 * unit)
                            }
                            rotate(local.second * 6f) {
                                drawLine(Red, Offset(120 * unit, 154 * unit), Offset(120 * unit, 49 * unit), 3 * unit)
                                drawCircle(Red, 11 * unit, Offset(120 * unit, 49 * unit))
                            }
                        }
                    }
                    if (digital) Text(display, color = Ink, fontSize = 31.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
