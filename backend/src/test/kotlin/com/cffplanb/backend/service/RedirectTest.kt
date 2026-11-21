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

    @Test fun `onward calls are parsed into normalised stop names`() {
        val xml = """<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri"><StopEventResult><StopEvent><ThisCall><CallAtStop><ServiceDeparture><TimetabledTime>2026-11-21T20:29:00Z</TimetabledTime></ServiceDeparture></CallAtStop></ThisCall>
          <OnwardCall><CallAtStop><StopPointName><Text>Zürich HB</Text></StopPointName></CallAtStop></OnwardCall>
          <Service><PublishedServiceName><Text>ICE 71</Text></PublishedServiceName></Service></StopEvent></StopEventResult></OJP>"""
        assertEquals(setOf("ZÜRICHHB"), OjpTransportProvider("k").parseStopEvents(xml).single().onward)
    }

    private fun plan(vararg e: StopEvent) = e.map { it.copy(estimated = null, cancelled = false) }

    @Test fun `classification on fixture`() {
        val plan = plan(*fixtureEvents.toTypedArray())
        assertEquals(DisruptionBasis.MISSING_ESTIMATE, (classifyLeg(0, leg("IC1", "Lausanne", "Genève", "15:10", "16:00"), plan, fixtureEvents) as LegCheck.Hit).disruption.basis)
        val delayed = classifyLeg(0, leg("RE 33", "Lausanne", "Genève", "15:12", "16:00"), plan, fixtureEvents) as LegCheck.Hit
        assertEquals(22, delayed.disruption.delayMinutes); assertEquals(DisruptionBasis.REALTIME_ESTIMATE, delayed.disruption.basis)
        val cancelled = classifyLeg(2, leg("IC 5", "Lausanne", "Genève", "15:20", "16:10"), plan, fixtureEvents) as LegCheck.Hit
        assertTrue(cancelled.disruption.cancelled); assertEquals(DisruptionBasis.CANCELLED_FLAG, cancelled.disruption.basis); assertNull(cancelled.disruption.delayMinutes)
        assertEquals(2, cancelled.disruption.legIndex)
    }

    @Test fun `planned trip absent from the live board is cancelled`() {
        val p = plan(ev("ICE 71", "15:29"), ev("IC 8", "15:40"))
        val live = listOf(ev("IC 8", "15:40"))
        val h = classifyLeg(0, leg("ICE71", "Olten", "Zürich HB", "15:29", "16:00"), p, live) as LegCheck.Hit
        assertTrue(h.disruption.cancelled); assertEquals(DisruptionBasis.CANCELLED_FLAG, h.disruption.basis); assertEquals("high", h.disruption.confidence); assertNull(h.disruption.delayMinutes)
        assertEquals(t("15:29"), h.disruption.leg.departure)
        // a live trip 3 min away from the plan is not a counterpart (window is 2 min)
        assertTrue(classifyLeg(0, leg("ICE71", "Olten", "Zürich HB", "15:29", "16:00"), p, listOf(ev("ICE 71", "15:32"))) is LegCheck.Hit)
        assertSame(LegCheck.Clear, classifyLeg(0, leg("ICE71", "Olten", "Zürich HB", "15:29", "16:00"), p, listOf(ev("ICE 71", "15:30", "15:30"))))
    }

    @Test fun `live trip without an estimate is MISSING_ESTIMATE with unknown delay`() {
        val h = classifyLeg(0, leg("IC 8", "A", "B", "15:40", "16:30"), plan(ev("IC 8", "15:40")), listOf(ev("IC 8", "15:40"))) as LegCheck.Hit
        assertEquals(DisruptionBasis.MISSING_ESTIMATE, h.disruption.basis); assertEquals("medium", h.disruption.confidence); assertNull(h.disruption.delayMinutes); assertFalse(h.disruption.cancelled)
    }

    @Test fun `leg not on the timetable board or an empty live board is Unknown`() {
        assertSame(LegCheck.Unknown, classifyLeg(0, leg("IC 8", "A", "B", "17:00", "18:00"), plan(ev("IC 8", "15:40")), listOf(ev("IC 8", "15:40", "15:40"))))
        assertSame(LegCheck.Unknown, classifyLeg(0, leg("IC 8", "A", "B", "15:40", "18:00"), plan(ev("IC 8", "15:40")), emptyList()))
    }

    @Test fun `cancelled train that the planner dropped is found when it calls at the destination`() {
        val ice = StopEvent("ICE 71", null, t("15:29"), null, false, null, setOf("ZÜRICHHB"))
        val other = StopEvent("IC 9", null, t("15:31"), null, false, null, setOf("BERN"))   // not going to Zürich
        val s26 = StopEvent("S26", null, t("15:37"), null, false, null, setOf("ZÜRICHHB"))
        val live = listOf(other.copy(estimated = t("15:31")), s26.copy(estimated = t("15:37")))
        val leg = leg("S26", "Olten", "Zürich HB", "15:37", "16:20")
        val d = cancelledEarlier(0, leg, t("15:20"), listOf(ice, other, s26), live)!!
        assertFalse(d.affectsJourney); assertTrue(d.cancelled); assertEquals("ICE 71", d.leg.serviceLabel); assertEquals(t("15:29"), d.leg.departure); assertNull(d.delayMinutes)
        assertNull(cancelledEarlier(0, leg, t("15:30"), listOf(ice, other, s26), live)) // before the traveller's floor
        assertNull(cancelledEarlier(0, leg, t("15:20"), listOf(ice, other, s26), live + ice.copy(estimated = t("15:29")))) // still running
    }

    @Test fun `cancelled direct train is found via the journey's final destination`() {
        val ice = StopEvent("RE 1", null, t("15:29"), null, false, null, setOf("HERISAU"))
        val first = leg("S1", "St. Gallen", "Wil", "15:37", "15:55")
        assertEquals("Herisau, Wiesental", cancelledEarlier(0, first, t("15:20"), listOf(ice), listOf(ice.copy(label = "S1", scheduled = t("15:37"))), finalDestination = "Herisau, Wiesental")!!.leg.destination)
        assertNull(cancelledEarlier(0, first, t("15:20"), listOf(ice), listOf(ice.copy(label = "S1", scheduled = t("15:37")))))
    }

    @Test fun `phantom cancellation is reported but the planned journey stays recommended`() {
        val s26 = journey("S26", leg("S26", "Olten", "Zürich HB", "15:37", "16:20", "4"))
        val ice = StopEvent("ICE 71", null, t("15:29"), null, false, null, setOf("ZÜRICHHB"))
        val s = StopEvent("S26", null, t("15:37"), null, false, null, setOf("ZÜRICHHB"))
        val f = Fake({ listOf(s26) }, listOf(ice, s), listOf(s.copy(estimated = t("15:37"))))
        api(f, "&departure=2026-11-21T15%3A20%3A00Z") { j ->
            assertEquals("DISRUPTION_DETECTED", j.str("situation"))
            assertEquals("ICE 71", j.obj("disruption").str("serviceLabel")); assertEquals("true", j.obj("disruption").str("cancelled"))
            assertEquals("2026-11-21T15:29:00Z", j.obj("disruption").str("scheduledDeparture"))
            assertEquals("S26", j.obj("recommendation").str("id")); assertEquals("next departure after the cancelled ICE 71, 8 min later", j.obj("recommendation").str("why"))
            assertTrue(j.str("message").startsWith("The 16:29 ICE 71 is cancelled — take S26 at 16:37 from platform 4"), j.str("message"))
            assertEquals(1, f.searches.size) // own journey is fine: no replacement search
        }
    }

    private val boards: (Map<String, StopEventsResult>, Map<String, StopEventsResult>) -> suspend (String, OffsetDateTime, Boolean) -> StopEventsResult =
        { p, l -> { stop, _, rt -> (if (rt) l else p).getValue(stop) } }
    private fun ok(vararg e: StopEvent) = StopEventsResult.Success(e.toList())

    @Test fun `worst leg wins and legIndex points into the journey legs`() = runBlocking {
        val j = journey("J", leg("S1", "A", "B", "15:05", "15:30"), walk("15:30", "15:35"), leg("IC 5", "B", "C", "15:40", "16:30"), leg("RE33", "C", "D", "16:35", "17:00"))
        val p = mapOf("A" to ok(ev("S1", "15:05")), "B" to ok(ev("IC 5", "15:40")), "C" to ok(ev("RE33", "16:35"), ev("X", "17:00")))
        val l = mapOf("A" to ok(ev("S1", "15:05", "15:20")), "B" to ok(ev("IC 5", "15:40", "15:46")), "C" to ok(ev("X", "17:00", "17:00")))
        val c = DisruptionDetector(boards(p, l)).detect(listOf(j), t("15:00"))!![0]
        assertEquals(3, c.count)
        assertEquals(3, c.worst!!.legIndex); assertTrue(c.worst!!.cancelled)
    }

    @Test fun `larger delay beats smaller when nothing is cancelled`() = runBlocking {
        val j = journey("J", leg("S1", "A", "B", "15:05", "15:30"), leg("IC 5", "B", "C", "15:40", "16:30"))
        val p = mapOf("A" to ok(ev("S1", "15:05")), "B" to ok(ev("IC 5", "15:40")))
        val l = mapOf("A" to ok(ev("S1", "15:05", "15:20")), "B" to ok(ev("IC 5", "15:40", "15:43")))
        assertEquals(0, DisruptionDetector(boards(p, l)).detect(listOf(j), t("15:00"))!![0].worst!!.legIndex)
    }

    @Test fun `provider error and deadline give UNKNOWN`() = runBlocking {
        val j = journey("J", leg("S1", "A", "B", "15:05", "15:30"))
        val failing = DisruptionDetector({ _, _, _ -> StopEventsResult.Failure("boom") }).detect(listOf(j), t("15:00"))!![0]
        assertNull(failing.worst); assertTrue(failing.unknown)
        assertNull(DisruptionDetector({ _, _, _ -> delay(5_000); StopEventsResult.Success(emptyList()) }, deadlineMs = 50).detect(listOf(j), t("15:00")))
    }

    @Test fun `each leg fetches plan and live, deduped per stop and window`() = runBlocking {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
        val js = (1..2).map { i -> journey("J$i", leg("S1", "A$i", "B$i", "15:05", "15:30"), leg("S2", "A$i", "D$i", "15:40", "16:00")) }
        DisruptionDetector({ s, _, rt -> calls += s to rt; StopEventsResult.Success(emptyList()) }).detect(js, t("15:00"))
        assertEquals(8, calls.size) // 2 journeys x (A$i plan+live, A$i again for the 15:40 leg is a different 5-min window)
        assertEquals(4, calls.count { it.second })
    }

    // ---- end to end through /api/redirect -----------------------------------------

    private class Fake(val journeys: (SearchRequest) -> List<Journey>, val plan: List<StopEvent>, val live: List<StopEvent>) : TransportProvider {
        val searches = mutableListOf<SearchRequest>()
        override suspend fun search(request: SearchRequest): ProviderResult { searches += request; return ProviderResult.Success(journeys(request)) }
        override suspend fun stopEvents(stop: String, from: OffsetDateTime, realtime: Boolean) = StopEventsResult.Success(if (realtime) live else plan)
    }
    private class Down(val journeys: (SearchRequest) -> List<Journey>) : TransportProvider {
        override suspend fun search(request: SearchRequest): ProviderResult = ProviderResult.Success(journeys(request))
        override suspend fun stopEvents(stop: String, from: OffsetDateTime, realtime: Boolean) = StopEventsResult.Failure("down")
    }

    private val re33 = journey("RE", leg("RE33", "Lausanne", "Genève", "15:10", "16:00", "3"))
    private val ic5 = journey("IC", leg("IC 5", "Lausanne", "Genève", "15:30", "16:15", "8"))
    private val slow = journey("SLOW", leg("S1", "Lausanne", "Renens", "15:20", "15:30"), leg("R", "Renens", "Genève", "15:35", "16:40"))
    private val ir = journey("IR", leg("IR 15", "Lausanne", "Genève", "16:00", "16:50"))
    private val all = listOf(re33, ic5, slow, ir)
    private fun search(r: SearchRequest) = all.filter { !it.departure.isBefore(r.earliestDeparture) }

    private fun api(f: TransportProvider, qs: String, block: (JsonObject) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(f), clock)
        }
        val r = client.get("/api/redirect?origin=Lausanne&destination=Gen%C3%A8ve$qs")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        block(Json.parseToJsonElement(r.bodyAsText()).jsonObject)
    }
    private fun JsonObject.str(k: String) = get(k)!!.jsonPrimitive.content
    private fun JsonObject.obj(k: String) = get(k)!!.jsonObject

    private val others = arrayOf(ev("IC 5", "15:30"), ev("S1", "15:20"), ev("IR 15", "16:00"))
    private fun fake(re33Live: StopEvent?) = Fake(::search, plan(ev("RE33", "15:10"), *others), listOfNotNull(re33Live) + others.map { it.copy(estimated = it.scheduled) })
    private fun onTime() = fake(ev("RE33", "15:10", "15:10"))
    private fun cancelled() = fake(null) // OJP omits cancelled trips from the live board
    private fun late(min: Int) = fake(ev("RE33", "15:10", "15:${10 + min}"))

    @Test fun `no disruption recommends the normal best`() = api(onTime(), "") { j ->
        assertEquals("NO_DISRUPTION", j.str("situation"))
        assertEquals(JsonNull, j["disruption"])
        assertEquals("RE", j.obj("recommendation").str("id")); assertEquals("fastest", j.obj("recommendation").str("why"))
        assertTrue(j.str("message").length <= 200)
    }

    @Test fun `cancelled leg always reroutes, found by the plan-vs-live diff`() = api(cancelled(), "") { j ->
        assertEquals("DISRUPTION_DETECTED", j.str("situation"))
        val d = j.obj("disruption")
        assertEquals("CANCELLED_FLAG", d.str("basis")); assertEquals("true", d.str("cancelled")); assertEquals(JsonNull, d["delayMinutes"]); assertEquals("high", d.str("confidence"))
        assertEquals("0", d.str("legIndex")); assertEquals("RE33", d.str("serviceLabel")); assertEquals("2026-11-21T15:10:00Z", d.str("scheduledDeparture"))
        assertEquals("IC", j.obj("recommendation").str("id"))
        assertTrue(j["alternatives"]!!.jsonArray.none { it.jsonObject.str("id") == "RE" })
        val m = j.str("message"); assertTrue(m.length <= 200); assertTrue("RE33" in m && "IC 5" in m && "16:30" in m, m) // 15:30Z = 16:30 Zurich
        assertEquals("skips the cancelled RE33", j.obj("recommendation").str("why"))
        assertFalse("sloid" in m)
    }

    @Test fun `cancelled even when the alternative gains nothing`() {
        // RE33 15:10-16:00 cancelled; the only other option arrives later -> still rerouted
        api(cancelled(), "&preference=SIMPLEST") { j -> assertEquals("IC", j.obj("recommendation").str("id")) }
    }

    @Test fun `Cancelled flag on a live event reroutes`() = api(fake(ev("RE33", "15:10", cancelled = true)), "") { j ->
        assertEquals("CANCELLED_FLAG", j.obj("disruption").str("basis")); assertEquals("IC", j.obj("recommendation").str("id"))
    }

    @Test fun `tiny delay stays, with the no-meaningful-gain reason`() {
        // RE33 +5 arrives 16:05; IC arrives 16:15 -> alternative is later, wait wins on speed
        api(late(5), "") { j ->
            assertEquals("DISRUPTION_DETECTED", j.str("situation")); assertEquals("5", j.obj("disruption").str("delayMinutes"))
            assertEquals("RE", j.obj("recommendation").str("id"))
        }
        // Montreux case: alternative arrives only 1 min earlier than the delayed original -> stay
        val orig = journey("RE", leg("RE33", "Lausanne", "Genève", "15:10", "16:27", "3"))
        val alt = journey("EC", leg("EC", "Lausanne", "Genève", "15:30", "16:31", "8"))
        val f = Fake({ listOf(orig, alt) }, plan(ev("RE33", "15:10"), ev("EC", "15:30")), listOf(ev("RE33", "15:10", "15:15"), ev("EC", "15:30", "15:30")))
        api(f, "") { j ->
            assertEquals("RE", j.obj("recommendation").str("id"))
            assertEquals("5 min late, no meaningful gain from changing", j.obj("recommendation").str("why"))
            assertTrue("stay" in j.str("message"), j.str("message"))
        }
    }

    @Test fun `delay of 10 min or less stays when the gain is only 5 min, reroutes beyond`() {
        fun f(alt: String, delay: Int): Fake {
            val orig = journey("RE", leg("RE33", "Lausanne", "Genève", "15:10", "16:00", "3"))
            val a = journey("EC", leg("EC", "Lausanne", "Genève", "15:20", alt, "8"))
            return Fake({ listOf(orig, a) }, plan(ev("RE33", "15:10"), ev("EC", "15:20")), listOf(ev("RE33", "15:10", "15:${10 + delay}"), ev("EC", "15:20", "15:20")))
        }
        api(f("16:05", 10), "") { assertEquals("RE", it.obj("recommendation").str("id")) } // wait arrives 16:10, gain exactly 5, delay <= 10
        api(f("16:04", 10), "") { assertEquals("EC", it.obj("recommendation").str("id")) } // gain 6
        api(f("16:15", 12), "") { assertEquals("RE", it.obj("recommendation").str("id")) } // wait arrives 16:12; alt is later
    }

    @Test fun `gain of at least 5 min reroutes and why carries the numbers`() {
        val orig = journey("RE", leg("RE33", "Lausanne", "Genève", "15:10", "16:00", "3"))
        val a = journey("EC", leg("EC", "Lausanne", "Genève", "15:35", "16:10", "8"))
        val f = Fake({ listOf(orig, a) }, plan(ev("RE33", "15:10"), ev("EC", "15:35")), listOf(ev("RE33", "15:10", "15:40"), ev("EC", "15:35", "15:35")))
        api(f, "&arrivalDeadline=2026-11-21T17%3A00%3A00Z") { j ->
            assertEquals("EC", j.obj("recommendation").str("id")); assertEquals("arrives 50 min before your deadline", j.obj("recommendation").str("why"))
            assertEquals("false", j.str("noFeasibleRoute"))
        }
        api(f, "") { j -> assertEquals("20 min faster than waiting", j.obj("recommendation").str("why")) }
    }

    @Test fun `delayed original that misses the deadline reroutes even for a small gain`() {
        val orig = journey("RE", leg("RE33", "Lausanne", "Genève", "15:10", "16:00", "3"))
        val a = journey("EC", leg("EC", "Lausanne", "Genève", "15:20", "16:03", "8"))
        val f = Fake({ listOf(orig, a) }, plan(ev("RE33", "15:10"), ev("EC", "15:20")), listOf(ev("RE33", "15:10", "15:14"), ev("EC", "15:20", "15:20")))
        api(f, "&arrivalDeadline=2026-11-21T16%3A03%3A00Z") { assertEquals("EC", it.obj("recommendation").str("id")) } // wait arrives 16:04 > 16:03
    }

    @Test fun `SIMPLEST prefers fewest changes`() = api(cancelled(), "&preference=SIMPLEST") { j ->
        assertEquals("IC", j.obj("recommendation").str("id"))
    }

    @Test fun `deadline filter and noFeasibleRoute say so`() = api(cancelled(), "&arrivalDeadline=2026-11-21T16%3A00%3A00Z") { j ->
        assertEquals("true", j.str("noFeasibleRoute"))
        assertTrue(j.str("message").contains("Nothing reaches your 17:00 deadline"), j.str("message"))
        assertTrue(j.str("message").contains("arrives 15 min late"), j.str("message"))
    }

    @Test fun `staying put with an unreachable deadline still says so`() = api(late(5), "&arrivalDeadline=2026-11-21T15%3A30%3A00Z") { j ->
        assertEquals("true", j.str("noFeasibleRoute"))
        assertTrue(j.str("message").contains("Nothing reaches your 16:30 deadline — staying on it arrives 35 min late"), j.str("message"))
    }

    @Test fun `replacement search starts at the boarding station and uses now as floor`() {
        val f = cancelled()
        api(f, "") { }
        assertEquals(2, f.searches.size)
        assertEquals("Lausanne", f.searches[1].origin); assertEquals("Genève", f.searches[1].destination)
        assertEquals(now, f.searches[1].earliestDeparture.toInstant())
    }

    @Test fun `provider error gives UNKNOWN with normal journeys`() = api(Down(::search), "") { j ->
        assertEquals("UNKNOWN", j.str("situation")); assertEquals(JsonNull, j["disruption"]); assertEquals("RE", j.obj("recommendation").str("id"))
        assertTrue(j.str("message").length <= 200)
    }

    @Test fun `message never exceeds 200 chars`() {
        val long = "A very long service label ".repeat(6)
        val l = journey("L", leg(long, "Lausanne", "Genève", "15:10", "16:00", "3"))
        api(Fake({ listOf(l, ic5) }, plan(ev(long, "15:10"), ev("IC 5", "15:30")), listOf(ev("IC 5", "15:30", "15:30"))), "") { assertTrue(it.str("message").length <= 200) }
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
