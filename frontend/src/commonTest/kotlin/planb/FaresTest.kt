@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaresTest {
    private fun journey(fares: String) = parseEnvelope(
        """
        { "status": "NO_DISRUPTION", "action": "STAY",
          "primaryJourney": { "id": "j1", "dataStatus": "LIVE_SCHEDULE", "futureField": true,
            "schedule": { "departure": "2026-11-21T14:02:00+01:00", "arrival": "2026-11-21T14:57:00+01:00", "durationMinutes": 55, "transfers": 0 },
            "fares": $fares,
            "legs": [ { "mode": "TRANSIT", "origin": "Bern", "destination": "Basel SBB",
                        "departure": "2026-11-21T14:02:00+01:00", "arrival": "2026-11-21T14:57:00+01:00" } ] } }
        """.trimIndent()
    ).journeys.single()

    @Test
    fun parsesThePricedCatalogAndPicksEachClassAndDiscountCombination() {
        val journey = journey(
            """{ "status": "PRICED", "product": "Streckenbillett", "source": "NOVA",
                 "catalogChf": { "secondClassFull": 42.0, "secondClassHalfFare": 21.0,
                                 "firstClassFull": 77.0, "firstClassHalfFare": 38.5 } }"""
        )
        assertEquals(42.0, journey.fare(firstClass = false, halfFare = false))
        assertEquals(21.0, journey.fare(firstClass = false, halfFare = true))
        assertEquals(77.0, journey.fare(firstClass = true, halfFare = false))
        assertEquals(38.5, journey.fare(firstClass = true, halfFare = true))
        assertEquals(42.0, journey.fullFare(firstClass = false))
        assertEquals("CHF 77.00", chf(journey.fullFare(firstClass = true)!!))
        assertEquals("Streckenbillett", journey.fareProduct)
        assertEquals("NOVA", journey.fareSource)
        assertEquals("CHF 42.00", chf(journey.fare(false, false)!!))
        assertEquals("CHF 21.50", chf(21.5))
        assertEquals("CHF 7.05", chf(7.05))
    }

    @Test
    fun selectedChfFallsBackOnlyForTheDefaultSelectionItWasComputedFor() {
        val journey = journey(
            """{ "status": "PRICED", "selectedChf": 38.0, "product": "Streckenbillett", "source": "NOVA",
                 "catalogChf": { "secondClassHalfFare": 21.0 } }"""
        )
        assertEquals(38.0, journey.fare(firstClass = false, halfFare = false)) // catalog full price missing -> selected
        assertEquals(21.0, journey.fare(firstClass = false, halfFare = true))  // catalog wins
        assertNull(journey.fare(firstClass = true, halfFare = false))          // never the other class
        assertNull(journey.fare(firstClass = true, halfFare = true))
    }

    @Test
    fun nullOrUnavailableFareIsUnknown() {
        val absent = journey("""{ }""")
        assertNull(absent.fare(false, false))
        assertNull(absent.fare(false, true))
        assertNull(absent.fare(true, true))
        assertNull(absent.fullFare(false))
        assertNull(absent.fareProduct)
        val unavailable = journey(
            """{ "status": "UNAVAILABLE", "selectedChf": 0, "product": null, "source": "NOVA",
                 "catalogChf": { "secondClassFull": 0, "secondClassHalfFare": 0 } }"""
        )
        assertNull(unavailable.fare(false, false))
        assertNull(unavailable.fare(false, true))
        assertNull(unavailable.fullFare(false))
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
    fun passMenuPricesBothClassesFromTheCatalogWithoutApplyingTheStandardQuoteToHalfFare() {
        val journey = journey(
            """{ "status": "PRICED", "product": "Streckenbillett", "source": "NOVA",
                 "catalogChf": { "secondClassFull": 42.0, "secondClassHalfFare": 21.0,
                                 "firstClassFull": 72.0, "firstClassHalfFare": 36.0 } }"""
        )
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
