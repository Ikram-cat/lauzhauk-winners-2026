package alt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/* ------------------------------------------------------------------ page */

@Composable
fun ResultsPage(st: AppState, pal: AltPalette, modifier: Modifier) {
    val res = st.result ?: return
    val recommended = res.recommendedJourney
    val others = buildList {
        res.primaryJourney?.let { if (it.id != recommended?.id) add(it) }
        addAll(res.alternatives.filter { it.id != recommended?.id })
    }.distinctBy { it.id }.take(5)
    var expanded by remember(res) { mutableStateOf<Set<String>>(emptySet()) }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Column(
            Modifier.widthIn(max = 920.dp).fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .padding(top = 32.dp, bottom = 56.dp),
        ) {
            Eyebrow(st.t("results"), pal)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    st.fromText + "  -  " + st.toText,
                    fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp,
                    color = pal.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    (if (st.arriveBy) st.t("arrive") else st.t("depart")) + " · " +
                        (if (st.timeText.isBlank()) st.t("now") else st.timeText) + " · " +
                        st.t(if (st.preference == "SIMPLEST") "easiest" else "fastest"),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    color = pal.muted,
                )
            }
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(pal.red, st.t("legend.known"), pal.muted)
                LegendDot(pal.estimate, st.t("legend.est"), pal.muted)
                fmtPct(recommended?.realtime?.transferMissRisk)?.let {
                    RiskChip(st.t("missrisk") + " " + it, pal, 10.sp)
                }
            }
            Spacer(Modifier.height(16.dp))

            StatusPanel(st, res, pal)
            if (res.demoPerturbation?.active == true) {
                Spacer(Modifier.height(12.dp))
                SimulationBanner(st, res.demoPerturbation, pal)
            }
            if (res.deadlineSummary != null) {
                Spacer(Modifier.height(12.dp))
                DeadlineBanner(st, res, pal)
            }

            Spacer(Modifier.height(20.dp))
            if (recommended != null) {
                FeatureCard(st, recommended, pal)
            } else {
                Text(st.t("noresults"), fontSize = 14.sp, color = pal.muted)
            }

            if (others.isNotEmpty()) {
                Spacer(Modifier.height(36.dp))
                Eyebrow(st.t("otheroptions"), pal)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth()) {
                    others.forEachIndexed { i, j ->
                        if (i > 0) Spacer(Modifier.height(12.dp))
                        Band(st, j, pal, expanded.contains(j.id), i == 0 && j.id == res.primaryJourney?.id) {
                            expanded = if (expanded.contains(j.id)) expanded - j.id else expanded + j.id
                        }
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- status */

@Composable
private fun StatusPanel(st: AppState, res: JourneyResponse, pal: AltPalette) {
    when (res.status) {
        "DISRUPTION_DETECTED" -> {
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(pal.red.copy(alpha = 0.14f), Color.Transparent),
                        ),
                    )
                    .border(1.dp, pal.red).padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.background(pal.red).size(22.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("!", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color.White) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            st.t("status.bad").uppercase(), fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = pal.ink,
                        )
                        val notes = buildList {
                            res.reasons.forEach { add(reasonText(it, st)) }
                            res.disruption?.let { d ->
                                val parts = buildList {
                                    d.serviceLabel?.let { add(it) }
                                    if (d.from != null && d.to != null) add(d.from + " - " + d.to)
                                    d.delayMinutes?.let { add("+" + it.toInt() + " min") }
                                    if (d.cancelled == true) add("cancelled")
                                    d.cause?.let { add(it) }
                                }
                                if (parts.isNotEmpty()) add(parts.joinToString(" · "))
                            }
                        }
                        if (notes.isNotEmpty()) {
                            Spacer(Modifier.height(5.dp))
                            notes.take(4).forEach {
                                Text("• " + it, fontSize = 12.sp, color = pal.ink)
                            }
                        }
                    }
                    if (res.action == "REROUTE") {
                        Box(Modifier.background(pal.red).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(st.t("reroute").uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        "NO_DISRUPTION" -> {
            Row(
                Modifier.fillMaxWidth().background(pal.surface).border(1.dp, pal.rule)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(22.dp).border(1.dp, pal.ink),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(10.dp).background(pal.ink)) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(st.t("status.ok").uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp, color = pal.ink)
                    Text(st.t("status.ok.line"), fontSize = 12.sp, color = pal.muted)
                }
            }
        }

        else -> Unit // UNKNOWN: omit the panel entirely (DESIGN.md)
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
        "CHEAPER" -> "Cheaper by " + (fmtChf(r.savedChf) ?: "")
        "STAY_FASTEST" -> "This route is still the fastest option"
        "DEADLINE_MET" -> "Arrives ${r.marginMinutes?.toInt() ?: 0} min before your deadline"
        "DEADLINE_MISSED" -> "Misses your deadline by ${r.marginMinutes?.toInt() ?: 0} min"
        "TRANSFER_MISS_RISK" -> "Tight transfer — risk of a missed connection"
        "TRANSFER_RISK" -> "Transfer risk on this route"
        else -> r.code.lowercase().replace('_', ' ')
    }
}

@Composable
private fun SimulationBanner(st: AppState, dp: DemoPerturbation, pal: AltPalette) {
    Row(
        Modifier.fillMaxWidth().background(pal.surface).border(1.dp, pal.rule)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(st.t("simchip"), fontSize = 10.sp, fontWeight = FontWeight.Black,
            letterSpacing = 1.sp, color = pal.muted)
        Spacer(Modifier.width(10.dp))
        Text(
            st.t("demo.seed") + " " + (dp.seed ?: "—") +
                if (dp.applied.isNotEmpty()) " · " + dp.applied.joinToString("; ") else "",
            fontSize = 11.sp, color = pal.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeadlineBanner(st: AppState, res: JourneyResponse, pal: AltPalette) {
    val ds = res.deadlineSummary ?: return
    val bad = ds.feasible == false
    Row(
        Modifier.fillMaxWidth().background(pal.surface)
            .border(1.dp, if (bad) pal.red else pal.rule)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (ds.feasible) {
                true -> st.t("deadlinemet")
                false -> st.t("nofeasible")
                else -> ""
            },
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = if (bad) pal.red else pal.ink, modifier = Modifier.weight(1f),
        )
        ds.latestFeasibleDeparture?.let {
            Text(st.t("latestdeparture") + " " + fmtTime(it), fontSize = 11.sp, color = pal.muted)
        }
    }
}

/* ----------------------------------------------------------- recommended */

@Composable
private fun FeatureCard(st: AppState, j: Journey, pal: AltPalette) {
    val sched = j.schedule
    val knownDep = fmtDelay(j.realtime?.departureDelayMinutes)
    val knownArr = fmtDelay(j.realtime?.arrivalDelayMinutes, roundUp = true)
    val estDep = fmtEstimate(j.realtime?.estimatedDepartureDelayMinutes)
    val estArr = fmtEstimate(j.realtime?.estimatedArrivalDelayMinutes)

    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, pal.red)) {
        Column(
            Modifier.weight(1f).background(pal.featureBg).padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(pal.action).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        st.t("recommended").uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp, color = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    fmtDuration(sched?.durationMinutes) + "  ·  " +
                        (if ((sched?.transfers ?: 0) == 0) st.t("direct")
                        else "${sched?.transfers} " + st.t("transfers")),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pal.featureMuted,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                FeatureTime(
                    label = st.t("depart.label"),
                    time = fmtTime(sched?.departure),
                    known = knownDep, est = estDep,
                    pal = pal, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                FeatureTime(
                    label = st.t("arrive.label"),
                    time = fmtTime(sched?.arrival),
                    known = knownArr, est = estArr,
                    pal = pal, modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.featureRule))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Fact(st.t("duration"), fmtDuration(sched?.durationMinutes), pal, Modifier.weight(1f))
                Fact(st.t("connections"),
                    if ((sched?.transfers ?: 0) == 0) st.t("direct") else "${sched?.transfers}",
                    pal, Modifier.weight(1f))
                Fact(st.t("price"),
                    fmtChf(j.fares?.selectedChf) ?: "—", pal, Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.featureRule))

            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().border(1.dp, pal.featureRule).clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    (if (expanded) st.t("lessinfo") else st.t("moreinfo")).uppercase(),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                    color = pal.red,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Text(st.t("itinerary").uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp, color = pal.featureMuted)
                Spacer(Modifier.height(6.dp))
                if (j.legs.isEmpty()) {
                    Text("—", fontSize = 12.sp, color = pal.featureMuted)
                }
                j.legs.forEach { leg -> FeatureLeg(st, leg, pal) }
            }

            val tags = buildList {
                if (j.dataStatus == "MOCK") add(st.t("mock"))
                if (j.verified == false) add(st.t("unverified"))
                j.maxOccupancy?.let { add(st.t("occupancy") + " " + st.t(it.lowercase())) }
                if (j.feasible == false) add(st.t("deadlinemissed"))
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach {
                        Box(Modifier.border(1.dp, pal.featureRule).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(it.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp, color = pal.featureMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureTime(
    label: String,
    time: String,
    known: String?,
    est: String?,
    pal: AltPalette,
    modifier: Modifier,
) {
    Column(
        modifier.background(pal.featureTimeBg).border(1.dp, pal.featureRule).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp, color = pal.featureMuted, modifier = Modifier.weight(1f))
            if (known != null) {
                Box(Modifier.background(pal.action).padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text(known, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            if (known != null && est != null) Spacer(Modifier.width(6.dp))
            if (est != null) {
                Box(Modifier.border(1.dp, pal.estimate).padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text(est, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pal.estimate)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(time, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp,
            color = pal.featureInk, maxLines = 1)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun Fact(label: String, value: String, pal: AltPalette, modifier: Modifier) {
    Column(modifier) {
        Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp, color = pal.featureMuted)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = pal.featureInk)
    }
}

@Composable
private fun FeatureLeg(st: AppState, leg: Leg, pal: AltPalette) {
    val isWalk = leg.mode == "WALK"
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            fmtTime(leg.departure) + " - " + fmtTime(leg.arrival),
            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pal.featureInk,
            modifier = Modifier.width(96.dp), maxLines = 1,
        )
        Box(Modifier.width(58.dp)) {
            if (isWalk) Text("WALK", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp, color = pal.featureMuted)
            else Text(leg.serviceLabel ?: "—", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = pal.red, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            (leg.origin ?: "-") + "  -  " + (leg.destination ?: "-"),
            fontSize = 12.sp, color = pal.featureInk, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        leg.departurePlatform?.let {
            Text(st.t("platform") + " " + it, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = pal.featureMuted)
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textColor)
    }
}

/* ----------------------------------------------------------- alternatives */

@Composable
private fun Band(
    st: AppState,
    j: Journey,
    pal: AltPalette,
    expanded: Boolean,
    isPlanned: Boolean,
    onToggle: () -> Unit,
) {
    val sched = j.schedule
    val known = fmtDelay(j.realtime?.arrivalDelayMinutes, roundUp = true)
    val est = fmtEstimate(j.realtime?.estimatedArrivalDelayMinutes)
    val missChip = fmtPct(j.realtime?.transferMissRisk)?.let { st.t("missrisk") + " " + it }

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(pal.rule))
        Column(Modifier.weight(1f)) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmtTime(sched?.departure), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp, color = pal.ink)
                    Text("  –  ", fontSize = 16.sp, color = pal.muted)
                    Text(fmtTime(sched?.arrival), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp, color = pal.ink)
                    if (isPlanned) {
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.border(1.dp, pal.rule).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(st.t("primary").uppercase(), fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp, color = pal.muted)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    fmtDuration(sched?.durationMinutes) + "  ·  " +
                        (if ((sched?.transfers ?: 0) == 0) st.t("direct")
                        else "${sched?.transfers} " + st.t("transfers")) + "  ·  " +
                        (fmtChf(j.fares?.selectedChf) ?: "—"),
                    fontSize = 12.sp, color = pal.muted,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (known != null) {
                    Box(Modifier.background(pal.action).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(known, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                if (known != null && est != null) Spacer(Modifier.width(6.dp))
                if (est != null) {
                    Box(Modifier.border(1.dp, pal.estimate).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(est, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pal.estimate)
                    }
                }
                if (missChip != null) {
                    if (known != null || est != null) Spacer(Modifier.width(6.dp))
                    RiskChip(missChip, pal, 10.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(if (expanded) "−" else "+", fontSize = 18.sp, color = pal.muted,
                textAlign = TextAlign.Center, modifier = Modifier.width(16.dp))
        }

        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(pal.rule))
                j.legs.forEach { leg -> BandLeg(st, leg, pal) }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    j.risk?.corridorIncidentRate?.let {
                        Text(st.t("risk") + " " + fmtRate(it) + " (" + (j.risk?.level ?: "?") + ")",
                            fontSize = 10.sp, color = pal.muted)
                        Spacer(Modifier.width(12.dp))
                    }
                    j.risk?.typicalIncidentDurationMinutes?.let {
                        Text("typ. " + fmtDuration(it), fontSize = 10.sp, color = pal.muted)
                    }
                    Spacer(Modifier.weight(1f))
                    j.realtime?.estimatedSource?.let {
                        Text("est. " + it.lowercase(), fontSize = 9.sp, color = pal.muted)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun BandLeg(st: AppState, leg: Leg, pal: AltPalette) {
    val isWalk = leg.mode == "WALK"
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                fmtTime(leg.projectedDeparture ?: leg.expectedDeparture ?: leg.departure) + " - " +
                    fmtTime(leg.projectedArrival ?: leg.expectedArrival ?: leg.arrival),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pal.ink,
                modifier = Modifier.width(100.dp),
            )
            Box(Modifier.width(56.dp)) {
                Text(
                    if (isWalk) "WALK" else (leg.serviceLabel ?: "—"),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp, color = if (isWalk) pal.muted else pal.red,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                (leg.origin ?: "-") + "  -  " + (leg.destination ?: "-"),
                fontSize = 12.sp, color = pal.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            leg.departurePlatform?.let {
                Text(st.t("platform") + " " + it, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = pal.muted)
            }
        }
        val chips = buildList {
            fmtDelay(leg.departureDelayMinutes)?.let { add(it + " " + st.t("live") to pal.muted) }
            fmtEstimate(leg.estimatedDepartureDelayMinutes)?.let { add(it + " " + st.t("estimated") to pal.estimate) }
            fmtPct(leg.incidentCancellationRisk)?.let { add(st.t("cancelrisk") + " " + it to pal.muted) }
            fmtPct(leg.incidentSuppressedStopRisk)?.let { add(st.t("suppressed") + " " + it to pal.muted) }
            fmtPct(leg.transferMissRisk)?.let { add(st.t("missrisk") + " " + it to pal.muted) }
        }
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.padding(start = 100.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { (c, col) ->
                    Box(Modifier.border(1.dp, if (col == pal.estimate) col else pal.rule)
                        .padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(c, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = col)
                    }
                }
            }
        }
    }
    if (abs(0) < 0) Text("")
}

/** Trip-level "connection at risk" chip: red-bordered like the other band badges. */
@Composable
private fun RiskChip(text: String, pal: AltPalette, size: androidx.compose.ui.unit.TextUnit) {
    Box(Modifier.border(1.dp, pal.red).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, fontSize = size, fontWeight = FontWeight.Bold, color = pal.red, maxLines = 1)
    }
}
