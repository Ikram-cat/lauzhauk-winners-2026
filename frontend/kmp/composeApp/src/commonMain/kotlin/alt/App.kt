package alt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import org.jetbrains.compose.resources.painterResource
import alt_kmp.composeapp.generated.resources.Res
import alt_kmp.composeapp.generated.resources.alt_mark
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.key.*

/**
 * ALT — Swiss editorial two-page layout:
 *   1. planner (from/to, date/time, depart-or-arrive, easiest/fastest)
 *   2. results (one dominant recommendation + five streamlined alternatives)
 */
@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val st = remember { AppState(scope) }
    val pal = if (st.dark) DarkAlt else LightAlt
    val scheme = if (st.dark) {
        darkColorScheme(primary = pal.red, background = pal.canvas, surface = pal.surface,
            onBackground = pal.ink, onSurface = pal.ink)
    } else {
        lightColorScheme(primary = pal.action, background = pal.canvas, surface = pal.surface,
            onBackground = pal.ink, onSurface = pal.ink)
    }
    var introDone by remember {
        mutableStateOf(prefersReducedMotion() || kvGet("alt-intro", true) == "1")
    }
    MaterialTheme(colorScheme = scheme) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to pal.canvas, 0.55f to pal.canvas, 1f to pal.surface),
            ),
        ) {
          CompositionLocalProvider(LocalCompact provides (maxWidth < 520.dp)) {
            Column(Modifier.fillMaxSize()) {
                Masthead(st, pal)
                if (st.page == PAGE_RESULTS && st.result != null) {
                    ResultsPage(st, pal, Modifier.weight(1f))
                } else {
                    PlannerPage(st, pal, Modifier.weight(1f))
                }
                SiteFooter(st, pal)
            }
            if (!introDone) {
                WelcomeIntro(pal) { introDone = true; kvSet("alt-intro", "1", true) }
            }
          }
        }
    }
}

val LocalCompact = compositionLocalOf { false }

/* ---------------------------------------------------------------- chrome */

@Composable
private fun Masthead(st: AppState, pal: AltPalette) {
    val compact = LocalCompact.current
    Box(Modifier.fillMaxWidth().background(pal.canvas).border(0.dp, pal.rule)) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(72.dp).padding(horizontal = if (compact) 12.dp else 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (st.page == PAGE_RESULTS) {
                    Box(
                        Modifier.height(40.dp).border(1.dp, pal.rule).clickable { st.goBack() }
                            .padding(horizontal = if (compact) 12.dp else 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (compact) "<" else "<  " + st.t("back"), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp, color = pal.ink)
                    }
                    Spacer(Modifier.width(if (compact) 8.dp else 18.dp))
                }
                // The SVG is transparent, so the tile carries the mark: the glyph is dark on
                // the light theme and light on the dark theme, and the tile is the opposite
                // tone so the mark stays legible.
                Box(
                    Modifier.size(44.dp).background(pal.logoTile).border(1.dp, pal.rule),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(Res.drawable.alt_mark),
                        contentDescription = "ALT",
                        modifier = Modifier.size(40.dp),
                        colorFilter = ColorFilter.tint(pal.ink),
                    )
                }
                Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
                Text("ALT", fontSize = 20.sp, fontWeight = FontWeight.Black,
                    letterSpacing = (-1.3).sp, color = pal.ink)
                Spacer(Modifier.weight(1f))
                if (!(compact && st.page == PAGE_RESULTS)) {
                    SwissClock(pal, if (compact) 44.dp else 56.dp)
                    Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
                }
                LangSwitch(st, pal, if (compact) 24.dp else 30.dp)
                Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
                HeaderToggle(
                    text = if (st.dark) "LIGHT" else "DARK",
                    pal = pal,
                    width = 52.dp,
                    onClick = { st.toggleTheme() },
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.ink))
        }
    }
}

@Composable
private fun LangSwitch(st: AppState, pal: AltPalette, cell: androidx.compose.ui.unit.Dp) {
    Row(Modifier.height(40.dp).border(1.dp, pal.rule)) {
        Lang.entries.forEach { l ->
            val on = l == st.lang
            Box(
                Modifier.width(cell).fillMaxHeight().background(if (on) pal.surface else pal.canvas)
                    .clickable { st.switchLang(l) },
                contentAlignment = Alignment.Center,
            ) {
                Text(l.name, fontSize = 9.sp, fontWeight = if (on) FontWeight.Black else FontWeight.Normal,
                    letterSpacing = 0.2.sp, color = pal.ink, maxLines = 1, softWrap = false)
                if (on) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(pal.red))
            }
        }
    }
}

@Composable
private fun HeaderToggle(text: String, pal: AltPalette, width: androidx.compose.ui.unit.Dp = 40.dp, onClick: () -> Unit) {
    Box(
        Modifier.width(width).height(40.dp).border(1.dp, pal.rule).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = pal.ink)
    }
}

@Composable
private fun SiteFooter(st: AppState, pal: AltPalette) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.rule))
    Row(
        Modifier.fillMaxWidth().background(pal.canvas).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            st.t("api.source") + ": " + st.api.base.ifBlank { "same origin" },
            fontSize = 10.sp, color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text("Compose Multiplatform · 24 h · Europe/Zurich", fontSize = 10.sp, color = pal.muted)
    }
}

/* --------------------------------------------------------------- planner */

@Composable
private fun PlannerPage(st: AppState, pal: AltPalette, modifier: Modifier) {
    // The loader IS the loading state: while a search is in flight the form is replaced by
    // the journey map, centred in the viewport, so it can never hide below the fold.
    if (st.loading) {
        Box(modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Eyebrow(st.t("loading"), pal)
                Spacer(Modifier.height(20.dp))
                RouteLoader(st.fromSel, st.toSel, st.fromText, st.toText, pal, st.t("loading"))
            }
        }
        return
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .padding(top = 36.dp, bottom = 48.dp),
        ) {
            Eyebrow(st.t("planner.eyebrow"), pal)
            Spacer(Modifier.height(20.dp))
            Text(
                welcomeText(st.welcome, st.lang),
                maxLines = 4,
                fontSize = if (LocalCompact.current) 34.sp else 48.sp,
                lineHeight = if (LocalCompact.current) 34.sp else 46.sp, fontWeight = FontWeight.Black,
                letterSpacing = (-2.2).sp, color = pal.ink,
            )
            Spacer(Modifier.height(10.dp))
            Text(st.t("planner.sub"), fontSize = 15.sp, color = pal.muted)
            Spacer(Modifier.height(24.dp))

            // FROM
            EditorialField(
                label = st.t("from"),
                value = st.fromText,
                onValue = st::onFromChange,
                placeholder = st.t("from.ph"),
                suggestions = st.fromSug,
                onPick = st::pickFrom,
                pal = pal,
                hint = st.fromSel?.kind?.lowercase(),
                searching = st.fromSearching,
            )
            Spacer(Modifier.height(12.dp))

            // TO + swap
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    EditorialField(
                        label = st.t("to"),
                        value = st.toText,
                        onValue = st::onToChange,
                        placeholder = st.t("to.ph"),
                        suggestions = st.toSug,
                        onPick = st::pickTo,
                        pal = pal,
                        hint = st.toSel?.kind?.lowercase(),
                        searching = st.toSearching,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(48.dp).border(1.dp, pal.rule).clickable { st.swap() },
                    contentAlignment = Alignment.Center,
                ) {
                    SwapIcon(pal.ink)
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    EditorialField(
                        label = st.t("date"),
                        value = st.dateText,
                        onValue = { st.dateText = it },
                        placeholder = "YYYY-MM-DD",
                        suggestions = emptyList(),
                        onPick = {},
                        pal = pal,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    EditorialField(
                        label = if (st.arriveBy) st.t("arrive") else st.t("time"),
                        value = st.timeText,
                        onValue = { st.timeText = it },
                        placeholder = st.t("now"),
                        suggestions = emptyList(),
                        onPick = {},
                        pal = pal,
                        hint = if (st.timeText.isBlank()) "now" else null,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            FieldLabel(if (st.arriveBy) st.t("arrive.label") else st.t("depart.label"), pal)
            Spacer(Modifier.height(6.dp))
            Segmented(
                options = listOf("DEPART" to st.t("depart"), "ARRIVE" to st.t("arrive")),
                selected = if (st.arriveBy) "ARRIVE" else "DEPART",
                onSelect = { st.arriveBy = it == "ARRIVE" },
                pal = pal,
            )

            Spacer(Modifier.height(20.dp))
            FieldLabel(st.t("preference"), pal)
            Spacer(Modifier.height(6.dp))
            Segmented(
                options = listOf("SIMPLEST" to st.t("easiest"), "FASTEST" to st.t("fastest")),
                selected = st.preference,
                onSelect = { st.preference = it },
                pal = pal,
                infos = mapOf("SIMPLEST" to st.t("info.easiest"), "FASTEST" to st.t("info.fastest")),
                infoLabel = st.t("info.label"),
            )

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    st.t("simulate"),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pal.muted,
                    modifier = Modifier.clickable(enabled = !st.loading) { st.simulate() }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                )
            }

            st.error?.let { msg ->
                Spacer(Modifier.height(16.dp))
                ErrorPanel(st.t("error"), msg, st.t("retry"), pal) { st.retry() }
            }

            Spacer(Modifier.height(20.dp))
            SearchButton(st, pal)
        }
    }
}

@Composable
private fun SwapIcon(color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val st = 1.6.dp.toPx()
        drawLine(color, Offset(w * 0.30f, h), Offset(w * 0.30f, 0f), st, StrokeCap.Butt)
        drawLine(color, Offset(w * 0.30f, 0f), Offset(w * 0.16f, h * 0.24f), st, StrokeCap.Butt)
        drawLine(color, Offset(w * 0.30f, 0f), Offset(w * 0.44f, h * 0.24f), st, StrokeCap.Butt)
        drawLine(color, Offset(w * 0.70f, 0f), Offset(w * 0.70f, h), st, StrokeCap.Butt)
        drawLine(color, Offset(w * 0.70f, h), Offset(w * 0.56f, h * 0.76f), st, StrokeCap.Butt)
        drawLine(color, Offset(w * 0.70f, h), Offset(w * 0.84f, h * 0.76f), st, StrokeCap.Butt)
    }
}

@Composable
private fun SearchButton(st: AppState, pal: AltPalette) {
    val enabled = !st.loading
    Box(
        Modifier.fillMaxWidth().height(48.dp)
            .background(if (enabled) pal.action else pal.surface)
            .border(1.dp, if (enabled) pal.action else pal.rule)
            .clickable(enabled = enabled) { st.search() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (st.loading) st.t("loading") else st.t("search"),
            fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
            color = if (enabled) pal.actionInk else pal.muted,
        )
    }
}

/* ------------------------------------------------------------- primitives */

@Composable
fun Eyebrow(text: String, pal: AltPalette, color: androidx.compose.ui.graphics.Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(pal.red))
        Spacer(Modifier.width(10.dp))
        Text(
            text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp, color = color ?: pal.muted,
        )
    }
}

@Composable
fun FieldLabel(text: String, pal: AltPalette) {
    Text(
        text.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.3.sp, color = pal.muted,
    )
}

@Composable
private fun EditorialField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    suggestions: List<Location>,
    onPick: (Location) -> Unit,
    pal: AltPalette,
    hint: String? = null,
    searching: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(label, pal)
            if (searching) {
                Spacer(Modifier.width(8.dp))
                Text("...", fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = pal.red)
            }
            if (hint != null) {
                Spacer(Modifier.width(8.dp))
                Text(hint.uppercase(), fontSize = 9.sp, letterSpacing = 0.8.sp, color = pal.muted)
            }
        }
        Spacer(Modifier.height(5.dp))
        var focused by remember { mutableStateOf(false) }
        Box(
            Modifier.fillMaxWidth().height(48.dp).background(pal.canvas)
                .border(1.dp, if (focused) pal.ink else pal.rule)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(color = pal.ink, fontSize = 16.sp, fontFamily = AltSans),
                cursorBrush = SolidColor(pal.red),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, fontSize = 16.sp, color = pal.muted)
                    inner()
                },
            )
        }
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Column(Modifier.fillMaxWidth().border(1.dp, pal.rule).background(pal.canvas)) {
                suggestions.forEachIndexed { i, loc ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .clickable { onPick(loc) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            loc.name, fontSize = 14.sp, color = pal.ink,
                            modifier = Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        loc.kind?.let {
                            Text(it.uppercase(), fontSize = 9.sp, letterSpacing = 0.8.sp,
                                fontWeight = FontWeight.Bold, color = pal.muted)
                        }
                    }
                    if (i < suggestions.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.rule))
                    }
                }
            }
        }
    }
}

@Composable
fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    pal: AltPalette,
    modifier: Modifier = Modifier,
    infos: Map<String, String> = emptyMap(),
    infoLabel: String = "",
) {
    var open by remember { mutableStateOf<String?>(null) }
    Row(modifier.fillMaxWidth().height(48.dp).border(1.dp, pal.rule)) {
        options.forEachIndexed { i, (key, label) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(pal.rule))
            val on = key == selected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (on) pal.surface else pal.canvas)
                    .clickable { onSelect(key) },
                contentAlignment = Alignment.Center,
            ) {
                val info = infos[key]
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(start = if (info != null) 44.dp else 0.dp), contentAlignment = Alignment.Center) {
                        Text(
                            label, fontSize = 13.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = pal.ink, textAlign = TextAlign.Center, maxLines = 2,
                        )
                    }
                    if (info != null) {
                        Box(
                            Modifier.size(44.dp).clickable { open = if (open == key) null else key },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(22.dp).border(1.dp, pal.ink, androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center) {
                                Text("?", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pal.ink)
                            }
                        }
                        if (open == key) InfoPopup(info, pal) { open = null }
                    }
                }
                if (on) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                            .background(pal.red),
                    )
                }
            }
        }
    }
}

@Composable
private fun SquareCheck(checked: Boolean, label: String, pal: AltPalette, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).border(1.dp, pal.ink)
                .background(if (checked) pal.ink else pal.canvas),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Box(Modifier.size(8.dp).background(pal.canvas))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, color = pal.ink)
    }
}

@Composable
fun ErrorPanel(title: String, message: String, action: String, pal: AltPalette, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, pal.red).padding(14.dp)) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp, color = pal.red)
        Spacer(Modifier.height(4.dp))
        Text(message, fontSize = 13.sp, color = pal.ink)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.height(36.dp).border(1.dp, pal.ink).clickable { onAction() }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(action, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pal.ink)
        }
    }
}

/** Small info panel under its anchor, clamped to the window; closes on outside click and Escape. */
@Composable
private fun InfoPopup(text: String, pal: AltPalette, onClose: () -> Unit) {
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.roundToPx() }
    val provider = remember(margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                                           layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.right - popupContentSize.width)
                    .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                val below = anchorBounds.bottom + 4
                val y = if (below + popupContentSize.height + margin <= windowSize.height) below
                        else (anchorBounds.top - popupContentSize.height - 4).coerceAtLeast(margin)
                return IntOffset(x, y)
            }
        }
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    Popup(popupPositionProvider = provider, onDismissRequest = onClose,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(
            Modifier.widthIn(max = 300.dp).background(pal.canvas).border(1.dp, pal.ink).padding(14.dp)
                .androidx_focus(focus)
                .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onClose(); true } else false },
        ) {
            Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = pal.ink)
        }
    }
}

private fun Modifier.androidx_focus(r: androidx.compose.ui.focus.FocusRequester): Modifier =
    this.then(Modifier.focusRequester(r).focusable())
