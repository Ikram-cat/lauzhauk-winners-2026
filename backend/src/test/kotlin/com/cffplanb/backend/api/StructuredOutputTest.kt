package com.cffplanb.backend.api

import com.cffplanb.backend.domain.*
import com.cffplanb.backend.provider.*
import com.cffplanb.backend.service.JourneySearchService
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
        val r = client.get(path); block(r.status, Json.parseToJsonElement(r.bodyAsText()).jsonObject)
    }
    private val paths = listOf("/api/journeys?origin=A&destination=B", "/api/redirect?origin=A&destination=B")
    private fun JsonObject.ids(k: String) = this[k]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test fun `default output has no prose anywhere`() = paths.forEach { p -> get(p) { s, j ->
        assertEquals(HttpStatusCode.OK, s)
        val text = j.toString()
        for (k in listOf("advice", "message", "why", "reasons", "recommendationIds")) assertFalse("\"$k\"" in text, "$p leaks $k")
        assertTrue("fareStatus" in text || "expectedArrival" in text) // per-journey fields stay
    } }

    @Test fun `includeText brings the prose back`() = paths.forEach { p -> get("$p&includeText=true") { _, j ->
        assertTrue(j["advice"]!!.jsonPrimitive.content.isNotBlank()); assertTrue(j["recommendationIds"] != null)
        assertTrue((j["journeys"]!!.jsonArray[0].jsonObject["reasons"] != null))
        if ("redirect" in p) assertTrue(j["message"]!!.jsonPrimitive.content.isNotBlank() && j["recommendation"]!!.jsonObject["why"] != null)
    } }

    @Test fun `limit caps journeys and defaults to 3`() = paths.forEach { p ->
        get(p) { _, j -> assertEquals(3, j.ids("journeys").size) }
        get("$p&limit=2") { _, j -> assertEquals(2, j.ids("journeys").size) }
        get("$p&limit=10") { _, j -> assertEquals(5, j.ids("journeys").size) }
    }

    @Test fun `limit out of range or not a number is 400`() = paths.forEach { p ->
        for (v in listOf("0", "11", "-1", "x", "")) get("$p&limit=$v") { s, j -> assertEquals(HttpStatusCode.BadRequest, s, v); assertEquals("INVALID_REQUEST", j["error"]!!.jsonObject["code"]!!.jsonPrimitive.content) }
    }

    @Test fun `recommendations are full journeys independent of preference`() = paths.forEach { p ->
        for (pref in listOf("FASTEST", "CHEAPEST", "SIMPLEST")) get("$p&preference=$pref&limit=1") { _, j ->
            val r = j["recommendations"]!!.jsonObject
            assertEquals("FAST", r["fastest"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals("SIMPLE", r["simplest"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertTrue(r["fastest"]!!.jsonObject["legs"]!!.jsonArray.size == 2) // a full journey, not an id
        }
    }

    @Test fun `recommendations are null when there is no route`() = paths.forEach { p -> get(p, js = emptyList()) { s, j ->
        assertEquals(HttpStatusCode.OK, s)
        val r = j["recommendations"]!!.jsonObject
        assertEquals(JsonNull, r["fastest"]); assertEquals(JsonNull, r["simplest"])
        assertTrue(j["journeys"]!!.jsonArray.isEmpty())
    } }
}
