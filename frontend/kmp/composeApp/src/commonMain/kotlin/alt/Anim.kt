package alt

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import alt_kmp.composeapp.generated.resources.Res
import alt_kmp.composeapp.generated.resources.alt_mark
import org.jetbrains.compose.resources.painterResource
import kotlin.math.PI
import kotlin.math.cos
import androidx.compose.ui.unit.min as dpMin
import kotlin.math.sin

/* ---- (a) Swiss railway clock — mirrors clock()/updateClock() in frontend/web/app.mjs (viewBox 100) ---- */

@Composable
fun SwissClock(pal: AltPalette, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    var secs by remember { mutableStateOf(zurichSecondsOfDay()) }
    LaunchedEffect(Unit) { while (true) { withFrameMillis { }; secs = zurichSecondsOfDay() } }
    Canvas(modifier.size(size)) {
        val u = this.size.width / 100f
        val c = Offset(50f * u, 50f * u)
        fun pt(deg: Float, r: Float): Offset {
            val a = (deg - 90f) * (PI.toFloat() / 180f)
            return Offset(c.x + r * u * cos(a), c.y + r * u * sin(a))
        }
        drawCircle(pal.canvas, 48f * u, c)
        drawCircle(pal.ink, 48f * u, c, style = Stroke(1.25f * u))
        for (i in 0 until 60) {
            val major = i % 5 == 0
            drawLine(pal.ink, pt(i * 6f, 44f), pt(i * 6f, if (major) 35f else 41f), (if (major) 2.6f else 1f) * u)
        }
        val h = ((secs / 3600.0) % 12).toFloat()
        val m = ((secs / 60.0) % 60).toFloat()
        val s = (secs % 60.0).toFloat()
        // hour / minute hands: from just past the centre toward the rim
        fun hand(deg: Float, back: Float, len: Float, w: Float) =
            drawLine(pal.ink, pt(deg + 180f, back), pt(deg, len), w * u, StrokeCap.Butt)
        hand(h * 30f, 5f, 24f, 5f)
        hand(m * 6f, 6f, 34f, 3.5f)
        // red paddle second hand: stem 61 -> 15 plus a filled disc at the tip
        drawLine(pal.red, pt(s * 6f + 180f, 11f), pt(s * 6f, 35f), 1.6f * u)
        drawCircle(pal.red, 4f * u, pt(s * 6f, 35f))
        drawCircle(pal.ink, 2f * u, c)
    }
}

/* ---- (b) Welcome animation — keyframes from .intro in frontend/web/style.css ---- */

private fun seg(p: Float, a: Float, b: Float): Float = ((p - a) / (b - a)).coerceIn(0f, 1f)
private fun ease(x: Float): Float = x * x * (3f - 2f * x)

@Composable
fun WelcomeIntro(pal: AltPalette, compact: Boolean, onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        p.animateTo(1f, tween(1850, easing = LinearEasing))
        onDone()
    }
    val v = p.value
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .drawWithContent {
                val away = ease(seg(v, 0.77f, 1f)) // clip-path: inset(0 0 100%) from the bottom up
                clipRect(bottom = size.height * (1f - away)) { this@drawWithContent.drawContent() }
            }
            .background(pal.canvas)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { onDone(); false }
            .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); onDone() } } },
        contentAlignment = Alignment.Center,
    ) {
        val sq = dpMin(96.dp, maxWidth / 4)
        val logo = 56.dp
        val fullW = dpMin(400.dp, maxWidth - 32.dp - logo - 12.dp).coerceAtLeast(sq)
        val w = sq + (fullW - sq) * ease(seg(v, 0.26f, 0.65f))
        val fs = if (fullW < 300.dp) 24.sp else 38.sp
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.graphicsAlpha(seg(v, 0f, 0.13f))) {
            Box(Modifier.size(logo).background(Color.White).border(1.dp, pal.ink), contentAlignment = Alignment.Center) {
                Image(painterResource(Res.drawable.alt_mark), "ALT", Modifier.size(logo - 4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(w, sq).background(pal.canvas).border(1.dp, pal.ink).clipToBounds()) {
                Row(
                    Modifier.align(Alignment.CenterStart).padding(start = (sq - fs.value.dp * 1.77f) / 2)
                        .wrapContentWidth(Alignment.Start, unbounded = true),
                ) {
                    Text("ALT", fontSize = fs, fontWeight = FontWeight.Black, letterSpacing = (-0.085f * fs.value).sp,
                        color = pal.ink, maxLines = 1, softWrap = false)
                    val rev = seg(v, 0.28f, 0.64f)
                    Text("ERNATIVE", fontSize = fs, fontWeight = FontWeight.Black, letterSpacing = (-0.085f * fs.value).sp,
                        color = pal.ink, maxLines = 1, softWrap = false,
                        modifier = Modifier.graphicsAlpha(rev).drawWithContent {
                            clipRect(right = size.width * rev) { this@drawWithContent.drawContent() }
                        })
                }
                val track = if (v < 0.24f) 0.42f * v / 0.24f else 0.42f + 0.58f * seg(v, 0.24f, 0.64f)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(track).height(3.dp).background(pal.red))
                val sig = seg(v, 0.58f, 0.67f)
                Box(Modifier.align(Alignment.TopEnd).padding(13.dp).size(8.dp).graphicsAlpha(sig).background(pal.red))
            }
        }
    }
}

private fun Modifier.graphicsAlpha(a: Float): Modifier = this.graphicsLayer { alpha = a }

/* ---- (c) Switzerland loading map — simplified outline of frontend/web/swiss-boundary.geojson ----
   Mainland ring, Douglas-Peucker to 90 points, in the web app's 540x340 map space
   (x = (lon-5.75)*108, y = (47.9-lat)*153). */

private val OUTLINE = floatArrayOf(35f,225f,44f,209f,39f,202f,76f,172f,74f,148f,104f,131f,142f,86f,122f,84f,133f,62f,157f,62f,153f,70f,163f,73f,179f,71f,199f,47f,210f,46f,207f,56f,223f,48f,254f,52f,264f,43f,291f,51f,311f,39f,287f,36f,305f,14f,323f,22f,322f,32f,329f,34f,330f,25f,340f,39f,357f,33f,394f,43f,422f,68f,424f,80f,408f,96f,402f,130f,446f,134f,446f,148f,470f,162f,484f,158f,496f,139f,512f,147f,500f,186f,512f,197f,510f,208f,491f,207f,485f,193f,464f,207f,463f,223f,477f,228f,470f,238f,474f,256f,464f,256f,455f,233f,418f,247f,401f,228f,401f,213f,381f,215f,378f,255f,352f,285f,350f,296f,361f,306f,353f,319f,342f,317f,340f,297f,328f,292f,335f,279f,309f,272f,291f,253f,292f,220f,252f,250f,260f,268f,229f,303f,194f,293f,157f,312f,146f,312f,130f,283f,121f,283f,124f,272f,113f,270f,120f,251f,110f,237f,116f,225f,82f,221f,63f,229f,51f,242f,60f,255f,43f,269f,24f,271f,24f,258f,40f,253f,45f,235f)

private fun mapX(lon: Double) = ((lon - 5.75) * 108).toFloat()
private fun mapY(lat: Double) = ((47.9 - lat) * 153).toFloat()

@Composable
fun RouteLoader(from: Location?, to: Location?, fromName: String, toName: String, pal: AltPalette, label: String) {
    val reduced = remember { prefersReducedMotion() }
    val t by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)))
    val prog = if (reduced) 0.5f else ((t - 0.10f) / 0.75f).coerceIn(0f, 1f)
    fun pt(l: Location?, fb: Offset): Offset {
        val c = l?.coordinates
        val la = c?.lat; val lo = c?.lon
        return if (la != null && lo != null) Offset(mapX(lo), mapY(la)) else fb
    }
    val a = pt(from, Offset(90f, 135f))
    val b = pt(to, Offset(410f, 135f))
    Column(Modifier.fillMaxWidth().border(1.dp, pal.rule).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(540f / 340f)) {
            val k = size.width / 540f
            val path = Path().apply {
                moveTo(OUTLINE[0] * k, OUTLINE[1] * k)
                for (i in 1 until OUTLINE.size / 2) lineTo(OUTLINE[2 * i] * k, OUTLINE[2 * i + 1] * k)
                close()
            }
            drawPath(path, pal.surface)
            drawPath(path, pal.ink, style = Stroke(1.5.dp.toPx()))
            val pa = a * k; val pb = b * k
            drawLine(pal.muted, pa, pb, 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            val pos = pa + (pb - pa) * prog
            drawLine(pal.red, pa, pos, 3f)
            for (p in listOf(pa, pb)) {
                drawCircle(pal.canvas, 4.dp.toPx(), p); drawCircle(pal.ink, 4.dp.toPx(), p, style = Stroke(2f))
            }
            // small red train: disc with a white carriage
            val r = 13.dp.toPx()
            drawCircle(pal.red, r, pos); drawCircle(pal.canvas, r, pos, style = Stroke(2f))
            drawRoundRect(Color.White, Offset(pos.x - r * 0.55f, pos.y - r * 0.5f), Size(r * 1.1f, r * 0.85f), CornerRadius(2f))
            drawRect(pal.red, Offset(pos.x - r * 0.38f, pos.y - r * 0.32f), Size(r * 0.76f, r * 0.3f))
            drawCircle(Color.White, r * 0.12f, Offset(pos.x - r * 0.3f, pos.y + r * 0.55f))
            drawCircle(Color.White, r * 0.12f, Offset(pos.x + r * 0.3f, pos.y + r * 0.55f))
        }
        Spacer(Modifier.height(8.dp))
        Text(fromName + "  >  " + toName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pal.ink,
            maxLines = 2, modifier = Modifier.fillMaxWidth())
        Text(label, fontSize = 11.sp, color = pal.muted)
    }
}
