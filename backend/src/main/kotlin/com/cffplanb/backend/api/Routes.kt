package com.cffplanb.backend.api

import com.cffplanb.backend.domain.Discount
import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.domain.LegMode
import com.cffplanb.backend.domain.SearchRequest
import com.cffplanb.backend.gtfs.GtfsRealtimeSource
import com.cffplanb.backend.provider.FareQuote
import com.cffplanb.backend.provider.LocationsResult
import com.cffplanb.backend.provider.PlaceResult
import com.cffplanb.backend.provider.ResolvedPlace
import com.cffplanb.backend.service.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

internal val RFC3339_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME
private val JOURNEY_PARAMS = setOf("origin", "destination", "departure", "arrivalDeadline", "preference", "fareClass", "discount", "limit")
private val LOCATION_PARAMS = setOf("query", "limit", "near", "radiusKm")
private val DECIMAL = Regex("[+-]?\\d+(\\.\\d+)?")

private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val a = Math.sin(Math.toRadians(lat2 - lat1) / 2).let { it * it } +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(Math.toRadians(lon2 - lon1) / 2).let { it * it }
    return 12742 * Math.asin(Math.sqrt(a))
}

/** A 400: [field] is the offending query parameter. */
private class ParamError(val field: String, message: String) : Exception(message)

private suspend fun ApplicationCall.fail(status: HttpStatusCode, code: String, message: String, field: String? = null) =
    respond(status, ErrorResponseDto(ErrorDetailDto(code, message, field)))

/** Unknown parameters are ignored, but a repeated known parameter is ambiguous. */
private fun ApplicationCall.rejectDuplicates(known: Set<String>) {
    val keys = request.local.uri.substringAfter("?", "").split("&").filter { it.isNotEmpty() }
        .map { java.net.URLDecoder.decode(it.substringBefore("="), "UTF-8") }
    keys.firstOrNull { it in known && keys.count { k -> k == it } > 1 }?.let { throw ParamError(it, "Duplicate parameter: $it") }
}

private fun Parameters.text(name: String): String {
    val v = this[name]?.trim() ?: throw ParamError(name, "Missing required parameter: $name")
    if (v.isEmpty()) throw ParamError(name, "Parameter '$name' must not be blank")
    if (v.codePointCount(0, v.length) > 120) throw ParamError(name, "Parameter '$name' exceeds 120 code points")
    return v
}

private fun Parameters.timestamp(name: String): OffsetDateTime? = this[name]?.let {
    try { OffsetDateTime.parse(it, RFC3339_FORMATTER) } catch (e: DateTimeParseException) {
        throw ParamError(name, "Invalid $name timestamp. Must be RFC 3339 with Z or numeric UTC offset.")
    }
}

private fun Parameters.limit(max: Int, default: Int): Int =
    this["limit"]?.let { v -> v.trim().toIntOrNull()?.takeIf { it in 1..max } ?: throw ParamError("limit", "Parameter 'limit' must be an integer from 1 to $max") } ?: default

private fun Parameters.ranked(): Preference = this["preference"]?.let { v ->
    Preference.entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) } ?: throw ParamError("preference", "Parameter 'preference' must be one of FASTEST, CHEAPEST, SIMPLEST")
} ?: Preference.FASTEST

fun Application.configureRoutes(searchService: JourneySearchService, clock: Clock, risk: RiskEstimator = NoRiskEstimator, gtfs: GtfsRealtimeSource? = null, delay: RealtimeDelayModel = RealtimeDelayModel.NONE) {
    val snap: (suspend () -> com.cffplanb.backend.gtfs.GtfsSnapshot?)? = gtfs?.let { g -> { g.current() } }
    val planner = JourneyPlanService(searchService, DisruptionDetector(searchService::stopEvents, gtfs = snap), clock, delay)

    routing {
        get("/health") {
            val g = gtfs?.peek()
            val rt = GtfsRtHealthDto(gtfs != null, gtfs?.health() ?: "DISABLED", gtfs?.ageSeconds(), g?.trips?.size, g?.cancelled)
            val feeds = FeedsDto(searchService.ojpStatus(), searchService.novaStatus(), rt)
            val ok = listOf(feeds.ojp, feeds.nova, rt.status).all { it == "OK" || it == "DEGRADED" || it == "DISABLED" }
            call.respond(HttpStatusCode.OK, HealthResponseDto(if (ok) "OK" else "DEGRADED", feeds))
        }

        get("/api/model") {
            val i = risk.info(); val d = delay.info()
            val cov = i.coverage as? JsonObject
            fun str(k: String) = (cov?.get(k) as? JsonPrimitive)?.contentOrNull
            fun day(k: String) = str(k)?.substringBefore('T')
            val window = try { ChronoUnit.DAYS.between(LocalDate.parse(day("from")), LocalDate.parse(day("to"))).toInt() + 1 } catch (e: Exception) { null }
            call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
            call.respond(HttpStatusCode.OK, ModelResponseDto(
                RiskModelDto(i.modelVersion, i.generatedAt, if (risk === NoRiskEstimator) "NONE" else "ARTIFACT", i.status.uppercase(),
                    cov?.let { CoverageDto(day("from"), day("to"), window, str("scope")) },
                    RiskCountsDto(i.keysLoaded, i.corridorsLoaded, i.baselineLoaded, i.byLineHourCells)),
                DelayModelDto(d.modelVersion, d.status.uppercase(), DelayCountsDto(d.portions, d.lines))))
        }

        get("/api/locations") {
            val q = call.request.queryParameters
            val query: String?; val limit: Int; var near: Pair<Double, Double>? = null; var radiusKm = 2.0
            try {
                call.rejectDuplicates(LOCATION_PARAMS)
                val nearRaw = q["near"]
                if (nearRaw == null) { query = q.text("query") } else {
                    val parts = nearRaw.split(",")
                    if (parts.size != 2 || !parts.all { DECIMAL.matches(it) }) throw ParamError("near", "Parameter 'near' must be <lat>,<lon> in decimal degrees")
                    val lat = parts[0].toDouble(); val lon = parts[1].toDouble()
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw ParamError("near", "Parameter 'near' latitude must be within ±90 and longitude within ±180")
                    near = lat to lon
                    query = if (q["query"] != null) q.text("query") else null
                    q["radiusKm"]?.let { v ->
                        if (!DECIMAL.matches(v) || v.startsWith("-")) throw ParamError("radiusKm", "Parameter 'radiusKm' must be a non-negative number")
                        radiusKm = minOf(v.toDouble(), 20.0)
                    }
                }
                limit = q.limit(20, 8)
            } catch (e: ParamError) { call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", e.message!!, e.field); return@get }
            val result = near?.let { (lat, lon) ->
                when (val n = searchService.searchNearby(lat, lon)) {
                    is LocationsResult.Success -> LocationsResult.Success(n.locations
                        .filter { it.kind.equals("stop", true) && it.x != null && it.y != null && (query == null || it.name.contains(query, ignoreCase = true)) }
                        .map { it to distanceKm(lat, lon, it.y!!, it.x!!) }
                        .filter { it.second <= radiusKm }.sortedBy { it.second }.map { it.first }.distinctBy { it.id }) // OJP lists each platform of a station separately
                    else -> n
                }
            } ?: searchService.searchLocations(query!!)
            when (val r = result) {
                is LocationsResult.Success -> call.respond(HttpStatusCode.OK, LocationsResponseDto(r.locations.take(limit).map {
                    LocationDto(it.id, it.name, it.kind.uppercase(), if (it.x != null && it.y != null) CoordinatesDto(it.y, it.x) else null)
                }))
                is LocationsResult.Timeout -> call.fail(HttpStatusCode.GatewayTimeout, "UPSTREAM_TIMEOUT", r.message)
                is LocationsResult.RateLimited -> call.fail(HttpStatusCode.ServiceUnavailable, "UPSTREAM_RATE_LIMITED", r.message)
                is LocationsResult.Failure -> call.fail(HttpStatusCode.BadGateway, "UPSTREAM_ERROR", r.message)
            }
        }

        get("/api/journeys") {
            val q = call.request.queryParameters
            val origin: String; val destination: String; val departure: OffsetDateTime; val deadline: OffsetDateTime?
            val pref: Preference; val fareClass: Int; val discount: Discount; val limit: Int
            try {
                call.rejectDuplicates(JOURNEY_PARAMS)
                origin = q.text("origin"); destination = q.text("destination")
                departure = q.timestamp("departure") ?: OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)
                deadline = q.timestamp("arrivalDeadline")
                pref = q.ranked()
                fareClass = q["fareClass"]?.let { v -> v.trim().takeIf { it == "1" || it == "2" }?.toInt() ?: throw ParamError("fareClass", "Parameter 'fareClass' must be 1 or 2") } ?: 2
                discount = q["discount"]?.let { v -> Discount.entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) } ?: throw ParamError("discount", "Parameter 'discount' must be one of NONE, HALF_FARE") } ?: Discount.NONE
                limit = q.limit(10, 3)
            } catch (e: ParamError) { call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", e.message!!, e.field); return@get }

            val (from, to) = coroutineScope {
                val o = async { searchService.resolvePlace(origin) }; val d = async { searchService.resolvePlace(destination) }
                o.await() to d.await()
            }
            for ((field, r) in listOf("origin" to from, "destination" to to)) when (r) {
                is PlaceResult.Found -> {}
                is PlaceResult.NotFound -> return@get call.fail(HttpStatusCode.NotFound, "LOCATION_NOT_FOUND", "Location not found", field)
                is PlaceResult.Timeout -> return@get call.fail(HttpStatusCode.GatewayTimeout, "UPSTREAM_TIMEOUT", r.message)
                is PlaceResult.RateLimited -> return@get call.fail(HttpStatusCode.ServiceUnavailable, "UPSTREAM_RATE_LIMITED", r.message)
                is PlaceResult.Failure -> return@get call.fail(HttpStatusCode.BadGateway, "UPSTREAM_ERROR", r.message)
            }
            val o = (from as PlaceResult.Found).place; val d = (to as PlaceResult.Found).place

            val req = SearchRequest(o.query, d.query, departure, fareClass, discount)
            val result = when (val r = searchService.search(req)) {
                is SearchResult.Success -> r
                is SearchResult.Timeout -> return@get call.fail(HttpStatusCode.GatewayTimeout, "UPSTREAM_TIMEOUT", r.message)
                is SearchResult.RateLimited -> return@get call.fail(HttpStatusCode.ServiceUnavailable, "UPSTREAM_RATE_LIMITED", r.message)
                is SearchResult.LocationResolutionError -> return@get call.fail(HttpStatusCode.NotFound, "LOCATION_NOT_FOUND", "Location not found", r.field ?: "origin")
                is SearchResult.AuthError -> return@get call.fail(HttpStatusCode.BadGateway, "UPSTREAM_ERROR", r.message)
                is SearchResult.Failure -> return@get call.fail(HttpStatusCode.BadGateway, "UPSTREAM_ERROR", r.message)
            }
            val plan = planner.plan(req, pref, deadline, result)
            call.respond(HttpStatusCode.OK, buildResponse(plan, o, d, departure, deadline, pref, fareClass, discount, limit, risk, clock))
        }
    }
}

private fun place(p: ResolvedPlace) = PlaceEchoDto(p.input, p.name, p.id)

private fun buildResponse(
    plan: JourneyPlan, o: ResolvedPlace, d: ResolvedPlace, departure: OffsetDateTime, deadline: OffsetDateTime?, pref: Preference,
    fareClass: Int, discount: Discount, limit: Int, risk: RiskEstimator, clock: Clock
): JourneysResponseDto {
    val info = risk.info()
    val windowDays = ((info.coverage as? JsonObject)?.let { c ->
        try { ChronoUnit.DAYS.between(LocalDate.parse((c["from"] as JsonPrimitive).content.substringBefore('T')), LocalDate.parse((c["to"] as JsonPrimitive).content.substringBefore('T'))).toInt() + 1 } catch (e: Exception) { null }
    })
    val price = { q: FareQuote -> q.price(fareClass, discount) }
    fun fareOf(j: Journey) = plan.fares?.get(j.id)?.let(price)
    fun dto(j: Journey?) = j?.let { journeyDto(it, plan, deadline, fareClass, discount, risk.estimate(it), windowDays) }

    val alternatives = plan.alternatives.take(limit)
    val shown = (listOfNotNull(plan.primary, plan.recommended) + alternatives).distinctBy { it.id }
    fun arrival(j: Journey) = (plan.realtime[j.id]?.expectedArrival ?: j.arrival).toInstant()
    val highlights = HighlightsDto(
        fastestId = shown.minWithOrNull(RecommendationService.comparator(Preference.FASTEST, ::fareOf, ::arrival))?.id,
        simplestId = shown.minWithOrNull(RecommendationService.comparator(Preference.SIMPLEST, ::fareOf, ::arrival))?.id,
        cheapestId = shown.filter { fareOf(it) != null }.minWithOrNull(RecommendationService.comparator(Preference.CHEAPEST, ::fareOf, ::arrival))?.id)
    val summary = deadline?.let { DeadlineSummaryDto(plan.latestFeasibleDeparture != null, plan.latestFeasibleDeparture?.let { RFC3339_FORMATTER.format(it) }) }
    return JourneysResponseDto(
        request = RequestDto(place(o), place(d), RFC3339_FORMATTER.format(departure), deadline?.let { RFC3339_FORMATTER.format(it) }, pref.name, fareClass, discount.name),
        status = plan.status.name, action = plan.action.name,
        reasons = JsonArray(plan.reasons.map { r ->
            buildJsonObject {
                put("code", r.code)
                r.serviceLabel?.let { put("serviceLabel", it) }; r.delayMinutes?.let { put("delayMinutes", it) }
                r.savedMinutes?.let { put("savedMinutes", it) }; r.savedTransfers?.let { put("savedTransfers", it) }
                r.savedChf?.let { put("savedChf", it) }; r.marginMinutes?.let { put("marginMinutes", it) }
            }
        }),
        disruption = plan.disruption?.let { x ->
            DisruptionDto(x.legIndex, x.leg.serviceLabel, x.leg.origin, x.leg.destination, RFC3339_FORMATTER.format(x.leg.departure),
                x.estimatedDeparture?.let { RFC3339_FORMATTER.format(it) }, x.delayMinutes, x.cancelled, x.basis.name, x.confidence.uppercase(), x.cause)
        },
        primaryJourney = dto(plan.primary), recommendedJourney = dto(plan.recommended), alternatives = alternatives.map { dto(it)!! },
        highlights = highlights, deadlineSummary = summary,
        meta = MetaDto(RFC3339_FORMATTER.format(OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)), info.modelVersion))
}

private fun journeyDto(j: Journey, plan: JourneyPlan, deadline: OffsetDateTime?, fareClass: Int, discount: Discount, r: RiskEstimate?, windowDays: Int?): JourneyDto {
    val rt = plan.realtime.getValue(j.id)
    fun f(t: OffsetDateTime?) = t?.let { RFC3339_FORMATTER.format(it) }
    val fare = plan.fares?.get(j.id)
    val selected = fare?.price(fareClass, discount)
    val rank = listOf("LOW", "MEDIUM", "HIGH")
    val legs = j.legs.mapIndexed { i, l ->
        val x = rt.legs[i]
        LegDto(l.mode.name, l.origin, l.destination, RFC3339_FORMATTER.format(l.departure), RFC3339_FORMATTER.format(l.arrival),
            f(x.expectedDeparture), x.departureDelayMinutes, f(x.expectedArrival), x.arrivalDelayMinutes,
            l.serviceLabel, l.departurePlatform, l.arrivalPlatform,
            if (l.mode == LegMode.TRANSIT) OccupancyDto(l.occupancyFirst, l.occupancySecond) else null)
    }
    val occupancy = j.legs.filter { it.mode == LegMode.TRANSIT }.mapNotNull { if (fareClass == 1) it.occupancyFirst else it.occupancySecond }.maxByOrNull { rank.indexOf(it) }
    val rate = r?.lateArrivalProbability
    return JourneyDto(
        id = j.id, dataStatus = j.dataStatus.name,
        feasible = deadline?.let { !(rt.expectedArrival ?: j.arrival).toInstant().isAfter(it.toInstant()) },
        maxOccupancy = occupancy,
        schedule = ScheduleDto(RFC3339_FORMATTER.format(j.departure), RFC3339_FORMATTER.format(j.arrival), j.durationMinutes, j.transfers),
        realtime = RealtimeDto(f(rt.expectedDeparture), rt.departureDelayMinutes, f(rt.expectedArrival), rt.arrivalDelayMinutes, rt.source, rt.sampleCount),
        risk = RiskDto(rate, r?.disruptionDurationMin, if (r == null) "NONE" else if (r.source == "corridor") "CORRIDOR" else "BASELINE",
            r?.basis?.let { if (r.source == "corridor") windowDays?.let { "DAY_WINDOW_$it" } ?: "DAY_WINDOW" else "HOURLY_BASELINE" },
            r?.confidence?.uppercase(), rate?.let { if (it < 0.02) "LOW" else if (it < 0.06) "MEDIUM" else "HIGH" }),
        fares = FaresDto(if (plan.fares == null) "DISABLED" else if (selected != null) "PRICED" else "UNAVAILABLE", selected, fare?.product, fare?.source,
            fare?.let { CatalogFaresDto(it.full2nd, it.half2nd, it.full1st, it.half1st) }),
        legs = legs)
}
