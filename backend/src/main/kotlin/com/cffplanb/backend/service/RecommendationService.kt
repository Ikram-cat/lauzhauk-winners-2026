package com.cffplanb.backend.service

import com.cffplanb.backend.domain.Journey
import java.time.OffsetDateTime

enum class Preference { FASTEST, CHEAPEST, SIMPLEST }

object RecommendationService {

    fun rank(journeys: List<Journey>, earliestDeparture: OffsetDateTime): List<Journey> = journeys
        .filter { !it.departure.toInstant().isBefore(earliestDeparture.toInstant()) }
        .sortedWith(comparator(Preference.FASTEST))

    fun comparator(preference: Preference, fareOf: (Journey) -> Double? = { null }): Comparator<Journey> = when (preference) {
        Preference.FASTEST -> compareBy({ it.arrival.toInstant() }, { it.transfers }, { it.departure.toInstant() }, { it.id })
        Preference.CHEAPEST -> compareBy<Journey, Double?>(nullsLast()) { fareOf(it) }.thenBy { it.arrival.toInstant() }.thenBy { it.id }
        Preference.SIMPLEST -> compareBy({ it.transfers }, { it.legs.size }, { it.arrival.toInstant() }, { it.id })
    }
}
