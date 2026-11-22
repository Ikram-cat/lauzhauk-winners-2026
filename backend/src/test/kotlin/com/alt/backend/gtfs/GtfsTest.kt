package com.alt.backend.gtfs

import com.alt.backend.api.configureRoutes
import com.alt.backend.domain.*
import com.alt.backend.provider.*
import com.alt.backend.service.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

/** Fixture = four real TripUpdate entities cut from the live feed: cancelled 91-7-J 21:26, skipped stop 91-95 21:10, delayed 91-33-C 21:35 and 22:28. */
class GtfsTest {
    private val fixture = GtfsTest::class.java.classLoader.getResourceAsStream("gtfsrt_fixture.pb")!!.readBytes()
    private fun snap() = GtfsDecoder.parse(fixture, 0)
    private fun t(hm: String) = OffsetDateTime.parse("2026-11-21T${hm}:00Z")

    // ---- decoder -------------------------------------------------------------------

    @Test fun `decoder reads cancelled, skipped and delayed trips from the captured fixture`() {
        val s = snap()
        assertEquals(4, s.entities); assertEquals(4, s.trips.size); assertEquals(1, s.cancelled); assertEquals(0, s.added); assertEquals(1, s.skippedStops)
        val c = s.byTripId[".ojp-91-7-J.1.TA.641.j26"]!!
        assertTrue(c.cancelled); assertEquals("91-7-J-j26-1", c.routeId); assertEquals("20261121", c.startDate); assertEquals("21:26:00", c.startTime)
        assertEquals(Instant.parse("2026-11-21T20:26:00Z"), c.startInstant)
        val sk = s.byTripId[".ojp-91-95.1.TA.997.j26"]!!
        assertTrue(sk.stops.first().skipped); assertEquals("ch:1:sloid:1026:2:4", sk.stops.first().stopId); assertFalse(sk.stops[1].skipped)
        val d = s.byTripId[".ojp-91-33-C.1.TA.2038.j26"]!!
        assertEquals(294, d.stops.first { it.stopId == "ch:1:sloid:1200:1:1" }.delaySeconds)
        assertEquals(1764, sk.stops[1].delaySeconds)
        assertEquals(setOf("91-95", "91-7-J", "91-33-C"), s.byRoute.keys)
    }

    @Test fun `decoder fails loudly on garbage and truncation`() {
        assertFailsWith<GtfsParseException> { GtfsDecoder.parse(byteArrayOf(), 0) }
        assertFailsWith<GtfsParseException> { GtfsDecoder.parse(fixture.copyOf(fixture.size / 2), 0) }
        assertFailsWith<GtfsParseException> { GtfsDecoder.parse("<html>403</html>".toByteArray(), 0) }
    }

    /** Ground truth: set GTFS_RT_SAMPLE to a feed dump and GTFS_RT_EXPECT to "entities,cancelled,added,skipped" counted by an independent decoder. */
    @Test fun `full feed counts match an independent decode when a sample is provided`() {
        val f = System.getenv("GTFS_RT_SAMPLE")?.let(::File)?.takeIf { it.exists() } ?: return
        val s = GtfsDecoder.parse(f.readBytes(), 0)
        assertEquals(System.getenv("GTFS_RT_EXPECT"), "${s.entities},${s.cancelled},${s.added},${s.skippedStops}")
    }

    // ---- id normalisation ----------------------------------------------------------

    @Test fun `OJP line refs map onto GTFS route ids`() {
        assertEquals("91-33-C", GtfsMatch.routeKey("ojp:91033:C"))
        assertEquals("91-7-J", GtfsMatch.routeKey("ojp:91007:J"))
        assertEquals("91-1-D", GtfsMatch.routeKey("ojp:91001:D"))
        assertEquals("91-95", GtfsMatch.routeKey("ojp:91095:_"))
        assertEquals("96-260", GtfsMatch.routeKey("ojp:96260:"))
        assertNull(GtfsMatch.routeKey("ojp:920N5:D")); assertNull(GtfsMatch.routeKey("IR90")); assertNull(GtfsMatch.routeKey(null))
        assertEquals("91-33-C", routeBase("91-33-C-j26-1")); assertEquals("91-57", routeBase("91-57-j26-1"))
    }

    @Test fun `stop ids compare by place across sloid and UIC forms`() {
        assertTrue(GtfsMatch.sameStop("ch:1:sloid:1200:1:1", "ch:1:sloid:1200:0:840061"))
        assertTrue(GtfsMatch.sameStop("ch:1:sloid:1026_gen:ch:1:sloid:1026:2:3_pf:3E-H", "ch:1:sloid:1026"))
        assertTrue(GtfsMatch.sameStop("8501120", "ch:1:sloid:1120:0:1"))
        assertFalse(GtfsMatch.sameStop("ch:1:sloid:1200:1:1", "ch:1:sloid:1201:1:1")); assertFalse(GtfsMatch.sameStop("ch:1:sloid:1200", null))
    }

    @Test fun `trip match needs the line and the exact first-stop time, never a guess`() {
        val s = snap()
        assertEquals(".ojp-91-7-J.1.TA.641.j26", GtfsMatch.find(s, "ojp:91007:J", Instant.parse("2026-11-21T20:26:30Z"))!!.tripId)
        assertNull(GtfsMatch.find(s, "ojp:91007:J", Instant.parse("2026-11-21T20:28:00Z")))   // 2 min off
        assertNull(GtfsMatch.find(s, "ojp:91007:K", Instant.parse("2026-11-21T20:26:00Z")))   // other line
        assertNull(GtfsMatch.find(s, "ojp:91007:J", null))
        val twin = s.trips[1].copy(tripId = "twin")
        assertNull(GtfsMatch.find(GtfsSnapshot(0, s.trips + twin, 5, 2, 0, 1), "ojp:91007:J", Instant.parse("2026-11-21T20:26:00Z"))) // ambiguous
    }

    // ---- priority a / b / c / d / e ------------------------------------------------

    private fun ev(label: String, sched: String, line: String?, origin: String, stop: String, est: String? = null, cancelled: Boolean = false) =
        StopEvent(label, line, t(sched), est?.let { t(it) }, cancelled, null, emptySet(), stop, t(origin))
    private fun leg(label: String, from: String, dep: String, arr: String) = JourneyLeg(LegMode.TRANSIT, from, "Genève", t(dep), t(arr), label, "3", null)

    @Test fun `a - cancelled GTFS trip wins, delay is null`() {
        val l = leg("IR7", "Lausanne", "20:40", "21:30")
        val h = classifyLegGtfs(0, l, ev("IR7", "20:40", "ojp:91007:J", "20:26", "ch:1:sloid:1120:0:1"), snap()) as LegCheck.Hit
        assertTrue(h.disruption.cancelled); assertEquals(DisruptionBasis.CANCELLED_FLAG, h.disruption.basis); assertEquals("high", h.disruption.confidence); assertNull(h.disruption.delayMinutes)
        assertEquals(l.departure, h.disruption.leg.departure)
    }

    @Test fun `b - skipped boarding stop is SUPPRESSED_STOP and not a cancellation`() {
        val h = classifyLegGtfs(0, leg("IR95", "Genève Aéroport", "20:10", "21:30"), ev("IR95", "20:10", "ojp:91095:_", "20:10", "ch:1:sloid:1026:2:4"), snap()) as LegCheck.Hit
        assertEquals(DisruptionBasis.SUPPRESSED_STOP, h.disruption.basis); assertFalse(h.disruption.cancelled); assertEquals("high", h.disruption.confidence)
    }

    @Test fun `c - boarding-stop delay of at least 3 min floors to minutes with a timetable-plus-delay estimate`() {
        val l = leg("RE33", "Montreux", "21:20", "22:10")
        val h = classifyLegGtfs(0, l, ev("RE33", "21:20", "ojp:91033:C", "20:35", "ch:1:sloid:1200:0:9"), snap()) as LegCheck.Hit
        assertEquals(4, h.disruption.delayMinutes); assertEquals(DisruptionBasis.REALTIME_ESTIMATE, h.disruption.basis)
        assertEquals(l.departure.plusSeconds(294), h.disruption.estimatedDeparture)
        // 54 s at Lausanne is below the threshold: GTFS says the train is fine
        assertIs<LegCheck.Clear>(classifyLegGtfs(0, l, ev("RE33", "21:20", "ojp:91033:C", "20:35", "ch:1:sloid:1120:0:840061"), snap()))
    }

    @Test fun `delays under 3 minutes are not a disruption`() {
        fun trip(delay: Int) = GtfsTrip(".x", "91-33-C-j26-1", "20261121", "21:35:00", false, false, listOf(GtfsStopUpdate("ch:1:sloid:1200:1:1", delay, false)))
        val l = leg("RE33", "Montreux", "21:20", "22:10"); val p = ev("RE33", "21:20", "ojp:91033:C", "20:35", "ch:1:sloid:1200:0:9")
        for (d in listOf(60, 119, 179)) assertEquals(LegCheck.Clear(d / 60, true), classifyLegGtfs(0, l, p, GtfsSnapshot(0, listOf(trip(d)), 1, 0, 0, 0)))
        assertEquals(3, ((classifyLegGtfs(0, l, p, GtfsSnapshot(0, listOf(trip(180)), 1, 0, 0, 0))) as LegCheck.Hit).disruption.delayMinutes)
    }

    @Test fun `cancelled trips are matched from route and start time alone, they have no stop updates`() {
        val s = snap()
        assertTrue(s.byTripId[".ojp-91-7-J.1.TA.641.j26"]!!.stops.isEmpty())          // as in the raw feed
        // the planned event knows nothing about stops (no stopRef) and the leg is at a stop GTFS never mentions
        val e = StopEvent("IR7", "ojp:91007:J", t("20:40"), null, false, null, emptySet(), null, t("20:26"))
        val h = classifyLegGtfs(0, leg("IR7", "Anywhere", "20:40", "21:30"), e, s) as LegCheck.Hit
        assertTrue(h.disruption.cancelled); assertEquals(DisruptionBasis.CANCELLED_FLAG, h.disruption.basis)
        // wrong start date: no match
        assertNull(classifyLegGtfs(0, leg("IR7", "Anywhere", "20:40", "21:30"), e.copy(originDeparture = t("20:26").minusDays(1)), s))
    }

    @Test fun `d - no confident GTFS match gives null so OJP takes over`() {
        val l = leg("RE33", "Montreux", "21:20", "22:10")
        assertNull(classifyLegGtfs(0, l, ev("RE33", "21:20", "ojp:91033:C", "20:50", "ch:1:sloid:1200:0:9"), snap()))   // no trip starting then
        assertNull(classifyLegGtfs(0, l, ev("RE33", "21:20", "ojp:91033:C", "20:35", "ch:1:sloid:9999:0:9"), snap()))   // stop not in the trip
    }

    private val jr = journey("J", leg("IR7", "Lausanne", "20:40", "21:30"))
    private fun journey(id: String, vararg legs: JourneyLeg) = Journey(id, legs.first().departure, legs.last().arrival, 50, legs.size - 1, legs.toList(), DataStatus.LIVE_SCHEDULE)
    private fun boards(plan: List<StopEvent>, live: List<StopEvent>): suspend (String, OffsetDateTime, Boolean) -> StopEventsResult = { _, _, rt -> StopEventsResult.Success(if (rt) live else plan) }

    @Test fun `priority - GTFS cancelled beats an on-time OJP board, OJP is the fallback, nothing known is UNKNOWN`() = runBlocking {
        val planned = ev("IR7", "20:40", "ojp:91007:J", "20:26", "ch:1:sloid:1120:0:1")
        val liveOk = planned.copy(estimated = planned.scheduled)
        val s = snap()
        // a) GTFS says cancelled although OJP's live board still lists the train as on time
        val a = DisruptionDetector(boards(listOf(planned), listOf(liveOk)), gtfs = { s }).detect(listOf(jr), t("20:00"))!![0]
        assertEquals(DisruptionBasis.CANCELLED_FLAG, a.worst!!.basis); assertTrue(a.worst!!.cancelled)
        // d) a train GTFS does not know -> OJP planned-vs-live diff decides (delayed by 7)
        val other = planned.copy(lineRef = "ojp:91099:Z", label = "IR7")
        val d = DisruptionDetector(boards(listOf(other), listOf(other.copy(estimated = t("20:47")))), gtfs = { s }).detect(listOf(jr), t("20:00"))!![0]
        assertEquals(7, d.worst!!.delayMinutes); assertEquals(DisruptionBasis.REALTIME_ESTIMATE, d.worst!!.basis)
        // e) no GTFS, no OJP -> unknown, never "all good"
        val e = DisruptionDetector({ _, _, _ -> StopEventsResult.Failure("down") }, gtfs = { null }).detect(listOf(jr), t("20:00"))!![0]
        assertNull(e.worst); assertTrue(e.unknown)
        // GTFS answers even when OJP's live board fails
        val g = DisruptionDetector({ _, _, rt -> if (rt) StopEventsResult.Failure("x") else StopEventsResult.Success(listOf(planned)) }, gtfs = { s }).detect(listOf(jr), t("20:00"))!![0]
        assertTrue(g.worst!!.cancelled)
    }

    // ---- source: cache, stale, rate-limit guard ------------------------------------

    @Test fun `source caches 60 s, serves stale on failure for 5 min and never fetches twice within 60 s`() = runBlocking {
        var now = 0L; var calls = 0; var failing = false
        val src = GtfsRealtimeSource({ calls++; if (failing) error("boom") else fixture }, { now }, CoroutineScope(Dispatchers.Default))
        assertNotNull(src.current()); assertEquals(1, calls)
        now = 30_000; assertNotNull(src.current()); src.settle(); assertEquals(1, calls)          // fresh: no network
        now = 61_000; assertNotNull(src.current()); src.settle(); assertEquals(2, calls)          // expired: served at once, refreshed once
        failing = true; now = 125_000; assertNotNull(src.current()); src.settle(); assertEquals(3, calls) // refresh fails, stale still usable
        now = 130_000; assertNotNull(src.current()); src.settle(); assertEquals(3, calls)         // guard: <60 s since the failed attempt
        assertEquals(69, src.ageSeconds())
        now = 400_000; assertNull(src.current()); assertEquals(4, calls)                           // older than 5 min and refresh fails: unusable
    }

    @Test fun `concurrent callers share one in-flight fetch`() = runBlocking {
        var calls = 0
        val src = GtfsRealtimeSource({ calls++; kotlinx.coroutines.delay(100); fixture }, { 0L }, CoroutineScope(Dispatchers.Default))
        (1..5).map { async { src.current() } }.forEach { assertNotNull(it.await()) }
        assertEquals(1, calls)
    }

    @Test fun `resilient provider forwards the realtime flag and caches the two boards separately`() = runBlocking {
        val seen = mutableListOf<Boolean>()
        val p = ResilientProvider(object : TransportProvider {
            override suspend fun search(request: SearchRequest): ProviderResult = ProviderResult.Success(emptyList())
            override suspend fun stopEvents(stop: String, from: OffsetDateTime, realtime: Boolean): StopEventsResult { seen += realtime; return StopEventsResult.Success(emptyList()) }
        })
        p.stopEvents("A", t("20:00"), false); p.stopEvents("A", t("20:00"), true); p.stopEvents("A", t("20:00"), false)
        assertEquals(listOf(false, true), seen)
    }

    // ---- endpoints -----------------------------------------------------------------

    private val clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)
    private class Fake(val journeys: List<Journey>, val plan: List<StopEvent>, val live: List<StopEvent>) : TransportProvider {
        override suspend fun search(request: SearchRequest): ProviderResult = ProviderResult.Success(journeys.filter { !it.departure.isBefore(request.earliestDeparture) })
        override suspend fun stopEvents(stop: String, from: OffsetDateTime, realtime: Boolean) = StopEventsResult.Success(if (realtime) live else plan)
    }

    @Test fun `journeys reroute on the GTFS cancellation, health shows the counters`() = testApplication {
        val alt = journey("ALT", leg("IC 5", "Lausanne", "20:50", "21:40"))
        val planned = ev("IR7", "20:40", "ojp:91007:J", "20:26", "ch:1:sloid:1120:0:1")
        val altEv = ev("IC 5", "20:50", "ojp:91005:A", "20:50", "ch:1:sloid:1120:0:2")
        val src = GtfsRealtimeSource({ fixture })
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(Fake(listOf(jr, alt), listOf(planned, altEv), listOf(planned.copy(estimated = planned.scheduled), altEv.copy(estimated = altEv.scheduled)))), clock, gtfs = src)
        }
        val j = Json.parseToJsonElement(client.get("/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve").bodyAsText()).jsonObject
        assertEquals("DISRUPTION_DETECTED", j["status"]!!.jsonPrimitive.content); assertEquals("REROUTE", j["action"]!!.jsonPrimitive.content)
        val d = j["disruption"]!!.jsonObject
        assertEquals("CANCELLED_FLAG", d["basis"]!!.jsonPrimitive.content); assertEquals("true", d["cancelled"]!!.jsonPrimitive.content); assertEquals(JsonNull, d["delayMinutes"])
        assertEquals("HIGH", d["confidence"]!!.jsonPrimitive.content)
        assertEquals("J", j["primaryJourney"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("ALT", j["recommendedJourney"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("PRIMARY_LEG_CANCELLED", j["reasons"]!!.jsonArray[0].jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(j["alternatives"]!!.jsonArray.none { it.jsonObject["id"]!!.jsonPrimitive.content == "J" }) // the cancelled trip is dropped
        val g = Json.parseToJsonElement(client.get("/health").bodyAsText()).jsonObject["feeds"]!!.jsonObject["gtfsRt"]!!.jsonObject
        assertEquals("true", g["enabled"]!!.jsonPrimitive.content); assertEquals("OK", g["status"]!!.jsonPrimitive.content)
        assertEquals("4", g["activeTripUpdates"]!!.jsonPrimitive.content); assertEquals("1", g["cancelledTrips"]!!.jsonPrimitive.content)
        assertNotEquals(JsonNull, g["ageSeconds"])
    }
}
