package com.cffplanb.backend.api

import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.service.RiskEstimate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private fun pct(p: Double) = String.format(Locale.ROOT, "%.1f%%", p * 100)

private fun OffsetDateTime.before(o: OffsetDateTime) = !toInstant().isAfter(o.toInstant())

/** Short reasons, only from real data; max 3. Risk outranks "fewest transfers" when all four apply. */
fun reasonsFor(j: Journey, all: List<Journey>, r: RiskEstimate?, deadline: OffsetDateTime?): List<String> {
    val out = mutableListOf<String>()
    if (j.arrival.toInstant() == all.minOf { it.arrival.toInstant() }) out += "earliest arrival"
    if (deadline != null && j.arrival.before(deadline)) {
        val m = java.time.Duration.between(j.arrival, deadline).toMinutes()
        out += if (m == 0L) "arrives exactly at your deadline" else "arrives $m min before your deadline"
    }
    if (r != null && r.source == "corridor") out += "corridor risk ${pct(r.lateArrivalProbability)}" + (r.confidence?.let { " ($it confidence)" } ?: "")
    if (j.transfers == all.minOf { it.transfers }) out += "fewest transfers"
    return out.take(3)
}

/** One sentence (<=240 chars) built from the ranked journeys; risk is taken from journeys that meet the deadline. */
fun adviceFor(journeys: List<Journey>, risks: List<RiskEstimate?>, deadline: OffsetDateTime?): String {
    val ok = journeys.indices.filter { deadline == null || journeys[it].arrival.before(deadline) }
    val lead = when {
        deadline != null && ok.isEmpty() -> return "No feasible route: no journey arrives before ${deadline.format(HM)}."
        journeys.isEmpty() -> return "No journeys found for this request."
        deadline != null -> "Leave by ${journeys[ok.maxByOrNull { journeys[it].departure.toInstant() }!!].departure.format(HM)} to arrive before ${deadline.format(HM)}"
        else -> "Earliest option leaves ${journeys[0].departure.format(HM)} and arrives ${journeys[0].arrival.format(HM)}"
    }
    val worst = ok.mapNotNull { risks[it] }.maxByOrNull { it.lateArrivalProbability }
    val tail = if (worst == null) "delay risk is unknown for this route" else {
        val what = if (worst.basis?.endsWith("_duration") == true) "of days disrupted" else "late-arrival chance"
        val bits = listOfNotNull(
            "${pct(worst.lateArrivalProbability)} $what",
            worst.disruptionDurationMin?.let { "typically $it min" },
            worst.confidence?.let { "$it confidence" }
        ).joinToString(", ")
        val where = if (worst.source == "corridor" && worst.label != null) "corridor ${worst.label}" else "${worst.source ?: "estimated"} estimate"
        "highest risk is on $where ($bits)"
    }
    return "$lead; $tail.".take(240)
}
