package com.cffplanb.backend.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class ErrorDetailDto(val code: String, val message: String, val field: String? = null)

@Serializable
data class ErrorResponseDto(val error: ErrorDetailDto)

// ---- /health ----

@Serializable
data class GtfsRtHealthDto(val enabled: Boolean, val status: String, val ageSeconds: Long?, val activeTripUpdates: Int?, val cancelledTrips: Int?)

@Serializable
data class FeedsDto(val ojp: String, val nova: String, val gtfsRt: GtfsRtHealthDto)

@Serializable
data class HealthResponseDto(val status: String, val feeds: FeedsDto)

// ---- /api/locations ----

@Serializable
data class CoordinatesDto(val lat: Double, val lon: Double)

@Serializable
data class LocationDto(val id: String, val name: String, val kind: String, val coordinates: CoordinatesDto?)

@Serializable
data class LocationsResponseDto(val locations: List<LocationDto>)

// ---- /api/model ----

@Serializable
data class CoverageDto(val from: String?, val to: String?, val windowDays: Int?, val scope: String?)

@Serializable
data class RiskCountsDto(val keys: Int, val corridors: Int, val baselineHours: Int, val byLineHourCells: Int)

@Serializable
data class RiskModelDto(val version: String?, val generatedAt: String?, val source: String, val status: String, val coverage: CoverageDto?, val counts: RiskCountsDto)

@Serializable
data class DelayCountsDto(val portions: Int, val lines: Int)

@Serializable
data class DelayModelDto(val version: String?, val status: String, val counts: DelayCountsDto)

@Serializable
data class ModelResponseDto(val riskModel: RiskModelDto, val delayModel: DelayModelDto)

// ---- /api/journeys ----

@Serializable
data class PlaceEchoDto(val input: String, val resolvedName: String?, val id: String?)

@Serializable
data class RequestDto(
    val origin: PlaceEchoDto, val destination: PlaceEchoDto, val departure: String, val arrivalDeadline: String?,
    val preference: String, val fareClass: Int, val discount: String
)

@Serializable
data class DisruptionDto(
    val legIndex: Int, val serviceLabel: String?, val from: String, val to: String,
    val scheduledDeparture: String, val estimatedDeparture: String?, val delayMinutes: Int?, val cancelled: Boolean,
    val basis: String, val confidence: String, val cause: String?
)

@Serializable
data class ScheduleDto(val departure: String, val arrival: String, val durationMinutes: Int, val transfers: Int)

@Serializable
data class RealtimeDto(
    val expectedDeparture: String?, val departureDelayMinutes: Int?, val expectedArrival: String?, val arrivalDelayMinutes: Int?,
    val source: String, val sampleCount: Int?
)

@Serializable
data class RiskDto(
    val corridorIncidentRate: Double?, val typicalIncidentDurationMinutes: Int?, val source: String,
    val basis: String?, val confidence: String?, val level: String?
)

@Serializable
data class CatalogFaresDto(val secondClassFull: Double?, val secondClassHalfFare: Double?, val firstClassFull: Double?, val firstClassHalfFare: Double?)

@Serializable
data class FaresDto(val status: String, val selectedChf: Double?, val product: String?, val source: String?, val catalogChf: CatalogFaresDto?)

@Serializable
data class OccupancyDto(val firstClass: String?, val secondClass: String?)

@Serializable
data class LegDto(
    val mode: String, val origin: String, val destination: String, val departure: String, val arrival: String,
    val expectedDeparture: String?, val departureDelayMinutes: Int?, val expectedArrival: String?, val arrivalDelayMinutes: Int?,
    val serviceLabel: String?, val departurePlatform: String?, val arrivalPlatform: String?, val occupancy: OccupancyDto?
)

@Serializable
data class JourneyDto(
    val id: String, val dataStatus: String, val feasible: Boolean?, val maxOccupancy: String?,
    val schedule: ScheduleDto, val realtime: RealtimeDto, val risk: RiskDto, val fares: FaresDto, val legs: List<LegDto>
)

@Serializable
data class HighlightsDto(val fastestId: String?, val simplestId: String?, val cheapestId: String?)

@Serializable
data class DeadlineSummaryDto(val feasible: Boolean, val latestFeasibleDeparture: String?)

@Serializable
data class MetaDto(val computedAt: String, val modelVersion: String?)

@Serializable
data class JourneysResponseDto(
    val request: RequestDto, val status: String, val action: String, val reasons: JsonArray, val disruption: DisruptionDto?,
    val primaryJourney: JourneyDto?, val recommendedJourney: JourneyDto?, val alternatives: List<JourneyDto>,
    val highlights: HighlightsDto, val deadlineSummary: DeadlineSummaryDto?, val meta: MetaDto
)
