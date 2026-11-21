package com.cffplanb.backend.service

import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.domain.SearchRequest
import com.cffplanb.backend.provider.FareQuote
import com.cffplanb.backend.provider.ProviderResult
import com.cffplanb.backend.provider.TransportProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

sealed class SearchResult {
    /** [fares] is keyed by journey id; null when fare lookup is disabled, missing ids mean UNAVAILABLE. */
    data class Success(val journeys: List<Journey>, val fares: Map<String, FareQuote>? = null) : SearchResult()
    data class Timeout(val message: String) : SearchResult()
    data class RateLimited(val message: String) : SearchResult()
    data class AuthError(val message: String) : SearchResult()
    data class LocationResolutionError(val message: String) : SearchResult()
    data class Failure(val message: String) : SearchResult()
}

class JourneySearchService(
    private val provider: TransportProvider,
    private val fares: FareCache? = null,
    private val fareBudgetMs: Long = 5_000
) {

    suspend fun searchLocations(query: String) = provider.searchLocations(query)

    suspend fun stopEvents(stop: String, from: java.time.OffsetDateTime, realtime: Boolean) = provider.stopEvents(stop, from, realtime)

    suspend fun search(request: SearchRequest): SearchResult {
        return when (val providerResult = provider.search(request)) {
            is ProviderResult.Success -> {
                val ranked = RecommendationService.rank(providerResult.journeys, request.earliestDeparture)
                SearchResult.Success(ranked, fares?.let { priceAll(it, ranked) })
            }
            is ProviderResult.Timeout -> SearchResult.Timeout(providerResult.message)
            is ProviderResult.RateLimited -> SearchResult.RateLimited(providerResult.message)
            is ProviderResult.AuthError -> SearchResult.AuthError(providerResult.message)
            is ProviderResult.LocationResolutionError -> SearchResult.LocationResolutionError(providerResult.message)
            is ProviderResult.Failure -> SearchResult.Failure(providerResult.message)
        }
    }

    /** One quote per distinct origin/destination pair, in parallel, each capped at the total budget. */
    private suspend fun priceAll(cache: FareCache, journeys: List<Journey>): Map<String, FareQuote> = coroutineScope {
        val byOd = journeys.filter { it.legs.isNotEmpty() }.groupBy { it.legs.first().origin to it.legs.last().destination }
        byOd.map { (od, js) ->
            async { withTimeoutOrNull(fareBudgetMs) { cache.quote(od.first, od.second, js.minOf { it.departure }) }?.let { q -> js.map { it.id to q } } }
        }.awaitAll().filterNotNull().flatten().toMap()
    }
}
