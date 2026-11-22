@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Instant

@Composable
internal fun JourneyOverview(origin: String, destination: String, journey: Journey?, language: Language) {
    val copy = language::text
    val colors = MaterialTheme.colorScheme
    val from = (journey?.legs?.firstOrNull()?.origin?.takeUnless(::isCoordinate) ?: origin.ifBlank { copy(Copy.From) })
        .let { localizedStationName(it, language) }
    val to = (journey?.legs?.lastOrNull()?.destination?.takeUnless(::isCoordinate) ?: destination.ifBlank { copy(Copy.To) })
        .let { localizedStationName(it, language) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceVariant,
        contentColor = colors.onSurface
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(copy(Copy.JourneyOverview), color = colors.onSurfaceVariant,
                fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            TimelineStop(from, journey?.let { "${copy(Copy.ScheduledDeparture)} · ${time(it.scheduleDeparture)}" }
                ?: copy(Copy.NowDeparture), true)
            Box(Modifier.padding(start = 6.dp).width(2.dp).height(18.dp).background(colors.primary))
            TimelineStop(to, journey?.let { "${copy(Copy.ScheduledArrival)} · ${time(it.scheduleArrival)}" }, false)
            if (journey != null) {
                val transfer = if (journey.transfers == 0) copy(Copy.Direct)
                    else language.count(journey.transfers, Copy.Transfer, Copy.TransferPlural)
                Text("${journey.durationMinutes} min · $transfer", color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal fun isCoordinate(value: String): Boolean =
    value.split(',').let { it.size == 2 && it.all { part -> part.trim().toDoubleOrNull() != null } }

@Composable
internal fun RouteMark(color: Color, swap: Boolean = false) {
    Canvas(Modifier.size(19.dp, 18.dp)) {
        routeArrow(color, size.height * if (swap) .30f else .50f, false)
        if (swap) routeArrow(color, size.height * .70f, true)
    }
}

private fun DrawScope.routeArrow(color: Color, y: Float, reverse: Boolean) {
    val end = size.width * if (reverse) .12f else .88f
    val start = size.width - end
    val wing = end + size.width * if (reverse) .22f else -.22f
    val height = size.height * .19f
    val stroke = 1.6.dp.toPx()
    drawLine(color, Offset(start, y), Offset(end, y), stroke)
    drawLine(color, Offset(wing, y - height), Offset(end, y), stroke)
    drawLine(color, Offset(wing, y + height), Offset(end, y), stroke)
}

@Composable
private fun TimelineStop(station: String, time: String?, departure: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(14.dp).background(if (departure) colors.primary else colors.onSurface, CircleShape))
        Column {
            Text(station, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (time != null) Text(time, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}
