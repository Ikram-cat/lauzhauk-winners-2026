package com.alt.backend.provider

import com.alt.backend.domain.Journey
import com.alt.backend.domain.SearchRequest

sealed class ProviderResult {
    data class Success(val journeys: List<Journey>) : ProviderResult()
    data class Timeout(val message: String) : ProviderResult()
    data class RateLimited(val message: String) : ProviderResult()
    data class AuthError(val message: String) : ProviderResult()
    data class LocationResolutionError(val message: String, val field: String? = null) : ProviderResult()
    data class Failure(val message: String) : ProviderResult()
}

interface TransportProvider {
    suspend fun search(request: SearchRequest): ProviderResult

    suspend fun searchLocations(query: String): LocationsResult = LocationsResult.Success(emptyList())

    /** Stops near a WGS84 point, unsorted and unfiltered by distance; the caller applies radius, order and limit. */
    suspend fun searchNearby(lat: Double, lon: Double): LocationsResult = LocationsResult.Success(emptyList())

    /** Resolves a stop id, "lat,lon" or free text to what [search] should be given; providers that need no resolution echo the input. */
    suspend fun resolvePlace(input: String): PlaceResult = PlaceResult.Found(ResolvedPlace(input, null, null, input))

    /** OK | DEGRADED | DOWN | DISABLED from configuration and the last known outcome; never makes a network call. */
    fun feedStatus(): String = "OK"

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
    data class Success(val events: List<StopEvent>, val situations: List<PtSituation> = emptyList()) : StopEventsResult()
    data class Failure(val message: String) : StopEventsResult()
}

/** A SIRI PtSituation carried by a provider response: [lineRefs]/[stopRefs] name what it affects; [cause]/[severity] are structured tokens, never prose. */
data class PtSituation(
    val number: String, val cause: String?, val severity: String?, val summary: String?,
    val lineRefs: List<String> = emptyList(), val stopRefs: List<String> = emptyList(),
    val validFrom: java.time.OffsetDateTime? = null, val validUntil: java.time.OffsetDateTime? = null
)

/** [query] is what to pass to [TransportProvider.search]; [id]/[name] are null when the provider cannot say (coordinates, mock). */
data class ResolvedPlace(val input: String, val id: String?, val name: String?, val query: String)

sealed class PlaceResult {
    data class Found(val place: ResolvedPlace) : PlaceResult()
    object NotFound : PlaceResult()
    data class Timeout(val message: String) : PlaceResult()
    data class RateLimited(val message: String) : PlaceResult()
    data class Failure(val message: String) : PlaceResult()
}

data class LocationSuggestion(val id: String, val name: String, val kind: String, val x: Double?, val y: Double?)

sealed class LocationsResult {
    data class Success(val locations: List<LocationSuggestion>) : LocationsResult()
    data class Timeout(val message: String) : LocationsResult()
    data class RateLimited(val message: String) : LocationsResult()
    data class Failure(val message: String) : LocationsResult()
}
