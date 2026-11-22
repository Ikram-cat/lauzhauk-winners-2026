package com.alt.backend.api

import kotlinx.serialization.Serializable

@Serializable
data class DisruptionInputDto(
    val reasonCode: String? = null,
    val durationType: String? = null,
    val startedAt: String,
    val announcedEndAt: String? = null,
    val updatesSoFar: Int = 0,
    val summaryEn: String? = null
)

@Serializable
data class DisruptionOutlookRequestDto(
    val disruption: DisruptionInputDto,
    val observedAt: String? = null,
    val detourExtraMinutes: Int? = null
)

@Serializable
data class RemainingMinutesDto(val p50: Int, val pessimistic: Int)

@Serializable
data class ExpectedEndDto(val p50: String, val pessimistic: String)

@Serializable
data class AdviceDto(val code: String, val detourExtraMinutes: Int?)

@Serializable
data class DurationProvenanceDto(
    val kind: String,
    val modelVersion: String,
    val pessimisticQuantile: Double,
    val pessimisticCoverage: Double?
)

@Serializable
data class ExplanationDto(
    val reasonCode: String?,
    val scope: String,
    val similarIncidents: Int,
    val medianDurationMinutes: Int,
    val announcementsCompared: Int,
    val medianEndMinusAnnouncedMinutes: Int?,
    val source: String = "TRAINING_DATA"
)

@Serializable
data class DisruptionOutlookResponseDto(
    val status: String,
    val remainingMinutes: RemainingMinutesDto?,
    val expectedEndAt: ExpectedEndDto?,
    val officialRemainingMinutes: Int?,
    val advice: AdviceDto,
    val provenance: DurationProvenanceDto?,
    val explanation: ExplanationDto? = null
)
