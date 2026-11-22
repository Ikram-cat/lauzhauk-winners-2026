package com.cffplanb.backend.service.impact

import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.domain.SearchRequest
import com.cffplanb.backend.service.JourneySearchService
import com.cffplanb.backend.service.SearchResult
import java.time.Duration
import java.time.OffsetDateTime

data class DisruptionImpact(
    val match: DisruptionMatch,
    /** Best unaffected alternative's arrival minus the planned arrival, >= 0; null without alternative. */
    val detourExtraMinutes: Int?,
    val alternative: Journey?
)

sealed class ImpactResult {
    data class Success(val planned: Journey?, val impacts: List<DisruptionImpact>) : ImpactResult()
    data class SearchFailed(val search: SearchResult) : ImpactResult()
}

/**
 * For the best journey of a search (or the given journeyId): which active disruptions touch it, and
 * how much later the earliest journey of the same search that avoids every active disruption arrives.
 */
class JourneyImpactService(private val searchService: JourneySearchService, private val source: DisruptionSource) {
    val dataStatus: String get() = source.dataStatus

    suspend fun check(request: SearchRequest, observedAt: OffsetDateTime, journeyId: String? = null): ImpactResult {
        val result = searchService.search(request)
        if (result !is SearchResult.Success) return ImpactResult.SearchFailed(result)
        val journeys = result.journeys
        val planned = journeyId?.let { id -> journeys.firstOrNull { it.id == id } } ?: journeys.firstOrNull()
            ?: return ImpactResult.Success(null, emptyList())
        val active = source.active(observedAt)
        val matches = active.mapNotNull { JourneyDisruptionMatcher.match(planned, it) }
        if (matches.isEmpty()) return ImpactResult.Success(planned, emptyList())
        val alternative = journeys.filter { it.id != planned.id && !JourneyDisruptionMatcher.isAffected(it, active) }
            .minByOrNull { it.arrival.toInstant() }
        val detour = alternative?.let { maxOf(0L, Duration.between(planned.arrival, it.arrival).toMinutes()).toInt() }
        return ImpactResult.Success(planned, matches.map { DisruptionImpact(it, detour, alternative) })
    }
}
