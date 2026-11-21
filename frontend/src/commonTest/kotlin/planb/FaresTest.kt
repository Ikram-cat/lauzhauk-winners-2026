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
        assertEquals(42.0, journey.fare(firstClass = false, halfFare = false))
        assertEquals(21.0, journey.fare(firstClass = false, halfFare = true))
        assertEquals(72.0, journey.fare(firstClass = true, halfFare = false))
        assertNull(journey.fare(firstClass = true, halfFare = true))
        assertEquals("Streckenbillett", journey.fareProduct)
        assertEquals("NOVA", journey.fareSource)
        assertEquals("j1", response.recommendations?.id(Preference.CHEAPEST))
        assertNull(response.recommendations?.id(Preference.SIMPLEST))
        assertEquals("CHF 42.00", chf(journey.fare(false, false)!!))
        assertEquals("CHF 21.50", chf(21.5))
        assertEquals("CHF 7.05", chf(7.05))
    }

    @Test
    fun nullOrUnavailableFareIsUnknown() {
        val nulls = parseJourneys(body(""","estimatedFareChf":null,"halfFareChf":null,"fareStatus":null""")).journeys.single()
        assertNull(nulls.fare(false, false))
        assertNull(nulls.fare(false, true))
        val unavailable = parseJourneys(body(""","estimatedFareChf":0,"halfFareChf":0,"fareStatus":"UNAVAILABLE"""")).journeys.single()
        assertNull(unavailable.fare(false, false))
        assertNull(unavailable.fare(false, true))
        assertNull(unavailable.fullFare(false))
        val v2Unavailable = parseJourneys(body(""","fareChf":null,"firstClassHalfFareChf":null,"fareStatus":"UNAVAILABLE"""")).journeys.single()
        for (first in listOf(false, true)) for (half in listOf(false, true)) assertNull(v2Unavailable.fare(first, half))
    }

    @Test
    fun missingFareFieldsAndRecommendationsAreTolerated() {
        val response = parseJourneys(body(""))
        assertNull(response.journeys.single().fare(false, false))
        assertNull(response.recommendations)
    }

    @Test
    fun allFourClassAndDiscountCombinationsPickTheirFare() {
        val fares = ""","estimatedFareChf":42.0,"halfFareChf":21.0,"firstClassFareChf":77.0,"firstClassHalfFareChf":38.5,"fareStatus":"PRICED""""
        val journey = parseJourneys(body(fares)).journeys.single()
        assertEquals(42.0, journey.fare(firstClass = false, halfFare = false))
        assertEquals(21.0, journey.fare(firstClass = false, halfFare = true))
        assertEquals(77.0, journey.fare(firstClass = true, halfFare = false))
        assertEquals(38.5, journey.fare(firstClass = true, halfFare = true))
        assertEquals(42.0, journey.fullFare(firstClass = false))
        assertEquals("CHF 77.00", chf(journey.fullFare(firstClass = true)!!))
        // Backend fareChf is the selected price and wins over the local mapping.
        val selected = parseJourneys(body(fares + ""","fareChf":38.0""")).journeys.single()
        assertEquals(38.0, selected.fare(firstClass = true, halfFare = true))
    }

    @Test
    fun fareClassAndDiscountAreSentInTheUrl() {
        val url = journeysUrl("http://api.test", "Bern", "Basel SBB", null, Preference.CHEAPEST, firstClass = true, halfFare = true)
        assertTrue("fareClass=1" in url && "discount=HALF_FARE" in url && "preference=CHEAPEST" in url, url)
        val defaults = journeysUrl("http://api.test", "Bern", "Basel SBB", null, Preference.FASTEST)
        assertTrue("fareClass=2" in defaults && "discount=NONE" in defaults, defaults)
    }

    @Test
    fun preferenceIsSentInTheUrl() {
        val url = journeysUrl("http://api.test/", "Bern", "Genève", null, Preference.SIMPLEST)
        assertTrue(url.startsWith("http://api.test/api/journeys?"), url)
        assertTrue("preference=SIMPLEST" in url, url)
        assertTrue("destination=Gen%C3%A8ve" in url, url)
    }

    @Test
    fun passMenuUsesBackendQuotesForBothClassesWithoutApplyingTheStandardQuoteToHalfFare() {
        val journey = parseJourneys(body(
            ""","fareChf":42.0,"halfFareChf":21.0,"firstClassFareChf":72.0,"firstClassHalfFareChf":36.0,"fareStatus":"PRICED""""
        )).journeys.single()
        assertEquals(42.0, FareProfile().price(journey))
        assertEquals(21.0, FareProfile(travelcard = Travelcard.HALF_FARE).price(journey))
        assertEquals(72.0, FareProfile(firstClass = true).price(journey))
        assertEquals(36.0, FareProfile(true, Travelcard.HALF_FARE).price(journey))
        for (pass in Travelcard.entries.filterNot { it.canQuote }) {
            assertNull(FareProfile(travelcard = pass).price(journey))
        }
        assertNull(FareProfile().price(journey.copy(dataStatus = "MOCK")))
        assertNull(FareProfile().price(journey.copy(fareStatus = "UNAVAILABLE")))
    }
}
