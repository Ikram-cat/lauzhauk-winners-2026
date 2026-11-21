package com.cffplanb.backend.api

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
data class DisruptionOutlookResponseDto(
    val status: String,
    val remainingMinutes: RemainingMinutesDto?,
    val expectedEndAt: ExpectedEndDto?,
    val officialRemainingMinutes: Int?,
    val advice: AdviceDto,
    val provenance: DurationProvenanceDto?
)
