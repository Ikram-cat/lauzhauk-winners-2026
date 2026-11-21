@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
internal fun PlanB(api: String, onLanguageChanged: (Language) -> Unit = {}) {
    var language by remember { mutableStateOf(Language.French) }
    var darkOverride by remember { mutableStateOf<Boolean?>(null) }
    val useDark = darkOverride ?: isSystemInDarkTheme()
    val colors = if (useDark) Dark else Light
    val copy = language::text
    LaunchedEffect(language) { onLanguageChanged(language) }
    val source = remember(api) { JourneySource(api) }
    DisposableEffect(source) { onDispose { source.close() } }
    var health by remember(source) { mutableStateOf(Copy.CheckingApi) }
    var model by remember(source) { mutableStateOf<ModelInfo?>(null) }
    LaunchedEffect(source) {
        if (!source.demo) {
            try {
                model = source.model()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                model = null
            }
        }
    }
    LaunchedEffect(source) {
        if (!source.demo) {
            health = try {
                if (source.health()) Copy.ApiAvailable else Copy.ApiUnavailable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Copy.ApiUnavailable
            }
        }
    }
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf("Lausanne") }
    var destination by remember { mutableStateOf("Genève") }
    var date by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("") }
    var showOptions by remember { mutableStateOf(false) }
    var directOnly by remember { mutableStateOf(false) }
    var deadlineDate by remember { mutableStateOf("") }
    var deadlineTime by remember { mutableStateOf("") }
    var appliedOptions by remember { mutableStateOf(JourneyOptions()) }
    var status by remember { mutableStateOf<Copy?>(null) }
    var searchedRoute by remember { mutableStateOf<Pair<String, String>?>(null) }
    var journeys by remember { mutableStateOf(emptyList<Journey>()) }
    var expandedJourneyId by remember { mutableStateOf<String?>(null) }
    var chosenJourneyId by remember { mutableStateOf<String?>(null) }
    var responseInfo by remember { mutableStateOf<JourneyResponse?>(null) }
    var rescueFrom by remember { mutableStateOf("") }
    var showRescue by remember { mutableStateOf(false) }
    var fareProfile by remember { mutableStateOf(FareProfile()) }
    var showTravelcards by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<Job?>(null) }
    val anchor = remember { Clock.System.now() }
    val departuresPanel = remember { BringIntoViewRequester() }
    val scroll = rememberScrollState()
    val visibleJourneys = remember(journeys, appliedOptions) { appliedOptions.applyTo(journeys) }
    fun search(rescue: Boolean) {
        request?.cancel()
        val from = (if (rescue) rescueFrom else origin).let { if (source.demo) canonicalStationInput(it) else it.trim() }
        val to = if (source.demo) canonicalStationInput(destination) else destination.trim()
        val day = date
        val departure = hour
        val requestedDeadlineDate = deadlineDate
        val requestedDeadlineTime = deadlineTime
        val requestedDirectOnly = directOnly
        journeys = emptyList()
        expandedJourneyId = null
        chosenJourneyId = null
        responseInfo = null
        status = Copy.Searching
        searchedRoute = null
        request = scope.launch {
            try {
                if (from.isEmpty() || to.isEmpty() || from.equals(to, ignoreCase = true)) {
                    throw SearchInputException(Copy.DistinctStations)
                }
                val start = if (rescue) Clock.System.now() else swissDeparture(day, departure)
                val deadline = swissDeparture(requestedDeadlineDate, requestedDeadlineTime)
                if (start != null && deadline != null && deadline < start) {
                    throw SearchInputException(Copy.DeadlineBeforeStart)
                }
                val options = JourneyOptions(requestedDirectOnly, deadline.takeIf { source.demo })
                val response = if (rescue) {
                    source.alternatives(from, to, checkNotNull(start), deadline)
                } else source.search(from, to, start, deadline = deadline)
                appliedOptions = options
                responseInfo = response
                journeys = response.journeys
                expandedJourneyId = (options.applyTo(journeys).firstOrNull() ?: journeys.firstOrNull())?.id
                status = if (journeys.isEmpty()) {
                    if (source.demo) Copy.EmptyDemo else Copy.EmptyJourney
                } else null
                searchedRoute = from to to
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: SearchInputException) {
                status = invalid.feedback
            } catch (_: Exception) {
                status = Copy.ServiceUnavailable
            }
        }
    }

    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                BoxWithConstraints(Modifier.fillMaxHeight().widthIn(max = 1200.dp).fillMaxWidth()) {
                    val compact = maxWidth < 700.dp
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scroll)
                            .padding(horizontal = if (compact) 16.dp else 36.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = colors.primary, shape = RoundedCornerShape(8.dp)) {
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { RouteMark(colors.onPrimary) }
                            }
                            Text("Plan B", Modifier.padding(start = 8.dp), fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.weight(1f))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(copy(if (source.demo) Copy.DemoMode else Copy.ApiMode), color = colors.onSurfaceVariant)
                                if (!source.demo) Text(copy(health), fontSize = 11.sp, color = colors.onSurfaceVariant)
                            }
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
                                Text(copy(Copy.YourJourney), fontWeight = FontWeight.Black,
                                    fontSize = if (compact) 38.sp else 56.sp, lineHeight = if (compact) 41.sp else 59.sp)
                                Text(copy(Copy.StartsHere), color = colors.primary, fontWeight = FontWeight.Black,
                                    fontSize = if (compact) 38.sp else 56.sp, lineHeight = if (compact) 41.sp else 59.sp)
                                Text(copy(Copy.Intro), color = colors.onSurfaceVariant)
                                Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 2.dp) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.Top) {
                                            StationField(copy(Copy.From), origin, language, source, { origin = it }, { search(false) }, Modifier.weight(1f))
                                            TextButton(onClick = {
                                                val previous = origin
                                                origin = destination
                                                destination = previous
                                            }, modifier = Modifier.semantics { contentDescription = copy(Copy.SwapStations) }) { RouteMark(colors.primary, swap = true) }
                                            StationField(copy(Copy.To), destination, language, source, { destination = it }, { search(false) }, Modifier.weight(1f))
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(date, { date = it }, Modifier.weight(1f), label = { Text(copy(Copy.Date)) }, placeholder = { Text(copy(Copy.DateFormat)) }, singleLine = true)
                                            OutlinedTextField(hour, { hour = it }, Modifier.weight(1f), label = { Text(copy(Copy.SwissTime)) }, placeholder = { Text("HH:mm") }, singleLine = true)
                                        }
                                        Text(copy(Copy.DateHint), color = colors.onSurfaceVariant, fontSize = 12.sp)
                                        TextButton(onClick = { showOptions = !showOptions }) {
                                            Text(copy(Copy.TripOptions) + if (showOptions) " -" else " +")
                                        }
                                        if (showOptions) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(directOnly, { directOnly = it }, modifier = Modifier.semantics {
                                                    contentDescription = copy(Copy.DirectOnly)
                                                })
                                                Text(copy(Copy.DirectOnly))
                                            }
                                            Text(copy(Copy.ArriveBy), fontWeight = FontWeight.Medium)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedTextField(deadlineDate, { deadlineDate = it }, Modifier.weight(1f),
                                                    label = { Text(copy(Copy.DeadlineDate)) }, placeholder = { Text(copy(Copy.DateFormat)) }, singleLine = true)
                                                OutlinedTextField(deadlineTime, { deadlineTime = it }, Modifier.weight(1f),
                                                    label = { Text(copy(Copy.DeadlineTime)) }, placeholder = { Text("HH:mm") }, singleLine = true)
                                            }
                                            Text(copy(Copy.DeadlineHint), color = colors.onSurfaceVariant, fontSize = 12.sp)
                                        }
                                        OutlinedButton(onClick = { showTravelcards = !showTravelcards }, modifier = Modifier.fillMaxWidth()) {
                                            Text(copy(Copy.AddTravelcards))
                                        }
                                        if (showTravelcards) TravelcardsMenu(
                                            language, fareProfile,
                                            journeys.firstOrNull { it.id == chosenJourneyId } ?: visibleJourneys.firstOrNull(),
                                            close = { showTravelcards = false },
                                            apply = { fareProfile = it; showTravelcards = false }
                                        )
                                        if (fareProfile.travelcard != Travelcard.STANDARD || fareProfile.firstClass) {
                                            Text(copy(fareProfile.travelcard.label) + " / " +
                                                copy(if (fareProfile.firstClass) Copy.FirstClass else Copy.SecondClass),
                                                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Button(onClick = { search(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) { Text(copy(Copy.Search)) }
                                    }
                                }
                            }
                        }
                        if (compact) {
                            searchPanel()
                            if (journeys.isEmpty()) StationClock(language)
                        } else Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1.55f)) { searchPanel() }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                StationClock(language)
                                val overviewJourney = journeys.firstOrNull { it.id == chosenJourneyId } ?: visibleJourneys.firstOrNull()
                                val route = searchedRoute.takeIf { overviewJourney != null }
                                JourneyOverview(route?.first ?: origin, route?.second ?: destination, overviewJourney, language)
                            }
                        }
                        if (!source.demo) {
                            TextButton(onClick = { showRescue = !showRescue }) { Text(copy(Copy.PlanB)) }
                            if (showRescue) {
                                Text(copy(Copy.PlanBHint))
                                StationField(copy(Copy.StuckAt), rescueFrom, language, source, { rescueFrom = it },
                                    { search(true) }, Modifier.fillMaxWidth())
                                Button(onClick = { search(true) }) { Text(copy(Copy.FindAlternatives)) }
                            }
                        }
                        val noMatch = journeys.isNotEmpty() && visibleJourneys.isEmpty()
                        val feedback = status?.let(copy) ?: searchedRoute?.let { (from, to) ->
                            if (noMatch) copy(Copy.NoMatchingJourneys)
                            else "$from - $to · ${language.count(visibleJourneys.size, Copy.Journey, Copy.JourneyPlural)}"
                        }
                        if (feedback != null) {
                            Text(copy(Copy.Journeys), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(feedback, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.onSurfaceVariant)
                            responseInfo?.let { info ->
                                if (info.noFeasibleRoute == true) Text(copy(Copy.NoFeasibleRoute), fontWeight = FontWeight.Bold)
                                info.latestFeasibleDeparture?.let {
                                    val instant = Instant.parse(it)
                                    Text("${copy(Copy.LeaveBy)} ${instant.toLocalDateTime(Zurich).date} · ${time(instant)}")
                                }
                            }
                            if (noMatch) {
                                TextButton(onClick = {
                                    showOptions = true
                                    scope.launch { scroll.scrollTo(0) }
                                }) { Text(copy(Copy.AdjustOptions)) }
                                Text(copy(Copy.EarliestReturned), fontWeight = FontWeight.Medium)
                            }
                            val cards = if (noMatch) listOfNotNull(journeys.minByOrNull { Instant.parse(it.arrival) }) else visibleJourneys
                            for (journey in cards) {
                                val expanded = expandedJourneyId == journey.id
                                JourneyChoiceCard(
                                    journey = journey,
                                    language = language,
                                    expanded = expanded,
                                    chosen = chosenJourneyId == journey.id,
                                    toggleDetails = { expandedJourneyId = if (expanded) null else journey.id },
                                    chooseJourney = {
                                        expandedJourneyId = journey.id
                                        chosenJourneyId = journey.id
                                    },
                                    recommended = !noMatch && journey.id == visibleJourneys.firstOrNull { it.feasible != false }?.id,
                                    fare = fareProfile.price(journey),
                                    fullFare = if (fareProfile.travelcard == Travelcard.HALF_FARE)
                                        fareProfile.copy(travelcard = Travelcard.STANDARD).price(journey) else null,
                                    fareLabel = if (fareProfile.travelcard.canQuote) copy(Copy.PriceUnavailable)
                                        else copy(Copy.CoverageUnverified),
                                    model = model
                                )
                            }
                            cards.firstOrNull { it.id == chosenJourneyId }?.let { chosen ->
                                SmartWaitingTime(chosen, language)
                            }
                        }
                        DepartureBoard(
                            source.demo, anchor, visibleJourneys, searchedRoute != null, language,
                            Modifier.bringIntoViewRequester(departuresPanel)
                        )
                        Text(copy(Copy.Prototype), color = colors.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DepartureBoard(
    demo: Boolean, anchor: Instant, journeys: List<Journey>, searched: Boolean,
    language: Language, modifier: Modifier
) {
    val copy = language::text
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val rows = if (demo) services.map { service ->
        listOf(time(anchor + service.offset.minutes), service.destination, service.platform,
            if (service.delay > 0) "+${service.delay} min" else copy(Copy.OnTime))
    } else journeys.mapNotNull { journey ->
        val leg = journey.legs.firstOrNull { it.mode == "TRANSIT" } ?: return@mapNotNull null
        listOf(time(Instant.parse(leg.departure)), "${leg.origin} - ${leg.destination}",
            leg.departurePlatform ?: "—", copy(journey.provenance))
    }.distinct()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(copy(if (demo) Copy.Upcoming else Copy.ResultDepartures),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (demo) Text(copy(Copy.DemoData), color = muted)
        if (rows.isEmpty()) {
            Text(copy(if (searched) Copy.NoBoardableDepartures else Copy.SearchForDepartures), color = muted)
        } else OutlinedCard(Modifier.fillMaxWidth()) {
            BoardRow(listOf(Copy.Departure, Copy.Destination, Copy.Platform, if (demo) Copy.Status else Copy.Source).map(copy), true)
            for (row in rows) {
                HorizontalDivider()
                BoardRow(row)
            }
        }
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
