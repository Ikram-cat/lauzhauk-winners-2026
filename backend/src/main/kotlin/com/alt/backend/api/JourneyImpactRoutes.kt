package com.alt.backend.api

import com.alt.backend.domain.AdviceCode
import com.alt.backend.domain.SearchRequest
import com.alt.backend.perturbation.DemoPerturbation
import com.alt.backend.service.SearchResult
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import com.alt.backend.service.duration.DisruptionDurationEstimator
import com.alt.backend.service.impact.ImpactResult
import com.alt.backend.service.impact.JourneyImpactService
import com.alt.backend.service.impact.ServiceAlertGrouper
import com.alt.backend.service.impact.ServiceAlertMatcher
import com.alt.backend.service.impact.ServiceAlertsSource
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException

private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

/**
 * POST /api/journeys/impact (proposal): searches the journey, finds the active disruptions that touch
 * it, computes detourExtraMinutes from the best unaffected alternative, and returns the outlook.
 */
fun Route.journeyImpactRoutes(
    impact: JourneyImpactService,
    estimator: DisruptionDurationEstimator,
    clock: Clock,
    serviceAlerts: ServiceAlertsSource? = null,
    demoPerturbationEnabled: Boolean = true
) {
    post("/api/journeys/impact") {
        val request = try {
            call.receive<JourneyImpactRequestDto>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@post call.respond(HttpStatusCode.BadRequest,
                ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", "Malformed or incomplete JSON body")))
        }
        val origin = request.origin.trim()
        val destination = request.destination.trim()
        if (origin.isEmpty() || destination.isEmpty() || origin.length > 120 || destination.length > 120) {
            return@post call.respond(HttpStatusCode.BadRequest,
                ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", "origin and destination must be 1-120 characters")))
        }
        val (observedAt, departure) = try {
            val observed = request.observedAt?.let { OffsetDateTime.parse(it, ISO) } ?: OffsetDateTime.now(clock)
            observed to (request.departure?.let { OffsetDateTime.parse(it, ISO) } ?: observed)
        } catch (e: DateTimeParseException) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST",
                "Invalid timestamp. Must be RFC 3339 with Z or numeric UTC offset.")))
        }

        val demo = try { DemoPerturbation.parse(call.request.queryParameters["demoPerturbation"], demoPerturbationEnabled) }
            catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", e.message!!, "demoPerturbation")))
            }

        when (val result = withContext(demo ?: EmptyCoroutineContext) {
            impact.check(SearchRequest(origin, destination, departure), observedAt, request.journeyId)
        }) {
            is ImpactResult.SearchFailed -> {
                val (status, code) = when (result.search) {
                    is SearchResult.Timeout -> HttpStatusCode.GatewayTimeout to "UPSTREAM_TIMEOUT"
                    is SearchResult.RateLimited -> HttpStatusCode.ServiceUnavailable to "UPSTREAM_RATE_LIMITED"
                    else -> HttpStatusCode.BadGateway to "UPSTREAM_ERROR"
                }
                call.respond(status, ErrorResponseDto(ErrorDetailDto(code, "Journey search failed")))
            }
            is ImpactResult.Success -> {
                val mock = impact.dataStatus == "MOCK" || result.planned?.dataStatus?.name == "MOCK"
                val live = liveAlerts(serviceAlerts, result.planned, observedAt)
                call.respond(HttpStatusCode.OK, JourneyImpactResponseDto(
                    dataStatus = if (mock) "MOCK" else result.planned?.dataStatus?.name ?: impact.dataStatus,
                    observedAt = ISO.format(observedAt),
                    journey = result.planned?.toImpactDto(),
                    affected = result.impacts.isNotEmpty(),
                    liveAlerts = live,
                    demoPerturbation = demo?.let { DemoPerturbationDto(true, it.seed, it.applied) },
                    impacts = result.impacts.map { i ->
                        val d = i.match.disruption
                        JourneyDisruptionImpactDto(
                            incidentId = d.incidentId,
                            summaryEn = d.summaryEn,
                            matchedStations = i.match.stations.map { m ->
                                MatchedStationDto(m.legIndex, m.station.uic, m.station.sloid, m.station.name,
                                    m.station.roles, m.by)
                            },
                            matchedLines = i.match.lines,
                            detourExtraMinutes = i.detourExtraMinutes,
                            alternative = AlternativeDto(if (i.alternative != null) "FOUND" else "NONE_UNAFFECTED",
                                i.alternative?.toImpactDto()),
                            outlook = d.context?.let { buildOutlook(estimator, it, observedAt, i.detourExtraMinutes) }
                                ?: DisruptionOutlookResponseDto(
                                    status = "UNAVAILABLE", remainingMinutes = null, expectedEndAt = null,
                                    officialRemainingMinutes = null,
                                    advice = AdviceDto(AdviceCode.UNKNOWN.name, i.detourExtraMinutes), provenance = null)
                        )
                    }
                ))
            }
        }
    }
}

/** Live alerts touching the planned journey at observedAt; past observedAt (replays) normally matches none. */
private suspend fun liveAlerts(source: ServiceAlertsSource?, planned: com.alt.backend.domain.Journey?, observedAt: OffsetDateTime): LiveAlertsDto {
    if (source == null) return LiveAlertsDto.disabled()
    val snapshot = source.current() ?: return LiveAlertsDto(status = "UNAVAILABLE")
    val matches = planned?.let { ServiceAlertMatcher.match(it, snapshot, observedAt.toInstant()) } ?: emptyList()
    val iso = { t: java.time.Instant? -> t?.atOffset(observedAt.offset)?.let(ISO::format) }
    return LiveAlertsDto(
        status = "OK",
        fetchedAt = iso(snapshot.fetchedAt),
        feedTimestamp = iso(snapshot.feedTimestamp),
        alerts = ServiceAlertGrouper.group(matches).map { g ->
            val first = g.alerts.first()
            LiveAlertDto(
                ids = g.alerts.map { it.id },
                operators = g.operators,
                directions = g.directions,
                headerText = first.header,
                descriptionText = first.description.mapValues { it.value.take(1000) },
                cause = first.cause,
                effect = first.effect,
                activePeriods = g.alerts.flatMap { it.periods }.distinct().map { (s, e) -> ActivePeriodDto(iso(s), iso(e)) },
                matchedStations = g.stops.map { (leg, sloid) ->
                    LiveAlertStopDto(leg, sloid, sloid.substringAfterLast(":").toIntOrNull()?.let { 8500000 + it })
                }
            )
        }
    )
}
