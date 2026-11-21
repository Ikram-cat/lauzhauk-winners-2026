package com.cffplanb.backend.service

import com.cffplanb.backend.api.configureRoutes
import com.cffplanb.backend.domain.*
import com.cffplanb.backend.gtfs.*
import com.cffplanb.backend.provider.*
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

    private fun api(js: List<Journey>, m: RealtimeDelayModel, path: String = "/api/redirect?origin=Lausanne&destination=Gen%C3%A8ve&includeText=true", block: (JsonObject) -> Unit) = testApplication {
        application { install(ContentNegotiation) { json(Json { encodeDefaults = true }) }; configureRoutes(JourneySearchService(Fake(js)), clock, delay = m) }
        block(Json.parseToJsonElement(client.get(path).bodyAsText()).jsonObject)
    }
    private fun JsonObject.str(k: String) = get(k)!!.jsonPrimitive.content
    private fun JsonObject.obj(k: String) = get(k)!!.jsonObject

    @Test fun `expected arrival switches the recommendation and why says so`() = api(listOf(early, later("16:06")), model) { j ->
        val r = j.obj("recommendation")
        assertEquals("LATE", r.str("id"))
        assertEquals("arrives 6 min later on paper but is usually on time, while the earlier one runs ~7 min late here", r.str("why"))
        assertEquals("2026-11-21T16:06:00Z", r.str("expectedArrival")); assertEquals("0", r.str("expectedDelayMin")); assertEquals("station", r.str("delaySource")); assertEquals("120", r.str("delaySamples"))
        val alt = j["alternatives"]!!.jsonArray[0].jsonObject
        assertEquals("2026-11-21T16:07:00Z", alt.str("expectedArrival")); assertEquals("7", alt.str("expectedDelayMin")); assertEquals("line", alt.str("delaySource"))
        assertEquals("2026-11-21T16:00:00Z", alt.str("arrival")) // scheduled values untouched
    }

    @Test fun `tie tolerance of 2 min prefers fewer delay-prone legs, beyond it the earlier expected arrival wins`() {
        api(listOf(early, later("16:08")), model) { assertEquals("LATE", it.obj("recommendation").str("id")) }   // 16:07 vs 16:08: tie, LATE has no prone leg
        api(listOf(early, later("16:11")), model) { assertEquals("EARLY", it.obj("recommendation").str("id")) }  // 16:07 vs 16:11: not a tie
    }

    @Test fun `no delay data behaves exactly as before`() {
        api(listOf(early, later("16:06")), RealtimeDelayModel(null)) { j ->
            val r = j.obj("recommendation")
            assertEquals("EARLY", r.str("id")); assertEquals("fastest", r.str("why"))
            assertEquals(JsonNull, r["expectedDelayMin"]); assertEquals("none", r.str("delaySource")); assertEquals(JsonNull, r["delaySamples"])
            assertEquals(r.str("arrival"), r.str("expectedArrival"))
        }
    }

    @Test fun `journeys endpoint and model endpoint carry the delay fields`() = testApplication {
        application { install(ContentNegotiation) { json(Json { encodeDefaults = true }) }; configureRoutes(JourneySearchService(Fake(listOf(early))), clock, delay = model) }
        val j = Json.parseToJsonElement(client.get("/api/journeys?origin=A&destination=B").bodyAsText()).jsonObject["journeys"]!!.jsonArray[0].jsonObject
        assertEquals("7", j.str("expectedDelayMin")); assertEquals("2026-11-21T16:00:00Z", j.str("arrival")); assertEquals("2026-11-21T16:07:00Z", j.str("expectedArrival"))
        val m = Json.parseToJsonElement(client.get("/api/model").bodyAsText()).jsonObject
        assertEquals("fixture-delays-1", m.str("delayModelVersion")); assertEquals("5", m.str("delayPortions")); assertEquals("2", m.str("delayLines")); assertEquals("ok", m.str("delayArtifactStatus"))
    }

    // ---- incident chain reaction ----

    private fun trip(start: String, cancelled: Boolean = false, delaySec: Int? = null) =
        GtfsTrip("t$start", "91-33-C-j26-1", "20261121", start, cancelled, false, listOf(GtfsStopUpdate("8501120", delaySec, false)))
    private fun snap(vararg t: GtfsTrip) = GtfsSnapshot(0, t.toList(), t.size, t.count { it.cancelled }, 0, 0)
    private val planned = StopEvent("IC 5", "ojp:91033:C", t("15:20"), null, false, null, originDeparture = t("15:00"))

    @Test fun `upstream incident needs an earlier cancelled or late trip on the same route and direction`() {
        assertTrue(upstreamIncident(snap(trip("15:45:00", cancelled = true)), planned)!!.contains("cancelled")) // 15:45 local = 14:45Z, 15 min earlier
        assertTrue(upstreamIncident(snap(trip("15:45:00", delaySec = 480)), planned)!!.contains("8 min late"))
        assertNull(upstreamIncident(snap(trip("15:45:00", delaySec = 60)), planned))  // small delay
        assertNull(upstreamIncident(snap(trip("16:30:00", cancelled = true)), planned)) // later trip
        assertNull(upstreamIncident(snap(trip("13:00:00", cancelled = true)), planned)) // more than 90 min earlier
        assertNull(upstreamIncident(snap(trip("15:45:00", cancelled = true)), planned.copy(lineRef = "ojp:91033:_"))) // no direction known
    }
}
