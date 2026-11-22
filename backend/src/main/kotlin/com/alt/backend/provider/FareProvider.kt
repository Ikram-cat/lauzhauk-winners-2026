package com.alt.backend.provider

import com.alt.backend.domain.Discount
import java.time.OffsetDateTime

/** Point-to-point CHF single fare for an origin/destination pair. */
data class FareQuote(val full2nd: Double, val half2nd: Double, val full1st: Double?, val product: String, val source: String, val half1st: Double? = null) {
    /** Price for the requested class and discount; null when upstream did not give that number. */
    fun price(fareClass: Int, discount: Discount): Double? = when (fareClass to discount) {
        1 to Discount.NONE -> full1st
        1 to Discount.HALF_FARE -> half1st
        2 to Discount.HALF_FARE -> half2nd
        else -> full2nd
    }
}

interface FareProvider {
    /** Null on any failure; never throws except for coroutine cancellation. */
    suspend fun quote(origin: String, destination: String, departure: OffsetDateTime): FareQuote?
}
