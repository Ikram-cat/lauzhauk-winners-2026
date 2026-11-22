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

    private fun journey(id: String, arrival: String, transfers: Int) = Journey(
        id = id, dataStatus = "MOCK",
        schedule = Schedule("2026-11-21T14:00:00+01:00", arrival, 30, transfers)
    )

    @Test
    fun appliesAnInclusiveArrivalDeadlineAndKeepsServerOrdering() {
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

    // Fixture trimmed from a real GET /api/journeys?origin=Lausanne&destination=Genève&limit=2
    // response against https://lauzhauk-winners-2026-production.up.railway.app (api-consolidation
    // contract, MSG-0027/0028/0032/0033) — the live shape this app must decode.
    private val liveFixture = """
        {
          "request": {
            "origin": {"input": "Lausanne", "resolvedName": "Lausanne", "id": "ch:1:sloid:1120"},
            "destination": {"input": "Genève", "resolvedName": "Genève", "id": "ch:1:sloid:1008"},
            "departure": "2026-11-22T00:20:54Z", "arrivalDeadline": null,
            "preference": "FASTEST", "fareClass": 2, "discount": "NONE"
          },
          "status": "UNKNOWN",
          "action": "STAY",
          "reasons": [{"code": "REALTIME_UNAVAILABLE"}, {"code": "STAY_FASTEST"}],
          "disruption": null,
          "primaryJourney": {
            "id": "ID-PRIMARY", "dataStatus": "LIVE_SCHEDULE", "verified": true, "feasible": null, "maxOccupancy": "LOW",
            "schedule": {"departure": "2026-11-22T00:50:00Z", "arrival": "2026-11-22T02:06:54Z", "durationMinutes": 76, "transfers": 2},
            "realtime": {"expectedDeparture": "2026-11-22T00:51:00Z", "departureDelayMinutes": 1, "expectedArrival": "2026-11-22T02:07:54Z", "arrivalDelayMinutes": 1, "source": "GTFS_RT", "sampleCount": null},
            "risk": {"corridorIncidentRate": 0.0346, "typicalIncidentDurationMinutes": 709, "source": "BASELINE", "basis": "HOURLY_BASELINE", "confidence": "HIGH", "level": "MEDIUM"},
            "fares": {"status": "PRICED", "selectedChf": 23.8, "product": "Streckenbillett", "source": "NOVA", "catalogChf": {"secondClassFull": 23.8, "secondClassHalfFare": 11.9, "firstClassFull": 40.6, "firstClassHalfFare": 20.3}},
            "legs": [{"mode": "TRANSIT", "origin": "Lausanne", "destination": "Renens VD", "departure": "2026-11-22T00:50:00Z", "arrival": "2026-11-22T00:56:00Z", "expectedDeparture": "2026-11-22T00:51:00Z", "departureDelayMinutes": 1, "expectedArrival": "2026-11-22T00:55:06Z", "arrivalDelayMinutes": 0, "serviceLabel": "RE33", "departurePlatform": "8", "arrivalPlatform": "5", "occupancy": {"firstClass": "LOW", "secondClass": "LOW"}}]
          },
          "recommendedJourney": {
            "id": "ID-PRIMARY", "dataStatus": "LIVE_SCHEDULE", "verified": true, "feasible": null, "maxOccupancy": "LOW",
            "schedule": {"departure": "2026-11-22T00:50:00Z", "arrival": "2026-11-22T02:06:54Z", "durationMinutes": 76, "transfers": 2},
            "realtime": {"expectedDeparture": "2026-11-22T00:51:00Z", "departureDelayMinutes": 1, "expectedArrival": "2026-11-22T02:07:54Z", "arrivalDelayMinutes": 1, "source": "GTFS_RT", "sampleCount": null},
            "risk": {"corridorIncidentRate": 0.0346, "typicalIncidentDurationMinutes": 709, "source": "BASELINE", "basis": "HOURLY_BASELINE", "confidence": "HIGH", "level": "MEDIUM"},
            "fares": {"status": "PRICED", "selectedChf": 23.8, "product": "Streckenbillett", "source": "NOVA", "catalogChf": {"secondClassFull": 23.8, "secondClassHalfFare": 11.9, "firstClassFull": 40.6, "firstClassHalfFare": 20.3}},
            "legs": []
          },
          "alternatives": [
            {
              "id": "ID-ALT", "dataStatus": "LIVE_SCHEDULE", "verified": true, "feasible": null, "maxOccupancy": "LOW",
              "schedule": {"departure": "2026-11-22T02:50:00Z", "arrival": "2026-11-22T03:45:00Z", "durationMinutes": 55, "transfers": 0},
              "realtime": {"expectedDeparture": null, "departureDelayMinutes": null, "expectedArrival": null, "arrivalDelayMinutes": null, "source": "NONE", "sampleCount": null},
              "risk": {"corridorIncidentRate": 0.0273, "typicalIncidentDurationMinutes": 196, "source": "CORRIDOR", "basis": "DAY_WINDOW_953", "confidence": "HIGH", "level": "MEDIUM"},
              "fares": {"status": "PRICED", "selectedChf": 23.8, "product": "Streckenbillett", "source": "NOVA", "catalogChf": {"secondClassFull": 23.8, "secondClassHalfFare": 11.9, "firstClassFull": 40.6, "firstClassHalfFare": 20.3}},
              "legs": [{"mode": "TRANSIT", "origin": "Lausanne", "destination": "Genève", "departure": "2026-11-22T02:50:00Z", "arrival": "2026-11-22T03:45:00Z", "expectedDeparture": null, "departureDelayMinutes": null, "expectedArrival": null, "arrivalDelayMinutes": null, "serviceLabel": "RE33", "departurePlatform": "8", "arrivalPlatform": "3", "occupancy": {"firstClass": "LOW", "secondClass": "LOW"}}]
            }
          ],
          "highlights": {"fastestId": "ID-PRIMARY", "simplestId": "ID-ALT", "cheapestId": "ID-PRIMARY"},
          "deadlineSummary": null,
          "meta": {"computedAt": "2026-11-22T00:20:54Z", "modelVersion": "2026-11-duration-v1"}
        }
    """.trimIndent()

    @Test
    fun decodesTheLiveApiConsolidationContract() {
        val parsed = parseJourneys(liveFixture)
        assertEquals("UNKNOWN", parsed.status)
        assertEquals("STAY", parsed.action)
        assertEquals(listOf("REALTIME_UNAVAILABLE", "STAY_FASTEST"), parsed.reasons.map { it.code })
        assertEquals(null, parsed.disruption)
        assertEquals("ID-PRIMARY", parsed.primaryJourney?.id)
        assertEquals("ID-PRIMARY", parsed.recommendedJourney?.id)
        assertEquals(parsed.primaryJourney?.id, parsed.recommendedJourney?.id) // STAY => same journey, one card
        assertEquals(1, parsed.alternatives.size)
        assertEquals("ID-ALT", parsed.alternatives[0].id)
        assertEquals(listOf("ID-PRIMARY", "ID-ALT"), parsed.displayJourneys.map { it.id })

        val recommended = parsed.recommendedJourney!!
        assertEquals(true, recommended.verified)
        assertEquals("GTFS_RT", recommended.realtime?.source)
        assertEquals(Instant.parse("2026-11-22T00:51:00Z"), recommended.effectiveDeparture)
        assertEquals(Instant.parse("2026-11-22T00:50:00Z"), recommended.scheduleDeparture)
        assertEquals(76, recommended.durationMinutes)
        assertEquals(2, recommended.transfers)
        assertEquals("BASELINE", recommended.risk?.source)
        assertEquals(23.8, recommended.catalogFare(firstClass = false, halfFare = false))
        assertEquals(11.9, recommended.catalogFare(firstClass = false, halfFare = true))
        assertEquals(40.6, recommended.catalogFare(firstClass = true, halfFare = false))

        assertEquals("ID-ALT", parsed.highlights?.id(Preference.SIMPLEST))
        assertEquals("ID-PRIMARY", parsed.highlights?.id(Preference.FASTEST))
        assertEquals("2026-11-duration-v1", parsed.meta?.modelVersion)
    }

    @Test
    fun reasonTextRendersEveryKnownCodeAndInterpolatesSavedMinutes() {
        val codes = listOf(
            "PRIMARY_LEG_DELAYED", "PRIMARY_LEG_CANCELLED", "SUPPRESSED_STOP", "EARLIER_ARRIVAL",
            "FEWER_TRANSFERS", "CHEAPER", "DEADLINE_MET", "DEADLINE_MISSED", "REALTIME_UNAVAILABLE", "STAY_FASTEST"
        )
        for (code in codes) for (language in Language.entries) {
            assertEquals(false, Reason(code).text(language).isBlank(), "expected non-blank text for $code/$language")
        }
        assertEquals("Typically faster by 5 min", Reason("EXPECTED_FASTER", savedMinutes = 5).text(Language.English))
        assertEquals("Généralement plus rapide de 5 min", Reason("EXPECTED_FASTER", savedMinutes = 5).text(Language.French))
    }
}
