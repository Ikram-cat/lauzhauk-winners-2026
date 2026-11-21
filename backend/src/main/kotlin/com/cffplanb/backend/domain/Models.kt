package com.cffplanb.backend.domain

import java.time.OffsetDateTime

enum class LegMode { TRANSIT, WALK }
enum class DataStatus { LIVE_SCHEDULE, MOCK }
enum class Discount { NONE, HALF_FARE }

data class JourneyLeg(
    val mode: LegMode,
    val origin: String,
    val destination: String,
    val departure: OffsetDateTime,
    val arrival: OffsetDateTime,
    val serviceLabel: String?,
    val departurePlatform: String?,
    val arrivalPlatform: String?,
    /** Provider estimates (OJP EstimatedTime); null when the provider gave none. */
    val expectedDeparture: OffsetDateTime? = null,
    val expectedArrival: OffsetDateTime? = null,
    /** LOW | MEDIUM | HIGH at boarding, per class; null = unknown. */
    val occupancyFirst: String? = null,
    val occupancySecond: String? = null
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
    val earliestDeparture: OffsetDateTime,
    val fareClass: Int = 2,
    val discount: Discount = Discount.NONE
)
