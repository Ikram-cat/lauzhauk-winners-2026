@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
    chooseJourney: () -> Unit,
    recommended: Boolean = false,
    whyText: String? = null,
    fare: Double? = null,
    fullFare: Double? = null,
    fareLabel: String = "",
    model: ModelInfo? = null,
    deadline: Instant? = null
) {
    val copy = language::text
    val colors = MaterialTheme.colorScheme
    val start = journey.effectiveDeparture
    val end = journey.effectiveArrival
    val startDay = start.toLocalDateTime(Zurich).date
    val endDay = end.toLocalDateTime(Zurich).date
    val transfer = if (journey.transfers == 0) copy(Copy.Direct)
        else language.count(journey.transfers, Copy.Transfer, Copy.TransferPlural)

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen || recommended) colors.primary else colors.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            whyText?.let {
                Text(it, color = colors.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(time(start), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        RouteMark(colors.primary)
                        Text(time(end), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (startDay == endDay) "$startDay · Europe/Zurich" else "$startDay - $endDay · Europe/Zurich",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                    )
                    Text(
                        copy(if (journey.isLive) Copy.ExpectedTimeTag else Copy.ScheduledTimeTag),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 126.dp)) {
                    Text(fare?.let(::chf) ?: fareLabel, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = if (fare == null) colors.onSurfaceVariant else colors.primary)
                    if (fare != null) journey.fares?.source?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            Text("${journey.durationMinutes} min · $transfer · ${copy(journey.provenance)}", color = colors.onSurfaceVariant)
            if (journey.feasible == false) {
                val lateMin = deadline?.let { (end - it).inWholeMinutes.coerceAtLeast(1) }
                val lateLabel = if (lateMin != null) "${copy(Copy.ArrivesLateBy)}$lateMin min" else copy(Copy.MissesDeadline)
                Text(lateLabel, color = colors.primary, fontWeight = FontWeight.SemiBold)
            } else if (deadline != null && whyText == null) {
                val earlyMin = (deadline - end).inWholeMinutes.coerceAtLeast(0)
                Text("${copy(Copy.ArrivesBeforeDeadlineBy)}$earlyMin min", color = colors.onSurfaceVariant)
            }
            if (fare != null && fullFare != null && fullFare > fare) {
                Text("${copy(Copy.FullPrice)} · ${chf(fullFare)}", color = colors.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = toggleDetails) {
                    Text(copy(if (expanded) Copy.HideDetails else Copy.Details))
                }
                if (chosen) Text(
                    copy(Copy.DepartureSelected),
                    color = colors.primary, fontWeight = FontWeight.SemiBold
                ) else Button(onClick = chooseJourney) {
                    Text(copy(Copy.ChooseThisDeparture))
                }
            }
            if (expanded) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (journey.verified) CheckIcon(colors.primary) else InfoIcon(colors.onSurfaceVariant)
                    Text(
                        copy(if (journey.verified) Copy.LiveVerified else Copy.EstimateOnly),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (journey.verified) colors.primary else colors.onSurfaceVariant
                    )
                }
                val risk = journey.risk
                val pct = risk?.corridorIncidentRate?.takeIf { it.isFinite() && it in 0.0..1.0 }
                val riskLabel = when (risk?.source) {
                    "CORRIDOR" -> copy(Copy.CorridorRisk)
                    "BASELINE" -> copy(Copy.HourRisk)
                    else -> null
                }
                if (pct != null && riskLabel != null && model?.riskModel?.version != null) {
                    val tenths = (pct * 1000).let { kotlin.math.round(it).toInt() }
                    val separator = if (language == Language.English) "." else ","
                    val confidence = risk.confidence?.lowercase()?.let { " · $it" }.orEmpty()
                    Text("${tenths / 10}$separator${tenths % 10}% $riskLabel$confidence",
                        color = if (risk.confidence == "VERY_LOW") colors.onSurfaceVariant else colors.onSurface)
                    risk.typicalIncidentDurationMinutes?.let {
                        Text("${copy(Copy.TypicalDisruption)} · $it min")
                    }
                    Text("${copy(Copy.DataModel)} · ${model.riskModel?.version}", style = MaterialTheme.typography.bodySmall)
                } else if (journey.dataStatus == "LIVE_SCHEDULE") Text(copy(Copy.RiskUnknown), color = colors.onSurfaceVariant)
                journey.maxOccupancy?.let { level ->
                    occupancyLabel(level, language)?.let {
                        Text("${copy(Copy.OccupancyLabel)} · $it", color = colors.onSurfaceVariant)
                    }
                }
            }
            if (expanded) for (leg in journey.legs) {
                HorizontalDivider(color = colors.outlineVariant)
                val departure = leg.expectedDeparture?.let(Instant::parse) ?: Instant.parse(leg.departure)
                val arrival = leg.expectedArrival?.let(Instant::parse) ?: Instant.parse(leg.arrival)
                fun label(instant: Instant) = if (startDay == endDay) time(instant)
                    else "${time(instant)} · ${instant.toLocalDateTime(Zurich).date}"
                fun endpoint(value: String, fallback: Copy) =
                    if (leg.mode == "WALK" && isCoordinate(value)) copy(fallback) else localizedStationName(value, language)
                Text("${label(departure)} ${endpoint(leg.origin, Copy.WalkFromAddress)} - ${label(arrival)} ${endpoint(leg.destination, Copy.WalkToAddress)}")
                val occupancy = occupancyLabel(leg.occupancy?.secondClass, language)
                val details = listOfNotNull(
                    when (leg.mode) {
                        "WALK" -> copy(Copy.Walking)
                        "TRANSIT" -> leg.serviceLabel ?: copy(Copy.Transit)
                        else -> leg.serviceLabel
                    },
                    leg.departurePlatform?.let { "${copy(Copy.BoardingPlatform)} $it" },
                    leg.arrivalPlatform?.let { "${copy(Copy.ArrivalPlatform)} $it" },
                    occupancy?.let { "${copy(Copy.OccupancyLabel)} $it" }
                )
                if (details.isNotEmpty()) Text(details.joinToString(" · "), color = colors.onSurfaceVariant)
            }
        }
    }
}
