package com.cffplanb.backend.provider

import java.time.OffsetDateTime

/** Point-to-point CHF single fare for an origin/destination pair. */
data class FareQuote(val full2nd: Double, val half2nd: Double, val full1st: Double?, val product: String, val source: String)

interface FareProvider {
    /** Null on any failure; never throws except for coroutine cancellation. */
    suspend fun quote(origin: String, destination: String, departure: OffsetDateTime): FareQuote?
}
