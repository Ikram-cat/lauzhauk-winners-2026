package com.alt.backend.service.estimate

/**
 * Transfer-miss risk: the chance that a predicted (chronic or incident-conditioned) arrival delay makes the
 * traveller miss the next connection. Pure math, no I/O; [JourneyPlanService] feeds it the per-leg distributions.
 *
 * The probability comes from the two anchors the artifact exposes — the median and the p90 — with a documented
 * piecewise approximation (docs/API_CONTRACT.md, transfer-miss risk):
 *
 *  - `slack <= median` → `1.0` (the typical delay already eats the connection)
 *  - `slack >= p90`    → `0.1` (only the tail beyond the 90th percentile remains)
 *  - in between        → linear from 1.0 down to 0.1
 *
 * Clamped to [0, 1]. A missing p90 (the live sampler only reports a median) gets a nominal +10-minute spread so
 * the estimate stays conservative instead of pretending the distribution has no tail.
 */
const val TRANSFER_RISK_BASIS_KNOWN = "KNOWN"
const val TRANSFER_RISK_BASIS_CONDITIONED_INCIDENT = "CONDITIONED_INCIDENT"
const val TRANSFER_RISK_BASIS_CHRONIC = "CHRONIC"
const val TRANSFER_RISK_BASIS_NONE = "NONE"

/** Journey-level threshold above which the current pick counts as "probably misses the connection". */
const val TRANSFER_MISS_RISK_THRESHOLD = 0.5
/** An alternative at or below this risk counts as genuinely safer for the recommendation switch. */
const val TRANSFER_SAFE_RISK_THRESHOLD = 0.2
/** Nominal tail used when only a median is known. */
private const val ASSUMED_P90_SPREAD_MIN = 10.0

/** One transfer of a journey: [fromLeg] / [toLeg] index the journey's legs, both TRANSIT. */
data class TransferMissRisk(
    val fromLeg: Int,
    val toLeg: Int,
    /** Scheduled departure of [toLeg] minus scheduled arrival of [fromLeg], minus any walk in between. */
    val slackMinutes: Int,
    val missRisk: Double,
    val basis: String = TRANSFER_RISK_BASIS_NONE,
    /** Predicted arrival delay (median or known) beyond the slack, rounded; null when unknown. */
    val overrunMinutes: Int? = null
)

/**
 * P(arrival delay > [slackMinutes]) from the (median, p90) anchors. Negative slack (the connection is already
 * impossible on paper) is the caller's guard: it returns 1.0 here too, because every delay misses it.
 */
fun missProbability(median: Double, p90: Double?, slackMinutes: Int): Double {
    val slack = slackMinutes.toDouble()
    if (slack <= median) return 1.0
    val tail = (p90 ?: (median + ASSUMED_P90_SPREAD_MIN)).let { if (it.isNaN()) median + ASSUMED_P90_SPREAD_MIN else it }
    if (tail <= median) return 0.1
    if (slack >= tail) return 0.1
    val risk = 1.0 - 0.9 * (slack - median) / (tail - median)
    return risk.coerceIn(0.0, 1.0)
}
