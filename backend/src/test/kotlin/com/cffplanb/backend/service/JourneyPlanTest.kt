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

class JourneyPlanTest {
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
        assertSame(LegCheck.Unknown, classifyLeg(0, leg("IC1", "Lausanne", "Genève", "15:10", "16:00"), plan, fixtureEvents)) // on-time event without estimate
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
        assertEquals(LegCheck.Clear(0), classifyLeg(0, leg("ICE71", "Olten", "Zürich HB", "15:29", "16:00"), p, listOf(ev("ICE 71", "15:30", "15:30"))))
    }

    @Test fun `OJP delays under 3 minutes are clear, 3 minutes is a disruption, no estimate is unknown`() {
        val l = leg("IC 8", "A", "B", "15:40", "16:30"); val p = plan(ev("IC 8", "15:40"))
        assertEquals(LegCheck.Clear(2), classifyLeg(0, l, p, listOf(ev("IC 8", "15:40", "15:42"))))
        assertEquals(3, (classifyLeg(0, l, p, listOf(ev("IC 8", "15:40", "15:43"))) as LegCheck.Hit).disruption.delayMinutes)
        assertSame(LegCheck.Unknown, classifyLeg(0, l, p, listOf(ev("IC 8", "15:40"))))
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

    // ---- end to end through /api/journeys -------------------------------------------

    private class Fake(val journeys: (SearchRequest) -> List<Journey>, val plan: List<StopEvent>, val live: List<StopEvent>) : TransportProvider {
        val searches = mutableListOf<SearchRequest>()
        override suspend fun search(request: SearchRequest): ProviderResult { searches += request; return ProviderResult.Success(journeys(request)) }
        override suspend fun stopEvents(stop: String, from: OffsetDateTime, realtime: Boolean) = StopEventsResult.Success(if (realtime) live else plan)
    }
    private class Down(val journeys: (SearchRequest) -> List<Journey>, val boom: Boolean = false) : TransportProvider {
        override suspend fun search(request: SearchRequest): ProviderResult = ProviderResult.Success(journeys(request))
        override suspend fun stopEvents(stop: String, from: OffsetDateTime, realtime: Boolean): StopEventsResult = if (boom) throw IllegalStateException("boom") else StopEventsResult.Failure("down")
    }

    private val re33 = journey("RE", leg("RE33", "Lausanne", "Genève", "15:10", "16:00", "3"))
    private val ic5 = journey("IC", leg("IC 5", "Lausanne", "Genève", "15:30", "16:15", "8"))
    private val slow = journey("SLOW", leg("S1", "Lausanne", "Renens", "15:20", "15:30"), leg("R", "Renens", "Genève", "15:35", "16:40"))
    private val ir = journey("IR", leg("IR 15", "Lausanne", "Genève", "16:00", "16:50"))
    private val all = listOf(re33, ic5, slow, ir)
    private fun search(r: SearchRequest) = all.filter { !it.departure.isBefore(r.earliestDeparture) }

    private fun api(f: TransportProvider, qs: String = "", block: (JsonObject) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(f), clock)
        }
        val r = client.get("/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve$qs")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        block(Json.parseToJsonElement(r.bodyAsText()).jsonObject)
    }
    private fun JsonObject.str(k: String) = get(k)!!.jsonPrimitive.content
    private fun JsonObject.obj(k: String) = get(k)!!.jsonObject
    private fun JsonObject.int(k: String) = get(k)!!.jsonPrimitive.int
    private fun JsonObject.codes() = get("reasons")!!.jsonArray.map { it.jsonObject.str("code") }
    private fun JsonObject.reason(code: String) = get("reasons")!!.jsonArray.map { it.jsonObject }.first { it.str("code") == code }
    private fun JsonObject.altIds() = get("alternatives")!!.jsonArray.map { it.jsonObject.str("id") }

    private val others = arrayOf(ev("IC 5", "15:30"), ev("S1", "15:20"), ev("IR 15", "16:00"))
    private fun fake(re33Live: StopEvent?) = Fake(::search, plan(ev("RE33", "15:10"), *others), listOfNotNull(re33Live) + others.map { it.copy(estimated = it.scheduled) })
    private fun onTime() = fake(ev("RE33", "15:10", "15:10"))
    private fun cancelled() = fake(null) // OJP omits cancelled trips from the live board
    private fun late(min: Int) = fake(ev("RE33", "15:10", "15:${10 + min}"))

    @Test fun `no disruption stays, with measured zeros as explicit zeros`() = api(onTime()) { j ->
        assertEquals("NO_DISRUPTION", j.str("status")); assertEquals("STAY", j.str("action")); assertEquals(JsonNull, j["disruption"])
        assertEquals("RE", j.obj("recommendedJourney").str("id")); assertEquals(j.obj("primaryJourney"), j.obj("recommendedJourney"))
        assertEquals(listOf("STAY_FASTEST"), j.codes())
        val rt = j.obj("primaryJourney").obj("realtime")
        assertEquals("OJP", rt.str("source")); assertEquals(0, rt.int("departureDelayMinutes")); assertEquals(0, rt.int("arrivalDelayMinutes"))
        assertEquals("2026-11-21T16:00:00Z", rt.str("expectedArrival"))
    }

    @Test fun `a 1 minute delay is no disruption but the measured delay is reported`() = api(late(1)) { j ->
        assertEquals("NO_DISRUPTION", j.str("status")); assertEquals(JsonNull, j["disruption"]); assertEquals("STAY", j.str("action"))
        val p = j.obj("primaryJourney")
        assertEquals(1, p.obj("realtime").int("departureDelayMinutes")); assertEquals(1, p.obj("realtime").int("arrivalDelayMinutes"))
        assertEquals("2026-11-21T16:01:00Z", p.obj("realtime").str("expectedArrival"))
        assertEquals("2026-11-21T16:00:00Z", p.obj("schedule").str("arrival")) // schedule is never mutated
        assertEquals(1, p.get("legs")!!.jsonArray[0].jsonObject.int("departureDelayMinutes"))
    }

    @Test fun `cancelled leg reroutes, found by the plan-vs-live diff`() = api(cancelled()) { j ->
        assertEquals("DISRUPTION_DETECTED", j.str("status")); assertEquals("REROUTE", j.str("action"))
        val d = j.obj("disruption")
        assertEquals("CANCELLED_FLAG", d.str("basis")); assertEquals("true", d.str("cancelled")); assertEquals(JsonNull, d["delayMinutes"]); assertEquals("HIGH", d.str("confidence")); assertEquals(JsonNull, d["cause"])
        assertEquals(0, d.int("legIndex")); assertEquals("RE33", d.str("serviceLabel")); assertEquals("2026-11-21T15:10:00Z", d.str("scheduledDeparture"))
        assertEquals("Lausanne", d.str("from")); assertEquals("Genève", d.str("to"))
        assertEquals("RE", j.obj("primaryJourney").str("id")); assertEquals("IC", j.obj("recommendedJourney").str("id"))
        assertEquals("PRIMARY_LEG_CANCELLED", j.codes()[0]); assertEquals("RE33", j.reason("PRIMARY_LEG_CANCELLED").str("serviceLabel"))
        assertFalse("RE" in j.altIds()); assertFalse("IC" in j.altIds())
    }

    @Test fun `cancelled reroutes whatever the preference, and even when nothing gains`() {
        api(cancelled(), "&preference=SIMPLEST") { j -> assertEquals("IC", j.obj("recommendedJourney").str("id")); assertEquals("REROUTE", j.str("action")) }
        api(cancelled(), "&preference=CHEAPEST") { j -> assertEquals("REROUTE", j.str("action")) }
    }

    @Test fun `Cancelled flag on a live event reroutes`() = api(fake(ev("RE33", "15:10", cancelled = true))) { j ->
        assertEquals("CANCELLED_FLAG", j.obj("disruption").str("basis")); assertEquals("IC", j.obj("recommendedJourney").str("id"))
    }

    @Test fun `delayed primary stays when no replacement saves 5 minutes, recommended equals primary`() {
        // RE33 +5 arrives 16:05; IC arrives 16:15
        api(late(5)) { j ->
            assertEquals("DISRUPTION_DETECTED", j.str("status")); assertEquals("STAY", j.str("action"))
            assertEquals("REALTIME_ESTIMATE", j.obj("disruption").str("basis")); assertEquals(5, j.obj("disruption").int("delayMinutes"))
            assertEquals("2026-11-21T15:15:00Z", j.obj("disruption").str("estimatedDeparture"))
            assertEquals(j.obj("primaryJourney"), j.obj("recommendedJourney"))
            assertEquals("PRIMARY_LEG_DELAYED", j.codes()[0]); assertEquals(5, j.reason("PRIMARY_LEG_DELAYED").int("delayMinutes")); assertEquals("RE33", j.reason("PRIMARY_LEG_DELAYED").str("serviceLabel"))
            assertTrue("STAY_FASTEST" in j.codes())
            val rt = j.obj("primaryJourney").obj("realtime")
            assertEquals("2026-11-21T15:15:00Z", rt.str("expectedDeparture")); assertEquals(5, rt.int("departureDelayMinutes"))
            assertEquals(5, rt.int("arrivalDelayMinutes")); assertEquals("2026-11-21T16:05:00Z", rt.str("expectedArrival")); assertEquals("OJP", rt.str("source"))
        }
    }

    @Test fun `replacement must arrive at least 5 minutes earlier than the delayed primary's expected arrival`() {
        fun f(alt: String, delay: Int): Fake {
            val orig = journey("RE", leg("RE33", "Lausanne", "Genève", "15:10", "16:00", "3"))
            val a = journey("EC", leg("EC", "Lausanne", "Genève", "15:20", alt, "8"))
            return Fake({ listOf(orig, a) }, plan(ev("RE33", "15:10"), ev("EC", "15:20")), listOf(ev("RE33", "15:10", "15:${10 + delay}"), ev("EC", "15:20", "15:20")))
        }
        api(f("16:06", 10)) { j -> assertEquals("STAY", j.str("action")); assertEquals("RE", j.obj("recommendedJourney").str("id")) } // expected 16:10 vs 16:06: gain 4
        api(f("16:05", 10)) { j ->
            assertEquals("REROUTE", j.str("action")); assertEquals("EC", j.obj("recommendedJourney").str("id")) // gain exactly 5
            assertEquals(5, j.reason("EARLIER_ARRIVAL").int("savedMinutes"))
            assertEquals("RE", j.obj("primaryJourney").str("id")); assertTrue("RE" in j.altIds()) // the delayed primary stays visible as an alternative
        }
        api(f("16:15", 12)) { j -> assertEquals("STAY", j.str("action")) } // alt later than the delayed primary
    }

    @Test fun `big gain reroutes and reports savedMinutes, FEWER_TRANSFERS and the deadline margin`() {
        val orig = journey("RE", leg("S1", "Lausanne", "Renens", "15:10", "15:20", "3"), leg("RE33", "Renens", "Genève", "15:25", "16:00"))
        val a = journey("EC", leg("EC", "Lausanne", "Genève", "15:35", "16:10", "8"))
        val f = Fake({ listOf(orig, a) }, plan(ev("S1", "15:10"), ev("EC", "15:35")), listOf(ev("S1", "15:10", "15:40"), ev("EC", "15:35", "15:35")))
        api(f, "&arrivalDeadline=2026-11-21T17%3A00%3A00Z") { j ->
            assertEquals("REROUTE", j.str("action")); assertEquals("EC", j.obj("recommendedJourney").str("id"))
            assertEquals(20, j.reason("EARLIER_ARRIVAL").int("savedMinutes")) // delayed primary expects 16:30, EC arrives 16:10
            assertEquals(1, j.reason("FEWER_TRANSFERS").int("savedTransfers"))
            assertEquals(50, j.reason("DEADLINE_MET").int("marginMinutes"))
            assertEquals(true, j.obj("deadlineSummary")["feasible"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun `deadline reasons say missed when nothing reaches it`() = api(cancelled(), "&arrivalDeadline=2026-11-21T16%3A00%3A00Z") { j ->
        assertEquals("DEADLINE_MISSED", j.codes().last()); assertEquals(15, j.reason("DEADLINE_MISSED").int("marginMinutes"))
        assertEquals(false, j.obj("deadlineSummary")["feasible"]!!.jsonPrimitive.boolean); assertEquals(JsonNull, j.obj("deadlineSummary")["latestFeasibleDeparture"])
        assertEquals(false, j.obj("recommendedJourney")["feasible"]!!.jsonPrimitive.boolean)
    }

    @Test fun `cancelled train that the planner dropped is reported but the planned journey is kept`() {
        val s26 = journey("S26", leg("S26", "Olten", "Zürich HB", "15:37", "16:20", "4"))
        val ice = StopEvent("ICE 71", null, t("15:29"), null, false, null, setOf("ZÜRICHHB"))
        val s = StopEvent("S26", null, t("15:37"), null, false, null, setOf("ZÜRICHHB"))
        val f = Fake({ listOf(s26) }, listOf(ice, s), listOf(s.copy(estimated = t("15:37"))))
        api(f, "&departure=2026-11-21T15%3A20%3A00Z") { j ->
            assertEquals("DISRUPTION_DETECTED", j.str("status")); assertEquals("STAY", j.str("action"))
            assertEquals("ICE 71", j.obj("disruption").str("serviceLabel")); assertEquals("true", j.obj("disruption").str("cancelled"))
            assertEquals("2026-11-21T15:29:00Z", j.obj("disruption").str("scheduledDeparture"))
            assertEquals("S26", j.obj("recommendedJourney").str("id")); assertEquals("EARLIER_DEPARTURE_CANCELLED", j.codes()[0])
            assertEquals(1, f.searches.size) // own journey is fine: no replacement search
        }
    }

    @Test fun `replacement search starts at the boarding station and uses now as floor`() {
        val f = cancelled()
        api(f) { }
        assertEquals(2, f.searches.size)
        assertEquals("Lausanne", f.searches[1].origin); assertEquals("Genève", f.searches[1].destination)
        assertEquals(now, f.searches[1].earliestDeparture.toInstant())
    }

    @Test fun `replacement ids that collide with original ids stay distinct`() {
        val a = journey("ID-1", leg("RE33", "Lausanne", "Genève", "15:10", "16:00", "3"))
        val b = journey("ID-1", leg("IC 5", "Lausanne", "Genève", "15:30", "16:15", "8")) // same id, different trip
        var n = 0
        val f = Fake({ _ -> if (n++ == 0) listOf(a) else listOf(b) }, plan(ev("RE33", "15:10"), ev("IC 5", "15:30")), listOf(ev("IC 5", "15:30", "15:30")))
        api(f, "&departure=2026-11-21T15%3A00%3A00Z") { j ->
            assertEquals("REROUTE", j.str("action"))
            assertEquals("ID-1~r", j.obj("recommendedJourney").str("id")); assertEquals("ID-1", j.obj("primaryJourney").str("id"))
        }
    }

    @Test fun `provider error gives UNKNOWN, STAY and scheduled journeys, never NO_DISRUPTION`() = api(Down(::search)) { j ->
        assertEquals("UNKNOWN", j.str("status")); assertEquals("STAY", j.str("action")); assertEquals(JsonNull, j["disruption"])
        assertEquals("RE", j.obj("recommendedJourney").str("id")); assertEquals(listOf("REALTIME_UNAVAILABLE", "STAY_FASTEST"), j.codes())
        assertEquals("NONE", j.obj("primaryJourney").obj("realtime").str("source"))
    }

    @Test fun `detection that throws is UNKNOWN with every realtime field null`() {
        val withEst = journey("RE", JourneyLeg(LegMode.TRANSIT, "Lausanne", "Genève", t("15:10"), t("16:00"), "RE33", "3", null, expectedDeparture = t("15:14"), expectedArrival = t("16:04")))
        api(Down({ listOf(withEst) }, boom = true)) { j ->
            assertEquals("UNKNOWN", j.str("status")); assertEquals("STAY", j.str("action"))
            val p = j.obj("primaryJourney"); val rt = p.obj("realtime")
            assertEquals("NONE", rt.str("source"))
            for (k in listOf("expectedDeparture", "departureDelayMinutes", "expectedArrival", "arrivalDelayMinutes")) assertEquals(JsonNull, rt[k], k)
            assertEquals(JsonNull, p.get("legs")!!.jsonArray[0].jsonObject["arrivalDelayMinutes"])
        }
    }

    @Test fun `OJP estimates map to leg and journey delays even when the board is unreachable`() {
        val a = JourneyLeg(LegMode.TRANSIT, "Lausanne", "Renens", t("15:10"), t("15:20"), "S1", "3", "1", expectedDeparture = t("15:12"), expectedArrival = t("15:23"))
        val w = JourneyLeg(LegMode.WALK, "Renens", "Renens", t("15:23"), t("15:26"), null, null, null)
        val b = JourneyLeg(LegMode.TRANSIT, "Renens", "Genève", t("15:30"), t("16:00"), "RE33", "2", null, expectedDeparture = null, expectedArrival = t("16:07"))
        api(Down({ listOf(journey("J", a, w, b)) })) { j ->
            val p = j.obj("primaryJourney"); val legs = p.get("legs")!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(2, 3), listOf(legs[0].int("departureDelayMinutes"), legs[0].int("arrivalDelayMinutes")))
            assertEquals(JsonNull, legs[1]["departureDelayMinutes"]); assertEquals(JsonNull, legs[1]["occupancy"]) // walk leg
            assertEquals(JsonNull, legs[2]["departureDelayMinutes"]); assertEquals(7, legs[2].int("arrivalDelayMinutes"))
            val rt = p.obj("realtime")
            assertEquals("OJP", rt.str("source")); assertEquals(2, rt.int("departureDelayMinutes")); assertEquals(7, rt.int("arrivalDelayMinutes"))
            assertEquals("2026-11-21T16:07:00Z", rt.str("expectedArrival")); assertEquals("2026-11-21T16:00:00Z", p.obj("schedule").str("arrival"))
            assertEquals("UNKNOWN", j.str("status")) // estimates are not a verified verdict
        }
    }

    @Test fun `occupancy is passed through per leg and maxOccupancy follows the requested class`() {
        fun l(dep: String, arr: String, f: String?, s: String?) = JourneyLeg(LegMode.TRANSIT, "A", "B", t(dep), t(arr), "IC", null, null, occupancyFirst = f, occupancySecond = s)
        val j = journey("J", l("15:10", "15:30", "LOW", "MEDIUM"), walk("15:30", "15:35"), l("15:40", "16:00", "MEDIUM", "HIGH"), l("16:05", "16:30", null, null))
        fun run(q: String, check: (JsonObject) -> Unit) = api(Down({ listOf(j) }), q) { check(it.obj("primaryJourney")) }
        run("") { p -> // second class
            assertEquals("HIGH", p.str("maxOccupancy"))
            val legs = p.get("legs")!!.jsonArray.map { it.jsonObject }
            assertEquals("LOW", legs[0].obj("occupancy").str("firstClass")); assertEquals("MEDIUM", legs[0].obj("occupancy").str("secondClass"))
            assertEquals(JsonNull, legs[1]["occupancy"])
            assertEquals(JsonNull, legs[3].obj("occupancy")["firstClass"]); assertEquals(JsonNull, legs[3].obj("occupancy")["secondClass"])
        }
        run("&fareClass=1") { p -> assertEquals("MEDIUM", p.str("maxOccupancy")) }
        val unknown = journey("U", l("15:10", "15:30", null, null))
        api(Down({ listOf(unknown) })) { it -> assertEquals(JsonNull, it.obj("primaryJourney")["maxOccupancy"]) }
    }

    @Test fun `risk is structured with a coarse level`() = testApplication {
        val risk = object : RiskEstimator {
            override fun estimate(journey: Journey) = RiskEstimate(null, 0.0412, "corridor", "high", "Lausanne - Genève", 145, "corridor_duration")
            override fun info() = ModelInfo("v", null, null, 0, "ok")
        }
        application { install(ContentNegotiation) { json(Json { encodeDefaults = true }) }; configureRoutes(JourneySearchService(Down(::search)), clock, risk) }
        val r = Json.parseToJsonElement(client.get("/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve").bodyAsText()).jsonObject.obj("primaryJourney").obj("risk")
        assertEquals(0.0412, r["corridorIncidentRate"]!!.jsonPrimitive.double); assertEquals(145, r.int("typicalIncidentDurationMinutes"))
        assertEquals("CORRIDOR", r.str("source")); assertEquals("HIGH", r.str("confidence")); assertEquals("MEDIUM", r.str("level"))
        assertEquals("DAY_WINDOW", r.str("basis")) // no coverage window in this stub
    }
}
