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
    val preference: String = "FASTEST"
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
    val riskLabel: String? = null
)

@Serializable
data class JourneySearchResponseDto(
    val request: SearchRequestDto,
    val journeys: List<JourneyDto>,
    val latestFeasibleDeparture: String? = null
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
    val corridorKeysSample: List<String> = emptyList()
)

@Serializable
data class LocationDto(val id: String, val name: String, val kind: String, val x: Double? = null, val y: Double? = null)

@Serializable
data class LocationsResponseDto(val locations: List<LocationDto>)
