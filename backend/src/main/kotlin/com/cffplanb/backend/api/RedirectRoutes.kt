package com.cffplanb.backend.api

import com.cffplanb.backend.domain.Discount
import com.cffplanb.backend.domain.SearchRequest
import com.cffplanb.backend.provider.FareQuote
import com.cffplanb.backend.service.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

private val KNOWN = setOf("origin", "destination", "departure", "arrivalDeadline", "preference", "fareClass", "discount", "limit", "includeText")
private val jsonOut = Json { encodeDefaults = true }

/** GET /api/redirect: same inputs and validation as /api/journeys, answers "what is broken, and where should I go instead". */
fun Route.redirectRoute(searchService: JourneySearchService, clock: Clock, risk: RiskEstimator, gtfs: com.cffplanb.backend.gtfs.GtfsRealtimeSource? = null, delay: RealtimeDelayModel = RealtimeDelayModel.NONE) {
    val snap: (suspend () -> com.cffplanb.backend.gtfs.GtfsSnapshot?)? = gtfs?.let { g -> { g.current() } }
    val redirect = RedirectService(searchService, DisruptionDetector(searchService::stopEvents, gtfs = snap), clock, delay, snap)

    get("/api/redirect") {
        val q = call.request.queryParameters
        val keys = call.request.local.uri.substringAfter("?", "").split("&").filter { it.isNotEmpty() }
            .map { java.net.URLDecoder.decode(it.substringBefore("="), "UTF-8") }.filter { it.isNotEmpty() }
        fun place(n: String): String? = q[n]?.trim()
        fun ts(n: String): OffsetDateTime? = q[n]?.let { try { OffsetDateTime.parse(it, RFC3339_FORMATTER) } catch (e: DateTimeParseException) { null } }
        val origin = place("origin"); val destination = place("destination")
        val departure = ts("departure"); val deadline = ts("arrivalDeadline")
        val pref = q["preference"]?.let { v -> Preference.entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) } } ?: Preference.FASTEST.takeIf { q["preference"] == null }
        val fareClass = q["fareClass"]?.let { it.trim().takeIf { v -> v == "1" || v == "2" }?.toInt() } ?: 2.takeIf { q["fareClass"] == null }
        val discount = q["discount"]?.let { v -> Discount.entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) } } ?: Discount.NONE.takeIf { q["discount"] == null }

        val limit = parseLimit(q["limit"]); val includeText = parseIncludeText(q["includeText"])
        val err = keys.firstOrNull { k -> keys.count { it == k } > 1 }?.let { "Duplicate parameter: $it" }
            ?: keys.firstOrNull { it !in KNOWN }?.let { "Unknown parameter: $it" }
            ?: listOf("origin" to origin, "destination" to destination).firstNotNullOfOrNull { (n, v) ->
                when {
                    v == null -> "Missing required parameter: $n"
                    v.isEmpty() -> "Parameter '$n' must not be blank"
                    v.codePointCount(0, v.length) > 120 -> "Parameter '$n' exceeds 120 code points"
                    else -> null
                }
            }
            ?: when {
                q["departure"] != null && departure == null -> "Invalid departure timestamp. Must be RFC 3339 with Z or numeric UTC offset."
                q["arrivalDeadline"] != null && deadline == null -> "Invalid arrivalDeadline timestamp. Must be RFC 3339 with Z or numeric UTC offset."
                pref == null -> "Parameter 'preference' must be one of FASTEST, CHEAPEST, SIMPLEST"
                fareClass == null -> "Parameter 'fareClass' must be 1 or 2"
                discount == null -> "Parameter 'discount' must be one of NONE, HALF_FARE"
                limit == null -> "Parameter 'limit' must be an integer from 1 to 10"
                includeText == null -> "Parameter 'includeText' must be true or false"
                else -> null
            }
        if (err != null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", err)))
            return@get
        }

        val now = OffsetDateTime.now(clock)
        val earliest = departure ?: now
        val req = SearchRequest(origin!!, destination!!, earliest, fareClass!!, discount!!)
        val result = searchService.search(req)
        if (result !is SearchResult.Success) {
            val (status, code, msg) = when (result) {
                is SearchResult.Timeout -> Triple(HttpStatusCode.GatewayTimeout, "UPSTREAM_TIMEOUT", result.message)
                is SearchResult.RateLimited -> Triple(HttpStatusCode.ServiceUnavailable, "UPSTREAM_RATE_LIMITED", result.message)
                is SearchResult.AuthError -> Triple(HttpStatusCode.BadGateway, "UPSTREAM_ERROR", result.message)
                is SearchResult.LocationResolutionError -> Triple(HttpStatusCode.BadGateway, "UPSTREAM_ERROR", result.message)
                is SearchResult.Failure -> Triple(HttpStatusCode.BadGateway, "UPSTREAM_ERROR", result.message)
                is SearchResult.Success -> error("unreachable")
            }
            call.respond(status, ErrorResponseDto(ErrorDetailDto(code, msg)))
            return@get
        }

        val plan = redirect.plan(req, pref!!, deadline, result)
        val price = { f: FareQuote -> f.price(fareClass, discount) }
        val fareOf = { j: com.cffplanb.backend.domain.Journey -> plan.fares?.get(j.id)?.let(price) }
        val shown = listOfNotNull(plan.recommendation) + plan.alternatives
        val (dtos, latest, advice) = rescue(shown, deadline, risk, plan.fares, price, delay)
        fun best(p: Preference) = shown.minWithOrNull(RecommendationService.comparator(p, fareOf))?.id
        val byId = dtos.associateBy { it.id }
        val fastest = shown.minWithOrNull(RecommendationService.comparator(Preference.FASTEST, fareOf) { j -> j.arrival.toInstant().plusSeconds(60L * (delay.forJourney(j)?.minutes ?: 0)) })
        val rec = dtos.firstOrNull()?.let { r ->
            JsonObject(((jsonOut.encodeToJsonElement(JourneyDto.serializer(), r)) as JsonObject) + ("why" to JsonPrimitive(plan.why)))
        }
        call.respond(HttpStatusCode.OK, textual(RedirectResponseDto.serializer(), RedirectResponseDto(
            request = SearchRequestDto(origin, destination, RFC3339_FORMATTER.format(earliest), deadline?.let { RFC3339_FORMATTER.format(it) }, pref.name, fareClass, discount.name),
            situation = plan.situation.name,
            disruption = plan.disruption?.let { d ->
                DisruptionDto(d.legIndex, d.leg.serviceLabel, d.leg.origin, d.leg.destination, RFC3339_FORMATTER.format(d.leg.departure),
                    d.estimatedDeparture?.let { RFC3339_FORMATTER.format(it) }, d.delayMinutes, d.cancelled, d.basis.name, d.confidence, d.cause)
            },
            recommendation = rec,
            alternatives = dtos.drop(1).take(3),
            journeys = dtos.take(limit!!),
            message = plan.message,
            latestFeasibleDeparture = latest,
            noFeasibleRoute = deadline?.let { latest == null },
            advice = advice,
            recommendations = RecommendationsDto(fastest?.let { byId[it.id] }, best(Preference.SIMPLEST)?.let { byId[it] }),
            recommendationIds = RecommendationIdsDto(best(Preference.FASTEST), best(Preference.CHEAPEST), best(Preference.SIMPLEST)),
            computedAt = RFC3339_FORMATTER.format(now),
            modelVersion = risk.info().modelVersion
        ), includeText!!))
    }
}
