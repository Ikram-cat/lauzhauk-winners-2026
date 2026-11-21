package com.cffplanb.backend.api

import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.domain.JourneyLeg
import com.cffplanb.backend.domain.SearchRequest
import com.cffplanb.backend.service.*
import com.cffplanb.backend.service.SearchResult
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val KNOWN_PARAMS = setOf("origin", "destination", "departure")
private val RFC3339_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME

fun Application.configureRoutes(searchService: JourneySearchService, clock: Clock, risk: RiskEstimator = NoRiskEstimator) {
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, HealthResponseDto("ok"))
        }

        get("/api/model") {
            val i = risk.info()
            call.respond(HttpStatusCode.OK, ModelInfoDto(i.modelVersion, i.generatedAt, i.coverage, "artifact", i.keysLoaded, i.status))
        }

        get("/api/journeys") {
            // --- Duplicate parameter detection via raw query string ---
            val rawQuery = call.request.queryParameters
            val rawQueryString = call.request.local.uri.substringAfter("?", "")

            // Count occurrences of each param key in raw query
            val paramCounts = mutableMapOf<String, Int>()
            if (rawQueryString.isNotEmpty()) {
                rawQueryString.split("&").forEach { pair ->
                    val key = pair.substringBefore("=").let {
                        java.net.URLDecoder.decode(it, "UTF-8")
                    }
                    if (key.isNotEmpty()) {
                        paramCounts[key] = (paramCounts[key] ?: 0) + 1
                    }
                }
            }

            // Check for duplicates
            val duplicateKey = paramCounts.entries.firstOrNull { it.value > 1 }?.key
            if (duplicateKey != null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", "Duplicate parameter: $duplicateKey"))
                )
                return@get
            }

            // Check for unknown parameters
            val unknownKey = paramCounts.keys.firstOrNull { it !in KNOWN_PARAMS }
            if (unknownKey != null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", "Unknown parameter: $unknownKey"))
                )
                return@get
            }

            // Validate origin
            val originRaw = rawQuery["origin"]
            if (originRaw == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", "Missing required parameter: origin"))
                )
                return@get
            }
            val origin = originRaw.trim()
            if (origin.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", "Parameter 'origin' must not be blank"))
                )
                return@get
            }
            if (origin.codePointCount(0, origin.length) > 120) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", "Parameter 'origin' exceeds 120 code points"))
                )
                return@get
            }

            // Validate destination
            val destinationRaw = rawQuery["destination"]
            if (destinationRaw == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", "Missing required parameter: destination"))
                )
                return@get
            }
            val destination = destinationRaw.trim()
            if (destination.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", "Parameter 'destination' must not be blank"))
                )
                return@get
            }
            if (destination.codePointCount(0, destination.length) > 120) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", "Parameter 'destination' exceeds 120 code points"))
                )
                return@get
            }

            // Parse departure (optional, defaults to now)
            val earliestDeparture: OffsetDateTime
            val departureRaw = rawQuery["departure"]
            if (departureRaw != null) {
                earliestDeparture = try {
                    OffsetDateTime.parse(departureRaw, RFC3339_FORMATTER)
                } catch (e: DateTimeParseException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponseDto(
                            ErrorDetailDto(
                                "INVALID_REQUEST",
                                "Invalid departure timestamp. Must be RFC 3339 with Z or numeric UTC offset."
                            )
                        )
                    )
                    return@get
                }
            } else {
                earliestDeparture = OffsetDateTime.now(clock)
            }

            val searchRequest = SearchRequest(
                origin = origin,
                destination = destination,
                earliestDeparture = earliestDeparture
            )

            when (val result = searchService.search(searchRequest)) {
                is SearchResult.Success -> {
                    val response = JourneySearchResponseDto(
                        request = SearchRequestDto(
                            origin = origin,
                            destination = destination,
                            earliestDeparture = RFC3339_FORMATTER.format(earliestDeparture),
                            arrivalDeadline = null,
                            preference = "FASTEST"
                        ),
                        journeys = result.journeys.map { it.toDto(risk.estimate(it)) }
                    )
                    call.respond(HttpStatusCode.OK, response)
                }
                is SearchResult.Timeout -> {
                    call.respond(
                        HttpStatusCode.GatewayTimeout,
                        ErrorResponseDto(ErrorDetailDto("UPSTREAM_TIMEOUT", result.message))
                    )
                }
                is SearchResult.RateLimited -> {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponseDto(ErrorDetailDto("UPSTREAM_RATE_LIMITED", result.message))
                    )
                }
                is SearchResult.AuthError -> {
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ErrorResponseDto(ErrorDetailDto("UPSTREAM_ERROR", result.message))
                    )
                }
                is SearchResult.LocationResolutionError -> {
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ErrorResponseDto(ErrorDetailDto("UPSTREAM_ERROR", result.message))
                    )
                }
                is SearchResult.Failure -> {
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ErrorResponseDto(ErrorDetailDto("UPSTREAM_ERROR", result.message))
                    )
                }
            }
        }
    }
}

private fun Journey.toDto(r: RiskEstimate?): JourneyDto = JourneyDto(
    id = id,
    departure = RFC3339_FORMATTER.format(departure),
    arrival = RFC3339_FORMATTER.format(arrival),
    durationMinutes = durationMinutes,
    transfers = transfers,
    legs = legs.map { it.toDto() },
    predictedDelayMinutes = r?.predictedDelayMinutes,
    lateArrivalProbability = r?.lateArrivalProbability,
    estimatedFareChf = null,
    dataStatus = dataStatus.name
)

private fun JourneyLeg.toDto(): JourneyLegDto = JourneyLegDto(
    mode = mode.name,
    origin = origin,
    destination = destination,
    departure = RFC3339_FORMATTER.format(departure),
    arrival = RFC3339_FORMATTER.format(arrival),
    serviceLabel = serviceLabel,
    departurePlatform = departurePlatform,
    arrivalPlatform = arrivalPlatform
)
