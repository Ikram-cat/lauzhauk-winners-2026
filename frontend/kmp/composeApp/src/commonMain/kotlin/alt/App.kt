package alt

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.focus.onFocusChanged
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
    MaterialTheme(colorScheme = scheme) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to pal.canvas, 0.55f to pal.canvas, 1f to pal.surface),
            ),
        ) {
            Column(Modifier.fillMaxSize()) {
                Masthead(st, pal)
                if (st.page == PAGE_RESULTS && st.result != null) {
                    ResultsPage(st, pal, Modifier.weight(1f))
                } else {
                    PlannerPage(st, pal, Modifier.weight(1f))
                }
                SiteFooter(st, pal)
            }
        }
    }
}

/* ---------------------------------------------------------------- chrome */

@Composable
private fun Masthead(st: AppState, pal: AltPalette) {
    Box(Modifier.fillMaxWidth().background(pal.canvas).border(0.dp, pal.rule)) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (st.page == PAGE_RESULTS) {
                    Box(
                        Modifier.height(40.dp).border(1.dp, pal.rule).clickable { st.goBack() }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("<  " + st.t("back"), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp, color = pal.ink)
                    }
                    Spacer(Modifier.width(18.dp))
                }
                Box(
                    Modifier.size(40.dp).border(1.dp, pal.ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("ALT", fontSize = 12.sp, fontWeight = FontWeight.Black,
                        letterSpacing = (-0.8).sp, color = pal.ink)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("ALT", fontSize = 20.sp, fontWeight = FontWeight.Black,
                        letterSpacing = (-1.3).sp, color = pal.ink)
                    Text(
                        st.t("app.sub").uppercase(),
                        fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold,
                        color = pal.muted, maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                HeaderToggle(
                    text = if (st.lang == Lang.EN) "FR" else "EN",
                    pal = pal,
                    onClick = { st.switchLang(if (st.lang == Lang.EN) Lang.FR else Lang.EN) },
                )
                Spacer(Modifier.width(8.dp))
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
                st.t("planner.title"),
                fontSize = 48.sp, lineHeight = 46.sp, fontWeight = FontWeight.Black,
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
            )

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SquareCheck(st.halfFare, st.t("half"), pal) { st.halfFare = it }
                Spacer(Modifier.weight(1f))
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
            if (st.loading) {
                Spacer(Modifier.height(10.dp))
                Text(st.t("loading"), fontSize = 12.sp, color = pal.muted)
            }
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
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(label, pal)
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
) {
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
                Text(
                    label, fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = pal.ink, textAlign = TextAlign.Center, maxLines = 1,
                )
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
