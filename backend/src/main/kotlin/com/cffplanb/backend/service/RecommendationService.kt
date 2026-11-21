package com.cffplanb.backend.service

import com.cffplanb.backend.domain.Journey
import java.time.OffsetDateTime

object RecommendationService {

    fun rank(journeys: List<Journey>, earliestDeparture: OffsetDateTime): List<Journey> {
        return journeys
            .filter { !it.departure.toInstant().isBefore(earliestDeparture.toInstant()) }
            .sortedWith(
                compareBy(
                    { it.arrival.toInstant() },
                    { it.transfers },
                    { it.departure.toInstant() },
                    { it.id }
                )
            )
    }
}
