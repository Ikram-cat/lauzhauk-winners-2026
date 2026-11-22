package com.alt.backend.api

import com.alt.backend.domain.*
import com.alt.backend.provider.*
import com.alt.backend.service.JourneySearchService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

class StructuredOutputTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)
    private fun t(hm: String) = OffsetDateTime.parse("2026-11-21T${hm}:00Z")
    private fun leg(l: String, dep: String, arr: String) = JourneyLeg(LegMode.TRANSIT, "A", "B", t(dep), t(arr), l, null, null)
    private fun j(id: String, vararg legs: JourneyLeg) = Journey(id, legs.first().departure, legs.last().arrival, 60, legs.size - 1, legs.toList(), DataStatus.LIVE_SCHEDULE)
    // FAST: earliest arrival but 1 change; SIMPLE: direct and later; the rest pad the list to 5
    private val all = listOf(
        j("FAST", leg("S1", "15:05", "15:20"), leg("IC", "15:25", "15:50")),
        j("SIMPLE", leg("IR", "15:10", "16:00")),
        j("P1", leg("X", "15:15", "16:10")), j("P2", leg("X", "15:20", "16:20")), j("P3", leg("X", "15:25", "16:30")))

    private class Fake(val js: List<Journey>) : TransportProvider {
        override suspend fun search(request: SearchRequest): ProviderResult = ProviderResult.Success(js)
        override suspend fun stopEvents(stop: String, from: OffsetDateTime, realtime: Boolean) = StopEventsResult.Failure("n/a")
    }
    private fun get(path: String, js: List<Journey> = all, block: suspend (HttpStatusCode, JsonObject) -> Unit) = testApplication {
        application { install(ContentNegotiation) { json(Json { encodeDefaults = true }) }; configureRoutes(JourneySearchService(Fake(js)), clock) }
        val r = client.get(path); block(r.status, parse(r.bodyAsText()))
    }
    private val base = "/api/journeys?origin=A&destination=B"
    private fun JsonObject.altIds() = a("alternatives").map { it.obj().str("id") }

    @Test fun `primary follows the preference and alternatives are sorted by it, excluding the recommendation`() {
        get(base) { _, j -> assertEquals("FAST", j.o("primaryJourney").str("id")); assertEquals(listOf("SIMPLE", "P1", "P2"), j.altIds()) }
        get("$base&preference=SIMPLEST&limit=10") { _, j -> assertEquals("SIMPLE", j.o("primaryJourney").str("id")); assertEquals(listOf("P1", "P2", "P3", "FAST"), j.altIds()) }
    }

    @Test fun `highlights name the best of the journeys shown regardless of preference`() {
        for (pref in listOf("FASTEST", "SIMPLEST")) get("$base&preference=$pref&limit=10") { _, j ->
            val h = j.o("highlights")
            assertEquals("FAST", h.str("fastestId")); assertEquals("SIMPLE", h.str("simplestId"))
            assertEquals(JsonNull, h["cheapestId"]) // fare lookup is off: nothing to be cheapest
        }
        get("$base&limit=1") { _, j -> // ids always refer to a journey in the payload
            val shown = (listOf(j.o("primaryJourney"), j.o("recommendedJourney")) + j.a("alternatives").map { it.obj() }).map { it.str("id") }.toSet()
            assertTrue(j.o("highlights").values.all { it is JsonNull || it.jsonPrimitive.content in shown })
        }
    }

    @Test fun `no route is action NONE with null journeys and an empty list`() = get(base, js = emptyList()) { s, j ->
        assertEquals(HttpStatusCode.OK, s)
        assertEquals("NONE", j.str("action")); assertEquals(JsonNull, j["primaryJourney"]); assertEquals(JsonNull, j["recommendedJourney"])
        assertEquals(0, j.a("alternatives").size); assertEquals(0, j.a("reasons").size)
        assertEquals(JsonNull, j.o("highlights")["fastestId"])
    }

    @Test fun `realtime and legs are null not zero when nothing is known`() = get(base) { _, j ->
        val p = j.o("primaryJourney")
        assertEquals("NONE", p.o("realtime").str("source")); assertEquals(JsonNull, p.o("realtime")["arrivalDelayMinutes"])
        for (l in p.a("legs")) { assertEquals(JsonNull, l.obj()["departureDelayMinutes"]); assertEquals(JsonNull, l.obj()["expectedArrival"]) }
        assertEquals("15:05", p.o("schedule").str("departure").substring(11, 16)) // scheduled values untouched
    }
}
