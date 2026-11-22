@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        fun journey(id: String, arrival: String, transfers: Int) = Journey(id, "2026-11-21T14:00:00+01:00", arrival, 30, transfers, "MOCK", emptyList())
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

    private val disruptedEnvelope = """
        {
          "status": "DISRUPTION_DETECTED",
          "action": "REROUTE",
          "reasons": [ { "code": "PRIMARY_LEG_DELAYED", "serviceLabel": "IC 1", "delayMinutes": 15 },
                       { "code": "EARLIER_ARRIVAL", "savedMinutes": 12 } ],
          "disruption": {
            "legIndex": 0, "serviceLabel": "IC 1", "from": "Lausanne", "to": "Genève",
            "scheduledDeparture": "2026-11-21T14:15:00Z", "estimatedDeparture": "2026-11-21T14:30:00Z",
            "delayMinutes": 15, "cancelled": false, "basis": "REALTIME_ESTIMATE",
            "confidence": "HIGH", "cause": "WORKS", "severity": "SEVERE"
          },
          "primaryJourney": {
            "id": "p1", "dataStatus": "LIVE_SCHEDULE", "verified": true, "feasible": null, "maxOccupancy": "MEDIUM",
            "schedule": { "departure": "2026-11-21T14:15:00Z", "arrival": "2026-11-21T15:20:00Z", "durationMinutes": 65, "transfers": 1 },
            "realtime": { "expectedDeparture": "2026-11-21T14:30:00Z", "departureDelayMinutes": 15, "arrivalDelayMinutes": 15, "source": "OJP", "sampleCount": null },
            "risk": { "corridorIncidentRate": 0.0412, "typicalIncidentDurationMinutes": 145, "source": "CORRIDOR", "basis": "DAY_WINDOW_953", "confidence": "HIGH", "level": "MEDIUM" },
            "fares": { "status": "PRICED", "selectedChf": 12.6, "product": "Streckenbillett", "source": "NOVA",
                       "catalogChf": { "secondClassFull": 25.2, "secondClassHalfFare": 12.6, "firstClassFull": 44.0, "firstClassHalfFare": 22.0 } },
            "legs": [ { "mode": "TRANSIT", "origin": "Lausanne", "destination": "Genève",
                        "departure": "2026-11-21T14:15:00Z", "arrival": "2026-11-21T15:20:00Z",
                        "serviceLabel": "IC 1", "departurePlatform": "4", "departureDelayMinutes": 15 } ]
          },
          "recommendedJourney": {
            "id": "rec1", "dataStatus": "LIVE_SCHEDULE", "verified": true, "maxOccupancy": null,
            "schedule": { "departure": "2026-11-21T14:30:00Z", "arrival": "2026-11-21T15:45:00Z", "durationMinutes": 75, "transfers": 0 },
            "legs": []
          },
          "alternatives": [ {
            "id": "alt1", "dataStatus": "LIVE_SCHEDULE",
            "schedule": { "departure": "2026-11-21T14:40:00Z", "arrival": "2026-11-21T16:00:00Z", "durationMinutes": 80, "transfers": 0 },
            "legs": []
          } ],
          "highlights": { "fastestId": "rec1", "simplestId": "rec1", "cheapestId": null },
          "deadlineSummary": { "feasible": true, "latestFeasibleDeparture": "2026-11-21T14:40:00Z" },
          "meta": { "computedAt": "2026-11-21T14:00:00Z", "modelVersion": "v-1" }
        }
    """.trimIndent()

    @Test
    fun decodesTheContractEnvelopeAndMapsIt() {
        val parsed = parseEnvelope(disruptedEnvelope)
        assertEquals("DISRUPTION_DETECTED", parsed.situation)
        assertEquals("IC 1", parsed.disruption?.serviceLabel)
        assertEquals(15, parsed.disruption?.delayMinutes)
        assertEquals("WORKS", parsed.disruption?.cause)
        assertEquals("SEVERE", parsed.disruption?.severity)
        assertEquals("high", parsed.disruption?.confidence)
        assertEquals("rec1", parsed.recommendation?.id)
        assertEquals(1, parsed.alternatives.size)
        assertEquals("alt1", parsed.alternatives[0].id)
        assertEquals(listOf("p1", "rec1", "alt1"), parsed.displayJourneys.map { it.id })
        assertEquals("2026-11-21T14:40:00Z", parsed.latestFeasibleDeparture)
        assertEquals("v-1", parsed.modelVersion)
        assertEquals(false, parsed.noFeasibleRoute)

        val primary = parsed.displayJourneys.first()
        assertEquals(true, primary.verified)
        assertEquals("MEDIUM", primary.maxOccupancy)
        assertEquals(15, primary.arrivalDelayMinutes)
        assertEquals("OJP", primary.realtimeSource)
        assertEquals(0.0412, primary.lateArrivalProbability)
        assertEquals("corridor", primary.riskSource)
        assertEquals("Lausanne - Genève", primary.riskLabel)
        assertEquals(145, primary.disruptionDurationMin)
        assertEquals(12.6, primary.fareChf)
        assertEquals("Streckenbillett", primary.fareProduct)
        assertEquals("NOVA", primary.fareSource)
        assertEquals(25.2, primary.fullFare(firstClass = false))
        assertEquals("4", primary.legs[0].departurePlatform)
        assertEquals(15, primary.legs[0].departureDelayMinutes)
    }

    @Test
    fun recommendationWhyIsClientOwnedWordingBuiltFromReasons() {
        val parsed = parseEnvelope(disruptedEnvelope)
        assertEquals("Avoids the disruption · faster by 12 min", parsed.withWhy(Language.English).recommendation?.why)
        assertEquals("Évite la perturbation · plus rapide de 12 min", parsed.withWhy(Language.French).recommendation?.why)
        // the wording follows the language switch, the underlying recommendation is untouched
        assertEquals("rec1", parsed.withWhy(Language.German).recommendation?.id)
    }

    @Test
    fun calmCaseExpectedFasterWhyIsRendered() {
        val json = """
            { "status": "NO_DISRUPTION", "action": "STAY",
              "reasons": [ { "code": "EXPECTED_FASTER", "savedMinutes": 7 } ],
              "primaryJourney": { "id": "p1", "schedule": { "departure": "2026-11-21T14:15:00Z", "arrival": "2026-11-21T15:20:00Z", "durationMinutes": 65, "transfers": 0 }, "legs": [] },
              "recommendedJourney": { "id": "rec1", "schedule": { "departure": "2026-11-21T14:20:00Z", "arrival": "2026-11-21T15:20:00Z", "durationMinutes": 60, "transfers": 0 }, "legs": [] } }
        """.trimIndent()
        val parsed = parseEnvelope(json)
        assertEquals("NO_DISRUPTION", parsed.situation)
        assertEquals("Usually faster by 7 min", parsed.withWhy(Language.English).recommendation?.why)
    }

    @Test
    fun nearUrlCarriesTheCoordinatesAndLimit() {
        val url = nearUrl("http://x/", 46.5, 6.63, 3)
        assertTrue(url.startsWith("http://x/api/locations?"), url)
        assertTrue(url.contains("near=46.5"), url)
        assertTrue(url.contains("6.63"), url)
        assertTrue(url.contains("limit=3"), url)
    }

    @Test
    fun journeysUrlCarriesEveryDocumentedParameter() {
        val deadline = Instant.parse("2026-11-21T15:00:00Z")
        val url = journeysUrl("http://x", "Lausanne", "Genève", null, Preference.CHEAPEST, firstClass = true, halfFare = true, deadline = deadline, limit = 5)
        assertEquals(
            setOf("origin=Lausanne", "destination=Gen%C3%A8ve", "arrivalDeadline=2026-11-21T15%3A00%3A00Z", "preference=CHEAPEST", "fareClass=1", "discount=HALF_FARE", "limit=5"),
            url.substringAfter('?').split('&').toSet()
        )
    }
}
