package planb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Small hand-drawn vector glyphs, matching RouteMark's style in JourneyOverview.kt.
 * Compose for Web/Wasm has no bundled color-emoji font, so emoji characters render as
 * blank boxes there; every icon in this app is drawn instead of relying on emoji glyphs.
 */

@Composable
internal fun LightningIcon(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.58f, 0f)
            lineTo(w * 0.12f, h * 0.58f)
            lineTo(w * 0.46f, h * 0.58f)
            lineTo(w * 0.38f, h)
            lineTo(w * 0.92f, h * 0.40f)
            lineTo(w * 0.56f, h * 0.40f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
internal fun CoinIcon(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val stroke = 1.6.dp.toPx()
        drawCircle(color, radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
        drawLine(
            color,
            Offset(size.width * 0.5f, size.height * 0.26f),
            Offset(size.width * 0.5f, size.height * 0.74f),
            stroke, cap = StrokeCap.Round
        )
    }
}

@Composable
internal fun PinIcon(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h)
            cubicTo(w * 0.04f, h * 0.55f, w * 0.14f, 0f, w * 0.5f, 0f)
            cubicTo(w * 0.86f, 0f, w * 0.96f, h * 0.55f, w * 0.5f, h)
            close()
        }
        drawPath(path, color)
        drawCircle(Color.White, radius = h * 0.13f, center = Offset(w * 0.5f, h * 0.36f))
    }
}

@Composable
internal fun WarningTriangleIcon(color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.04f)
            lineTo(w * 0.97f, h * 0.93f)
            lineTo(w * 0.03f, h * 0.93f)
            close()
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
        drawLine(
            color,
            Offset(w * 0.5f, h * 0.38f),
            Offset(w * 0.5f, h * 0.66f),
            stroke, cap = StrokeCap.Round
        )
        drawCircle(color, radius = stroke * 0.9f, center = Offset(w * 0.5f, h * 0.79f))
    }
}

@Composable
internal fun CheckIcon(color: Color, modifier: Modifier = Modifier.size(14.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.52f)
            lineTo(w * 0.40f, h * 0.84f)
            lineTo(w * 0.94f, h * 0.18f)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
internal fun InfoIcon(color: Color, modifier: Modifier = Modifier.size(14.dp)) {
    Canvas(modifier) {
        val stroke = 1.4.dp.toPx()
        val w = size.width
        val h = size.height
        drawCircle(color, radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
        drawCircle(color, radius = stroke * 0.65f, center = Offset(w * 0.5f, h * 0.30f))
        drawLine(color, Offset(w * 0.5f, h * 0.46f), Offset(w * 0.5f, h * 0.74f), stroke, cap = StrokeCap.Round)
    }
}
