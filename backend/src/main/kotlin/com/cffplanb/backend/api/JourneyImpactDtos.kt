package com.cffplanb.backend.api

import kotlinx.serialization.Serializable

@Serializable
data class JourneyImpactRequestDto(
    val origin: String,
    val destination: String,
    val departure: String? = null,
    val observedAt: String? = null,
    val journeyId: String? = null
)

@Serializable
data class MatchedStationDto(
    val legIndex: Int,
    val uic: Int,
    val sloid: String?,
    val name: String?,
    val roles: List<String>,
    val matchedBy: String
)

@Serializable
data class AlternativeDto(val status: String, val journey: JourneyDto?)

@Serializable
data class JourneyDisruptionImpactDto(
    val incidentId: String,
    val summaryEn: String?,
    val matchedStations: List<MatchedStationDto>,
    val matchedLines: List<String>,
    val detourExtraMinutes: Int?,
    val alternative: AlternativeDto,
    val outlook: DisruptionOutlookResponseDto
)

@Serializable
data class JourneyImpactResponseDto(
    val dataStatus: String,
    val observedAt: String,
    val journey: JourneyDto?,
    val affected: Boolean,
    val impacts: List<JourneyDisruptionImpactDto>
)
