@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
internal fun JourneyChoiceCard(
    journey: Journey,
    language: Language,
    expanded: Boolean,
    chosen: Boolean,
    toggleDetails: () -> Unit,
    chooseJourney: () -> Unit
) {
    val copy = language::text
    val colors = MaterialTheme.colorScheme
    val start = Instant.parse(journey.departure)
    val end = Instant.parse(journey.arrival)
    val startDay = start.toLocalDateTime(Zurich).date
    val endDay = end.toLocalDateTime(Zurich).date
    val transfer = if (journey.transfers == 0) copy(Copy.Direct)
        else language.count(journey.transfers, Copy.Transfer, Copy.TransferPlural)

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, if (chosen) colors.primary else colors.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${time(start)} → ${time(end)}", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text(
                        if (startDay == endDay) "$startDay · Europe/Zurich" else "$startDay → $endDay · Europe/Zurich",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                    )
                }
                Text("${journey.durationMinutes} min", fontWeight = FontWeight.Medium, color = colors.primary)
            }
            Text("$transfer · ${copy(journey.provenance)}", color = colors.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = toggleDetails) {
                    Text(when {
                        language == Language.French && expanded -> "Masquer les détails"
                        language == Language.French -> "Détails"
                        expanded -> "Hide details"
                        else -> "Details"
                    })
                }
                if (chosen) Text(
                    if (language == Language.French) "Départ choisi" else "Departure selected",
                    color = colors.primary, fontWeight = FontWeight.SemiBold
                ) else Button(onClick = chooseJourney) {
                    Text(if (language == Language.French) "Choisir ce départ" else "Choose this departure")
                }
            }
            if (expanded) for (leg in journey.legs) {
                HorizontalDivider(color = colors.outlineVariant)
                val departure = Instant.parse(leg.departure)
                val arrival = Instant.parse(leg.arrival)
                fun label(instant: Instant) = if (startDay == endDay) time(instant)
                    else "${time(instant)} · ${instant.toLocalDateTime(Zurich).date}"
                Text("${label(departure)} ${leg.origin} → ${label(arrival)} ${leg.destination}")
                val details = listOfNotNull(
                    when (leg.mode) {
                        "WALK" -> copy(Copy.Walking)
                        "TRANSIT" -> leg.serviceLabel ?: copy(Copy.Transit)
                        else -> leg.serviceLabel
                    },
                    leg.departurePlatform?.let { "${copy(Copy.BoardingPlatform)} $it" },
                    leg.arrivalPlatform?.let { "${copy(Copy.ArrivalPlatform)} $it" }
                )
                if (details.isNotEmpty()) Text(details.joinToString(" · "), color = colors.onSurfaceVariant)
            }
        }
    }
}
