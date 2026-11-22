package alt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Sbb = Color(0xFFEB0000)

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val st = remember { AppState(scope) }
    val scheme = if (st.dark) darkColorScheme(primary = Sbb) else lightColorScheme(primary = Sbb)
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Header(st)
                SearchPanel(st)
                HorizontalDivider()
                Box(Modifier.weight(1f)) { ResultsPane(st) }
                Footer(st)
            }
        }
    }
}

@Composable
private fun Header(st: AppState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("ALT", fontWeight = FontWeight.Black, fontSize = 26.sp, color = Sbb)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        st.t("app.title").substringAfter("— ").ifBlank { st.t("app.title") },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    st.t("app.sub"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Chip(
                if (st.lang == Lang.EN) "EN" else "FR",
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = {
                    st.switchLang(if (st.lang == Lang.EN) Lang.FR else Lang.EN)
                },
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { st.toggleTheme() }) {
                Text(if (st.dark) st.t("light") else st.t("dark"))
            }
        }
    }
}

@Composable
fun Chip(text: String, bg: Color, fg: Color, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        contentColor = fg,
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun SearchPanel(st: AppState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            LocationField(
                modifier = Modifier.weight(1f),
                label = st.t("from"),
                placeholder = st.t("from.ph"),
                text = st.fromText,
                onText = st::onFromChange,
                suggestions = st.fromSug,
                onPick = st::pickFrom,
            )
            Column(Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { st.swap() }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("Swap")
                }
            }
            LocationField(
                modifier = Modifier.weight(1f),
                label = st.t("to"),
                placeholder = st.t("to.ph"),
                text = st.toText,
                onText = st::onToChange,
                suggestions = st.toSug,
                onPick = st::pickTo,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = st.dateText,
                onValueChange = { st.dateText = it },
                label = { Text(st.t("date"), fontSize = 12.sp) },
                placeholder = { Text("—", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.width(170.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = st.timeText,
                onValueChange = { st.timeText = it },
                label = { Text(st.t("time"), fontSize = 12.sp) },
                placeholder = { Text(st.t("now"), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.width(130.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = st.deadlineText,
                onValueChange = { st.deadlineText = it },
                label = { Text(st.t("deadline"), fontSize = 12.sp) },
                placeholder = { Text("—", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.width(170.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(st.t("preference"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Row {
                listOf("FASTEST" to "fastest", "CHEAPEST" to "cheapest", "SIMPLEST" to "simplest").forEach { (code, key) ->
                    FilterChip(
                        selected = st.preference == code,
                        onClick = { st.preference = code },
                        label = { Text(st.t(key), fontSize = 12.sp) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = st.halfFare, onCheckedChange = { st.halfFare = it })
                Spacer(Modifier.width(6.dp))
                Text(st.t("half"), fontSize = 13.sp)
            }
            Spacer(Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = st.firstClass, onCheckedChange = { st.firstClass = it })
                Spacer(Modifier.width(6.dp))
                Text(st.t("first"), fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { st.simulate() }, enabled = !st.loading) {
                Text(st.t("simulate"))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { st.search() }, enabled = !st.loading) {
                Text(st.t("search"), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LocationField(
    modifier: Modifier,
    label: String,
    placeholder: String,
    text: String,
    onText: (String) -> Unit,
    suggestions: List<Location>,
    onPick: (Location) -> Unit,
) {
    Column(modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            label = { Text(label, fontSize = 12.sp) },
            placeholder = { Text(placeholder, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty()) {
            Surface(
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
                Column(Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
                    suggestions.forEachIndexed { i, loc ->
                        Text(
                            loc.name,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(loc) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        if (i < suggestions.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun Footer(st: AppState) {
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${st.t("api.source")}: ${st.api.base.ifBlank { "same origin" }}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text("Compose Multiplatform · Desktop + Wasm", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
