@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ktor.http.encodeURLQueryComponent
import kotlinx.coroutines.delay
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal data class WaitingBudget(val outboundMinutes: Int, val activityMinutes: Int, val returnMinutes: Int) {
    fun returnBy(departure: Instant, marginMinutes: Int): Instant =
        departure - (returnMinutes + marginMinutes).minutes

    fun fits(now: Instant, departure: Instant, marginMinutes: Int): Boolean =
        now < departure && now + (outboundMinutes + activityMinutes).minutes <= returnBy(departure, marginMinutes)
}

private data class Opportunity(val title: WaitingCopy, val budget: WaitingBudget)

private val stationActivities = listOf(
    Opportunity(WaitingCopy.TakeBreak, WaitingBudget(1, 5, 2)),
    Opportunity(WaitingCopy.ShortWalk, WaitingBudget(2, 6, 3)),
    Opportunity(WaitingCopy.Coffee, WaitingBudget(3, 6, 4)),
    Opportunity(WaitingCopy.Shop, WaitingBudget(4, 8, 5))
)

@Composable
internal fun SmartWaitingTime(journey: Journey, language: Language, modifier: Modifier = Modifier) {
    val boarding = journey.legs.firstOrNull { it.mode == "TRANSIT" } ?: return
    val departure = Instant.parse(boarding.departure)
    var now by remember { mutableStateOf(Clock.System.now()) }
    var atStation by remember(boarding.origin) { mutableStateOf(false) }
    var margin by remember { mutableIntStateOf(5) }
    var chosen by remember(journey.id, boarding.origin) { mutableStateOf<Opportunity?>(null) }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(departure) {
        while (true) {
            now = Clock.System.now()
            if (now >= departure) break
            delay(1_000)
        }
    }

    val timeLeft = departure - now
    val departureDate = departure.toLocalDateTime(Zurich).date
    val date = if (departureDate != now.toLocalDateTime(Zurich).date) " · $departureDate" else ""
    val platform = boarding.departurePlatform?.let { " · ${language.text(Copy.Platform)} $it" }.orEmpty()
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(language.waiting(WaitingCopy.Title), style = MaterialTheme.typography.headlineSmall)
            Text("${boarding.origin} · ${time(departure)}$date$platform · ${language.text(journey.provenance)}")
            Text(
                if (timeLeft > Duration.ZERO) language.waiting(WaitingCopy.TimeLeft) + remainingTime(timeLeft)
                else language.waiting(WaitingCopy.DeparturePassed),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(language.waiting(WaitingCopy.ScheduleOnly), style = MaterialTheme.typography.bodySmall)
            if (timeLeft > Duration.ZERO) {
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = atStation, role = Role.Checkbox, onValueChange = { atStation = it }
                    ).padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = atStation, onCheckedChange = null)
                    Text("${language.waiting(WaitingCopy.AtStation)} ${boarding.origin}")
                }
                TextButton(onClick = {
                    val query = "${boarding.origin}, Switzerland".encodeURLQueryComponent()
                    uriHandler.openUri("https://www.openstreetmap.org/search?query=$query")
                }) { Text(language.waiting(WaitingCopy.StationMap)) }
                if (atStation) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = margin > 3,
                            onClick = { margin-- },
                            modifier = Modifier.semantics { contentDescription = language.waiting(WaitingCopy.LowerMargin) }
                        ) { Text("−") }
                        Text("${language.waiting(WaitingCopy.Margin)} · $margin min", Modifier.weight(1f))
                        TextButton(
                            enabled = margin < 30,
                            onClick = { margin++ },
                            modifier = Modifier.semantics { contentDescription = language.waiting(WaitingCopy.RaiseMargin) }
                        ) { Text("+") }
                    }
                    Text(language.waiting(WaitingCopy.Estimates), style = MaterialTheme.typography.bodySmall)
                    val matching = stationActivities.filter { it.budget.fits(now, departure, margin) }
                    chosen?.let { activity ->
                        val returnBy = activity.budget.returnBy(departure, margin)
                        val reminder = if (now >= returnBy) language.waiting(WaitingCopy.ReturnNow)
                        else language.waiting(WaitingCopy.ReturnBy) + time(returnBy)
                        Text(
                            reminder,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        if (activity !in matching) {
                            TextButton(onClick = { chosen = null }) { Text(language.waiting(WaitingCopy.ClearReminder)) }
                        }
                    }
                    if (matching.isEmpty() && chosen == null) Text(language.waiting(WaitingCopy.StayAtStation))
                    for (activity in matching) {
                        WaitingOpportunityCard(activity, margin, activity == chosen, language) {
                            chosen = if (activity == chosen) null else activity
                        }
                    }
                }
            }
        }
    }
}

private fun remainingTime(duration: Duration): String {
    val seconds = duration.inWholeSeconds.coerceAtLeast(0)
    return "${two((seconds / 3600).toInt())}:${two((seconds / 60 % 60).toInt())}:${two((seconds % 60).toInt())}"
}

@Composable
private fun WaitingOpportunityCard(
    opportunity: Opportunity, margin: Int, selected: Boolean, language: Language, onSelect: () -> Unit
) {
    val budget = opportunity.budget
    val total = budget.outboundMinutes + budget.activityMinutes + budget.returnMinutes + margin
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(language.waiting(opportunity.title), fontWeight = FontWeight.Bold)
            Text(
                "${language.waiting(WaitingCopy.WalkOut)} ${budget.outboundMinutes} min · " +
                    "${language.waiting(WaitingCopy.Activity)} ${budget.activityMinutes} min · " +
                    "${language.waiting(WaitingCopy.WalkBack)} ${budget.returnMinutes} min · " +
                    "${language.waiting(WaitingCopy.Margin)} $margin min",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "${language.waiting(WaitingCopy.Fits)} · $total min",
                color = MaterialTheme.colorScheme.primary
            )
            val action = language.waiting(if (selected) WaitingCopy.ClearReminder else WaitingCopy.SetReminder)
            TextButton(onClick = onSelect, modifier = Modifier.semantics {
                contentDescription = "$action · ${language.waiting(opportunity.title)}"
            }) { Text(action) }
        }
    }
}

private fun Language.waiting(text: WaitingCopy): String = when (this) {
    Language.French -> text.french
    Language.English -> text.english
}

private enum class WaitingCopy(val french: String, val english: String) {
    Title("Que faire en attendant ?", "Smart Waiting Time"),
    TimeLeft("Temps avant le départ · ", "Time before departure · "),
    DeparturePassed("Heure de départ prévue passée", "Scheduled departure has passed"),
    ScheduleOnly("Compte à rebours basé sur l’horaire sélectionné. Vérifiez les annonces en gare.",
        "Countdown based on the selected timetable. Check station announcements."),
    AtStation("Je suis en gare de", "I’m at"),
    Estimates("Estimations pour la gare. Vérifiez l’accès, l’attente et le temps jusqu’à votre voie.",
        "Station estimates. Check access, queues and the time to reach your platform."),
    StationMap("Voir les environs sur OpenStreetMap", "View the station area on OpenStreetMap"),
    Margin("Marge de sécurité", "Safety margin"),
    LowerMargin("Réduire la marge de sécurité", "Reduce safety margin"),
    RaiseMargin("Augmenter la marge de sécurité", "Increase safety margin"),
    TakeBreak("S’asseoir et faire une pause", "Take a seated break"),
    ShortWalk("Marcher quelques minutes dans la gare", "Take a short walk inside the station"),
    Coffee("Café ou en-cas, si disponible", "Coffee or snack if available"),
    Shop("Faire un tour dans une boutique ouverte", "Browse an open shop"),
    WalkOut("Aller à pied", "Walk there"),
    Activity("Activité", "Activity"),
    WalkBack("Retour à pied", "Walk back"),
    Fits("Temps estimé compatible", "Fits the estimated time"),
    StayAtStation("Restez près de la voie. Le temps estimé est trop court pour ces activités.",
        "Stay near the platform. The estimated time is too short for these activities."),
    SetReminder("Afficher l’heure de retour", "Show return reminder"),
    ClearReminder("Masquer le rappel", "Clear reminder"),
    ReturnBy("Repartez vers la voie avant ", "Head back to the platform by "),
    ReturnNow("Retournez à la voie maintenant", "Return to the platform now")
}
