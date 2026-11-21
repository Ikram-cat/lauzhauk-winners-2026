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
    val arrivalPlatform: String?
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
