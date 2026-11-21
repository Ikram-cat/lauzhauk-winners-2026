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
}
