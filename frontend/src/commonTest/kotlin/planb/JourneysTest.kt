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
}
