@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaresTest {
    private fun body(fare: String, recommendations: String = "") = """
        {"request":{"origin":"Bern","destination":"Basel SBB","preference":"CHEAPEST"},
        "journeys":[{"id":"j1","departure":"2026-11-21T14:02:00+01:00","arrival":"2026-11-21T14:57:00+01:00",
        "durationMinutes":55,"transfers":0,"dataStatus":"LIVE_SCHEDULE","futureField":true,
        "legs":[{"mode":"TRANSIT","origin":"Bern","destination":"Basel SBB",
        "departure":"2026-11-21T14:02:00+01:00","arrival":"2026-11-21T14:57:00+01:00"}]$fare}]$recommendations}
    """.trimIndent()

    @Test
    fun parsesPricedFareAndRecommendations() {
        val response = parseJourneys(body(
            ""","estimatedFareChf":42.0,"halfFareChf":21.0,"firstClassFareChf":72.0,
            "fareProduct":"Streckenbillett","fareSource":"NOVA","fareStatus":"PRICED"""",
            ""","recommendations":{"fastest":"j1","cheapest":"j1","simplest":null}"""
        ))
        val journey = response.journeys.single()
        assertEquals(42.0, journey.fare)
        assertEquals(21.0, journey.halfFare)
        assertEquals(72.0, journey.firstClassFareChf)
        assertEquals("Streckenbillett", journey.fareProduct)
        assertEquals("NOVA", journey.fareSource)
        assertEquals("j1", response.recommendations?.id(Preference.CHEAPEST))
        assertNull(response.recommendations?.id(Preference.SIMPLEST))
        assertEquals("CHF 42.00", chf(journey.fare!!))
        assertEquals("CHF 21.50", chf(21.5))
        assertEquals("CHF 7.05", chf(7.05))
    }

    @Test
    fun nullOrUnavailableFareIsUnknown() {
        val nulls = parseJourneys(body(""","estimatedFareChf":null,"halfFareChf":null,"fareStatus":null""")).journeys.single()
        assertNull(nulls.fare)
        assertNull(nulls.halfFare)
        val unavailable = parseJourneys(body(""","estimatedFareChf":0,"halfFareChf":0,"fareStatus":"UNAVAILABLE"""")).journeys.single()
        assertNull(unavailable.fare)
        assertNull(unavailable.halfFare)
    }

    @Test
    fun missingFareFieldsAndRecommendationsAreTolerated() {
        val response = parseJourneys(body(""))
        assertNull(response.journeys.single().fare)
        assertNull(response.recommendations)
    }

    @Test
    fun preferenceIsSentInTheUrl() {
        val url = journeysUrl("http://api.test/", "Bern", "Genève", null, Preference.SIMPLEST)
        assertTrue(url.startsWith("http://api.test/api/journeys?"), url)
        assertTrue("preference=SIMPLEST" in url, url)
        assertTrue("destination=Gen%C3%A8ve" in url, url)
    }
}
