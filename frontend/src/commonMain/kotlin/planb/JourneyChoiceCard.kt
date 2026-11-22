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
import androidx.compose.material3.Surface
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
    fare: Double? = null,
    fullFare: Double? = null,
    fareLabel: String = "",
    model: ModelInfo? = null,
    deadline: Instant? = null
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
        border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen || recommended) colors.primary else colors.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            journey.why?.let { whyText ->
                Text(whyText, color = colors.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
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
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 126.dp)) {
                    Text(fare?.let(::chf) ?: fareLabel, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = if (fare == null) colors.onSurfaceVariant else colors.primary)
                    if (fare != null) journey.fareSource?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            Text("${journey.durationMinutes} min · $transfer · ${copy(journey.provenance)}", color = colors.onSurfaceVariant)
            if (journey.arrivalDelayMinutes != null && journey.arrivalDelayMinutes > 0) {
                val sourceCopy = when (journey.realtimeSource) {
                    "GTFS_RT" -> Copy.RealtimeSourceGtfs
                    "DELAY_MODEL" -> Copy.RealtimeSourceModel
                    else -> Copy.RealtimeSourceOjp
                }
                Text("${copy(Copy.EstimatedDelay)}+${journey.arrivalDelayMinutes} min · ${copy(sourceCopy)}",
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            }
            journey.maxOccupancy?.let { level ->
                val levelCopy = when (level.uppercase()) {
                    "LOW" -> Copy.OccupancyLow
                    "MEDIUM" -> Copy.OccupancyMedium
                    "HIGH" -> Copy.OccupancyHigh
                    else -> null
                }
                levelCopy?.let { Text("${copy(Copy.OccupancyLabel)} · ${copy(it)}", color = colors.onSurfaceVariant) }
            }
            if (journey.feasible == false) {
                val lateMin = deadline?.let { (end - it).inWholeMinutes.coerceAtLeast(1) }
                val lateLabel = if (lateMin != null) "${copy(Copy.ArrivesLateBy)}$lateMin min" else copy(Copy.MissesDeadline)
                Text(lateLabel, color = colors.primary, fontWeight = FontWeight.SemiBold)
            } else if (deadline != null && journey.why == null) {
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
                val risk = journey.lateArrivalProbability?.takeIf { it.isFinite() && it in 0.0..1.0 }
                val riskLabel = when (journey.riskSource) {
                    "corridor" -> journey.riskLabel?.let { "${copy(Copy.CorridorRisk)} $it" }
                    "baseline" -> copy(Copy.HourRisk)
                    else -> null
                }
                if (risk != null && riskLabel != null && model?.modelVersion != null) {
                    val tenths = (risk * 1000).let { kotlin.math.round(it).toInt() }
                    val separator = if (language == Language.English) "." else ","
                    val confidence = journey.riskConfidence?.let { " · $it" }.orEmpty()
                    Text("${tenths / 10}$separator${tenths % 10}% $riskLabel$confidence",
                        color = if (journey.riskConfidence == "very_low") colors.onSurfaceVariant else colors.onSurface)
                    if (journey.riskBasis == "corridor_duration") journey.disruptionDurationMin?.let {
                        Text("${copy(Copy.TypicalDisruption)} · $it min")
                    }
                    Text("${copy(Copy.DataModel)} · ${model.modelVersion}", style = MaterialTheme.typography.bodySmall)
                } else if (journey.dataStatus == "LIVE_SCHEDULE") Text(copy(Copy.RiskUnknown), color = colors.onSurfaceVariant)
            }
            if (expanded) for (leg in journey.legs) {
                HorizontalDivider(color = colors.outlineVariant)
                val departure = Instant.parse(leg.departure)
                val arrival = Instant.parse(leg.arrival)
                fun label(instant: Instant) = if (startDay == endDay) time(instant)
                    else "${time(instant)} · ${instant.toLocalDateTime(Zurich).date}"
                fun endpoint(value: String, fallback: Copy) =
                    if (leg.mode == "WALK" && isCoordinate(value)) copy(fallback) else localizedStationName(value, language)
                Text("${label(departure)} ${endpoint(leg.origin, Copy.WalkFromAddress)} - ${label(arrival)} ${endpoint(leg.destination, Copy.WalkToAddress)}")
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
