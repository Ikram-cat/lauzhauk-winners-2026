package com.cffplanb.backend.api

import com.cffplanb.backend.config.AppConfig
import com.cffplanb.backend.config.configureApp
import com.cffplanb.backend.domain.SearchRequest
import com.cffplanb.backend.gtfs.GtfsRealtimeSource
import com.cffplanb.backend.provider.*
import com.cffplanb.backend.service.FareCache
import com.cffplanb.backend.service.JourneySearchService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import com.cffplanb.backend.provider.LocationsResult as LR

internal fun JsonElement.obj() = jsonObject
internal fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
internal fun JsonObject.o(k: String) = this[k]!!.jsonObject
internal fun JsonObject.a(k: String) = this[k]!!.jsonArray
internal fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

class RouteTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { configureApp(fixedClock, AppConfig(transportProvider = "mock", fareBaseUrl = "")) }
        block()
    }

    private fun customProviderTest(p: TransportProvider, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(p), fixedClock)
        }
        block()
    }

    private suspend fun assertError(r: HttpResponse, status: HttpStatusCode, code: String, field: String?) {
        assertEquals(status, r.status)
        val e = parse(r.bodyAsText()).o("error")
        assertEquals(code, e.str("code"))
        assertTrue(e.str("message").isNotBlank())
        if (field == null) assertEquals(JsonNull, e["field"]) else assertEquals(field, e.str("field"))
    }

    // --- Journeys success ---

    @Test
    fun `GET api journeys returns the unified envelope`() = appTest {
        val r = client.get("/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&departure=2026-11-21T16%3A00%3A00%2B01%3A00")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(ContentType.Application.Json.withCharset(Charsets.UTF_8), r.contentType())
        val j = parse(r.bodyAsText())
        val req = j.o("request")
        assertEquals("Lausanne", req.o("origin").str("input"))
        assertEquals(JsonNull, req.o("origin")["id"]) // mock provider cannot resolve
        assertEquals("2026-11-21T16:00:00+01:00", req.str("departure"))
        assertEquals(JsonNull, req["arrivalDeadline"])
        assertEquals(listOf("FASTEST", "2", "NONE"), listOf(req.str("preference"), req.str("fareClass"), req.str("discount")))
        // mock has no realtime board: detection is UNKNOWN and must never pretend NO_DISRUPTION
        assertEquals("UNKNOWN", j.str("status")); assertEquals("STAY", j.str("action"))
        assertEquals("REALTIME_UNAVAILABLE", j.a("reasons")[0].obj().str("code"))
        assertEquals(j.o("primaryJourney"), j.o("recommendedJourney"))
        assertEquals(2, j.a("alternatives").size) // 3 journeys minus the recommended one
        assertEquals(JsonNull, j["deadlineSummary"]); assertEquals(JsonNull, j["disruption"])
        assertEquals("MOCK", j.o("primaryJourney").str("dataStatus"))
        assertEquals("NONE", j.o("primaryJourney").o("realtime").str("source"))
        for (k in listOf("expectedDeparture", "departureDelayMinutes", "expectedArrival", "arrivalDelayMinutes", "sampleCount")) assertEquals(JsonNull, j.o("primaryJourney").o("realtime")[k], k)
        assertEquals("DISABLED", j.o("primaryJourney").o("fares").str("status"))
        assertTrue(j.o("primaryJourney").o("risk").str("source") in setOf("CORRIDOR", "BASELINE", "NONE"))
        assertTrue(j.o("meta").str("computedAt").startsWith("2026-11-21"))
    }

    @Test
    fun `journey object carries every documented field`() = appTest {
        val j = parse(client.get("/api/journeys?origin=A&destination=B").bodyAsText()).o("primaryJourney")
        assertEquals(setOf("id", "dataStatus", "verified", "feasible", "maxOccupancy", "schedule", "realtime", "risk", "fares", "legs"), j.keys)
        assertEquals(setOf("departure", "arrival", "durationMinutes", "transfers"), j.o("schedule").keys)
        assertEquals(setOf("expectedDeparture", "departureDelayMinutes", "expectedArrival", "arrivalDelayMinutes", "source", "sampleCount"), j.o("realtime").keys)
        assertEquals(setOf("corridorIncidentRate", "typicalIncidentDurationMinutes", "source", "basis", "confidence", "level"), j.o("risk").keys)
        assertEquals(setOf("status", "selectedChf", "product", "source", "catalogChf"), j.o("fares").keys)
        assertEquals(setOf("mode", "origin", "destination", "departure", "arrival", "expectedDeparture", "departureDelayMinutes", "expectedArrival", "arrivalDelayMinutes",
            "serviceLabel", "departurePlatform", "arrivalPlatform", "occupancy"), j.a("legs")[0].obj().keys)
        assertEquals(JsonNull, j["feasible"]) // no deadline
    }

    @Test
    fun `response has no generated prose and no removed fields`() = appTest {
        val body = client.get("/api/journeys?origin=A&destination=B&arrivalDeadline=2026-11-21T17%3A00%3A00Z&limit=10").bodyAsText()
        for (k in listOf("advice", "message", "why", "includeText", "recommendationIds", "recommendations", "lateArrivalProbability", "disruptionDurationMin", "journeys"))
            assertFalse("\"$k\"" in body, "found $k")
        // every string value is an enum token, an id, a name, a number or a timestamp; none is a sentence
        fun walk(e: JsonElement) { when (e) {
            is JsonObject -> e.values.forEach(::walk); is JsonArray -> e.forEach(::walk)
            is JsonPrimitive -> if (e.isString) assertFalse(e.content.contains(". ") || e.content.split(' ').size > 6, "prose: ${e.content}")
        } }
        walk(Json.parseToJsonElement(body))
    }

    @Test
    fun `unknown query parameters are ignored`() = appTest {
        assertEquals(HttpStatusCode.OK, client.get("/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&foo=bar&utm_source=x&includeText=true").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/locations?query=laus&foo=bar").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/journeys?origin=A&destination=B&foo=1&foo=2").status) // repeated unknown keys are not ambiguous
    }

    @Test
    fun `parameters are case-insensitive enums and defaults apply`() = appTest {
        val req = parse(client.get("/api/journeys?origin=A&destination=B&preference=cheapest&discount=half_fare&fareClass=1").bodyAsText()).o("request")
        assertEquals("CHEAPEST", req.str("preference")); assertEquals("HALF_FARE", req.str("discount")); assertEquals("1", req.str("fareClass"))
    }

    // --- limit ---

    @Test
    fun `journeys limit caps alternatives, defaults to 3 and is bounded 1 to 10`() = testApplication {
        val many = (1..12).map { i -> com.cffplanb.backend.domain.Journey("j$i", java.time.OffsetDateTime.parse("2026-11-21T15:${10 + i}:00Z"), java.time.OffsetDateTime.parse("2026-11-21T16:${10 + i}:00Z"), 60, 0,
            listOf(com.cffplanb.backend.domain.JourneyLeg(com.cffplanb.backend.domain.LegMode.TRANSIT, "A", "B", java.time.OffsetDateTime.parse("2026-11-21T15:${10 + i}:00Z"), java.time.OffsetDateTime.parse("2026-11-21T16:${10 + i}:00Z"), "IC$i", null, null)),
            com.cffplanb.backend.domain.DataStatus.MOCK) }
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(object : TransportProvider { override suspend fun search(request: SearchRequest) = ProviderResult.Success(many) }), fixedClock)
        }
        suspend fun n(q: String) = parse(client.get("/api/journeys?origin=A&destination=B$q").bodyAsText()).a("alternatives").size
        assertEquals(3, n("")); assertEquals(1, n("&limit=1")); assertEquals(10, n("&limit=10"))
        for (v in listOf("0", "11", "-1", "x", "")) assertError(client.get("/api/journeys?origin=A&destination=B&limit=$v"), HttpStatusCode.BadRequest, "INVALID_REQUEST", "limit")
    }

    // --- Validation errors ---

    @Test
    fun `invalid journey requests are 400 INVALID_REQUEST naming the field`() = appTest {
        val cases = listOf(
            "destination=Bern" to "origin", "origin=Lausanne" to "destination",
            "origin=&destination=Bern" to "origin", "origin=%20&destination=Bern" to "origin", "origin=Lausanne&destination=" to "destination",
            "origin=A&destination=B&departure=not-a-date" to "departure", "origin=A&destination=B&departure=2026-01-01T12:00:00" to "departure",
            "origin=A&destination=B&arrivalDeadline=soon" to "arrivalDeadline",
            "origin=A&destination=B&preference=BOGUS" to "preference", "origin=A&destination=B&fareClass=3" to "fareClass",
            "origin=A&destination=B&discount=GA" to "discount",
            "origin=A&origin=B&destination=C" to "origin", "origin=A&destination=B&destination=C" to "destination",
            "origin=${"a".repeat(121)}&destination=Bern" to "origin",
        )
        for ((q, f) in cases) assertError(client.get("/api/journeys?$q"), HttpStatusCode.BadRequest, "INVALID_REQUEST", f)
    }

    @Test
    fun `duplicate key is still 400 while an unknown param is not`() = appTest {
        assertError(client.get("/api/journeys?origin=A&destination=B&limit=1&limit=2"), HttpStatusCode.BadRequest, "INVALID_REQUEST", "limit")
        assertError(client.get("/api/locations?query=a&query=b"), HttpStatusCode.BadRequest, "INVALID_REQUEST", "query")
    }

    // --- Provider failure mappings ---

    @Test
    fun `startup with ojp provider but missing api key fails fast`() {
        assertFailsWith<IllegalStateException> {
            testApplication { application { configureApp(fixedClock, AppConfig(transportProvider = "ojp", ojpApiKey = null)) } }
        }
    }

    private fun failing(r: ProviderResult) = object : TransportProvider { override suspend fun search(request: SearchRequest) = r }

    @Test
    fun `provider failures map to documented statuses`() {
        for ((r, st, code) in listOf(
            Triple(ProviderResult.AuthError("Transport provider authentication failed. Check OJP_API_KEY configuration."), HttpStatusCode.BadGateway, "UPSTREAM_ERROR"),
            Triple(ProviderResult.Timeout("OJP did not respond within 8000ms"), HttpStatusCode.GatewayTimeout, "UPSTREAM_TIMEOUT"),
            Triple(ProviderResult.RateLimited("OJP rate limit reached"), HttpStatusCode.ServiceUnavailable, "UPSTREAM_RATE_LIMITED"),
            Triple(ProviderResult.Failure("boom"), HttpStatusCode.BadGateway, "UPSTREAM_ERROR"),
        )) customProviderTest(failing(r)) { assertError(client.get("/api/journeys?origin=A&destination=B"), st, code, null) }
    }

    @Test
    fun `unresolvable location during search is 404 LOCATION_NOT_FOUND`() = customProviderTest(
        failing(ProviderResult.LocationResolutionError("Could not resolve stop 'UnknownStation'", "destination"))) {
        val r = client.get("/api/journeys?origin=A&destination=UnknownStation")
        assertError(r, HttpStatusCode.NotFound, "LOCATION_NOT_FOUND", "destination")
        assertFalse(r.bodyAsText().contains("UnknownStation")) // the query text is not echoed
    }

    private class Resolver(val unknown: Set<String>) : TransportProvider {
        override suspend fun search(request: SearchRequest) = ProviderResult.Success(emptyList())
        override suspend fun resolvePlace(input: String): PlaceResult =
            if (input in unknown) PlaceResult.NotFound else PlaceResult.Found(ResolvedPlace(input, "ch:1:sloid:${input.length}", input.uppercase(), "ch:1:sloid:${input.length}"))
    }

    @Test
    fun `unknown origin or destination is 404 with the field, resolved names are echoed`() = customProviderTest(Resolver(setOf("Nowhereville-XYZ"))) {
        assertError(client.get("/api/journeys?origin=Nowhereville-XYZ&destination=Bern"), HttpStatusCode.NotFound, "LOCATION_NOT_FOUND", "origin")
        assertError(client.get("/api/journeys?origin=Bern&destination=Nowhereville-XYZ"), HttpStatusCode.NotFound, "LOCATION_NOT_FOUND", "destination")
        val j = parse(client.get("/api/journeys?origin=Bern&destination=Zug").bodyAsText())
        assertEquals("BERN", j.o("request").o("origin").str("resolvedName")); assertEquals("ch:1:sloid:4", j.o("request").o("origin").str("id"))
        assertEquals("NONE", j.str("action")); assertEquals(JsonNull, j["primaryJourney"]); assertEquals(JsonNull, j["recommendedJourney"]); assertEquals(0, j.a("alternatives").size)
    }

    @Test
    fun `place lookup failures map to upstream errors`() {
        for ((r, st, code) in listOf(
            Triple(PlaceResult.Timeout("t"), HttpStatusCode.GatewayTimeout, "UPSTREAM_TIMEOUT"),
            Triple(PlaceResult.RateLimited("r"), HttpStatusCode.ServiceUnavailable, "UPSTREAM_RATE_LIMITED"),
            Triple(PlaceResult.Failure("f"), HttpStatusCode.BadGateway, "UPSTREAM_ERROR"),
        )) customProviderTest(object : TransportProvider {
            override suspend fun search(request: SearchRequest) = ProviderResult.Success(emptyList())
            override suspend fun resolvePlace(input: String) = r
        }) { assertError(client.get("/api/journeys?origin=A&destination=B"), st, code, null) }
    }
}

class DeadlineRouteTest {
    // clock 15:00Z; mock arrivals at +60, +80, +90 min (deps +10, +5, +40)
    private fun get(q: String, block: suspend (HttpResponse) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(MockTransportProvider()), Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC))
        }
        block(client.get("/api/journeys?origin=A&destination=B$q"))
    }
    private fun JsonObject.all() = listOf(o("primaryJourney")) + a("alternatives").map { it.obj() }
    private fun JsonObject.feasible() = all().associate { it.str("id") to it["feasible"]!!.jsonPrimitive.boolean }

    @Test fun `some feasible, infeasible kept and flagged, summary says so`() = get("&arrivalDeadline=2026-11-21T16%3A25%3A00Z") {
        val j = parse(it.bodyAsText())
        assertEquals("2026-11-21T16:25:00Z", j.o("request").str("arrivalDeadline"))
        assertEquals(mapOf("mock-direct-1" to true, "mock-transfer-2" to true, "mock-direct-3" to false), j.feasible())
        assertEquals("2026-11-21T15:10:00Z", j.o("deadlineSummary").str("latestFeasibleDeparture"))
        assertEquals(true, j.o("deadlineSummary")["feasible"]!!.jsonPrimitive.boolean)
        assertEquals("DEADLINE_MET", j.a("reasons").last().obj().str("code")) // reasons are objects, margin 25 min
        assertEquals(25, j.a("reasons").last().obj()["marginMinutes"]!!.jsonPrimitive.int)
    }

    @Test fun `all feasible`() = get("&arrivalDeadline=2026-11-21T17%3A00%3A00Z") {
        val j = parse(it.bodyAsText())
        assertTrue(j.feasible().values.all { v -> v })
        assertEquals("2026-11-21T15:40:00Z", j.o("deadlineSummary").str("latestFeasibleDeparture"))
    }

    @Test fun `none feasible`() = get("&arrivalDeadline=2026-11-21T15%3A30%3A00Z") {
        val j = parse(it.bodyAsText())
        assertTrue(j.feasible().values.none { v -> v })
        assertEquals(false, j.o("deadlineSummary")["feasible"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, j.o("deadlineSummary")["latestFeasibleDeparture"])
        assertEquals("DEADLINE_MISSED", j.a("reasons").last().obj().str("code"))
    }

    @Test fun `a journey that meets the deadline is preferred as primary even if another ranks first`() = get("&preference=CHEAPEST&arrivalDeadline=2026-11-21T16%3A25%3A00Z") {
        assertEquals(true, parse(it.bodyAsText()).o("primaryJourney")["feasible"]!!.jsonPrimitive.boolean)
    }

    @Test fun `no deadline leaves fields null`() = get("") {
        val j = parse(it.bodyAsText())
        assertEquals(JsonNull, j["deadlineSummary"]); assertEquals(JsonNull, j.o("primaryJourney")["feasible"])
    }

    @Test fun `invalid deadline is 400`() = get("&arrivalDeadline=tomorrow") {
        assertEquals(HttpStatusCode.BadRequest, it.status)
        assertEquals("arrivalDeadline", parse(it.bodyAsText()).o("error").str("field"))
    }
}

class LocationsRouteTest {
    private fun app(p: TransportProvider, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(p), Clock.systemUTC())
        }
        block()
    }

    private fun fixed(r: LR) = object : TransportProvider {
        override suspend fun search(request: SearchRequest) = ProviderResult.Success(emptyList())
        override suspend fun searchLocations(query: String) = r
    }
    private val twenty = LR.Success((1..25).map { LocationSuggestion("id$it", "n$it", if (it == 1) "address" else "stop", if (it == 2) null else 6.6, 46.5) })

    @Test fun `default limit 8, explicit limit up to 20, uppercase kind, nullable coordinates`() = app(fixed(twenty)) {
        suspend fun locs(q: String) = parse(client.get("/api/locations?query=n$q").bodyAsText()).a("locations")
        assertEquals(8, locs("").size); assertEquals(1, locs("&limit=1").size); assertEquals(20, locs("&limit=20").size)
        val l = locs("&limit=3")
        assertEquals(setOf("id", "name", "kind", "coordinates"), l[0].obj().keys)
        assertEquals(listOf("ADDRESS", "STOP"), l.take(2).map { it.obj().str("kind") })
        assertEquals(46.5, l[0].obj().o("coordinates")["lat"]!!.jsonPrimitive.double); assertEquals(6.6, l[0].obj().o("coordinates")["lon"]!!.jsonPrimitive.double)
        assertEquals(JsonNull, l[1].obj()["coordinates"]) // longitude unknown: no half coordinates
    }

    @Test fun `limit out of range is 400 with field limit`() = app(fixed(twenty)) {
        for (v in listOf("0", "21", "-1", "abc", "")) {
            val r = client.get("/api/locations?query=n&limit=$v")
            assertEquals(HttpStatusCode.BadRequest, r.status, v)
            assertEquals("limit", parse(r.bodyAsText()).o("error").str("field"))
        }
    }

    private val nearby = object : TransportProvider {
        override suspend fun search(request: SearchRequest) = ProviderResult.Success(emptyList())
        override suspend fun searchNearby(lat: Double, lon: Double) = LR.Success(listOf(
            LocationSuggestion("far", "Lausanne-Flon", "stop", 6.6300, 46.5200),
            LocationSuggestion("addr", "Rue Lausanne 1", "address", 6.6323, 46.5197),
            LocationSuggestion("close", "Lausanne", "stop", 6.6290, 46.5168),
            LocationSuggestion("zero", "Lausanne gare", "stop", 6.6323, 46.5197),
            LocationSuggestion("close", "Lausanne", "stop", 6.6400, 46.5190),
            LocationSuggestion("out", "Geneve", "stop", 6.14, 46.21)))
    }

    @Test fun `near returns stops within radius closest first and honours query, limit, radiusKm`() = app(nearby) {
        suspend fun ids(q: String) = parse(client.get("/api/locations?$q").bodyAsText()).a("locations").map { it.obj().str("id") }
        assertEquals(listOf("zero", "far", "close"), ids("near=46.5197,6.6323"))
        assertEquals(listOf("zero", "far"), ids("near=46.5197,6.6323&limit=2"))
        assertEquals(listOf("zero"), ids("near=46.5197,6.6323&radiusKm=0.1"))
        assertEquals(listOf("zero", "far", "close"), ids("near=46.5197,6.6323&radiusKm=999")) // clamped to 20 km: Geneve (~50 km) stays out
    }

    @Test fun `near combined with query filters by name, invalid near and radiusKm are 400`() = app(nearby) {
        val l = parse(client.get("/api/locations?near=46.5197,6.6323&query=GARE").bodyAsText()).a("locations")
        assertEquals(listOf("zero"), l.map { it.obj().str("id") })
        for ((u, f) in listOf("near=badinput" to "near", "near=46.5" to "near", "near=1,2,3" to "near", "near=46.5,%206.6" to "near", "near=91,6" to "near",
            "near=46,181" to "near", "near=NaN,1" to "near", "near=46.5197,6.6323&radiusKm=-1" to "radiusKm", "near=46.5197,6.6323&radiusKm=x" to "radiusKm", "near=46,6&query=" to "query")) {
            val r = client.get("/api/locations?$u")
            assertEquals(HttpStatusCode.BadRequest, r.status, u)
            assertEquals(f, parse(r.bodyAsText()).o("error").str("field"), u)
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/locations?near=46.5197,6.6323&foo=1").status)
    }

    @Test fun `mock provider filters by name`() = app(MockTransportProvider()) {
        val arr = parse(client.get("/api/locations?query=laus").bodyAsText()).a("locations")
        assertEquals(1, arr.size); assertEquals("STOP", arr[0].obj().str("kind"))
    }

    @Test fun `invalid queries are 400, unknown params are ignored`() = app(MockTransportProvider()) {
        for (u in listOf("/api/locations", "/api/locations?query=", "/api/locations?query=%20%20", "/api/locations?query=a&query=b", "/api/locations?query=" + "a".repeat(121))) {
            val r = client.get(u)
            assertEquals(HttpStatusCode.BadRequest, r.status, u)
            assertEquals("query", parse(r.bodyAsText()).o("error").str("field"), u)
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/locations?query=a&x=1").status)
    }

    @Test fun `upstream failures map to error envelope`() {
        for ((r, st) in listOf(LR.Timeout("t") to 504, LR.RateLimited("r") to 503, LR.Failure("f") to 502))
            app(fixed(r)) { assertEquals(st, client.get("/api/locations?query=x").status.value) }
    }
}

class ModelRouteTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `GET api model is static artifact metadata with cache header and no live counters`() = testApplication {
        val fixture = java.nio.file.Path.of(javaClass.getResource("/predictions_fixture.json")!!.toURI())
        val delays = com.cffplanb.backend.service.RealtimeDelayModel(java.nio.file.Path.of(javaClass.getResource("/realtime_delays_fixture.json")!!.toURI()))
        val src = GtfsRealtimeSource({ javaClass.getResourceAsStream("/gtfsrt_fixture.pb")!!.readBytes() })
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(MockTransportProvider()), clock, com.cffplanb.backend.service.ArtifactRiskEstimator(fixture), src, delays)
        }
        src.current()
        val r = client.get("/api/model")
        assertEquals("public, max-age=300", r.headers[HttpHeaders.CacheControl])
        val m = parse(r.bodyAsText())
        assertEquals(setOf("riskModel", "delayModel"), m.keys)
        val rm = m.o("riskModel")
        assertEquals("ARTIFACT", rm.str("source")); assertEquals("v0.1", rm.str("version")); assertEquals(3, rm.o("counts")["keys"]!!.jsonPrimitive.int)
        assertEquals(setOf("keys", "corridors", "baselineHours", "byLineHourCells"), rm.o("counts").keys)
        assertEquals(setOf("from", "to", "windowDays", "scope"), rm.o("coverage").keys)
        assertEquals("fixture-delays-1", m.o("delayModel").str("version")); assertEquals("OK", m.o("delayModel").str("status"))
        assertEquals(5, m.o("delayModel").o("counts")["portions"]!!.jsonPrimitive.int)
        assertEquals(2, m.o("delayModel").o("counts")["lines"]!!.jsonPrimitive.int)
        val text = r.bodyAsText()
        for (k in listOf("gtfsRt", "gtfsRtAgeSeconds", "gtfsRtTripUpdates", "gtfsRtCancelled", "corridorKeysSample")) assertFalse(k in text, k)
    }

    @Test
    fun `coverage dates and window come from the artifact`() = testApplication {
        application { configureApp(clock, AppConfig(transportProvider = "mock", riskArtifactPath = null, fareBaseUrl = "")) }
        val c = parse(client.get("/api/model").bodyAsText()).o("riskModel").o("coverage")
        assertEquals("2024-03-03", c.str("from")); assertEquals("2026-10-11", c.str("to")); assertEquals(953, c["windowDays"]!!.jsonPrimitive.int)
    }

    @Test
    fun `no artifacts means NONE and MISSING, not an error`() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(MockTransportProvider()), clock)
        }
        val m = parse(client.get("/api/model").bodyAsText())
        assertEquals("NONE", m.o("riskModel").str("source")); assertEquals("MISSING", m.o("riskModel").str("status")); assertEquals(JsonNull, m.o("riskModel")["coverage"])
        assertEquals("MISSING", m.o("delayModel").str("status")); assertEquals(JsonNull, m.o("delayModel")["version"])
    }
}

class HealthRouteTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)
    private val fixture = HealthRouteTest::class.java.getResourceAsStream("/gtfsrt_fixture.pb")!!.readBytes()

    private fun health(searchService: JourneySearchService, gtfs: GtfsRealtimeSource? = null, block: suspend (JsonObject) -> Unit = {}) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(searchService, clock, gtfs = gtfs)
        }
        val r = client.get("/health")
        assertEquals(HttpStatusCode.OK, r.status)
        block(parse(r.bodyAsText()))
    }
    private class Quote(val ok: Boolean) : FareProvider { override suspend fun quote(origin: String, destination: String, departure: java.time.OffsetDateTime) = if (ok) FareQuote(1.0, 1.0, 1.0, "p", "NOVA") else null }
    private val t0 = java.time.OffsetDateTime.parse("2026-11-21T15:00:00Z")

    @Test fun `mock provider and no fares and no gtfs are all DISABLED yet overall OK`() = health(JourneySearchService(MockTransportProvider())) { j ->
        assertEquals("OK", j.str("status"))
        assertEquals("DISABLED", j.o("feeds").str("ojp")); assertEquals("DISABLED", j.o("feeds").str("nova"))
        val g = j.o("feeds").o("gtfsRt")
        assertEquals(false, g["enabled"]!!.jsonPrimitive.boolean); assertEquals("DISABLED", g.str("status"))
        for (k in listOf("ageSeconds", "activeTripUpdates", "cancelledTrips")) assertEquals(JsonNull, g[k], k)
    }

    @Test fun `gtfs snapshot is OK with counters, DEGRADED when stale, DOWN when absent`() {
        var now = 0L
        val src = GtfsRealtimeSource({ fixture }, { now }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        health(JourneySearchService(MockTransportProvider()), src) { j -> // nothing fetched yet
            assertEquals("DOWN", j.o("feeds").o("gtfsRt").str("status")); assertTrue(j.o("feeds").o("gtfsRt")["enabled"]!!.jsonPrimitive.boolean)
            assertEquals("DEGRADED", j.str("status"))
        }
        kotlinx.coroutines.runBlocking { src.current() }
        now = 42_000
        health(JourneySearchService(MockTransportProvider()), src) { j ->
            val g = j.o("feeds").o("gtfsRt")
            assertEquals("OK", g.str("status")); assertEquals(42, g["ageSeconds"]!!.jsonPrimitive.int)
            assertEquals(4, g["activeTripUpdates"]!!.jsonPrimitive.int); assertEquals(1, g["cancelledTrips"]!!.jsonPrimitive.int)
            assertEquals("OK", j.str("status"))
        }
        now = 200_000
        health(JourneySearchService(MockTransportProvider()), src) { j ->
            assertEquals("DEGRADED", j.o("feeds").o("gtfsRt").str("status")); assertEquals("OK", j.str("status")) // stale but present still counts as healthy overall
        }
    }

    @Test fun `nova follows its last outcomes`() {
        fun nova(ok: Boolean, calls: Int): String { val c = FareCache(Quote(ok), negativeTtlMs = 0); kotlinx.coroutines.runBlocking { repeat(calls) { c.quote("a$it", "b", t0) } }; return c.status() }
        assertEquals("OK", nova(true, 3)); assertEquals("OK", nova(true, 0))
        assertEquals("DEGRADED", nova(false, 1)); assertEquals("DEGRADED", nova(false, 2)); assertEquals("DOWN", nova(false, 3))
        val down = FareCache(Quote(false)); kotlinx.coroutines.runBlocking { repeat(3) { down.quote("a$it", "b", t0) } }
        health(JourneySearchService(MockTransportProvider(), down)) { j -> assertEquals("DOWN", j.o("feeds").str("nova")); assertEquals("DEGRADED", j.str("status")) }
    }

    @Test fun `ojp reflects the circuit breaker and health never calls the provider`() {
        var calls = 0
        val failing = object : TransportProvider {
            override suspend fun search(request: SearchRequest): ProviderResult { calls++; return ProviderResult.Failure("x") }
            override fun feedStatus() = "OK"
        }
        val r = ResilientProvider(failing)
        assertEquals("OK", r.feedStatus())
        kotlinx.coroutines.runBlocking { r.search(SearchRequest("A", "B", t0)) }
        assertEquals("DEGRADED", r.feedStatus())
        kotlinx.coroutines.runBlocking { repeat(4) { r.search(SearchRequest("A$it", "B", t0)) } }
        assertEquals("DOWN", r.feedStatus())
        val before = calls
        health(JourneySearchService(r)) { j -> assertEquals("DOWN", j.o("feeds").str("ojp")); assertEquals("DEGRADED", j.str("status")) }
        assertEquals(before, calls)
    }
}
