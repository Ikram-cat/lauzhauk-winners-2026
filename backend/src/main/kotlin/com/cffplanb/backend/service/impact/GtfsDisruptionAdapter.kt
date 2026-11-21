package com.cffplanb.backend.service.impact

import com.cffplanb.backend.domain.ActiveDisruption
import com.cffplanb.backend.domain.AffectedStation
import com.cffplanb.backend.gtfs.GtfsMatch
import com.cffplanb.backend.gtfs.GtfsSnapshot

/** One GTFS-RT trip that calls at the stations of a disruption. UIC codes of the matched stations. */
data class GtfsTripImpact(
    val tripId: String,
    val routeId: String,
    val cancelled: Boolean,
    val matchedUics: List<Int>,
    val skippedAtAffected: List<Int>,
    val maxDelaySecondsAtAffected: Int?
)

/**
 * Adapter between VIA disruptions (UIC codes / stop-place sloids) and the team's GTFS-RT TripUpdates
 * snapshot (gtfs/GtfsRealtime.kt, unchanged). GTFS-RT stop_id values are platform sloids such as
 * "ch:1:sloid:1026:2:4" (captured fixture); the team's GtfsMatch.sameStop compares stop places.
 *
 * A trip is affected with the same rule as journey legs: one of its stops (every stop_time_update,
 * so intermediate stops included) is a DISRUPTION_POINT, or at least two are affected stations.
 * This gives realtime evidence (cancelled trips, skipped stops, delays at the affected stations);
 * it does not search journeys and does not compute a detour.
 */
object GtfsDisruptionAdapter {

    fun affectedTrips(snapshot: GtfsSnapshot, disruption: ActiveDisruption): List<GtfsTripImpact> =
        snapshot.trips.mapNotNull { trip ->
            val hits = disruption.stations.mapNotNull { station ->
                val updates = trip.stops.filter { GtfsMatch.sameStop(it.stopId, ref(station)) }
                if (updates.isEmpty()) null else station to updates
            }
            val affected = hits.any { (s, _) -> "DISRUPTION_POINT" in s.roles } || hits.size >= 2
            if (!affected) return@mapNotNull null
            GtfsTripImpact(
                tripId = trip.tripId,
                routeId = trip.routeId,
                cancelled = trip.cancelled,
                matchedUics = hits.map { it.first.uic },
                skippedAtAffected = hits.filter { (_, u) -> u.any { it.skipped } }.map { it.first.uic },
                maxDelaySecondsAtAffected = hits.flatMap { (_, u) -> u.mapNotNull { it.delaySeconds } }.maxOrNull()
            )
        }

    /** Stop reference understood by GtfsMatch.sameStop: the stop-place sloid, else the UIC code. */
    private fun ref(station: AffectedStation): String = station.sloid ?: station.uic.toString()
}
