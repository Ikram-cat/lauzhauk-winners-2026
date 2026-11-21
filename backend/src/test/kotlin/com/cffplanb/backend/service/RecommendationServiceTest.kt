package com.cffplanb.backend.service

import com.cffplanb.backend.domain.DataStatus
import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.domain.JourneyLeg
import com.cffplanb.backend.domain.LegMode
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class RecommendationServiceTest {

    private val base = OffsetDateTime.of(2026, 11, 21, 16, 0, 0, 0, ZoneOffset.ofHours(1))

    private fun makeJourney(
        id: String,
        depMinutes: Long,
        arrMinutes: Long,
        transfers: Int = 0
    ): Journey {
        val dep = base.plusMinutes(depMinutes)
        val arr = base.plusMinutes(arrMinutes)
        return Journey(
            id = id,
            departure = dep,
            arrival = arr,
            durationMinutes = (arrMinutes - depMinutes).toInt(),
            transfers = transfers,
            legs = listOf(
                JourneyLeg(
                    mode = LegMode.TRANSIT,
                    origin = "A",
                    destination = "B",
                    departure = dep,
                    arrival = arr,
                    serviceLabel = null,
                    departurePlatform = null,
                    arrivalPlatform = null
                )
            ),
            dataStatus = DataStatus.MOCK
        )
    }

    @Test
    fun `filters out journeys departing before earliestDeparture`() {
        val tooEarly = makeJourney("early", -10L, 40L)
        val onTime = makeJourney("ontime", 0L, 50L)
        val late = makeJourney("late", 10L, 60L)

        val ranked = RecommendationService.rank(listOf(tooEarly, onTime, late), base)

        assertEquals(2, ranked.size)
        assertEquals("ontime", ranked[0].id)
        assertEquals("late", ranked[1].id)
    }

    @Test
    fun `sorts by arrival instant ascending`() {
        val j1 = makeJourney("j1", 10L, 90L)
        val j2 = makeJourney("j2", 5L, 60L)
        val j3 = makeJourney("j3", 20L, 75L)

        val ranked = RecommendationService.rank(listOf(j1, j2, j3), base)

        assertEquals("j2", ranked[0].id)
        assertEquals("j3", ranked[1].id)
        assertEquals("j1", ranked[2].id)
    }

    @Test
    fun `tie-breaks by transfers then departure then id`() {
        // Same arrival time
        val arrival = base.plusMinutes(70)
        val dep1 = base.plusMinutes(10)
        val dep2 = base.plusMinutes(5)

        fun makeWithArrival(id: String, dep: OffsetDateTime, transfers: Int): Journey {
            return Journey(
                id = id,
                departure = dep,
                arrival = arrival,
                durationMinutes = 60,
                transfers = transfers,
                legs = listOf(
                    JourneyLeg(LegMode.TRANSIT, "A", "B", dep, arrival, null, null, null)
                ),
                dataStatus = DataStatus.MOCK
            )
        }

        // Same arrival, same transfers, different departure: earlier dep wins
        val jA = makeWithArrival("jA", dep1, 1)  // later dep
        val jB = makeWithArrival("jB", dep2, 1)  // earlier dep → wins by dep
        val jC = makeWithArrival("jC", dep2, 0)  // fewer transfers → first

        val ranked = RecommendationService.rank(listOf(jA, jB, jC), base)

        // jC has fewer transfers → ranked first
        assertEquals("jC", ranked[0].id)
        // jB has earlier departure than jA, same transfers
        assertEquals("jB", ranked[1].id)
        assertEquals("jA", ranked[2].id)
    }

    @Test
    fun `tie-breaks by lexicographic id when all else equal`() {
        val dep = base.plusMinutes(10)
        val arr = base.plusMinutes(70)
        fun makeEq(id: String): Journey =
            Journey(id, dep, arr, 60, 0,
                listOf(JourneyLeg(LegMode.TRANSIT, "A", "B", dep, arr, null, null, null)),
                DataStatus.MOCK)

        val jZ = makeEq("zzz")
        val jA = makeEq("aaa")
        val jM = makeEq("mmm")

        val ranked = RecommendationService.rank(listOf(jZ, jA, jM), base)
        assertEquals("aaa", ranked[0].id)
        assertEquals("mmm", ranked[1].id)
        assertEquals("zzz", ranked[2].id)
    }

    @Test
    fun `returns empty list when all journeys are before earliestDeparture`() {
        val j = makeJourney("j1", -30L, -5L)
        val ranked = RecommendationService.rank(listOf(j), base)
        assertEquals(0, ranked.size)
    }

    @Test
    fun `returns empty list for empty input`() {
        val ranked = RecommendationService.rank(emptyList(), base)
        assertEquals(0, ranked.size)
    }
}
