package com.cffplanb.backend.service

import com.cffplanb.backend.api.configureRoutes
import com.cffplanb.backend.domain.*
import com.cffplanb.backend.provider.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

class RedirectTest {
    private val now = Instant.parse("2026-11-21T15:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private fun t(hm: String) = OffsetDateTime.parse("2026-11-21T${hm}:00Z")

    private fun leg(label: String, from: String, to: String, dep: String, arr: String, plat: String? = null) =
        JourneyLeg(LegMode.TRANSIT, from, to, t(dep), t(arr), label, plat, null)
    private fun walk(dep: String, arr: String) = JourneyLeg(LegMode.WALK, "X", "X", t(dep), t(arr), null, null, null)
    private fun journey(id: String, vararg legs: JourneyLeg) = Journey(id, legs.first().departure, legs.last().arrival,
        java.time.Duration.between(legs.first().departure, legs.last().arrival).toMinutes().toInt(),
        legs.count { it.mode == LegMode.TRANSIT } - 1, legs.toList(), DataStatus.LIVE_SCHEDULE)
    private fun ev(label: String, sched: String, est: String? = null, cancelled: Boolean = false) =
        StopEvent(label, null, t(sched), est?.let { t(it) }, cancelled, null)

    // ---- parsing + classification -------------------------------------------------

    private val fixtureEvents = OjpTransportProvider("k").parseStopEvents(
        javaClass.classLoader.getResourceAsStream("stop_events_fixture.xml")!!.bufferedReader().readText())

    @Test fun `stop event fixture parses on-time delayed cancelled and absent Cancelled element`() {
        assertEquals(4, fixtureEvents.size)
        assertEquals(listOf(false, false, true, false), fixtureEvents.map { it.cancelled })
        assertNull(fixtureEvents[0].estimated)
        assertEquals(22, java.time.Duration.between(fixtureEvents[1].scheduled, fixtureEvents[1].estimated).toMinutes())
        assertEquals("8", fixtureEvents[0].platform)
    }

    @Test fun `classification on fixture`() {
        val clear = classifyLeg(0, leg("IC1", "Lausanne", "Genève", "15:10", "16:00"), fixtureEvents)
        assertSame(LegCheck.Clear, clear)
        val delayed = classifyLeg(0, leg("RE 33", "Lausanne", "Genève", "15:12", "16:00"), fixtureEvents) as LegCheck.Hit
        assertEquals(22, delayed.disruption.delayMinutes); assertEquals(DisruptionBasis.REALTIME_ESTIMATE, delayed.disruption.basis)
        val cancelled = classifyLeg(2, leg("IC 5", "Lausanne", "Genève", "15:20", "16:10"), fixtureEvents) as LegCheck.Hit
        assertTrue(cancelled.disruption.cancelled); assertEquals(DisruptionBasis.CANCELLED_FLAG, cancelled.disruption.basis); assertEquals(0, cancelled.disruption.delayMinutes)
        assertEquals(2, cancelled.disruption.legIndex)
    }

    @Test fun `trip missing from realtime list is low confidence only when the list reaches past it`() {
        val missing = classifyLeg(0, leg("IC 8", "Lausanne", "Genève", "15:30", "16:20"), fixtureEvents) as LegCheck.Hit
        assertEquals(DisruptionBasis.MISSING_FROM_REALTIME, missing.disruption.basis); assertEquals("low", missing.disruption.confidence)
        assertSame(LegCheck.Unknown, classifyLeg(0, leg("IC 8", "Lausanne", "Genève", "17:00", "18:00"), fixtureEvents))
    }

    @Test fun `worst leg wins and legIndex points into the journey legs`() = runBlocking {
        val j = journey("J", leg("S1", "A", "B", "15:05", "15:30"), walk("15:30", "15:35"), leg("IC 5", "B", "C", "15:40", "16:30"), leg("RE33", "C", "D", "16:35", "17:00"))
        val fetch: suspend (String, OffsetDateTime) -> StopEventsResult = { stop, _ -> StopEventsResult.Success(when (stop) {
            "A" -> listOf(ev("S1", "15:05", "15:20"), ev("X", "15:50"))
            "B" -> listOf(ev("IC 5", "15:40", "15:46"), ev("X", "15:50"))
            else -> listOf(ev("RE33", "16:35", cancelled = true), ev("X", "17:00"))
        }) }
        val c = DisruptionDetector(fetch).detect(listOf(j))!![0]
        assertEquals(3, c.count)
        assertEquals(3, c.worst!!.legIndex); assertTrue(c.worst!!.cancelled)
    }

    @Test fun `larger delay beats smaller when nothing is cancelled`() = runBlocking {
        val j = journey("J", leg("S1", "A", "B", "15:05", "15:30"), leg("IC 5", "B", "C", "15:40", "16:30"))
        val fetch: suspend (String, OffsetDateTime) -> StopEventsResult = { stop, _ -> StopEventsResult.Success(
            if (stop == "A") listOf(ev("S1", "15:05", "15:20")) else listOf(ev("IC 5", "15:40", "15:43"))) }
        assertEquals(0, DisruptionDetector(fetch).detect(listOf(j))!![0].worst!!.legIndex)
    }

    @Test fun `provider error and deadline give null so the situation is UNKNOWN`() = runBlocking {
        val j = journey("J", leg("S1", "A", "B", "15:05", "15:30"))
        val failing = DisruptionDetector({ _, _ -> StopEventsResult.Failure("boom") }).detect(listOf(j))!![0]
        assertNull(failing.worst); assertTrue(failing.unknown)
        assertNull(DisruptionDetector({ _, _ -> delay(5_000); StopEventsResult.Success(emptyList()) }, deadlineMs = 50).detect(listOf(j)))
    }

    @Test fun `at most maxQueries distinct stop queries`() = runBlocking {
        var calls = 0
        val js = (1..3).map { i -> journey("J$i", leg("S1", "A$i", "B$i", "15:05", "15:30"), leg("S2", "C$i", "D$i", "15:40", "16:00")) }
        DisruptionDetector({ _, _ -> calls++; StopEventsResult.Success(emptyList()) }, maxQueries = 6).detect(js)
        assertEquals(6, calls)
    }

    // ---- end to end through /api/redirect -----------------------------------------

    private class Fake(val journeys: (SearchRequest) -> List<Journey>, val events: (String) -> StopEventsResult) : TransportProvider {
        val searches = mutableListOf<SearchRequest>()
        override suspend fun search(request: SearchRequest): ProviderResult { searches += request; return ProviderResult.Success(journeys(request)) }
        override suspend fun stopEvents(stop: String, from: OffsetDateTime) = events(stop)
    }

    private val re33 = journey("RE", leg("RE33", "Lausanne", "Genève", "15:10", "16:00", "3"))
    private val ic5 = journey("IC", leg("IC 5", "Lausanne", "Genève", "15:30", "16:15", "8"))
    private val slow = journey("SLOW", leg("S1", "Lausanne", "Renens", "15:20", "15:30"), leg("R", "Renens", "Genève", "15:35", "16:40"))
    private val ir = journey("IR", leg("IR 15", "Lausanne", "Genève", "16:00", "16:50"))
    private val all = listOf(re33, ic5, slow, ir)
    private fun search(r: SearchRequest) = all.filter { !it.departure.isBefore(r.earliestDeparture) }

    private fun api(fake: Fake, qs: String, block: (JsonObject) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(fake), clock)
        }
        val r = client.get("/api/redirect?origin=Lausanne&destination=Gen%C3%A8ve$qs")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        block(Json.parseToJsonElement(r.bodyAsText()).jsonObject)
    }
    private fun JsonObject.str(k: String) = get(k)!!.jsonPrimitive.content
    private fun JsonObject.obj(k: String) = get(k)!!.jsonObject

    private val delayed = { _: String -> StopEventsResult.Success(listOf(ev("RE33", "15:10", "15:32"), ev("IC 5", "15:30"), ev("S1", "15:20"), ev("IR 15", "16:00"))) }
    private val cancelled = { _: String -> StopEventsResult.Success(listOf(ev("RE33", "15:10", cancelled = true), ev("IC 5", "15:30"), ev("S1", "15:20"), ev("IR 15", "16:00"))) }
    private val onTime = { _: String -> StopEventsResult.Success(listOf(ev("RE33", "15:10"), ev("IC 5", "15:30"), ev("S1", "15:20"), ev("IR 15", "16:00"))) }

    @Test fun `no disruption recommends the normal best`() = api(Fake(::search, onTime), "") { j ->
        assertEquals("NO_DISRUPTION", j.str("situation"))
        assertEquals(JsonNull, j["disruption"])
        assertEquals("RE", j.obj("recommendation").str("id")); assertEquals("fastest", j.obj("recommendation").str("why"))
        assertTrue(j.str("message").length <= 200)
    }

    @Test fun `cancelled leg redirects to the next best and excludes the affected service`() = api(Fake(::search, cancelled), "") { j ->
        assertEquals("DISRUPTION_DETECTED", j.str("situation"))
        val d = j.obj("disruption")
        assertEquals("CANCELLED_FLAG", d.str("basis")); assertEquals("true", d.str("cancelled")); assertEquals("0", d.str("legIndex")); assertEquals("RE33", d.str("serviceLabel"))
        assertEquals("IC", j.obj("recommendation").str("id"))
        assertTrue(j["alternatives"]!!.jsonArray.none { it.jsonObject.str("id") == "RE" })
        val m = j.str("message"); assertTrue(m.length <= 200); assertTrue("RE33" in m && "IC 5" in m && "16:30" in m, m) // 15:30Z = 16:30 Zurich
        assertEquals("skips the cancelled RE33", j.obj("recommendation").str("why"))
        assertFalse("Lausanne" in m && "sloid" in m)
    }

    @Test fun `small delay means waiting is faster`() = api(Fake(::search, { _ -> StopEventsResult.Success(listOf(ev("RE33", "15:10", "15:15"), ev("IC 5", "15:30"))) }), "") { j ->
        assertEquals("DISRUPTION_DETECTED", j.str("situation")); assertEquals("5", j.obj("disruption").str("delayMinutes"))
        assertEquals("RE", j.obj("recommendation").str("id"))
        assertEquals("waiting is faster: 5 min delay vs 15 min longer route", j.obj("recommendation").str("why"))
    }

    @Test fun `staying put with an unreachable deadline still says so`() = api(Fake(::search, { _ -> StopEventsResult.Success(listOf(ev("RE33", "15:10", "15:15"), ev("IC 5", "15:30"))) }), "&arrivalDeadline=2026-11-21T15%3A30%3A00Z") { j ->
        assertEquals("true", j.str("noFeasibleRoute"))
        assertTrue(j.str("message").contains("Nothing reaches your 16:30 deadline — staying on it arrives 35 min late"), j.str("message"))
    }

    @Test fun `large delay redirects and why carries the numbers`() = api(Fake(::search, delayed), "&arrivalDeadline=2026-11-21T17%3A00%3A00Z") { j ->
        assertEquals("22", j.obj("disruption").str("delayMinutes")); assertEquals("REALTIME_ESTIMATE", j.obj("disruption").str("basis"))
        assertEquals("IC", j.obj("recommendation").str("id")); assertEquals("45 min", j.obj("recommendation").str("why").substringAfter("arrives ").substringBefore(" before"))
        assertEquals("false", j.str("noFeasibleRoute"))
    }

    @Test fun `SIMPLEST prefers fewest changes`() = api(Fake(::search, cancelled), "&preference=SIMPLEST") { j ->
        assertEquals("IC", j.obj("recommendation").str("id"))
    }

    @Test fun `deadline filter and noFeasibleRoute say so`() = api(Fake(::search, cancelled), "&arrivalDeadline=2026-11-21T16%3A00%3A00Z") { j ->
        assertEquals("true", j.str("noFeasibleRoute"))
        assertTrue(j.str("message").contains("Nothing reaches your 17:00 deadline"), j.str("message"))
        assertTrue(j.str("message").contains("arrives 15 min late"), j.str("message"))
    }

    @Test fun `replacement search starts at the boarding station and uses now as floor`() {
        val f = Fake(::search, cancelled)
        api(f, "") { }
        assertEquals(2, f.searches.size)
        assertEquals("Lausanne", f.searches[1].origin); assertEquals("Genève", f.searches[1].destination)
        assertEquals(now, f.searches[1].earliestDeparture.toInstant())
    }

    @Test fun `provider error gives UNKNOWN with normal journeys`() = api(Fake(::search, { StopEventsResult.Failure("down") }), "") { j ->
        assertEquals("UNKNOWN", j.str("situation")); assertEquals(JsonNull, j["disruption"]); assertEquals("RE", j.obj("recommendation").str("id"))
        assertTrue(j.str("message").length <= 200)
    }

    @Test fun `message never exceeds 200 chars`() = api(Fake({ _ -> listOf(journey("L", leg("A very long service label ".repeat(6), "Lausanne", "Genève", "15:10", "16:00", "3")), ic5) },
        { StopEventsResult.Success(listOf(ev("Averylongservicelabel".repeat(1), "15:10", cancelled = true))) }), "") { j ->
        assertTrue(j.str("message").length <= 200)
    }

    @Test fun `validation matches journeys and the journeys endpoint is unchanged`() = testApplication {
        application { install(ContentNegotiation) { json() }; configureRoutes(JourneySearchService(MockTransportProvider()), clock) }
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/redirect?destination=B").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/redirect?origin=A&destination=B&bogus=1").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/redirect?origin=A&destination=B&preference=X").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/redirect?origin=A&destination=B&fareClass=3").status)
        val r = client.get("/api/redirect?origin=A&destination=B")
        assertEquals("UNKNOWN", Json.parseToJsonElement(r.bodyAsText()).jsonObject.str("situation")) // mock has no realtime
        assertFalse("situation" in client.get("/api/journeys?origin=A&destination=B").bodyAsText())
    }
}
