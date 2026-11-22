package com.alt.backend.service.impact

import com.alt.backend.domain.ActiveDisruption
import com.alt.backend.domain.AffectedStation
import com.alt.backend.domain.Journey
import com.alt.backend.domain.LegMode
import java.text.Normalizer
import java.util.Locale

data class StationMatch(val legIndex: Int, val station: AffectedStation, val by: String)

data class DisruptionMatch(
    val disruption: ActiveDisruption,
    val stations: List<StationMatch>,
    val lines: List<String>
)

/**
 * A transit leg is affected when one of its stops (board, intermediate, alight) is a VIA
 * DISRUPTION_POINT, or when at least two of its stops are affected stations of any role, i.e. the leg
 * runs along the affected line. Stops match on stop-place sloid, else on normalized name.
 * Line numbers (IC1, R9, ...) equal to the leg's service label are reported but not required.
 */
object JourneyDisruptionMatcher {

    fun match(journey: Journey, disruption: ActiveDisruption): DisruptionMatch? {
        val bySloid = disruption.stations.filter { it.sloid != null }.associateBy { it.sloid!! }
        val byName = disruption.stations.filter { it.name != null }.associateBy { normalize(it.name!!) }
        val lines = disruption.lines.map { normalizeLine(it) }.toSet()
        val stations = mutableListOf<StationMatch>()
        val matchedLines = mutableListOf<String>()
        journey.legs.forEachIndexed { index, leg ->
            if (leg.mode != LegMode.TRANSIT) return@forEachIndexed
            val stops = listOf(leg.originSloid to leg.origin) + leg.intermediateSloids.map { it to null } +
                listOf(leg.destinationSloid to leg.destination)
            val hits = stops.mapNotNull { (sloid, name) ->
                sloid?.let { bySloid[it] }?.let { StationMatch(index, it, "SLOID") }
                    ?: name?.let { byName[normalize(it)] }?.let { StationMatch(index, it, "NAME") }
            }.distinctBy { it.station.uic }
            if (hits.any { "DISRUPTION_POINT" in it.station.roles } || hits.size >= 2) {
                stations += hits
                leg.serviceLabel?.let { normalizeLine(it) }?.takeIf { it in lines }?.let { matchedLines += it }
            }
        }
        return if (stations.isEmpty()) null else DisruptionMatch(disruption, stations, matchedLines.distinct())
    }

    fun isAffected(journey: Journey, disruptions: List<ActiveDisruption>) = disruptions.any { match(journey, it) != null }

    private fun normalize(name: String): String =
        Normalizer.normalize(name.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun normalizeLine(label: String): String = label.replace(" ", "").uppercase(Locale.ROOT)
}
