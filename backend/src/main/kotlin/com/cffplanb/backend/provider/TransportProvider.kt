package com.cffplanb.backend.provider

import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.domain.SearchRequest

sealed class ProviderResult {
    data class Success(val journeys: List<Journey>) : ProviderResult()
    data class Timeout(val message: String) : ProviderResult()
    data class RateLimited(val message: String) : ProviderResult()
    data class AuthError(val message: String) : ProviderResult()
    data class LocationResolutionError(val message: String) : ProviderResult()
    data class Failure(val message: String) : ProviderResult()
}

interface TransportProvider {
    suspend fun search(request: SearchRequest): ProviderResult

    suspend fun searchLocations(query: String): LocationsResult = LocationsResult.Success(emptyList())

    /** Departures at [stop] (the live board when [realtime], else the timetable) (free-text name) starting at [from]. Providers without realtime data report Failure. */
    suspend fun stopEvents(stop: String, from: java.time.OffsetDateTime, realtime: Boolean = true): StopEventsResult = StopEventsResult.Failure("stop events not supported")
}

data class StopEvent(
    val label: String?, val lineRef: String?,
    val scheduled: java.time.OffsetDateTime, val estimated: java.time.OffsetDateTime?,
    val cancelled: Boolean, val platform: String?,
    /** Normalised names of the stops this trip calls at after this one (empty unless the provider supplied onward calls). */
    val onward: Set<String> = emptySet(),
    /** Stop point ref at this stop ("ch:1:sloid:1120:0:840061") and the timetabled departure at the trip's first stop; used to match GTFS-RT. */
    val stopRef: String? = null,
    val originDeparture: java.time.OffsetDateTime? = null
)

sealed class StopEventsResult {
    data class Success(val events: List<StopEvent>) : StopEventsResult()
    data class Failure(val message: String) : StopEventsResult()
}

data class LocationSuggestion(val id: String, val name: String, val kind: String, val x: Double?, val y: Double?)

sealed class LocationsResult {
    data class Success(val locations: List<LocationSuggestion>) : LocationsResult()
    data class Timeout(val message: String) : LocationsResult()
    data class RateLimited(val message: String) : LocationsResult()
    data class Failure(val message: String) : LocationsResult()
}
