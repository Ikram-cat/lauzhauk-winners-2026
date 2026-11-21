@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val Red = Color(0xFFD71920)
private val Ink = Color(0xFF17191C)
private val Light = lightColorScheme(
    primary = Red, onPrimary = Color.White, background = Color(0xFFF5F6F4),
    onBackground = Ink, surface = Color.White, onSurface = Ink,
    onSurfaceVariant = Color(0xFF58636E), surfaceVariant = Color(0xFFE9ECE9),
    outlineVariant = Color(0xFFDCE0E2)
)
private val Dark = darkColorScheme(
    primary = Color(0xFFFF8C8C), onPrimary = Color(0xFF370005), background = Color(0xFF15171B),
    onBackground = Color(0xFFF1F2F4), surface = Color(0xFF202329), onSurface = Color(0xFFF1F2F4),
    onSurfaceVariant = Color(0xFFBDC2CA), surfaceVariant = Color(0xFF292D34),
    outlineVariant = Color(0xFF545963)
)

@Composable
internal fun PlanB(api: String, reduceMotion: Boolean = false, onLanguageChanged: (Language) -> Unit = {}) {
    var language by remember { mutableStateOf(Language.French) }
    var darkOverride by remember { mutableStateOf<Boolean?>(null) }
    val useDark = darkOverride ?: isSystemInDarkTheme()
    val colors = if (useDark) Dark else Light
    val copy = language::text
    LaunchedEffect(language) { onLanguageChanged(language) }
    val source = remember(api) { JourneySource(api) }
    DisposableEffect(source) { onDispose { source.close() } }
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf("Lausanne") }
    var destination by remember { mutableStateOf("Genève") }
    var date by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Copy?>(null) }
    var searchedRoute by remember { mutableStateOf<Pair<String, String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var journeys by remember { mutableStateOf(emptyList<Journey>()) }
    var recommendations by remember { mutableStateOf<Recommendations?>(null) }
    var preference by remember { mutableStateOf(Preference.FASTEST) }
    var firstClass by remember { mutableStateOf(false) }
    var halfFare by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<Job?>(null) }
    var railRun by remember { mutableStateOf(0) }
    val anchor = remember { Clock.System.now() }
    val departuresPanel = remember { BringIntoViewRequester() }
    val scroll = rememberScrollState()
    fun search() {
        request?.cancel()
        val from = origin.trim()
        val to = destination.trim()
        val day = date
        val departure = hour
        val mode = preference
        val first = firstClass
        val half = halfFare
        journeys = emptyList()
        recommendations = null
        status = Copy.Searching
        searchedRoute = null
        loading = true
        request = scope.launch {
            try {
                if (from.isEmpty() || to.isEmpty() || from.equals(to, ignoreCase = true)) {
                    throw SearchInputException(Copy.DistinctStations)
                }
                val response = source.search(from, to, swissDeparture(day, departure), mode, first, half)
                journeys = response.journeys
                recommendations = response.recommendations
                selected = journeys.firstOrNull()?.id
                status = if (journeys.isEmpty()) {
                    if (source.demo) Copy.EmptyDemo else Copy.EmptyJourney
                } else null
                if (journeys.isNotEmpty()) {
                    searchedRoute = from to to
                    railRun++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: SearchInputException) {
                status = invalid.feedback
            } catch (_: Exception) {
                status = Copy.ServiceUnavailable
            }
            loading = false
        }
    }

    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                BoxWithConstraints(Modifier.fillMaxHeight().widthIn(max = 1200.dp).fillMaxWidth()) {
                    val compact = maxWidth < 700.dp
                    var motion by remember(reduceMotion) { mutableStateOf(!reduceMotion) }
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scroll)
                            .padding(horizontal = if (compact) 16.dp else 36.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("↗ Plan B", color = colors.primary, fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.weight(1f))
                            Text(copy(if (source.demo) Copy.DemoMode else Copy.ApiMode), color = colors.onSurfaceVariant)
                            TextButton(
                                onClick = { language = if (language == Language.French) Language.English else Language.French },
                                modifier = Modifier.semantics { contentDescription = copy(Copy.UseOtherLanguage) }
                            ) { Text(if (language == Language.French) "EN" else "FR") }
                        }
                        HorizontalDivider(color = colors.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { scope.launch { scroll.scrollTo(0) } }) { Text(copy(Copy.Journeys)) }
                            TextButton(onClick = { scope.launch { departuresPanel.bringIntoView() } }) { Text(copy(Copy.Departures)) }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { darkOverride = !useDark }) {
                                Text(copy(if (useDark) Copy.UseLight else Copy.UseDark))
                            }
                        }
                        val searchPanel: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(copy(Copy.Planner), color = colors.onSurfaceVariant, fontSize = 12.sp, letterSpacing = 2.sp)
                                Text(copy(Copy.YourJourney), fontWeight = FontWeight.Black,
                                    fontSize = if (compact) 38.sp else 56.sp, lineHeight = if (compact) 41.sp else 59.sp)
                                Text(copy(Copy.StartsHere), color = colors.primary, fontWeight = FontWeight.Black,
                                    fontSize = if (compact) 38.sp else 56.sp, lineHeight = if (compact) 41.sp else 59.sp)
                                Text(copy(Copy.Intro), color = colors.onSurfaceVariant)
                                Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 2.dp) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            StationField(copy(Copy.From), origin, { origin = it }, ::search, Modifier.weight(1f))
                                            TextButton(onClick = {
                                                val previous = origin
                                                origin = destination
                                                destination = previous
                                                railRun++
                                            }, modifier = Modifier.semantics { contentDescription = copy(Copy.SwapStations) }) { Text("⇄", fontSize = 22.sp) }
                                            StationField(copy(Copy.To), destination, { destination = it }, ::search, Modifier.weight(1f))
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(date, { date = it }, Modifier.weight(1f), label = { Text(copy(Copy.Date)) }, placeholder = { Text(copy(Copy.DateFormat)) }, singleLine = true)
                                            OutlinedTextField(hour, { hour = it }, Modifier.weight(1f), label = { Text(copy(Copy.SwissTime)) }, placeholder = { Text("HH:mm") }, singleLine = true)
                                        }
                                        Text(copy(Copy.DateHint), color = colors.onSurfaceVariant, fontSize = 12.sp)
                                        Button(onClick = ::search, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) { Text(copy(Copy.Search)) }
                                    }
                                }
                            }
                        }
                        if (compact) {
                            searchPanel()
                            StationClock(anchor, source.demo, language)
                        } else Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1.55f)) { searchPanel() }
                            Box(Modifier.weight(1f)) { StationClock(anchor, source.demo, language) }
                        }
                        Track(origin, destination, railRun, motion)
                        val feedback = status?.let(copy) ?: searchedRoute?.let { (from, to) ->
                            "$from → $to · ${language.count(journeys.size, Copy.Journey, Copy.JourneyPlural)}"
                        }
                        if (feedback != null) {
                            Text(copy(Copy.Journeys), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            SingleChoiceSegmentedButtonRow(Modifier.semantics { contentDescription = copy(Copy.SortBy) }) {
                                for ((index, mode) in Preference.entries.withIndex()) SegmentedButton(
                                    selected = preference == mode,
                                    onClick = { if (preference != mode) { preference = mode; search() } },
                                    shape = SegmentedButtonDefaults.itemShape(index, Preference.entries.size)
                                ) { Text(copy(mode.label)) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                                itemVerticalAlignment = Alignment.CenterVertically) {
                                SingleChoiceSegmentedButtonRow(Modifier.semantics { contentDescription = copy(Copy.TravelClass) }) {
                                    for ((index, first) in listOf(false, true).withIndex()) SegmentedButton(
                                        selected = firstClass == first,
                                        onClick = { if (firstClass != first) { firstClass = first; search() } },
                                        shape = SegmentedButtonDefaults.itemShape(index, 2)
                                    ) { Text(copy(if (first) Copy.FirstClass else Copy.SecondClass)) }
                                }
                                Row(Modifier.toggleable(halfFare, role = Role.Switch) { halfFare = it; search() },
                                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Switch(halfFare, onCheckedChange = null)
                                    Text(copy(Copy.HalfFare))
                                }
                            }
                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(feedback, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.onSurfaceVariant)
                            for (journey in journeys) {
                                val expanded = selected == journey.id
                                OutlinedCard(onClick = { selected = if (expanded) null else journey.id; if (!expanded) railRun++ }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        val start = Instant.parse(journey.departure)
                                        val end = Instant.parse(journey.arrival)
                                        val crossesDay = start.toLocalDateTime(Zurich).date != end.toLocalDateTime(Zurich).date
                                        fun label(instant: Instant) = time(instant) +
                                            if (crossesDay) " · ${instant.toLocalDateTime(Zurich).date}" else ""
                                        val badges = Preference.entries.filter { recommendations?.id(it) == journey.id }
                                        if (badges.isNotEmpty()) Text(badges.joinToString(" · ") { copy(it.label) },
                                            color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("${label(start)} → ${label(end)}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                            journey.fare(firstClass, halfFare)?.let { Text(chf(it), fontWeight = FontWeight.Bold) }
                                        }
                                        if (halfFare && journey.fare(firstClass, true) != null) journey.fullFare(firstClass)?.let {
                                            Text(chf(it), Modifier.align(Alignment.End).semantics { contentDescription = "${copy(Copy.FullPrice)} ${chf(it)}" },
                                                color = colors.onSurfaceVariant, fontSize = 12.sp, textDecoration = TextDecoration.LineThrough)
                                        }
                                        val provenance = when (journey.dataStatus) {
                                            "MOCK" -> Copy.Demo
                                            "LIVE_SCHEDULE" -> Copy.ProviderSchedule
                                            else -> Copy.UnknownSource
                                        }
                                        val transfer = if (journey.transfers == 0) copy(Copy.Direct)
                                            else language.count(journey.transfers, Copy.Transfer, Copy.TransferPlural)
                                        Text("${journey.durationMinutes} min · $transfer · ${copy(provenance)}", color = colors.onSurfaceVariant)
                                        if (expanded) for (leg in journey.legs) {
                                            Text("${time(Instant.parse(leg.departure))} ${leg.origin} → ${time(Instant.parse(leg.arrival))} ${leg.destination}")
                                            val details = listOfNotNull(leg.serviceLabel, leg.departurePlatform?.let { "${copy(Copy.Platform)} $it" })
                                            if (details.isNotEmpty()) Text(details.joinToString(" · "), color = colors.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        Column(Modifier.bringIntoViewRequester(departuresPanel), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(copy(Copy.Upcoming), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(copy(Copy.DemoData), color = colors.onSurfaceVariant)
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                BoardRow(listOf(Copy.Departure, Copy.Destination, Copy.Platform, Copy.Status).map(copy), true)
                                for (service in services) {
                                    HorizontalDivider()
                                    BoardRow(listOf(time(anchor + service.offset.minutes), service.destination,
                                        service.platform, if (service.delay > 0) "+${service.delay} min" else copy(Copy.OnTime)))
                                }
                            }
                        }
                        Text(copy(Copy.Prototype), color = colors.onSurfaceVariant, fontSize = 12.sp)
                        TextButton(onClick = { motion = !motion }) { Text(copy(if (motion) Copy.ReduceMotion else Copy.EnableMotion)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationField(label: String, value: String, update: (String) -> Unit, search: () -> Unit, modifier: Modifier) {
    val stations = listOf("Lausanne", "Genève", "Bern", "Zürich HB", "Fribourg", "Biel/Bienne")
    val suggestion = if (value.trim().length >= 2) stations.firstOrNull {
        it.startsWith(value.trim(), ignoreCase = true) && !it.equals(value.trim(), ignoreCase = true)
    } else null
    Column(modifier) {
        OutlinedTextField(value, { update(it.take(120)) }, Modifier.fillMaxWidth(), label = { Text(label) },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }))
        if (suggestion != null) TextButton(onClick = { update(suggestion) }) { Text(suggestion) }
    }
}

@Composable
private fun BoardRow(values: List<String>, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
        for ((index, value) in values.withIndex()) Text(
            value, Modifier.weight(if (index == 1) 1.45f else 1f).padding(horizontal = 4.dp),
            fontSize = 13.sp, fontWeight = if (header || index < 3) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun StationClock(anchor: Instant, demo: Boolean, language: Language) {
    val copy = language::text
    val colors = MaterialTheme.colorScheme
    var now by remember { mutableStateOf(Clock.System.now()) }
    var digital by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Clock.System.now()
            delay(250)
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
            if (demo) {
                val departure = anchor + services.first().offset.minutes
                val seconds = (departure - now).inWholeSeconds
                val countdown = if (seconds > 0) "${copy(Copy.In)} ${two((seconds / 60).toInt())}:${two((seconds % 60).toInt())}"
                    else copy(Copy.DeparturePassed)
                Text(copy(Copy.NextDeparture), color = colors.onSurfaceVariant, fontSize = 12.sp)
                Text("Lausanne → Genève · ${time(departure)}", fontWeight = FontWeight.Bold)
                Text(countdown, color = colors.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Track(origin: String, destination: String, run: Int, motion: Boolean) {
    val colors = MaterialTheme.colorScheme
    val position = remember { Animatable(.28f) }
    LaunchedEffect(run, motion) {
        position.snapTo(if (motion) 0f else .28f)
        if (motion) position.animateTo(1f, tween(1250))
    }
    Column {
        Canvas(Modifier.fillMaxWidth().height(29.dp)) {
            val y = size.height - 5.dp.toPx()
            val trainWidth = 42.dp.toPx()
            val x = (size.width - trainWidth).coerceAtLeast(0f) * position.value
            drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y), 3.dp.toPx())
            drawLine(colors.primary, Offset(0f, y), Offset(x + trainWidth / 2, y), 3.dp.toPx())
            drawRoundRect(colors.primary, Offset(x, y - 16.dp.toPx()), androidx.compose.ui.geometry.Size(trainWidth, 12.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            repeat(4) { index ->
                drawRect(colors.onPrimary, Offset(x + (5 + index * 8).dp.toPx(), y - 13.dp.toPx()),
                    androidx.compose.ui.geometry.Size(4.dp.toPx(), 3.dp.toPx()))
            }
            drawCircle(colors.primary, 4.dp.toPx(), Offset(4.dp.toPx(), y))
            drawCircle(colors.primary, 4.dp.toPx(), Offset(size.width - 4.dp.toPx(), y))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(origin, color = colors.onSurfaceVariant, fontSize = 12.sp)
            Text(destination, color = colors.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}
