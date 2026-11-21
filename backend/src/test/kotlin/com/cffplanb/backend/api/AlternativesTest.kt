package com.cffplanb.backend.api

import com.cffplanb.backend.domain.*
import com.cffplanb.backend.provider.MockTransportProvider
import com.cffplanb.backend.service.JourneySearchService
import com.cffplanb.backend.service.RiskEstimate
import com.cffplanb.backend.service.RiskEstimator
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

class AlternativesTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)
    private val corridor = RiskEstimate(null, 0.027, "corridor", "high", "Lausanne - Genève", 178, "corridor_duration")
    private val stub = object : RiskEstimator { override fun estimate(journey: Journey) = corridor
        override fun info() = com.cffplanb.backend.service.ModelInfo(null, null, null, 0, "ok") }

    private fun call(path: String, r: RiskEstimator = stub, block: suspend (HttpResponse) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(MockTransportProvider()), clock, r)
        }
        block(client.get(path))
    }
    private fun JsonElement.o() = jsonObject
    private val base = "/api/alternatives?from=A&to=B&notBefore=2026-11-21T15%3A00%3A00Z"

    @Test fun `success returns ranked journeys with reasons and advice`() = call(base) {
        val j = Json.parseToJsonElement(it.bodyAsText()).o()
        assertEquals(HttpStatusCode.OK, it.status)
        assertEquals("A", j["request"]!!.o()["from"]!!.jsonPrimitive.content)
        assertEquals(3, j["alternatives"]!!.jsonArray.size)
        assertEquals(false, j["noFeasibleRoute"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, j["latestFeasibleDeparture"])
        assertEquals("earliest arrival", j["alternatives"]!!.jsonArray[0].o()["reasons"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(j["advice"]!!.jsonPrimitive.content.contains("corridor Lausanne - Genève"))
    }

    @Test fun `deadline gives latest departure and feasible none flag`() = call("$base&arrivalDeadline=2026-11-21T16%3A25%3A00Z") {
        val j = Json.parseToJsonElement(it.bodyAsText()).o()
        assertEquals("2026-11-21T15:10:00Z", j["latestFeasibleDeparture"]!!.jsonPrimitive.content)
        assertTrue(j["advice"]!!.jsonPrimitive.content.startsWith("Leave by 15:10 to arrive before 16:25"))
    }

    @Test fun `no feasible route`() = call("$base&arrivalDeadline=2026-11-21T15%3A30%3A00Z") {
        val j = Json.parseToJsonElement(it.bodyAsText()).o()
        assertEquals(true, j["noFeasibleRoute"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, j["latestFeasibleDeparture"])
        assertEquals("No feasible route: no journey arrives before 15:30.", j["advice"]!!.jsonPrimitive.content)
    }

    @Test fun `journeys endpoint also carries reasons and advice`() = call("/api/journeys?origin=A&destination=B&arrivalDeadline=2026-11-21T16%3A25%3A00Z") {
        val j = Json.parseToJsonElement(it.bodyAsText()).o()
        val first = j["journeys"]!!.jsonArray[0].o()["reasons"]!!.jsonArray.map { r -> r.jsonPrimitive.content }
        assertTrue(first.size <= 3 && first.any { r -> r.startsWith("arrives ") && r.endsWith("before your deadline") }, first.toString())
        assertTrue(j["advice"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test fun `400 cases`() {
        listOf(
            "/api/alternatives?to=B&notBefore=2026-11-21T15%3A00%3A00Z",
            "/api/alternatives?from=A&notBefore=2026-11-21T15%3A00%3A00Z",
            "/api/alternatives?from=A&to=B",
            "/api/alternatives?from=%20&to=B&notBefore=2026-11-21T15%3A00%3A00Z",
            "/api/alternatives?from=A&to=B&notBefore=yesterday",
            "$base&arrivalDeadline=soon",
            "$base&from=C",
            "$base&bogus=1",
        ).forEach { p -> call(p) {
            assertEquals(HttpStatusCode.BadRequest, it.status, p)
            assertEquals("INVALID_REQUEST", Json.parseToJsonElement(it.bodyAsText()).o()["error"]!!.o()["code"]!!.jsonPrimitive.content)
        } }
    }

    // --- reason selection / advice unit tests ---
    private fun jy(id: String, arr: String, tr: Int) = OffsetDateTime.parse(arr).let {
        Journey(id, it.minusMinutes(60), it, 60, tr, emptyList(), DataStatus.MOCK)
    }
    private val a = jy("a", "2026-11-21T14:00:00Z", 1)
    private val b = jy("b", "2026-11-21T14:00:00Z", 0) // ties a on arrival
    private val c = jy("c", "2026-11-21T14:30:00Z", 0)
    private val all = listOf(a, b, c)

    @Test fun `ties both get earliest arrival, min transfers shared`() {
        assertEquals(listOf("earliest arrival"), reasonsFor(a, all, null, null))
        assertEquals(listOf("earliest arrival", "fewest transfers"), reasonsFor(b, all, null, null))
        assertEquals(listOf("fewest transfers"), reasonsFor(c, all, null, null))
    }

    @Test fun `deadline edge - exact, after, none`() {
        val d = OffsetDateTime.parse("2026-11-21T14:00:00Z")
        assertTrue("arrives exactly at your deadline" in reasonsFor(a, all, null, d))
        assertTrue(reasonsFor(c, all, null, d).none { it.contains("deadline") })
        assertTrue("arrives 30 min before your deadline" in reasonsFor(a, all, null, d.plusMinutes(30)))
        assertTrue(reasonsFor(a, all, null, null).none { it.contains("deadline") })
    }

    @Test fun `reasons capped at 3 and risk kept`() {
        val r = reasonsFor(b, all, corridor, OffsetDateTime.parse("2026-11-21T15:00:00Z"))
        assertEquals(3, r.size); assertTrue("corridor risk 2.7% (high confidence)" in r); assertFalse("fewest transfers" in r)
    }

    @Test fun `advice four states`() {
        val d = OffsetDateTime.parse("2026-11-21T15:00:00Z")
        assertEquals("Leave by 13:30 to arrive before 15:00; highest risk is on corridor Lausanne - Genève (2.7% of days disrupted, typically 178 min, high confidence).",
            adviceFor(all, List(3) { corridor }, d))
        assertEquals("Leave by 13:30 to arrive before 15:00; delay risk is unknown for this route.", adviceFor(all, List(3) { null }, d))
        assertEquals("No feasible route: no journey arrives before 13:00.", adviceFor(all, List(3) { corridor }, OffsetDateTime.parse("2026-11-21T13:00:00Z")))
        assertEquals("No journeys found for this request.", adviceFor(emptyList(), emptyList(), null))
        assertTrue(adviceFor(all, List(3) { corridor.copy(label = "x".repeat(400)) }, d).length <= 240)
    }
}
