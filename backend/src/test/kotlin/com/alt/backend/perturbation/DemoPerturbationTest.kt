package com.alt.backend.perturbation

import com.alt.backend.api.configureRoutes
import com.alt.backend.domain.LegMode
import com.alt.backend.domain.SearchRequest
import com.alt.backend.provider.MockTransportProvider
import com.alt.backend.provider.ProviderResult
import com.alt.backend.provider.StopEventsResult
import com.alt.backend.provider.TransportProvider
import com.alt.backend.service.JourneySearchService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

class DemoPerturbationTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)
    private val plain = MockTransportProvider()
    private val wrapped = PerturbationProvider(MockTransportProvider())
    private val base = "/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&departure=2026-11-21T16%3A00%3A00%2B01%3A00"
    private val envelopeKeys = setOf(
        "request", "status", "action", "reasons", "disruption", "primaryJourney", "recommendedJourney",
        "alternatives", "highlights", "deadlineSummary", "meta"
    )

    private fun app(provider: TransportProvider, enabled: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(provider), clock, demoPerturbationEnabled = enabled)
        }
        block()
    }

    /** A complete response body from a separate application instance, for baseline comparisons. */
    private fun getBody(provider: TransportProvider, url: String, enabled: Boolean = true): String {
        var out = ""
        app(provider, enabled) { out = client.get(url).let { r -> assertEquals(HttpStatusCode.OK, r.status); r.bodyAsText() } }
        return out
    }

    private suspend fun ApplicationTestBuilder.body(url: String): String =
        client.get(url).let { r -> assertEquals(HttpStatusCode.OK, r.status); r.bodyAsText() }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject
    private fun JsonObject.a(k: String) = this[k]!!.jsonArray
    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

    // ---- trigger parsing -----------------------------------------------------------------------------

    @Test fun `absent parameter disables the layer`() {
        assertNull(DemoPerturbation.parse(null, enabled = true))
        assertNull(DemoPerturbation.parse("false", enabled = true))
        assertNull(DemoPerturbation.parse("true", enabled = false))
    }

    @Test fun `true draws a fresh seed and a number is reproducible`() {
        val a = DemoPerturbation.parse("true", true)!!
        val b = DemoPerturbation.parse("TRUE", true)!!
        assertNotEquals(a.seed, b.seed)
        assertTrue(a.randomDraw && b.randomDraw)
        val s = DemoPerturbation.parse("42", true)!!
        assertEquals(42L, s.seed); assertFalse(s.randomDraw)
        assertFailsWith<IllegalArgumentException> { DemoPerturbation.parse("yes", true) }
    }

    // ---- zero impact when off ------------------------------------------------------------------------

    @Test fun `without the field the envelope is byte-identical to the unperturbed provider`() = app(wrapped) {
        val reference = getBody(plain, base)
        val wrappedBody = body(base)
        assertEquals(reference, wrappedBody)
        assertEquals(envelopeKeys, parse(wrappedBody).keys)
    }

    @Test fun `the kill switch ignores the parameter entirely`() = app(wrapped, enabled = false) {
        assertEquals(getBody(plain, base), body("$base&demoPerturbation=true"))
    }

    @Test fun `demoPerturbation=false is the same as absent`() = app(wrapped) {
        assertEquals(getBody(plain, base), body("$base&demoPerturbation=false"))
    }

    @Test fun `an invalid trigger is a 400 with the field`() = app(wrapped) {
        val r = client.get("$base&demoPerturbation=maybe")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val e = parse(r.bodyAsText()).o("error")
        assertEquals("INVALID_REQUEST", e.str("code")); assertEquals("demoPerturbation", e.str("field"))
    }

    // ---- the perturbation itself ---------------------------------------------------------------------

    @Test fun `a drawn perturbation is labelled and the detector reports the delay`() = app(wrapped) {
        val j = parse(body("$base&demoPerturbation=true"))
        val demo = j.o("demoPerturbation")
        assertTrue(demo["active"]!!.jsonPrimitive.boolean)
        assertEquals("DISRUPTION_DETECTED", j.str("status"))
        assertTrue(j.a("reasons").any { it.jsonObject.str("code") == "PRIMARY_LEG_DELAYED" })
        assertTrue(demo.a("applied").isNotEmpty())
        val delay = j.a("reasons").first { it.jsonObject.str("code") == "PRIMARY_LEG_DELAYED" }.jsonObject.str("delayMinutes").toInt()
        assertTrue(delay in 5..45, "detected delay $delay outside 5..45")
    }

    @Test fun `repeating true draws different perturbations`() = app(wrapped) {
        val a = parse(body("$base&demoPerturbation=true")).o("demoPerturbation")
        val b = parse(body("$base&demoPerturbation=true")).o("demoPerturbation")
        assertNotEquals(a.str("seed"), b.str("seed"))
        assertNotEquals(a.a("applied").toString(), b.a("applied").toString())
    }

    @Test fun `a seed reproduces the same perturbation`() = app(wrapped) {
        val first = parse(body("$base&demoPerturbation=12345"))
        val second = parse(body("$base&demoPerturbation=12345"))
        assertEquals(first.o("demoPerturbation").toString(), second.o("demoPerturbation").toString())
        assertEquals(first.str("status"), second.str("status"))
        assertEquals(first.o("demoPerturbation").str("seed"), "12345")
    }

    // ---- provider-level coherence --------------------------------------------------------------------

    private fun mockJourneys(): List<com.alt.backend.domain.Journey> = runBlocking {
        (plain.search(SearchRequest("Lausanne", "Genève", OffsetDateTime.parse("2026-11-21T15:00:00Z"))) as ProviderResult.Success).journeys
    }

    @Test fun `a seed that cancels an alternative drops it and flags its live departure`() {
        val journeys = mockJourneys()
        val (seed, profile) = (0L..2_000L).firstNotNullOf { s -> drawProfile(s, journeys)?.takeIf { it.cancelTarget != null }?.let { s to it } }
        assertNotNull(profile.cancelTarget)
        val state = DemoPerturbationState(seed, randomDraw = false)
        runBlocking {
            withContext(state) {
                val result = wrapped.search(SearchRequest("Lausanne", "Genève", OffsetDateTime.parse("2026-11-21T15:00:00Z"))) as ProviderResult.Success
                // the cancelled journey is no longer offered
                assertTrue(result.journeys.none { j -> j.legs.any { it.mode == LegMode.TRANSIT && profile.cancelTarget!!.matches(it.serviceLabel, it.departure) } })
                val live = wrapped.stopEvents("Lausanne", OffsetDateTime.parse("2026-11-21T14:40:00Z"), realtime = true) as StopEventsResult.Success
                val cancelled = live.events.firstOrNull { it.cancelled }
                assertNotNull(cancelled, "live board must flag the cancelled trip")
                val delayed = live.events.first { !it.cancelled && it.estimated != null && it.estimated.isAfter(it.scheduled) }
                assertEquals(profile.delayMinutes.toLong(), java.time.Duration.between(delayed.scheduled, delayed.estimated).toMinutes())
            }
        }
    }

    @Test fun `a seeded cancellation is visible in the envelope`() = app(wrapped) {
        val journeys = mockJourneys()
        val seed = (0L..2_000L).first { drawProfile(it, journeys)?.cancelTarget != null }
        val j = parse(body("$base&demoPerturbation=$seed"))
        val applied = j.o("demoPerturbation").a("applied").map { it.jsonPrimitive.content }
        assertTrue(applied.any { it.startsWith("cancelled") }, "applied=$applied")
        assertEquals("DISRUPTION_DETECTED", j.str("status"))
    }

    @Test fun `without the parameter boards are untouched`() = runBlocking {
        assertEquals(wrapped.stopEvents("Lausanne", OffsetDateTime.parse("2026-11-21T14:40:00Z"), true).toString(),
            plain.stopEvents("Lausanne", OffsetDateTime.parse("2026-11-21T14:40:00Z"), true).toString())
    }
}
