package com.cffplanb.backend.domain

import java.time.OffsetDateTime

/** What is publicly known about a disruption at the observation instant. */
data class DisruptionContext(
    val reasonCode: String?,
    val durationType: String?,
    val startedAt: OffsetDateTime,
    val announcedEndAt: OffsetDateTime?,
    val updatesSoFar: Int,
    val summaryEn: String?
)

/** Remaining duration in minutes, from the offline-trained model artifact. */
data class DurationEstimate(
    val p50Minutes: Double,
    val pessimisticMinutes: Double,
    val modelVersion: String,
    val pessimisticQuantile: Double,
    val pessimisticCoverage: Double?
)

enum class AdviceCode { TAKE_DETOUR, DETOUR_IF_DEADLINE, WAIT, UNKNOWN }
