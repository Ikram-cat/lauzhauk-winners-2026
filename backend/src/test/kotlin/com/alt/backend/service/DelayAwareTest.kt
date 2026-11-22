package com.alt.backend.service

import com.alt.backend.api.configureRoutes
import com.alt.backend.domain.*
import com.alt.backend.gtfs.*
import com.alt.backend.provider.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

/** Zurich is UTC+1 on the test day, so 15:xxZ legs fall in local hour 16, which the fixture covers. */
class DelayAwareTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)
    private fun t(hm: String) = OffsetDateTime.parse("2026-11-21T${hm}:00Z")
    private val model = RealtimeDelayModel(Path.of(javaClass.classLoader.getResource("realtime_delays_fixture.json")!!.toURI()))
    private fun leg(label: String, from: String, to: String, dep: String, arr: String) = JourneyLeg(LegMode.TRANSIT, from, to, t(dep), t(arr), label, null, null)
    private fun journey(id: String, vararg legs: JourneyLeg) = Journey(id, legs.first().departure, legs.last().arrival,
        java.time.Duration.between(legs.first().departure, legs.last().arrival).toMinutes().toInt(), legs.size - 1, legs.toList(), DataStatus.LIVE_SCHEDULE)

    @Test fun `artifact parsing, line beats station, thresholds, clamp and cap`() {
        assertEquals(ExpectedDelay(7.0, 40, "line"), model.expectedDelayMinutes("Lausanne", "IR 90", 16))
        assertEquals(ExpectedDelay(0.3, 120, "station"), model.expectedDelayMinutes("Lausanne", "IC 5", 16)) // line row has 9 samples: ignored
        assertEquals("none", model.expectedDelayMinutes("Lausanne", "IR 90", 17).source) // station row has 5 samples
        assertEquals("none", model.expectedDelayMinutes("Lausanne", "IR 90", 3).source) // missing hour
        assertEquals("none", model.expectedDelayMinutes("Nowhere", "IR 90", 16).source) // missing station
        assertEquals(0.0, model.expectedDelayMinutes("Zürich HB", null, 16).minutes) // negative median -> 0
        assertEquals(30.0, model.expectedDelayMinutes("Bern", null, 16).minutes) // capped
        assertEquals("ok", model.info().status); assertEquals(5, model.info().portions); assertEquals(2, model.info().lines); assertEquals("fixture-delays-1", model.info().modelVersion)
    }

    @Test fun `missing artifact leaves everything unknown`() {
        val m = RealtimeDelayModel(Path.of("/nonexistent/delays.json"))
        assertEquals("missing", m.info().status)
        assertEquals("none", m.expectedDelayMinutes("Lausanne", "IR 90", 16).source)
        assertNull(m.forJourney(journey("J", leg("IR 90", "Lausanne", "Genève", "15:10", "16:00"))))
    }

    @Test fun `journey delay is its worst transit leg`() {
        val j = journey("J", leg("S1", "Lausanne", "Renens", "15:10", "15:20"), leg("IR 90", "Renens", "Genève", "15:25", "16:00"), leg("X", "Genève", "Y", "16:05", "16:30"))
        // leg1 Lausanne station 0.3, leg2 Renens station 4.0, leg3 Genève unknown
        assertEquals(JourneyDelay(4, 30, "station"), model.forJourney(j))
        assertEquals(JourneyDelay(7, 40, "line"), model.forJourney(journey("K", leg("IR 90", "Lausanne", "Genève", "15:10", "16:00"), leg("S1", "Lausanne", "Renens", "15:10", "15:20"))))
    }

    private class Fake(val js: List<Journey>) : TransportProvider {
        override suspend fun search(request: SearchRequest): ProviderResult = ProviderResult.Success(js)
        override suspend fun stopEvents(stop: String, from: OffsetDateTime, realtime: Boolean) = StopEventsResult.Failure("n/a")
    }
    private val early = journey("EARLY", leg("IR 90", "Lausanne", "Genève", "15:10", "16:00")) // usually ~7 min late
    private fun later(arr: String) = journey("LATE", leg("IC 5", "Lausanne", "Genève", "15:20", arr)) // station row: on time

    private fun api(js: List<Journey>, m: RealtimeDelayModel, path: String = "/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve", block: (JsonObject) -> Unit) = testApplication {
        application { install(ContentNegotiation) { json(Json { encodeDefaults = true }) }; configureRoutes(JourneySearchService(Fake(js)), clock, delay = m) }
        block(Json.parseToJsonElement(client.get(path).bodyAsText()).jsonObject)
    }
    private fun JsonObject.str(k: String) = get(k)!!.jsonPrimitive.content
    private fun JsonObject.obj(k: String) = get(k)!!.jsonObject

    @Test fun `statistical delay is reported as DELAY_MODEL realtime without touching the schedule`() = api(listOf(early), model) { j ->
        val p = j.obj("primaryJourney"); val rt = p.obj("realtime")
        assertEquals("DELAY_MODEL", rt.str("source")); assertEquals("7", rt.str("arrivalDelayMinutes")); assertEquals("40", rt.str("sampleCount"))
        assertEquals("2026-11-21T16:07:00Z", rt.str("expectedArrival"))
        assertEquals(JsonNull, rt["expectedDeparture"]); assertEquals(JsonNull, rt["departureDelayMinutes"]) // the model only knows arrivals
        assertEquals("2026-11-21T16:00:00Z", p.obj("schedule").str("arrival"))
    }

    @Test fun `the detector reported UNKNOWN so the model stays out of status but still fills realtime`() = api(listOf(early), model) { j ->
        assertEquals("UNKNOWN", j.str("status")); assertEquals("STAY", j.str("action"))
    }

    @Test fun `no delay data means source NONE with nulls`() = api(listOf(early), RealtimeDelayModel(null)) { j ->
        val rt = j.obj("primaryJourney").obj("realtime")
        assertEquals("NONE", rt.str("source")); assertEquals(JsonNull, rt["expectedArrival"]); assertEquals(JsonNull, rt["arrivalDelayMinutes"]); assertEquals(JsonNull, rt["sampleCount"])
    }

    @Test fun `alternatives carry their own delay-model realtime`() = api(listOf(early, later("16:30")), model, "/api/journeys?origin=A&destination=B&preference=SIMPLEST") { j ->
        val all = listOf(j.obj("primaryJourney")) + j["alternatives"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("EARLY", "LATE"), all.map { it.str("id") }.toSet())
        assertEquals(setOf("7", "0"), all.map { it.obj("realtime").str("arrivalDelayMinutes") }.toSet())
    }

    @Test fun `model endpoint reports the delay artifact`() = testApplication {
        application { install(ContentNegotiation) { json(Json { encodeDefaults = true }) }; configureRoutes(JourneySearchService(Fake(listOf(early))), clock, delay = model) }
        val d = Json.parseToJsonElement(client.get("/api/model").bodyAsText()).jsonObject.obj("delayModel")
        assertEquals("fixture-delays-1", d.str("version")); assertEquals("OK", d.str("status")); assertEquals("5", d.obj("counts").str("portions")); assertEquals("2", d.obj("counts").str("lines"))
    }
}
