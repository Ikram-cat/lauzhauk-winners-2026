package com.alt.backend.api

import com.alt.backend.domain.*
import com.alt.backend.perturbation.PerturbationProvider
import com.alt.backend.provider.*
import com.alt.backend.service.JourneySearchService
import com.alt.backend.service.estimate.DelayEstimateModel
import com.alt.backend.service.impact.DisruptionSource
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

/**
 * Transfer-miss risk end to end (docs/API_CONTRACT.md, transfer-miss risk): a predicted delay that probably
 * breaks the next connection marks the journey with `transferMissRisk` >= 0.5 and the recommendation switches
 * to a safer alternative with `TRANSFER_MISS_RISK` — without the usual 5-minute arrival-gain floor. The risky
 * journey stays listed among the alternatives.
 */
class TransferMissRiskRouteTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)
    private fun t(hm: String) = OffsetDateTime.parse("2026-11-21T${hm}:00Z")
    private val estimates = DelayEstimateModel(Path.of(javaClass.classLoader.getResource("delay_estimates_fixture.json")!!.toURI()))

    private fun leg(
        label: String?, from: String, to: String, dep: String, arr: String,
        mode: LegMode = LegMode.TRANSIT, expectedArrival: OffsetDateTime? = null
    ) = JourneyLeg(mode, from, to, t(dep), t(arr), label, null, null, null, null, emptyList(), null, expectedArrival)

    private fun journey(id: String, vararg legs: JourneyLeg) = Journey(
        id, legs.first().departure, legs.last().arrival,
        Duration.between(legs.first().departure, legs.last().arrival).toMinutes().toInt(),
        legs.count { it.mode == LegMode.TRANSIT } - 1, legs.toList(), DataStatus.LIVE_SCHEDULE
    )

    /** IR 90 Lausanne -> Genève (SAT, local hour 17 arrival: median 2, p90 5) with a 5-minute connection to Bern. */
    private fun risky() = journey(
        "RISKY",
        leg("IR 90", "Lausanne", "Genève", "15:10", "16:00"),
        leg("IC 5", "Genève", "Bern", "16:05", "17:00"))

    /** The same connection with 45 minutes of slack: comfortably safe. */
    private fun safe() = journey(
        "SAFE",
        leg("IR 90", "Lausanne", "Genève", "15:10", "16:00"),
        leg("IC 5", "Genève", "Bern", "16:45", "17:35"))

    /** Timetable boards are on time, so detection is live and reports no delay. */
    private class Fake(private val js: List<Journey>) : TransportProvider {
        override suspend fun search(request: SearchRequest): ProviderResult = ProviderResult.Success(js)
        override suspend fun stopEvents(stop: String, from: OffsetDateTime, realtime: Boolean): StopEventsResult =
            StopEventsResult.Success(js.flatMap { j -> j.legs.filter { it.mode == LegMode.TRANSIT }.map { l ->
                StopEvent(l.serviceLabel ?: "", null, l.departure, if (realtime) l.departure else null, false, null)
            } })
    }

    /** An ACTIVE incident on IR 90: the conditioned bucket answers (median 8, p90 14 at Genève). */
    private class Fixed(val d: List<ActiveDisruption>) : DisruptionSource {
        override val dataStatus = "MOCK"
        override fun active(at: OffsetDateTime) = d
    }

    private val incident = ActiveDisruption("inc-transfer", "synthetic", null, listOf(
        AffectedStation(8501120, "ch:1:sloid:1120", "Lausanne", listOf("DISRUPTION_POINT")),
        AffectedStation(8501008, "ch:1:sloid:1008", "Genève", listOf("LINE_TO"))), listOf("IR 90"), "MOCK")

    private fun body(
        js: List<Journey>, model: DelayEstimateModel,
        source: DisruptionSource = com.alt.backend.service.impact.EmptyDisruptionSource
    ): JsonObject {
        lateinit var out: JsonObject
        testApplication {
            application {
                install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
                configureRoutes(JourneySearchService(Fake(js)), clock, estimates = model, disruptionSource = source)
            }
            out = Json.parseToJsonElement(client.get("/api/journeys?origin=Lausanne&destination=Bern").bodyAsText()).jsonObject
        }
        return out
    }

    private fun JsonObject.str(k: String) = get(k)!!.jsonPrimitive.content
    private fun JsonObject.obj(k: String) = get(k)!!.jsonObject
    private fun JsonObject.num(k: String) = get(k)!!.jsonPrimitive.double
    private fun JsonObject.reasons() = get("reasons")!!.jsonArray.map { it.jsonObject }

    @Test fun `an incident-conditioned delay that breaks the connection reroutes to the safe alternative`() {
        val j = body(listOf(risky(), safe()), estimates, Fixed(listOf(incident)))
        val p = j.obj("primaryJourney"); val rt = p.obj("realtime")
        assertEquals("RISKY", p.str("id"))
        assertEquals(1.0, rt.num("transferMissRisk"), absoluteTolerance = 1e-9) // median 8 > slack 5
        assertEquals("CONDITIONED_INCIDENT", rt.str("transferRiskBasis"))
        val detail = rt["transferRiskDetail"]!!.jsonArray.single().jsonObject
        assertEquals("0", detail.str("fromLeg")); assertEquals("1", detail.str("toLeg")); assertEquals("5", detail.str("slackMinutes"))

        val reason = j.reasons().single { it.str("code") == "TRANSFER_MISS_RISK" }
        assertEquals("3", reason.str("delayMinutes")) // conditioned median 8 minus 5 minutes of slack
        assertEquals("REROUTE", j.str("action"))
        assertEquals("SAFE", j.obj("recommendedJourney").str("id"))
        assertEquals(0.1, j.obj("recommendedJourney").obj("realtime").num("transferMissRisk"), absoluteTolerance = 1e-9)

        // nothing is hidden: the risky journey keeps its risk on the alternatives list
        val alt = j["alternatives"]!!.jsonArray.map { it.jsonObject }.single { it.str("id") == "RISKY" }
        assertEquals(1.0, alt.obj("realtime").num("transferMissRisk"), absoluteTolerance = 1e-9)
    }

    @Test fun `a known live overrun past the slack is a sure miss with the overrun in the reason`() {
        val known = journey(
            "KNOWN",
            leg("IR 90", "Lausanne", "Genève", "15:10", "16:00", expectedArrival = t("16:12")),
            leg("IC 5", "Genève", "Bern", "16:05", "17:00"))
        val j = body(listOf(known, safe()), estimates)
        val rt = j.obj("primaryJourney").obj("realtime")
        assertEquals(1.0, rt.num("transferMissRisk"), absoluteTolerance = 1e-9)
        assertEquals("KNOWN", rt.str("transferRiskBasis"))
        assertEquals("7", j.reasons().single { it.str("code") == "TRANSFER_MISS_RISK" }.str("delayMinutes"))
        assertEquals("SAFE", j.obj("recommendedJourney").str("id"))
    }

    @Test fun `a walk between the trains shortens the slack by its duration`() {
        val walked = journey(
            "WALKED",
            leg("IR 90", "Lausanne", "Genève", "15:10", "16:00"),
            leg(null, "Genève", "Genève, gare routière", "16:00", "16:10", mode = LegMode.WALK),
            leg("IC 5", "Genève, gare routière", "Bern", "16:20", "17:00"))
        val j = body(listOf(walked), estimates)
        val rt = j.obj("primaryJourney").obj("realtime")
        val detail = rt["transferRiskDetail"]!!.jsonArray.single().jsonObject
        assertEquals("10", detail.str("slackMinutes"))  // 20 scheduled minus the 10-minute walk
        assertEquals(0.1, rt.num("transferMissRisk"), absoluteTolerance = 1e-9) // chronic p90 is 5
        assertEquals("CHRONIC", rt.str("transferRiskBasis"))
    }

    @Test fun `without a delay model or incident the transfer layer stays null and never fabricates a risk`() {
        val j = body(listOf(risky()), DelayEstimateModel.NONE)
        val rt = j.obj("primaryJourney").obj("realtime")
        assertEquals(JsonNull, rt["transferMissRisk"])
        assertEquals(JsonNull, rt["transferRiskDetail"])
        assertEquals("NONE", rt.str("transferRiskBasis"))
        // a lone risky journey cannot be rerouted to anything: the recommendation stays put
        assertEquals("STAY", j.str("action"))
        assertEquals("RISKY", j.obj("recommendedJourney").str("id"))
        assertTrue(j.reasons().none { it.str("code") == "TRANSFER_MISS_RISK" })
    }

    @Test fun `a direct journey has no transfer risk and counts as safe against a risky alternative`() {
        val direct = journey("DIRECT", leg("IC 1", "Lausanne", "Bern", "15:20", "17:10"))
        val j = body(listOf(risky(), direct), estimates, Fixed(listOf(incident)))
        // RISKY arrives first, so it is the primary; the direct train has nothing to miss and takes the recommendation
        assertEquals("RISKY", j.obj("primaryJourney").str("id"))
        assertEquals("DIRECT", j.obj("recommendedJourney").str("id"))
        assertEquals("REROUTE", j.str("action"))
        assertEquals(JsonNull, j.obj("recommendedJourney").obj("realtime")["transferMissRisk"])
    }

    /** The demo scenario has a 3-minute Fribourg connection; a seeded perturbation delays its first leg past that. */
    @Test fun `the demo perturbation turns the tight Fribourg connection into a transfer-miss reroute`() {
        lateinit var j: JsonObject
        lateinit var raw: String
        testApplication {
            application {
                install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
                configureRoutes(JourneySearchService(PerturbationProvider(DemoScenarioTransportProvider())), clock, estimates = estimates)
            }
            raw = client.get("/api/journeys?origin=Lausanne&destination=Fribourg&demoPerturbation=9").bodyAsText()
            j = Json.parseToJsonElement(raw).jsonObject
        }
        val risky = (j["primaryJourney"] as? JsonObject) ?: fail("no primary journey: $raw")
        assertEquals("demo-fribourg-tight", risky.str("id"), raw)

        assertEquals("KNOWN", risky.obj("realtime").str("transferRiskBasis"))
        assertTrue(j.reasons().any { it.str("code") == "TRANSFER_MISS_RISK" })
        assertEquals("demo-fribourg-safe", j.obj("recommendedJourney").str("id"))
        assertEquals("REROUTE", j.str("action"))
    }
}
