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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
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

/* ---- (b) Welcome animation — copied from .intro in frontend/web/style.css of the
   deleted JS app (git a826199~1). 1.85 s, `both` fill, and — as CSS does — the
   animation's timing function is applied to every keyframe interval, not to the whole
   timeline. Phases: square mark holds (0–26 %), frame widens while the red rule and
   ERNATIVE reveal (26–65 %), ALT symbol punctuates at the right (60–70 %), then the
   layer is clipped away from the bottom (77–100 %). ---- */

private const val IntroDuration = 1850

private val EaseFrame = CubicBezierEasing(0.65f, 0f, 0.22f, 1f)   // intro-frame / intro-track
private val EaseReveal = CubicBezierEasing(0.56f, 0f, 0.18f, 1f)  // intro-reveal
private val EaseAway = CubicBezierEasing(0.76f, 0f, 0.24f, 1f)    // intro-away
private val EaseOutLogo = CubicBezierEasing(0f, 0f, 0.58f, 1f)    // ease-out

/** CSS @keyframes lookup: piecewise linear between frame positions, each interval eased. */
private fun kf(p: Float, frames: List<Pair<Float, Float>>, easing: Easing): Float {
    if (p <= frames.first().first) return frames.first().second
    for (i in 1 until frames.size) {
        val (p0, v0) = frames[i - 1]
        val (p1, v1) = frames[i]
        if (p <= p1) {
            val local = if (p1 > p0) (p - p0) / (p1 - p0) else 1f
            return v0 + (v1 - v0) * easing.transform(local)
        }
    }
    return frames.last().second
}

private fun DrawScope.introBackdrop(pal: AltPalette) {
    drawRect(pal.canvas)
    // linear-gradient(165deg, var(--bg) 40%, var(--surface))
    val angle = 165.0 * PI / 180.0
    val dx = sin(angle).toFloat()
    val dy = -cos(angle).toFloat()
    val len = (size.width * kotlin.math.abs(dx) + size.height * kotlin.math.abs(dy)).coerceAtLeast(1f)
    drawRect(
        Brush.linearGradient(
            0f to pal.canvas, 0.4f to pal.canvas, 1f to pal.surface,
            start = Offset(center.x - dx * len / 2f, center.y - dy * len / 2f),
            end = Offset(center.x + dx * len / 2f, center.y + dy * len / 2f),
        ),
    )
    // radial-gradient(ellipse at 72% 112%, rgba(229,57,53,.18), transparent 60%)
    drawRect(
        Brush.radialGradient(
            0f to Color(0x2EE53935), 0.6f to Color.Transparent,
            center = Offset(size.width * 0.72f, size.height * 1.12f),
            radius = maxOf(size.width, size.height) * 0.6f,
        ),
    )
}

@Composable
fun WelcomeIntro(pal: AltPalette, onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        p.animateTo(1f, tween(IntroDuration, easing = LinearEasing))
        onDone()
    }
    val v = p.value
    val away = kf(v, listOf(0f to 0f, 0.77f to 0f, 1f to 1f), EaseAway)
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .drawWithContent {
                clipRect(bottom = size.height * (1f - away)) {
                    introBackdrop(pal)
                    this@drawWithContent.drawContent()
                }
            }
            .graphicsLayer { alpha = 1f - away }
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { onDone(); false }
            .pointerInput(Unit) {
                // The layer is decorative (pointer-events: none in the old app): it never
                // consumes, and any press — which focuses the planner underneath — dismisses
                // it, mirroring the JS `focusin` listener.
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.changes.any { it.pressed }) onDone()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val square = 104.dp
        // font-size: clamp(1.7rem, 6vw, 3rem); letter-spacing: -.085em
        val fs = (maxWidth * 0.06f).coerceIn(27.2.dp, 48.dp)
        // --intro-width: min(100vw - 32px, clamp(228px, 75vw, 400px))
        val wide = dpMin(maxWidth - 32.dp, (maxWidth * 0.75f).coerceIn(228.dp, 400.dp))
            .coerceAtLeast(square)
        val widthPx = kf(
            v,
            listOf(0f to square.value, 0.13f to square.value, 0.26f to square.value,
                0.65f to wide.value, 1f to wide.value),
            EaseFrame,
        )
        val frameAlpha = kf(v, listOf(0f to 0f, 0.13f to 1f, 1f to 1f), EaseFrame)
        val frameScale = 0.92f + 0.08f * kf(v, listOf(0f to 0f, 0.13f to 1f, 1f to 1f), EaseFrame)
        val track = kf(v, listOf(0f to 0f, 0.24f to 0.42f, 0.64f to 1f, 1f to 1f), EaseFrame)
        val reveal = kf(v, listOf(0f to 0f, 0.28f to 0f, 0.64f to 1f, 1f to 1f), EaseReveal)
        val logoIn = kf(v, listOf(0f to 0f, 0.60f to 0f, 0.70f to 1f, 1f to 1f), EaseOutLogo)
        val logoSize = (maxWidth * 0.07f).coerceIn(27.dp, 42.dp)
        val logoPad = (maxWidth * 0.02f).coerceIn(7.dp, 15.dp)

        Box(
            Modifier.size(widthPx.dp, square)
                .graphicsLayer { alpha = frameAlpha; scaleX = frameScale; scaleY = frameScale }
                .background(pal.canvas).border(1.dp, pal.ink).clipToBounds(),
        ) {
            // .intro-word: left = (--intro-square - 1.77em) / 2, line-height 1, nowrap
            Row(
                Modifier.align(Alignment.CenterStart)
                    .offset(x = (square - fs * 1.77f) / 2)
                    .wrapContentWidth(Alignment.Start, unbounded = true),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ALT", fontSize = fs.value.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.085f * fs.value).sp, color = pal.ink,
                    maxLines = 1, softWrap = false)
                Text("ERNATIVE", fontSize = fs.value.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.085f * fs.value).sp, color = pal.ink,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier
                        .drawWithContent {
                            clipRect(right = size.width * reveal) { this@drawWithContent.drawContent() }
                        }
                        .graphicsLayer { alpha = reveal; translationX = -5.dp.toPx() * (1f - reveal) })
            }
            // .intro-mark::after — 3px red rule growing from the left
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(track).height(3.dp).background(pal.red))
            // .intro-logo — the ALT symbol in ink, right-aligned inside the widening frame
            Image(
                painter = painterResource(Res.drawable.alt_mark),
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = logoPad).size(logoSize)
                    .graphicsLayer {
                        alpha = logoIn
                        scaleX = 0.7f + 0.3f * logoIn
                        scaleY = 0.7f + 0.3f * logoIn
                    },
                colorFilter = ColorFilter.tint(pal.ink),
            )
        }
    }
}

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
