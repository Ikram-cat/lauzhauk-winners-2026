package com.cffplanb.backend.service

import com.cffplanb.backend.domain.Journey
import java.time.OffsetDateTime

enum class Preference { FASTEST, CHEAPEST, SIMPLEST }

object RecommendationService {

    fun rank(journeys: List<Journey>, earliestDeparture: OffsetDateTime): List<Journey> = journeys
        .filter { !it.departure.toInstant().isBefore(earliestDeparture.toInstant()) }
        .sortedWith(comparator(Preference.FASTEST))

    /** [arrival] lets callers rank by expected instead of scheduled arrival. */
    fun comparator(preference: Preference, fareOf: (Journey) -> Double? = { null }, arrival: (Journey) -> java.time.Instant = { it.arrival.toInstant() }): Comparator<Journey> = when (preference) {
        Preference.FASTEST -> compareBy({ arrival(it) }, { it.transfers }, { it.departure.toInstant() }, { it.id })
        Preference.CHEAPEST -> compareBy<Journey, Double?>(nullsLast()) { fareOf(it) }.thenBy { arrival(it) }.thenBy { it.id }
        Preference.SIMPLEST -> compareBy({ it.transfers }, { it.legs.size }, { arrival(it) }, { it.id })
    }
}
