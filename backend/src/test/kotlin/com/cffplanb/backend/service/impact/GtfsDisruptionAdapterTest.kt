package com.cffplanb.backend.service.impact

import com.cffplanb.backend.domain.ActiveDisruption
import com.cffplanb.backend.domain.AffectedStation
import com.cffplanb.backend.gtfs.GtfsDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Uses the team's captured GTFS-RT fixture (gtfsrt_fixture.pb) through their decoder, unchanged. */
class GtfsDisruptionAdapterTest {
    private val snapshot = GtfsDecoder.parse(
        GtfsDisruptionAdapterTest::class.java.classLoader.getResourceAsStream("gtfsrt_fixture.pb")!!.readBytes(), 0)
    private val skippedTrip = snapshot.byTripId[".ojp-91-95.1.TA.997.j26"]!!

    private fun uic(stopId: String) = 8500000 + Regex("ch:1:sloid:(\\d+)").find(stopId)!!.groupValues[1].toInt()
    private fun station(uic: Int, role: String) = AffectedStation(uic, "ch:1:sloid:${uic - 8500000}", null, listOf(role))
    private fun disruption(vararg s: AffectedStation) = ActiveDisruption("t", null, null, s.toList(), emptyList(), "MOCK")

    @Test
    fun `a disruption point in a GTFS-RT trip is found with its skipped stop`() {
        val first = uic(skippedTrip.stops.first().stopId)   // ch:1:sloid:1026:2:4 -> 8501026, skipped in the feed
        val impacts = GtfsDisruptionAdapter.affectedTrips(snapshot, disruption(station(first, "DISRUPTION_POINT")))
        val hit = impacts.single { it.tripId == skippedTrip.tripId }
        assertEquals(listOf(first), hit.skippedAtAffected)
    }

    @Test
    fun `two line stations of the same trip match, one alone or an unknown station does not`() {
        val a = uic(skippedTrip.stops[0].stopId)
        val b = uic(skippedTrip.stops[1].stopId)
        val both = GtfsDisruptionAdapter.affectedTrips(snapshot, disruption(station(a, "LINE_FROM"), station(b, "LINE_TO")))
        assertTrue(both.any { it.tripId == skippedTrip.tripId && it.maxDelaySecondsAtAffected == 1764 })
        val one = GtfsDisruptionAdapter.affectedTrips(snapshot, disruption(station(a, "LINE_FROM")))
        assertTrue(one.none { it.tripId == skippedTrip.tripId })
        assertTrue(GtfsDisruptionAdapter.affectedTrips(snapshot, disruption(station(8509999, "DISRUPTION_POINT"))).isEmpty())
    }
}
