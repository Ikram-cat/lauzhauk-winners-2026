package alt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LocationsResponse(val locations: List<Location> = emptyList())

@Serializable
data class Location(
    val id: String = "",
    val name: String = "",
    val kind: String? = null,
    val coordinates: Coords? = null,
)

@Serializable
data class Coords(val lat: Double? = null, val lon: Double? = null)

@Serializable
data class ErrorEnvelope(val error: ApiError? = null)

@Serializable
data class ApiError(val code: String = "ERROR", val message: String = "")

@Serializable
data class JourneyResponse(
    val request: SearchRequest? = null,
    val status: String? = null,
    val action: String? = null,
    val reasons: List<Reason> = emptyList(),
    val disruption: Disruption? = null,
    val primaryJourney: Journey? = null,
    val recommendedJourney: Journey? = null,
    val alternatives: List<Journey> = emptyList(),
    val highlights: Highlights? = null,
    val deadlineSummary: DeadlineSummary? = null,
    val demoPerturbation: DemoPerturbation? = null,
    val meta: Meta? = null,
)

@Serializable
data class SearchRequest(
    val origin: Endpoint? = null,
    val destination: Endpoint? = null,
    val departure: String? = null,
    val arrivalDeadline: String? = null,
    val preference: String? = null,
    val fareClass: Int? = null,
    val discount: String? = null,
)

@Serializable
data class Endpoint(val input: String? = null, val resolvedName: String? = null, val id: String? = null)

@Serializable
data class Reason(
    val code: String = "",
    val serviceLabel: String? = null,
    val delayMinutes: Double? = null,
    val savedMinutes: Double? = null,
    val savedTransfers: Int? = null,
    val savedChf: Double? = null,
    val marginMinutes: Double? = null,
)

@Serializable
data class Disruption(
    val legIndex: Int? = null,
    val serviceLabel: String? = null,
    val from: String? = null,
    val to: String? = null,
    val scheduledDeparture: String? = null,
    val estimatedDeparture: String? = null,
    val delayMinutes: Double? = null,
    val cancelled: Boolean? = null,
    val basis: String? = null,
    val confidence: String? = null,
    val cause: String? = null,
    val severity: String? = null,
)

@Serializable
data class Meta(val computedAt: String? = null, val modelVersion: String? = null)

@Serializable
data class DemoPerturbation(
    val active: Boolean = false,
    val seed: Long? = null,
    val applied: List<String> = emptyList(),
)

@Serializable
data class Highlights(
    val fastestId: String? = null,
    val simplestId: String? = null,
    val cheapestId: String? = null,
)

@Serializable
data class DeadlineSummary(val feasible: Boolean? = null, val latestFeasibleDeparture: String? = null)

@Serializable
data class Journey(
    val id: String = "",
    val dataStatus: String? = null,
    val verified: Boolean? = null,
    val feasible: Boolean? = null,
    val maxOccupancy: String? = null,
    val schedule: Schedule? = null,
    val realtime: Realtime? = null,
    val risk: Risk? = null,
    val fares: Fares? = null,
    val legs: List<Leg> = emptyList(),
)

@Serializable
data class Schedule(
    val departure: String? = null,
    val arrival: String? = null,
    val durationMinutes: Int? = null,
    val transfers: Int? = null,
)

@Serializable
data class Realtime(
    val expectedDeparture: String? = null,
    val departureDelayMinutes: Double? = null,
    val expectedArrival: String? = null,
    val arrivalDelayMinutes: Double? = null,
    val source: String? = null,
    val sampleCount: Int? = null,
    val estimatedDepartureDelayMinutes: Double? = null,
    val estimatedArrivalDelayMinutes: Double? = null,
    val estimatedSource: String? = null,
    val estimatedSamples: Int? = null,
    val projectedDeparture: String? = null,
    val projectedArrival: String? = null,
    val estimatedBasis: String? = null,
    val transferMissRisk: Double? = null,
    val transferRiskBasis: String? = null,
    val incidentExtraMinutes: Double? = null,
    val incidentCancellationRisk: Double? = null,
    val incidentSuppressedStopRisk: Double? = null,
    val incidentSamples: Int? = null,
    val incidentCount: Int? = null,
)

@Serializable
data class Risk(
    val corridorIncidentRate: Double? = null,
    val typicalIncidentDurationMinutes: Int? = null,
    val source: String? = null,
    val basis: String? = null,
    val confidence: String? = null,
    val level: String? = null,
)

@Serializable
data class Fares(
    val status: String? = null,
    val selectedChf: Double? = null,
    val product: String? = null,
    val source: String? = null,
    val catalogChf: CatalogFares? = null,
)

@Serializable
data class CatalogFares(
    @SerialName("secondClassFull") val secondClassFull: Double? = null,
    @SerialName("secondClassHalfFare") val secondClassHalfFare: Double? = null,
    @SerialName("firstClassFull") val firstClassFull: Double? = null,
    @SerialName("firstClassHalfFare") val firstClassHalfFare: Double? = null,
)

@Serializable
data class Occupancy(val firstClass: String? = null, val secondClass: String? = null)

@Serializable
data class Leg(
    val mode: String? = null,
    val origin: String? = null,
    val destination: String? = null,
    val departure: String? = null,
    val arrival: String? = null,
    val expectedDeparture: String? = null,
    val departureDelayMinutes: Double? = null,
    val expectedArrival: String? = null,
    val arrivalDelayMinutes: Double? = null,
    val serviceLabel: String? = null,
    val departurePlatform: String? = null,
    val arrivalPlatform: String? = null,
    val occupancy: Occupancy? = null,
    val estimatedDepartureDelayMinutes: Double? = null,
    val estimatedArrivalDelayMinutes: Double? = null,
    val estimatedSource: String? = null,
    val estimatedSamples: Int? = null,
    val projectedDeparture: String? = null,
    val projectedArrival: String? = null,
    val estimatedBasis: String? = null,
    val incidentExtraMinutes: Double? = null,
    val incidentCancellationRisk: Double? = null,
    val incidentSuppressedStopRisk: Double? = null,
    val incidentSamples: Int? = null,
    val incidentCount: Int? = null,
    // present only on newer backends; null-safe
    val transferMissRisk: Double? = null,
    val transferRiskBasis: String? = null,
)
