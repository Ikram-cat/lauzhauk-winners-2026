@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SmartWaitingTest {
    private val departure = Instant.parse("2026-11-21T14:00:00Z")
    private val budget = WaitingBudget(outboundMinutes = 3, activityMinutes = 6, returnMinutes = 4)

    @Test
    fun roundTripActivityAndSafetyMarginMustAllFit() {
        val now = departure - 18.minutes
        assertTrue(budget.fits(now, departure, marginMinutes = 5))
        assertFalse(budget.fits(now + 1.seconds, departure, marginMinutes = 5))
        assertFalse(budget.fits(now, departure, marginMinutes = 6))
        assertFalse(budget.fits(departure, departure, marginMinutes = 5))
    }

    @Test
    fun departureChangesMoveTheReturnReminderAndUpdateFit() {
        val now = departure - 10.minutes
        val rescheduled = departure + 10.minutes
        assertFalse(budget.fits(now, departure, marginMinutes = 5))
        assertTrue(budget.fits(now, rescheduled, marginMinutes = 5))
        assertEquals(rescheduled - 9.minutes, budget.returnBy(rescheduled, marginMinutes = 5))
    }
}
