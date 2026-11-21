package com.cffplanb.backend.service

import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.domain.SearchRequest
import com.cffplanb.backend.provider.ProviderResult
import com.cffplanb.backend.provider.TransportProvider

sealed class SearchResult {
    data class Success(val journeys: List<Journey>) : SearchResult()
    data class Timeout(val message: String) : SearchResult()
    data class RateLimited(val message: String) : SearchResult()
    data class AuthError(val message: String) : SearchResult()
    data class LocationResolutionError(val message: String) : SearchResult()
    data class Failure(val message: String) : SearchResult()
}

class JourneySearchService(private val provider: TransportProvider) {

    suspend fun searchLocations(query: String) = provider.searchLocations(query)

    suspend fun search(request: SearchRequest): SearchResult {
        return when (val providerResult = provider.search(request)) {
            is ProviderResult.Success -> {
                val ranked = RecommendationService.rank(providerResult.journeys, request.earliestDeparture)
                SearchResult.Success(ranked)
            }
            is ProviderResult.Timeout -> SearchResult.Timeout(providerResult.message)
            is ProviderResult.RateLimited -> SearchResult.RateLimited(providerResult.message)
            is ProviderResult.AuthError -> SearchResult.AuthError(providerResult.message)
            is ProviderResult.LocationResolutionError -> SearchResult.LocationResolutionError(providerResult.message)
            is ProviderResult.Failure -> SearchResult.Failure(providerResult.message)
        }
    }
}
