package com.cffplanb.backend.domain

import java.time.OffsetDateTime

enum class LegMode { TRANSIT, WALK }
enum class DataStatus { LIVE_SCHEDULE, MOCK }

data class JourneyLeg(
    val mode: LegMode,
    val origin: String,
    val destination: String,
    val departure: OffsetDateTime,
    val arrival: OffsetDateTime,
    val serviceLabel: String?,
    val departurePlatform: String?,
    val arrivalPlatform: String?,
    /** Stop-place sloids (ch:1:sloid:<n>) when the provider supplies them; used to match disruptions. */
    val originSloid: String? = null,
    val destinationSloid: String? = null,
    val intermediateSloids: List<String> = emptyList()
)

data class Journey(
    val id: String,
    val departure: OffsetDateTime,
    val arrival: OffsetDateTime,
    val durationMinutes: Int,
    val transfers: Int,
    val legs: List<JourneyLeg>,
    val dataStatus: DataStatus
)

data class SearchRequest(
    val origin: String,
    val destination: String,
    val earliestDeparture: OffsetDateTime
)
