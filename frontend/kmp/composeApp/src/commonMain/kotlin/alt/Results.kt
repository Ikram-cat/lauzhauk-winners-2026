package alt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun ResultsPane(st: AppState) {
    val res = st.result
    when {
        st.loading -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(st.t("loading"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        st.error != null -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(20.dp)) {
                    Text(st.t("error"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(6.dp))
                    Text(st.error ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { st.retry() }) { Text(st.t("retry")) }
                }
            }
        }

        res == null -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Search a Swiss connection to see journeys, delays and alternatives.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        else -> ResultsList(st, res)
    }
}

@Composable
private fun ResultsList(st: AppState, res: JourneyResponse) {
    val recommended = res.recommendedJourney
    val primary = res.primaryJourney
    val alternatives = res.alternatives
    var expandedIds by remember(res) { mutableStateOf(setOf(recommended?.id ?: "")) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("status") { StatusBanner(st, res) }
        item("sim") {
            if (res.demoPerturbation?.active == true) SimulationBanner(st, res.demoPerturbation)
        }
        item("deadline") {
            if (res.deadlineSummary != null) DeadlineBanner(st, res)
        }
        if (recommended != null) {
            item("rec") {
                JourneyCard(
                    st, recommended,
                    badges = buildList {
                        add(st.t("recommended"))
                        if (recommended.id == res.highlights?.fastestId) add(st.t("fastest"))
                        if (recommended.id == res.highlights?.simplestId) add(st.t("simplest"))
                        if (recommended.id == res.highlights?.cheapestId) add(st.t("cheapest"))
                    },
                    expanded = true,
                    onToggle = null,
                )
            }
        }
        if (primary != null && primary.id != recommended?.id) {
            item("primary") {
                Column {
                    Text(st.t("primary"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    JourneyCard(
                        st, primary,
                        badges = listOfNotNull(
                            if (primary.id == res.highlights?.fastestId) st.t("fastest") else null,
                            if (primary.id == res.highlights?.simplestId) st.t("simplest") else null,
                            if (primary.id == res.highlights?.cheapestId) st.t("cheapest") else null,
                        ),
                        expanded = expandedIds.contains(primary.id),
                        onToggle = { expandedIds = toggle(expandedIds, primary.id) },
                    )
                }
            }
        }
        if (alternatives.isNotEmpty()) {
            item("alts") {
                Text(st.t("alternatives"), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            alternatives.forEach { j ->
                item("alt-" + j.id) {
                    JourneyCard(
                        st, j,
                        badges = listOfNotNull(
                            if (j.id == res.highlights?.fastestId) st.t("fastest") else null,
                            if (j.id == res.highlights?.simplestId) st.t("simplest") else null,
                            if (j.id == res.highlights?.cheapestId) st.t("cheapest") else null,
                        ),
                        expanded = expandedIds.contains(j.id),
                        onToggle = { expandedIds = toggle(expandedIds, j.id) },
                    )
                }
            }
        }
        if (recommended == null && primary == null && alternatives.isEmpty()) {
            item("empty") {
                Text(st.t("noresults"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun toggle(set: Set<String>, id: String): Set<String> =
    if (set.contains(id)) set - id else set + id

@Composable
private fun StatusBanner(st: AppState, res: JourneyResponse) {
    val status = res.status
    val (bg, fg, title) = when (status) {
        "DISRUPTION_DETECTED" -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            st.t("disruption"),
        )
        "NO_DISRUPTION" -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            st.t("allclear"),
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            st.t("unknown"),
        )
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = fg)
                Spacer(Modifier.width(8.dp))
                if (res.action == "REROUTE") {
                    Surface(color = fg.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
                        Text("REROUTE — " + st.t("reroute"), fontSize = 10.sp, color = fg,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                if (res.status == "NO_DISRUPTION" && res.action == "STAY") {
                    Spacer(Modifier.weight(1f))
                    Text(st.t("stay"), fontSize = 11.sp, color = fg)
                }
            }
            if (res.reasons.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                res.reasons.forEach { r ->
                    Text("• " + reasonText(r, st), fontSize = 12.sp, color = fg)
                }
            }
            val d = res.disruption
            if (d != null) {
                Spacer(Modifier.height(6.dp))
                val parts = buildList {
                    d.serviceLabel?.let { add(it) }
                    if (d.from != null && d.to != null) add("${d.from} → ${d.to}")
                    d.delayMinutes?.let { add("+${it.toInt()} min") }
                    if (d.cancelled == true) add("cancelled")
                    d.cause?.let { add(it) }
                }
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), fontSize = 11.sp, color = fg.copy(alpha = 0.85f))
            }
        }
    }
}

private fun reasonText(r: Reason, st: AppState): String {
    val svc = r.serviceLabel ?: ""
    val d = r.delayMinutes?.toInt()
    val s = r.savedMinutes?.toInt()
    return when (r.code) {
        "PRIMARY_LEG_DELAYED" -> "$svc delayed +${d ?: 0} min"
        "PRIMARY_LEG_CANCELLED" -> "$svc cancelled"
        "REALTIME_UNAVAILABLE" -> "Live data unavailable — ranked on schedule and estimates"
        "SUPPRESSED_STOP" -> "$svc may skip a stop"
        "EARLIER_ARRIVAL" -> "Alternative arrives ${s ?: 0} min earlier"
        "EARLIER_DEPARTURE_CANCELLED" -> "An earlier departure is cancelled"
        "EXPECTED_FASTER" -> "Alternative expected ${s ?: 0} min faster"
        "FEWER_TRANSFERS" -> "${r.savedTransfers ?: 0} fewer transfers"
        "CHEAPER" -> "Cheaper by ${fmtChf(r.savedChf) ?: ""}"
        "STAY_FASTEST" -> "This route is still the fastest option"
        "DEADLINE_MET" -> "Arrives ${r.marginMinutes?.toInt() ?: 0} min before your deadline"
        "DEADLINE_MISSED" -> "Misses your deadline by ${r.marginMinutes?.toInt() ?: 0} min"
        "TRANSFER_MISS_RISK" -> "Tight transfer — risk of a missed connection"
        "TRANSFER_RISK" -> "Transfer risk on this route"
        else -> r.code.lowercase().replace('_', ' ')
    }
}

@Composable
private fun SimulationBanner(st: AppState, dp: DemoPerturbation) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.t("simchip"), fontWeight = FontWeight.Black, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(8.dp))
                Text("${st.t("demo.seed")} ${dp.seed ?: "—"}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            if (dp.applied.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(st.t("simapplied") + " " + dp.applied.joinToString("; "), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun DeadlineBanner(st: AppState, res: JourneyResponse) {
    val ds = res.deadlineSummary ?: return
    val feasible = ds.feasible
    val bg = if (feasible == false) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (feasible == false) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Card(colors = CardDefaults.cardColors(containerColor = bg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                when (feasible) {
                    true -> st.t("deadlinemet")
                    false -> st.t("nofeasible")
                    else -> ""
                },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg,
            )
            if (ds.latestFeasibleDeparture != null) {
                Text("${st.t("latestdeparture")} ${fmtTime(ds.latestFeasibleDeparture)}",
                    fontSize = 11.sp, color = fg)
            }
        }
    }
}

@Composable
private fun JourneyCard(
    st: AppState,
    j: Journey,
    badges: List<String>,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
) {
    val sched = j.schedule
    val rt = j.realtime
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(sched?.departure), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("  →  ", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmtTime(sched?.arrival), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(fmtDuration(sched?.durationMinutes), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if ((sched?.transfers ?: 0) == 0) st.t("direct")
                        else "${sched?.transfers} ${st.t("transfers")}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    val fare = j.fares?.selectedChf
                    Text(
                        fmtChf(fare) ?: if (j.fares?.status == "DISABLED" || j.fares?.status == "UNAVAILABLE") st.t("fare.unavailable") else "—",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                    j.fares?.product?.let {
                        Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                badges.forEach { Chip(it, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) }
                if (j.dataStatus == "MOCK") Chip(st.t("mock"), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                if (j.verified == false) Chip(st.t("unverified"), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                j.maxOccupancy?.let { occ ->
                    Chip("${st.t("occupancy")}: ${st.t(occ.lowercase())}", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (j.feasible == false) Chip(st.t("deadlinemissed"), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.weight(1f))
                if (onToggle != null) {
                    TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(if (expanded) "▲" else "▼", fontSize = 12.sp)
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                if (j.legs.isEmpty()) {
                    Text("—", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                j.legs.forEachIndexed { i, leg ->
                    LegRow(st, leg, i)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val risk = j.risk
                    if (risk?.corridorIncidentRate != null) {
                        Chip(
                            "${st.t("risk")} ${fmtRate(risk.corridorIncidentRate)} (${risk.level ?: "?"})",
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    risk?.typicalIncidentDurationMinutes?.let {
                        Text("typ. disruption ${fmtDuration(it)}", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    rt?.estimatedSource?.let { src ->
                        Text("est. ${src.lowercase()}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegRow(st: AppState, leg: Leg, index: Int) {
    val isWalk = leg.mode == "WALK"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(74.dp)) {
            Text(fmtTime(leg.projectedDeparture ?: leg.expectedDeparture ?: leg.departure),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(fmtTime(leg.projectedArrival ?: leg.expectedArrival ?: leg.arrival),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.width(56.dp)) {
            if (isWalk) Chip("walk", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            else Chip(leg.serviceLabel ?: "train", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${leg.origin ?: "—"}  →  ${leg.destination ?: "—"}",
                fontSize = 12.sp, maxLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fmtDelay(leg.departureDelayMinutes)?.let {
                    Chip("$it ${st.t("live")}", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                }
                fmtEstimate(leg.estimatedDepartureDelayMinutes)?.let {
                    Chip("≈$it ${st.t("estimated")}", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                fmtPct(leg.incidentCancellationRisk)?.let {
                    Chip("${st.t("cancelrisk")} $it", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                }
                fmtPct(leg.incidentSuppressedStopRisk)?.let {
                    Chip("${st.t("suppressed")} $it", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
                fmtPct(leg.transferMissRisk)?.let {
                    Chip("${st.t("missrisk")} $it", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            leg.departurePlatform?.let {
                Text("${st.t("platform")} $it", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            leg.occupancy?.let { o ->
                val occ = if (st.firstClass) o.firstClass else o.secondClass
                if (occ != null) Text(occ.lowercase(), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (index < 0) Text("")
}
