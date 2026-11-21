package com.cffplanb.backend.api

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponseDto(val status: String = "ok")

@Serializable
data class ErrorDetailDto(val code: String, val message: String)

@Serializable
data class ErrorResponseDto(val error: ErrorDetailDto)

@Serializable
data class SearchRequestDto(
    val origin: String,
    val destination: String,
    val earliestDeparture: String,
    val arrivalDeadline: String? = null,
    val preference: String = "FASTEST",
    val fareClass: Int = 2,
    val discount: String = "NONE"
)

@Serializable
data class JourneyLegDto(
    val mode: String,
    val origin: String,
    val destination: String,
    val departure: String,
    val arrival: String,
    val serviceLabel: String? = null,
    val departurePlatform: String? = null,
    val arrivalPlatform: String? = null
)

@Serializable
data class JourneyDto(
    val id: String,
    val departure: String,
    val arrival: String,
    val durationMinutes: Int,
    val transfers: Int,
    val legs: List<JourneyLegDto>,
    val predictedDelayMinutes: Int? = null,
    val lateArrivalProbability: Double? = null,
    val estimatedFareChf: Double? = null,
    val feasible: Boolean? = null,
    val dataStatus: String,
    val riskSource: String? = null,
    val riskConfidence: String? = null,
    val riskLabel: String? = null,
    val disruptionDurationMin: Int? = null,
    val riskBasis: String? = null,
    val reasons: List<String> = emptyList(),
    val halfFareChf: Double? = null,
    val firstClassFareChf: Double? = null,
    val fareProduct: String? = null,
    val fareSource: String? = null,
    val fareStatus: String? = null,
    val fareChf: Double? = null,
    val firstClassHalfFareChf: Double? = null,
    /** Scheduled arrival + worst measured leg delay; equals [arrival] when delaySource is "none". */
    val expectedArrival: String? = null,
    val expectedDelayMin: Int? = null,
    val delaySource: String? = null,
    val delaySamples: Int? = null
)

@Serializable
data class RecommendationsDto(val fastest: String?, val cheapest: String?, val simplest: String?)

@Serializable
data class JourneySearchResponseDto(
    val request: SearchRequestDto,
    val journeys: List<JourneyDto>,
    val latestFeasibleDeparture: String? = null,
    val noFeasibleRoute: Boolean? = null,
    val advice: String? = null,
    val recommendations: RecommendationsDto? = null
)

@Serializable
data class AlternativesRequestDto(val from: String, val to: String, val notBefore: String, val arrivalDeadline: String? = null)

@Serializable
data class AlternativesResponseDto(
    val request: AlternativesRequestDto,
    val alternatives: List<JourneyDto>,
    val latestFeasibleDeparture: String? = null,
    val noFeasibleRoute: Boolean,
    val advice: String
)

@Serializable
data class ModelInfoDto(
    val modelVersion: String?,
    val generatedAt: String?,
    val coverage: kotlinx.serialization.json.JsonElement?,
    val source: String,
    val keysLoaded: Int,
    val status: String,
    val corridorsLoaded: Int = 0,
    val baselineLoaded: Int = 0,
    val byLineHourCells: Int = 0,
    val corridorKeysSample: List<String> = emptyList(),
    val gtfsRtEnabled: Boolean = false,
    val gtfsRtAgeSeconds: Long? = null,
    val gtfsRtTripUpdates: Int = 0,
    val gtfsRtCancelled: Int = 0,
    val delayModelVersion: String? = null,
    val delayPortions: Int = 0,
    val delayLines: Int = 0,
    val delayArtifactStatus: String = "missing"
)

@Serializable
data class LocationDto(val id: String, val name: String, val kind: String, val x: Double? = null, val y: Double? = null)

@Serializable
data class LocationsResponseDto(val locations: List<LocationDto>)

@Serializable
data class DisruptionDto(
    val legIndex: Int,
    val serviceLabel: String?,
    val from: String,
    val to: String,
    val scheduledDeparture: String,
    val estimatedDeparture: String?,
    val delayMinutes: Int?,
    val cancelled: Boolean,
    val basis: String,
    val confidence: String,
    val cause: String?
)

/** [recommendation] is a JourneyDto plus "why"; it is null only when nothing could be found. */
@Serializable
data class RedirectResponseDto(
    val request: SearchRequestDto,
    val situation: String,
    val disruption: DisruptionDto?,
    val recommendation: kotlinx.serialization.json.JsonObject?,
    val alternatives: List<JourneyDto>,
    val message: String,
    val latestFeasibleDeparture: String? = null,
    val noFeasibleRoute: Boolean? = null,
    val advice: String? = null,
    val recommendations: RecommendationsDto? = null,
    val computedAt: String,
    val modelVersion: String?
)
