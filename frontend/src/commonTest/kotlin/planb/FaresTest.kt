@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaresTest {
    private fun body(fares: String, highlights: String = """{"fastestId":"j1","cheapestId":"j1","simplestId":null}""") = """
        {"status":"NO_DISRUPTION","action":"STAY","reasons":[],"disruption":null,
        "primaryJourney":{"id":"j1","dataStatus":"LIVE_SCHEDULE","verified":true,
        "schedule":{"departure":"2026-11-21T14:02:00+01:00","arrival":"2026-11-21T14:57:00+01:00","durationMinutes":55,"transfers":0},
        "legs":[{"mode":"TRANSIT","origin":"Bern","destination":"Basel SBB",
        "departure":"2026-11-21T14:02:00+01:00","arrival":"2026-11-21T14:57:00+01:00"}]$fares},
        "recommendedJourney":{"id":"j1","dataStatus":"LIVE_SCHEDULE","verified":true,
        "schedule":{"departure":"2026-11-21T14:02:00+01:00","arrival":"2026-11-21T14:57:00+01:00","durationMinutes":55,"transfers":0},
        "legs":[{"mode":"TRANSIT","origin":"Bern","destination":"Basel SBB",
        "departure":"2026-11-21T14:02:00+01:00","arrival":"2026-11-21T14:57:00+01:00"}]$fares},
        "alternatives":[],"highlights":$highlights,"deadlineSummary":null,"meta":null}
    """.trimIndent()

    @Test
    fun parsesPricedFareAndHighlights() {
        val response = parseJourneys(body(
            ""","fares":{"status":"PRICED","selectedChf":42.0,"product":"Streckenbillett","source":"NOVA",
            "catalogChf":{"secondClassFull":42.0,"secondClassHalfFare":21.0,"firstClassFull":72.0,"firstClassHalfFare":null}}"""
        ))
        val journey = response.recommendedJourney!!
        assertEquals(42.0, journey.catalogFare(firstClass = false, halfFare = false))
        assertEquals(21.0, journey.catalogFare(firstClass = false, halfFare = true))
        assertEquals(72.0, journey.catalogFare(firstClass = true, halfFare = false))
        assertNull(journey.catalogFare(firstClass = true, halfFare = true))
        assertEquals("Streckenbillett", journey.fares?.product)
        assertEquals("NOVA", journey.fares?.source)
        assertEquals(42.0, journey.fares?.selectedChf)
        assertEquals("j1", response.highlights?.id(Preference.CHEAPEST))
        assertNull(response.highlights?.id(Preference.SIMPLEST))
        assertEquals("CHF 42.00", chf(journey.catalogFare(false, false)!!))
        assertEquals("CHF 21.50", chf(21.5))
        assertEquals("CHF 7.05", chf(7.05))
    }

    @Test
    fun nullOrUnavailableFareIsUnknown() {
        val nulls = parseJourneys(body(""","fares":{"status":null}""")).recommendedJourney!!
        assertNull(nulls.catalogFare(false, false))
        assertNull(nulls.catalogFare(false, true))
        val unavailable = parseJourneys(body(""","fares":{"status":"UNAVAILABLE","catalogChf":{"secondClassFull":0.0}}""")).recommendedJourney!!
        assertNull(unavailable.catalogFare(false, false))
        assertNull(unavailable.catalogFare(false, true))
        val disabled = parseJourneys(body(""","fares":{"status":"DISABLED"}""")).recommendedJourney!!
        for (first in listOf(false, true)) for (half in listOf(false, true)) assertNull(disabled.catalogFare(first, half))
    }

    @Test
    fun missingFareFieldsAndHighlightsAreTolerated() {
        val response = parseJourneys(body("", "null"))
        assertNull(response.recommendedJourney!!.catalogFare(false, false))
        assertNull(response.highlights)
    }

    @Test
    fun allFourClassAndDiscountCombinationsPickTheirCatalogFare() {
        val fares = ""","fares":{"status":"PRICED","selectedChf":42.0,
            "catalogChf":{"secondClassFull":42.0,"secondClassHalfFare":21.0,"firstClassFull":77.0,"firstClassHalfFare":38.5}}"""
        val journey = parseJourneys(body(fares)).recommendedJourney!!
        assertEquals(42.0, journey.catalogFare(firstClass = false, halfFare = false))
        assertEquals(21.0, journey.catalogFare(firstClass = false, halfFare = true))
        assertEquals(77.0, journey.catalogFare(firstClass = true, halfFare = false))
        assertEquals(38.5, journey.catalogFare(firstClass = true, halfFare = true))
        assertEquals("CHF 77.00", chf(journey.catalogFare(firstClass = true, halfFare = false)!!))
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
    fun travelcardMenuUsesCatalogChfForBothClassesWithoutApplyingTheStandardQuoteToHalfFare() {
        val journey = parseJourneys(body(
            ""","fares":{"status":"PRICED","selectedChf":42.0,
            "catalogChf":{"secondClassFull":42.0,"secondClassHalfFare":21.0,"firstClassFull":72.0,"firstClassHalfFare":36.0}}"""
        )).recommendedJourney!!
        assertEquals(42.0, FareProfile().price(journey))
        assertEquals(21.0, FareProfile(travelcard = Travelcard.HALF_FARE).price(journey))
        assertEquals(72.0, FareProfile(firstClass = true).price(journey))
        assertEquals(36.0, FareProfile(true, Travelcard.HALF_FARE).price(journey))
        for (pass in Travelcard.entries.filterNot { it.canQuote }) {
            assertNull(FareProfile(travelcard = pass).price(journey))
        }
        assertNull(FareProfile().price(journey.copy(dataStatus = "MOCK", fares = journey.fares?.copy(status = "DISABLED"))))
        assertNull(FareProfile().price(journey.copy(fares = journey.fares?.copy(status = "UNAVAILABLE"))))
    }
}
