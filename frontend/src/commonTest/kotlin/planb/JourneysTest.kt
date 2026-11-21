@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class JourneysTest {
    @Test
    fun blankDepartureMeansNow() {
        assertNull(swissDeparture("", ""))
    }

    @Test
    fun convertsSwissMidnightAcrossUtcDateAndSeason() {
        assertEquals(Instant.parse("2026-01-20T23:10:00Z"), swissDeparture("2026-01-21", "00:10"))
        assertEquals(Instant.parse("2026-07-20T22:10:00Z"), swissDeparture("2026-07-21", "00:10"))
    }

    @Test
    fun rejectsMissingAndAmbiguousSwissLocalTimes() {
        assertFailsWith<IllegalArgumentException> { swissDeparture("2026-03-29", "02:30") }
        assertFailsWith<IllegalArgumentException> { swissDeparture("2026-10-25", "02:30") }
    }

    @Test
    fun appliesAnInclusiveArrivalDeadlineAndKeepsServerOrdering() {
        fun journey(id: String, arrival: String, transfers: Int) =
            Journey(id, "2026-11-21T14:00:00+01:00", arrival, 30, transfers, "MOCK", emptyList())
        val results = listOf(
            journey("transfer", "2026-11-21T14:20:00+01:00", 1),
            journey("at-deadline", "2026-11-21T13:30:00Z", 0),
            journey("late", "2026-11-21T14:40:00+01:00", 0)
        )
        val deadline = Instant.parse("2026-11-21T14:30:00+01:00")
        assertEquals(listOf("transfer", "at-deadline"), JourneyOptions(arriveBy = deadline).applyTo(results).map { it.id })
        assertEquals(listOf("at-deadline"), JourneyOptions(true, deadline).applyTo(results).map { it.id })
        assertEquals(emptyList(), JourneyOptions(arriveBy = Instant.parse("2026-11-21T13:00:00Z")).applyTo(results))
    }

    @Test
    fun decodesRedirectResponseWithDisruptionAndWhy() {
        val jsonString = """
            {
              "situation": "DISRUPTION_DETECTED",
              "disruption": {
                "serviceLabel": "IC 1",
                "from": "Lausanne",
                "to": "Bern",
                "delayMinutes": 15,
                "cancelled": false,
                "confidence": "low",
                "cause": "Panne de materiel"
              },
              "recommendation": {
                "id": "j-rec",
                "departure": "2026-11-21T14:15:00Z",
                "arrival": "2026-11-21T15:20:00Z",
                "durationMinutes": 65,
                "transfers": 1,
                "dataStatus": "MOCK",
                "legs": [],
                "why": "Arrives before deadline"
              },
              "alternatives": [
                {
                  "id": "j-alt-1",
                  "departure": "2026-11-21T14:30:00Z",
                  "arrival": "2026-11-21T15:45:00Z",
                  "durationMinutes": 75,
                  "transfers": 0,
                  "dataStatus": "MOCK",
                  "legs": []
                }
              ],
              "message": "RE33 is delayed. We redirected you to IC 1."
            }
        """.trimIndent()
        val parsed = parseRedirect(jsonString)
        assertEquals("DISRUPTION_DETECTED", parsed.situation)
        assertEquals("IC 1", parsed.disruption?.serviceLabel)
        assertEquals(15, parsed.disruption?.delayMinutes)
        assertEquals(false, parsed.disruption?.cancelled)
        assertEquals("low", parsed.disruption?.confidence)
        assertEquals("j-rec", parsed.recommendation?.id)
        assertEquals("Arrives before deadline", parsed.recommendation?.why)
        assertEquals(1, parsed.alternatives.size)
        assertEquals("j-alt-1", parsed.alternatives[0].id)
        assertEquals(listOf("j-rec", "j-alt-1"), parsed.displayJourneys.map { it.id })
    }
}
