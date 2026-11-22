package com.alt.backend.perturbation

import com.alt.backend.domain.SearchRequest
import com.alt.backend.provider.LocationsResult
import com.alt.backend.provider.PlaceResult
import com.alt.backend.provider.ProviderResult
import com.alt.backend.provider.StopEventsResult
import com.alt.backend.provider.TransportProvider
import kotlinx.coroutines.currentCoroutineContext

/**
 * DEMO-ONLY transport decorator. Sits outside the resilient/caching provider so the delegate result stays pristine
 * and every request draws its own perturbations. Outside a [DemoPerturbationState] context it is a pure pass-through:
 * search results, boards and status are exactly the delegate's.
 */
class PerturbationProvider(private val delegate: TransportProvider) : TransportProvider {

    private suspend fun state(): DemoPerturbationState? = currentCoroutineContext()[DemoPerturbationState]

    override fun feedStatus(): String = delegate.feedStatus()

    override suspend fun search(request: SearchRequest): ProviderResult {
        val s = state() ?: return delegate.search(request)
        return when (val r = delegate.search(request)) {
            is ProviderResult.Success -> ProviderResult.Success(s.applyToSearch(r.journeys))
            else -> r
        }
    }

    override suspend fun searchLocations(query: String): LocationsResult = delegate.searchLocations(query)

    override suspend fun searchNearby(lat: Double, lon: Double): LocationsResult = delegate.searchNearby(lat, lon)

    override suspend fun resolvePlace(input: String): PlaceResult = delegate.resolvePlace(input)

    override suspend fun stopEvents(stop: String, from: java.time.OffsetDateTime, realtime: Boolean): StopEventsResult {
        val s = state() ?: return delegate.stopEvents(stop, from, realtime)
        return s.applyToBoard(stop, realtime, delegate.stopEvents(stop, from, realtime))
    }
}
