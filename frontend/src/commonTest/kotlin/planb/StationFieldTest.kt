package planb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StationFieldTest {
    @Test
    fun matchNamesAndAddressAliasesWithoutAccents() {
        assertEquals("Genève", stationSuggestions(" Geneve ").first().name)
        assertEquals("Place de la Gare", stationSuggestions("place de la ga").first().address)
        assertEquals(listOf("Lausanne", "Genève"), stationSuggestions("place de").map { it.name })
        assertEquals("Genève", canonicalStationInput("Place de Cornavin"))
    }

    @Test
    fun leaveUnrecognizedLocationsAsFreeTextWithoutSuggestions() {
        assertTrue(stationSuggestions("Unmapped street 42").isEmpty())
        assertEquals("Unmapped street 42", canonicalStationInput(" Unmapped street 42 "))
        assertTrue(stationSuggestions(" ").isEmpty())
    }
}
