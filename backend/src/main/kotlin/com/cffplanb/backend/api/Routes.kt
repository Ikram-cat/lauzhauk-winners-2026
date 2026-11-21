package com.cffplanb.backend.api

import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.domain.JourneyLeg
import com.cffplanb.backend.domain.SearchRequest
import com.cffplanb.backend.provider.LocationsResult
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

private val KNOWN_PARAMS = setOf("origin", "destination", "departure", "arrivalDeadline")
private val ALT_PARAMS = setOf("from", "to", "notBefore", "arrivalDeadline")
private val RFC3339_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME

fun Application.configureRoutes(searchService: JourneySearchService, clock: Clock, risk: RiskEstimator = NoRiskEstimator) {
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, HealthResponseDto("ok"))
        }

        get("/api/model") {
            val i = risk.info()
            call.respond(HttpStatusCode.OK, ModelInfoDto(i.modelVersion, i.generatedAt, i.coverage, "artifact", i.keysLoaded, i.status,
                i.corridorsLoaded, i.baselineLoaded, i.byLineHourCells, i.corridorSample))
        }

        get("/api/locations") {
            val params = call.request.queryParameters
            val bad = params.names().firstOrNull { it != "query" }?.let { "Unknown parameter: $it" }
                ?: if ((params.getAll("query")?.size ?: 0) > 1) "Duplicate parameter: query" else null
            val q = params["query"]?.trim()
            val err = bad ?: when {
                q == null -> "Missing required parameter: query"
                q.isEmpty() -> "Parameter 'query' must not be blank"
                q.codePointCount(0, q.length) > 120 -> "Parameter 'query' exceeds 120 code points"
                else -> null
            }
            if (err != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", err)))
                return@get
            }
            when (val r = searchService.searchLocations(q!!)) {
                is LocationsResult.Success -> call.respond(
                    HttpStatusCode.OK,
                    LocationsResponseDto(r.locations.take(10).map { LocationDto(it.id, it.name, it.kind, it.x, it.y) })
                )
                is LocationsResult.Timeout -> call.respond(HttpStatusCode.GatewayTimeout, ErrorResponseDto(ErrorDetailDto("UPSTREAM_TIMEOUT", r.message)))
                is LocationsResult.RateLimited -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponseDto(ErrorDetailDto("UPSTREAM_RATE_LIMITED", r.message)))
                is LocationsResult.Failure -> call.respond(HttpStatusCode.BadGateway, ErrorResponseDto(ErrorDetailDto("UPSTREAM_ERROR", r.message)))
            }
        }

        get("/api/alternatives") {
            val q = call.request.queryParameters
            val keys = call.request.local.uri.substringAfter("?", "").split("&").filter { it.isNotEmpty() }
                .map { java.net.URLDecoder.decode(it.substringBefore("="), "UTF-8") }
            fun station(n: String): String? = q[n]?.trim()?.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= 120 }
            fun ts(n: String): OffsetDateTime? = q[n]?.let { try { OffsetDateTime.parse(it, RFC3339_FORMATTER) } catch (e: DateTimeParseException) { null } }
            val from = station("from"); val to = station("to"); val notBefore = ts("notBefore"); val deadline = ts("arrivalDeadline")
            val err = keys.firstOrNull { keys.count { k -> k == it } > 1 }?.let { "Duplicate parameter: $it" }
                ?: keys.firstOrNull { it !in ALT_PARAMS }?.let { "Unknown parameter: $it" }
                ?: listOf("from", "to", "notBefore").firstOrNull { q[it] == null }?.let { "Missing required parameter: $it" }
                ?: when {
                    from == null -> "Parameter 'from' must be 1-120 non-blank code points"
                    to == null -> "Parameter 'to' must be 1-120 non-blank code points"
                    notBefore == null -> "Invalid notBefore timestamp. Must be RFC 3339 with Z or numeric UTC offset."
                    q["arrivalDeadline"] != null && deadline == null -> "Invalid arrivalDeadline timestamp. Must be RFC 3339 with Z or numeric UTC offset."
                    else -> null
                }
            if (err != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", err)))
                return@get
            }
            when (val result = searchService.search(SearchRequest(from!!, to!!, notBefore!!))) {
                is SearchResult.Success -> {
                    val (dtos, latest, advice) = rescue(result.journeys, deadline, risk)
                    call.respond(HttpStatusCode.OK, AlternativesResponseDto(
                        AlternativesRequestDto(from, to, RFC3339_FORMATTER.format(notBefore), deadline?.let { RFC3339_FORMATTER.format(it) }),
                        dtos, latest, if (deadline != null) latest == null else dtos.isEmpty(), advice))
                }
                is SearchResult.Timeout -> call.respond(HttpStatusCode.GatewayTimeout, ErrorResponseDto(ErrorDetailDto("UPSTREAM_TIMEOUT", result.message)))
                is SearchResult.RateLimited -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponseDto(ErrorDetailDto("UPSTREAM_RATE_LIMITED", result.message)))
                is SearchResult.AuthError -> call.respond(HttpStatusCode.BadGateway, ErrorResponseDto(ErrorDetailDto("UPSTREAM_ERROR", result.message)))
                is SearchResult.LocationResolutionError -> call.respond(HttpStatusCode.BadGateway, ErrorResponseDto(ErrorDetailDto("UPSTREAM_ERROR", result.message)))
                is SearchResult.Failure -> call.respond(HttpStatusCode.BadGateway, ErrorResponseDto(ErrorDetailDto("UPSTREAM_ERROR", result.message)))
            }
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

            val deadlineRaw = rawQuery["arrivalDeadline"]
            val deadline: OffsetDateTime? = if (deadlineRaw == null) null else try {
                OffsetDateTime.parse(deadlineRaw, RFC3339_FORMATTER)
            } catch (e: DateTimeParseException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponseDto(
                        ErrorDetailDto(
                            "INVALID_REQUEST",
                            "Invalid arrivalDeadline timestamp. Must be RFC 3339 with Z or numeric UTC offset."
                        )
                    )
                )
                return@get
            }

            val searchRequest = SearchRequest(
                origin = origin,
                destination = destination,
                earliestDeparture = earliestDeparture
            )

            when (val result = searchService.search(searchRequest)) {
                is SearchResult.Success -> {
                    val (dtos, latestFeasible, advice) = rescue(result.journeys, deadline, risk)
                    val response = JourneySearchResponseDto(
                        request = SearchRequestDto(
                            origin = origin,
                            destination = destination,
                            earliestDeparture = RFC3339_FORMATTER.format(earliestDeparture),
                            arrivalDeadline = deadline?.let { RFC3339_FORMATTER.format(it) },
                            preference = "FASTEST"
                        ),
                        journeys = dtos,
                        latestFeasibleDeparture = latestFeasible,
                        noFeasibleRoute = deadline?.let { latestFeasible == null },
                        advice = advice
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

/** Shared by /api/journeys and /api/alternatives: DTOs with reasons, latestFeasibleDeparture, advice. */
private fun rescue(journeys: List<Journey>, deadline: OffsetDateTime?, risk: RiskEstimator): Triple<List<JourneyDto>, String?, String> {
    val risks = journeys.map { risk.estimate(it) }
    val latest = deadline?.let { d -> journeys.filter { !it.arrival.toInstant().isAfter(d.toInstant()) }.maxByOrNull { it.departure.toInstant() } }
        ?.let { RFC3339_FORMATTER.format(it.departure) }
    val dtos = journeys.mapIndexed { i, j ->
        j.toDto(risks[i], deadline?.let { !j.arrival.toInstant().isAfter(it.toInstant()) }, reasonsFor(j, journeys, risks[i], deadline))
    }
    return Triple(dtos, latest, adviceFor(journeys, risks, deadline))
}

private fun Journey.toDto(r: RiskEstimate?, feasible: Boolean?, reasons: List<String>): JourneyDto = JourneyDto(
    id = id,
    departure = RFC3339_FORMATTER.format(departure),
    arrival = RFC3339_FORMATTER.format(arrival),
    durationMinutes = durationMinutes,
    transfers = transfers,
    legs = legs.map { it.toDto() },
    predictedDelayMinutes = r?.predictedDelayMinutes,
    lateArrivalProbability = r?.lateArrivalProbability,
    estimatedFareChf = null,
    feasible = feasible,
    dataStatus = dataStatus.name,
    riskSource = r?.source,
    riskConfidence = r?.confidence,
    riskLabel = r?.label,
    disruptionDurationMin = r?.disruptionDurationMin,
    riskBasis = r?.basis,
    reasons = reasons
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
