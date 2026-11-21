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
    val arrivalPlatform: String? = null,
    val originSloid: String? = null,
    val destinationSloid: String? = null
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
data class RecommendationIdsDto(val fastest: String?, val cheapest: String?, val simplest: String?)

/** Two full journeys chosen regardless of the requested preference; null when there is no route. */
@Serializable
data class RecommendationsDto(val fastest: JourneyDto?, val simplest: JourneyDto?)

internal val TEXT_KEYS = setOf("advice", "message", "why", "reasons", "recommendationIds")
private val jsonOut = kotlinx.serialization.json.Json { encodeDefaults = true }

/** Default output carries no prose; [includeText] keeps advice, message, why, reasons and the old recommendation ids. */
internal fun kotlinx.serialization.json.JsonElement.stripText(): kotlinx.serialization.json.JsonElement = when (this) {
    is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(filterKeys { it !in TEXT_KEYS }.mapValues { it.value.stripText() })
    is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(map { it.stripText() })
    else -> this
}
internal fun <T> textual(ser: kotlinx.serialization.KSerializer<T>, dto: T, includeText: Boolean): kotlinx.serialization.json.JsonObject =
    (jsonOut.encodeToJsonElement(ser, dto).let { if (includeText) it else it.stripText() }) as kotlinx.serialization.json.JsonObject

/** Query validation shared by /api/journeys and /api/redirect; null = invalid. */
internal fun parseLimit(raw: String?): Int? = if (raw == null) 3 else raw.trim().toIntOrNull()?.takeIf { it in 1..10 }
internal fun parseIncludeText(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) { null, "false" -> false; "true" -> true; else -> null }

@Serializable
data class JourneySearchResponseDto(
    val request: SearchRequestDto,
    val journeys: List<JourneyDto>,
    val latestFeasibleDeparture: String? = null,
    val noFeasibleRoute: Boolean? = null,
    val advice: String? = null,
    val recommendations: RecommendationsDto? = null,
    val recommendationIds: RecommendationIdsDto? = null
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
    val journeys: List<JourneyDto> = emptyList(),
    val recommendations: RecommendationsDto? = null,
    val recommendationIds: RecommendationIdsDto? = null,
    val computedAt: String,
    val modelVersion: String?
)
