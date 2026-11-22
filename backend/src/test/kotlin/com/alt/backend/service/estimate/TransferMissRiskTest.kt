package com.alt.backend.service.estimate

import kotlin.test.*

/**
 * Unit tests for the transfer-miss approximation: P(arrival delay > slack) from the (median, p90) anchors
 * (docs/API_CONTRACT.md, transfer-miss risk). Unknown p90 falls back to a nominal +10-minute tail.
 */
class TransferMissRiskTest {

    @Test fun `slack at or below the median is a sure miss`() {
        assertEquals(1.0, missProbability(5.0, 12.0, 5), 1e-9)
        assertEquals(1.0, missProbability(5.0, 12.0, 2), 1e-9)
        assertEquals(1.0, missProbability(5.0, 12.0, -3), 1e-9) // already impossible on paper
    }

    @Test fun `slack at or beyond p90 keeps only the documented tail`() {
        assertEquals(0.1, missProbability(2.0, 12.0, 12), 1e-9)
        assertEquals(0.1, missProbability(2.0, 12.0, 40), 1e-9)
    }

    @Test fun `between the anchors the risk falls linearly from 1 to 0_1`() {
        assertEquals(0.55, missProbability(2.0, 12.0, 7), absoluteTolerance = 1e-9)   // halfway: 1 - 0.9/2
        assertEquals(0.82, missProbability(2.0, 12.0, 4), absoluteTolerance = 1e-9)  // 1 - 0.9*2/10
        assertEquals(0.28, missProbability(2.0, 12.0, 10), absoluteTolerance = 1e-9) // 1 - 0.9*8/10
    }

    @Test fun `a missing p90 uses the nominal ten minute tail instead of pretending there is none`() {
        assertEquals(1.0, missProbability(4.0, null, 3), 1e-9)
        assertEquals(0.55, missProbability(4.0, null, 9), absoluteTolerance = 1e-9) // 4..14 midpoint
        assertEquals(0.1, missProbability(4.0, null, 14), 1e-9)
    }

    @Test fun `a degenerate tail never divides by zero and stays clamped`() {
        assertEquals(0.1, missProbability(6.0, 6.0, 7), 1e-9)
        assertEquals(1.0, missProbability(6.0, 6.0, 6), 1e-9)
        assertTrue(missProbability(-8.0, 2.0, 0) in 0.0..1.0)
    }
}
