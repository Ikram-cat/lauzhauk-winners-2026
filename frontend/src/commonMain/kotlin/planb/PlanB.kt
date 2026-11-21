@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
    var languageMenuExpanded by remember { mutableStateOf(false) }
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
    var origin by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
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
    var redirectResponse by remember { mutableStateOf<RedirectResponse?>(null) }
    var preference by remember { mutableStateOf(Preference.FASTEST) }
    var showOtherOptions by remember { mutableStateOf(false) }
    var searchCollapsed by remember { mutableStateOf(false) }
    var simplestInfoOpen by remember { mutableStateOf(false) }
    var locationStatus by remember { mutableStateOf<Copy?>(null) }
    var activeDeadline by remember { mutableStateOf<Instant?>(null) }
    var rescueFrom by remember { mutableStateOf("") }
    var showRescue by remember { mutableStateOf(false) }
    var fareProfile by remember { mutableStateOf(FareProfile()) }
    var showTravelcards by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<Job?>(null) }
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
        redirectResponse = null
        showOtherOptions = false
        locationStatus = null
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
                activeDeadline = deadline
                val options = JourneyOptions(requestedDirectOnly, deadline.takeIf { source.demo })
                val response = if (rescue) {
                    val alt = source.alternatives(from, to, checkNotNull(start), deadline)
                    RedirectResponse(
                        situation = "UNKNOWN",
                        recommendation = alt.journeys.firstOrNull(),
                        alternatives = alt.journeys.drop(1).take(3),
                        journeys = alt.journeys,
                        latestFeasibleDeparture = alt.latestFeasibleDeparture,
                        noFeasibleRoute = alt.noFeasibleRoute,
                        advice = alt.advice
                    )
                } else source.search(from, to, start, preference = preference, deadline = deadline)
                appliedOptions = options
                redirectResponse = response
                val allJourneys = response.displayJourneys
                journeys = allJourneys
                expandedJourneyId = (options.applyTo(allJourneys).firstOrNull() ?: allJourneys.firstOrNull())?.id
                status = if (allJourneys.isEmpty()) {
                    if (source.demo) Copy.EmptyDemo else Copy.EmptyJourney
                } else null
                searchedRoute = from to to
                searchCollapsed = true
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
                            Box {
                                TextButton(
                                    onClick = { languageMenuExpanded = true },
                                    modifier = Modifier.semantics { contentDescription = copy(Copy.UseOtherLanguage) }
                                ) { Text(language.tag.uppercase()) }
                                DropdownMenu(expanded = languageMenuExpanded, onDismissRequest = { languageMenuExpanded = false }) {
                                    for (option in Language.entries) DropdownMenuItem(
                                        text = { Text(option.tag.uppercase()) },
                                        onClick = { language = option; languageMenuExpanded = false }
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = colors.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { scope.launch { scroll.scrollTo(0) } }) { Text(copy(Copy.Journeys)) }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { darkOverride = !useDark }) {
                                Text(copy(if (useDark) Copy.UseLight else Copy.UseDark))
                            }
                        }
                        val searchPanel: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (searchCollapsed && searchedRoute != null) {
                                    Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 1.dp, color = colors.surfaceVariant) {
                                        Row(
                                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    "${searchedRoute?.first?.let { localizedStationName(it, language) }} → " +
                                                        "${searchedRoute?.second?.let { localizedStationName(it, language) }}",
                                                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium
                                                )
                                                val whenText = activeDeadline?.let { "${copy(Copy.ArriveBy)} ${time(it)}" }
                                                    ?: hour.takeIf { it.isNotBlank() }?.let { "${copy(Copy.SwissTime)} $it" }
                                                    ?: copy(Copy.NowDeparture)
                                                Text(whenText, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                            }
                                            TextButton(onClick = { searchCollapsed = false }) { Text(copy(Copy.EditSearch)) }
                                        }
                                    }
                                } else {
                                    Text(copy(Copy.YourJourney), fontWeight = FontWeight.Black,
                                        fontSize = if (compact) 38.sp else 56.sp, lineHeight = if (compact) 41.sp else 59.sp)
                                    Text(copy(Copy.StartsHere), color = colors.primary, fontWeight = FontWeight.Black,
                                        fontSize = if (compact) 38.sp else 56.sp, lineHeight = if (compact) 41.sp else 59.sp)
                                    Text(copy(Copy.Intro), color = colors.onSurfaceVariant)
                                    Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 2.dp) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(verticalAlignment = Alignment.Top) {
                                                StationField(
                                                    copy(Copy.From), origin, language, source, { origin = it }, { search(false) }, Modifier.weight(1f),
                                                    trailing = {
                                                        IconButton(onClick = {
                                                            locationStatus = Copy.NearestStationPending
                                                        }, modifier = Modifier.semantics { contentDescription = copy(Copy.UseMyLocation) }) {
                                                            PinIcon(colors.primary)
                                                        }
                                                    }
                                                )
                                                TextButton(onClick = {
                                                    val previous = origin
                                                    origin = destination
                                                    destination = previous
                                                }, modifier = Modifier.semantics { contentDescription = copy(Copy.SwapStations) }) { RouteMark(colors.primary, swap = true) }
                                                StationField(copy(Copy.To), destination, language, source, { destination = it }, { search(false) }, Modifier.weight(1f))
                                            }
                                            locationStatus?.let {
                                                Text(copy(it), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedTextField(date, { date = it }, Modifier.weight(1f), label = { Text(copy(Copy.Date)) }, placeholder = { Text(copy(Copy.DateFormat)) }, singleLine = true)
                                                OutlinedTextField(hour, { hour = it }, Modifier.weight(1f), label = { Text(copy(Copy.SwissTime)) }, placeholder = { Text("HH:mm") }, singleLine = true)
                                            }
                                            Text(copy(Copy.DateHint), color = colors.onSurfaceVariant, fontSize = 12.sp)
                                            Text(copy(Copy.ArriveBy), fontWeight = FontWeight.Medium)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedTextField(deadlineDate, { deadlineDate = it }, Modifier.weight(1f),
                                                    label = { Text(copy(Copy.DeadlineDate)) }, placeholder = { Text(copy(Copy.DateFormat)) }, singleLine = true)
                                                OutlinedTextField(deadlineTime, { deadlineTime = it }, Modifier.weight(1f),
                                                    label = { Text(copy(Copy.DeadlineTime)) }, placeholder = { Text("HH:mm") }, singleLine = true)
                                            }
                                            Text(copy(Copy.DeadlineHint), color = colors.onSurfaceVariant, fontSize = 12.sp)
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
                                            Button(
                                                onClick = { search(false) },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                                            ) { Text(copy(Copy.TravelNow), fontWeight = FontWeight.Bold) }
                                        }
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
                            else "${localizedStationName(from, language)} - ${localizedStationName(to, language)} · " +
                                language.count(visibleJourneys.size, Copy.Journey, Copy.JourneyPlural)
                        }
                        if (feedback != null) {
                            Text(copy(Copy.Journeys), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(feedback, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.onSurfaceVariant)
                            redirectResponse?.let { info ->
                                val hasDisruption = info.situation == "DISRUPTION_DETECTED"
                                if (hasDisruption) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.error),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    ) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                WarningTriangleIcon(MaterialTheme.colorScheme.error, Modifier.size(22.dp))
                                                Text(
                                                    copy(Copy.DisruptionBannerTitle),
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                            }
                                            Text(
                                                copy(Copy.DisruptionBannerBody),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            info.message?.let {
                                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                            }
                                            info.disruption?.let { disruption ->
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    disruption.serviceLabel?.let {
                                                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.error) {
                                                            Text(it, Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                        }
                                                    }
                                                    val routeInfo = listOfNotNull(disruption.from, disruption.to)
                                                        .map { localizedStationName(it, language) }.joinToString(" → ")
                                                    if (routeInfo.isNotBlank()) {
                                                        Text(routeInfo, fontWeight = FontWeight.SemiBold)
                                                    }
                                                }
                                                if (disruption.cancelled == true) {
                                                    Text(copy(Copy.ServiceCancelled), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                                } else if (disruption.delayMinutes != null) {
                                                    Text("${copy(Copy.EstimatedDelay)}+${disruption.delayMinutes} min", fontWeight = FontWeight.Bold)
                                                }
                                                disruption.cause?.let {
                                                    Text(it, style = MaterialTheme.typography.bodyMedium)
                                                }
                                                if (disruption.confidence == "low") {
                                                    Text(copy(Copy.LimitedLiveData), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                } else if (info.situation == "UNKNOWN") {
                                    Text(copy(Copy.LiveStatusUnavailable), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (!info.message.isNullOrBlank() && !hasDisruption) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = colors.surfaceVariant,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(info.message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    }
                                }
                                if (info.noFeasibleRoute == true) Text(copy(Copy.NoFeasibleRoute), fontWeight = FontWeight.Bold, color = colors.primary)
                                info.latestFeasibleDeparture?.let {
                                    val instant = Instant.parse(it)
                                    Text("${copy(Copy.LeaveBy)} ${instant.toLocalDateTime(Zurich).date} · ${time(instant)}")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                data class ModeTab(val pref: Preference, val icon: @Composable (Color) -> Unit, val label: Copy)
                                val tabs = listOf(
                                    ModeTab(Preference.FASTEST, { c -> LightningIcon(c) }, Copy.FastestMode),
                                    ModeTab(Preference.SIMPLEST, { c -> RouteMark(c, swap = true) }, Copy.SimplestMode),
                                    ModeTab(Preference.CHEAPEST, { c -> CoinIcon(c) }, Copy.CostMode)
                                )
                                for (tab in tabs) {
                                    val active = preference == tab.pref
                                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                        val onTabColor = if (active) colors.onPrimary else colors.primary
                                        if (active) {
                                            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                                                tab.icon(onTabColor)
                                                Spacer(Modifier.width(6.dp))
                                                Text(copy(tab.label), fontWeight = FontWeight.Bold)
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { preference = tab.pref; search(false) },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                tab.icon(onTabColor)
                                                Spacer(Modifier.width(6.dp))
                                                Text(copy(tab.label))
                                            }
                                        }
                                        if (tab.pref == Preference.SIMPLEST) {
                                            Surface(
                                                shape = CircleShape, color = colors.surfaceVariant,
                                                modifier = Modifier.padding(top = 2.dp).size(20.dp)
                                                    .semantics { contentDescription = copy(Copy.SimplestInfoTooltip) }
                                            ) {
                                                TextButton(onClick = { simplestInfoOpen = true }, contentPadding = PaddingValues(0.dp)) {
                                                    InfoIcon(colors.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (simplestInfoOpen) AlertDialog(
                                onDismissRequest = { simplestInfoOpen = false },
                                confirmButton = { TextButton(onClick = { simplestInfoOpen = false }) { Text(copy(Copy.Close)) } },
                                title = { Text(copy(Copy.SimplestMode)) },
                                text = { Text(copy(Copy.SimplestInfoTooltip)) }
                            )
                            if (noMatch) {
                                TextButton(onClick = {
                                    showOptions = true
                                    scope.launch { scroll.scrollTo(0) }
                                }) { Text(copy(Copy.AdjustOptions)) }
                                Text(copy(Copy.EarliestReturned), fontWeight = FontWeight.Medium)
                            }
                            val cards = if (noMatch) listOfNotNull(journeys.minByOrNull { Instant.parse(it.arrival) }) else visibleJourneys
                            val recommendation = redirectResponse?.recommendation?.takeIf { !noMatch && appliedOptions.applyTo(listOf(it)).isNotEmpty() }
                            val nonRecommendationCards = if (recommendation != null) cards.filterNot { it.id == recommendation.id } else cards

                            if (recommendation != null) {
                                val expanded = expandedJourneyId == recommendation.id
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                    Text(
                                        copy(Copy.MostRecommendedLabel),
                                        modifier = Modifier.width(72.dp).padding(top = 18.dp),
                                        color = colors.primary, fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Box(Modifier.weight(1f)) {
                                        JourneyChoiceCard(
                                            journey = recommendation,
                                            language = language,
                                            expanded = expanded,
                                            chosen = chosenJourneyId == recommendation.id,
                                            toggleDetails = { expandedJourneyId = if (expanded) null else recommendation.id },
                                            chooseJourney = {
                                                expandedJourneyId = recommendation.id
                                                chosenJourneyId = recommendation.id
                                            },
                                            recommended = true,
                                            fare = fareProfile.price(recommendation),
                                            fullFare = if (fareProfile.travelcard == Travelcard.HALF_FARE)
                                                fareProfile.copy(travelcard = Travelcard.STANDARD).price(recommendation) else null,
                                            fareLabel = if (fareProfile.travelcard.canQuote) copy(Copy.PriceUnavailable)
                                                else copy(Copy.CoverageUnverified),
                                            model = model,
                                            deadline = activeDeadline
                                        )
                                    }
                                }
                                if (nonRecommendationCards.isNotEmpty()) {
                                    if (!showOtherOptions) {
                                        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                                            OutlinedButton(onClick = { showOtherOptions = true }) { Text(copy(Copy.ViewOtherOptions)) }
                                        }
                                    } else {
                                        Text(
                                            copy(Copy.OtherItineraries),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(top = 10.dp)
                                        )
                                        for (journey in nonRecommendationCards) {
                                            val isExpanded = expandedJourneyId == journey.id
                                            JourneyChoiceCard(
                                                journey = journey,
                                                language = language,
                                                expanded = isExpanded,
                                                chosen = chosenJourneyId == journey.id,
                                                toggleDetails = { expandedJourneyId = if (isExpanded) null else journey.id },
                                                chooseJourney = {
                                                    expandedJourneyId = journey.id
                                                    chosenJourneyId = journey.id
                                                },
                                                recommended = false,
                                                fare = fareProfile.price(journey),
                                                fullFare = if (fareProfile.travelcard == Travelcard.HALF_FARE)
                                                    fareProfile.copy(travelcard = Travelcard.STANDARD).price(journey) else null,
                                                fareLabel = if (fareProfile.travelcard.canQuote) copy(Copy.PriceUnavailable)
                                                    else copy(Copy.CoverageUnverified),
                                                model = model,
                                                deadline = activeDeadline
                                            )
                                        }
                                    }
                                }
                            } else {
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
                                        model = model,
                                        deadline = activeDeadline
                                    )
                                }
                            }
                            cards.firstOrNull { it.id == chosenJourneyId }?.let { chosen ->
                                SmartWaitingTime(chosen, language)
                            }
                        }
                        val activeModel = redirectResponse?.modelVersion ?: model?.modelVersion
                        if (activeModel != null) {
                            Text("${copy(Copy.ModelVersionLabel)}$activeModel", color = colors.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Text(copy(Copy.Prototype), color = colors.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

