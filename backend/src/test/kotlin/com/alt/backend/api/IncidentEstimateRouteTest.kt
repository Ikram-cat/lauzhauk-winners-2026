package com.alt.backend.api

import com.alt.backend.domain.*
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

/**
 * End-to-end incident-conditioned estimates (docs/INCIDENT_DELAYS_PLAN.md §4): a live disruption detected on
 * the leg switches the estimate to the conditioned bucket and exposes the risk layer; without one the wire is
 * exactly the Phase-1 payload (same bytes whether or not the artifact carries incident tables).
 */
class IncidentEstimateRouteTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)
    private fun t(hm: String) = OffsetDateTime.parse("2026-11-21T${hm}:00Z")
    private val fixture = Path.of(javaClass.classLoader.getResource("delay_estimates_fixture.json")!!.toURI())
    private val estimates = DelayEstimateModel(fixture)

    private fun journey(id: String = "J"): Journey {
        val leg = JourneyLeg(LegMode.TRANSIT, "Lausanne", "Genève", t("15:10"), t("16:00"), "IR 90", null, null)
        return Journey(id, leg.departure, leg.arrival, Duration.between(leg.departure, leg.arrival).toMinutes().toInt(), 0,
            listOf(leg), DataStatus.LIVE_SCHEDULE)
    }

    /** Timetable board is on time; the live board is 10 minutes late, so detection reports a disruption. */
    private class Fake(private val js: List<Journey>, private val liveDelay: Long) : TransportProvider {
        override suspend fun search(request: SearchRequest): ProviderResult = ProviderResult.Success(js)
        override suspend fun stopEvents(stop: String, from: OffsetDateTime, realtime: Boolean): StopEventsResult {
            val scheduled = js.first().legs.first().departure
            val events = if (realtime) listOf(StopEvent("IR 90", null, scheduled, scheduled.plusMinutes(liveDelay), false, null))
            else listOf(StopEvent("IR 90", null, scheduled, null, false, null))
            return StopEventsResult.Success(events)
        }
    }

    /** An ACTIVE SIRI/replay-style disruption; the planner treats legs it touches as incident-conditioned. */
    private class Fixed(val d: List<ActiveDisruption>) : DisruptionSource {
        override val dataStatus = "MOCK"
        override fun active(at: OffsetDateTime) = d
    }

    private fun body(
        provider: TransportProvider, model: DelayEstimateModel,
        source: DisruptionSource = com.alt.backend.service.impact.EmptyDisruptionSource
    ): JsonObject {
        lateinit var out: JsonObject
        testApplication {
            application {
                install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
                configureRoutes(JourneySearchService(provider), clock, estimates = model, disruptionSource = source)
            }
            out = Json.parseToJsonElement(client.get("/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve").bodyAsText()).jsonObject
        }
        return out
    }

    private fun JsonObject.str(k: String) = get(k)!!.jsonPrimitive.content
    private fun JsonObject.obj(k: String) = get(k)!!.jsonObject

    @Test fun `a detected disruption switches the leg to the conditioned estimate and exposes the risk layer`() {
        val j = body(Fake(listOf(journey()), liveDelay = 10), estimates)
        val p = j.obj("primaryJourney")
        val leg = p["legs"]!!.jsonArray[0].jsonObject
        assertEquals("9.0", leg.str("estimatedDepartureDelayMinutes"))   // conditioned median, not the chronic 3.0
        assertEquals("8.0", leg.str("estimatedArrivalDelayMinutes"))
        assertEquals("LINE_INCIDENT", leg.str("estimatedBasis"))
        assertEquals("6.0", leg.str("incidentExtraMinutes"))             // 9.0 - 3.0 unconditional
        assertEquals("0.12", leg.str("incidentCancellationRisk"))
        assertEquals("0.04", leg.str("incidentSuppressedStopRisk"))
        assertEquals("25", leg.str("incidentSamples"))
        assertEquals("4", leg.str("incidentCount"))
        // projected = scheduled + known + CONDITIONED estimate (composed in seconds)
        assertEquals("2026-11-21T15:29:00Z", leg.str("projectedDeparture"))
        assertEquals("2026-11-21T16:08:00Z", leg.str("projectedArrival"))
        // journey level mirrors the same rule
        val rt = p.obj("realtime")
        assertEquals("LINE_INCIDENT", rt.str("estimatedBasis"))
        assertEquals("6.0", rt.str("incidentExtraMinutes"))
        assertEquals("0.12", rt.str("incidentCancellationRisk"))
        assertEquals("2026-11-21T15:29:00Z", rt.str("projectedDeparture"))
    }

    @Test fun `an ACTIVE alert-source disruption on the leg also switches to the conditioned estimate`() {
        val d = ActiveDisruption("inc-1", "synthetic", null, listOf(
            AffectedStation(8501120, "ch:1:sloid:1120", "Lausanne", listOf("DISRUPTION_POINT")),
            AffectedStation(8501008, "ch:1:sloid:1008", "Genève", listOf("LINE_TO"))), listOf("IR 90"), "MOCK")
        val j = body(Fake(listOf(journey()), liveDelay = 0), estimates, Fixed(listOf(d)))
        val leg = j.obj("primaryJourney")["legs"]!!.jsonArray[0].jsonObject
        assertEquals("LINE_INCIDENT", leg.str("estimatedBasis"))
        assertEquals("9.0", leg.str("estimatedDepartureDelayMinutes"))
        assertEquals("0.12", leg.str("incidentCancellationRisk"))
    }

    @Test fun `without an incident the payload is byte-identical with or without incident tables in the artifact`() {
        val withoutIncidents = Files.createTempFile("delay_estimates_phase1", ".json")
        try {
            val full = Json.parseToJsonElement(Files.readString(fixture)).jsonObject
            val phase1 = JsonObject(full.filterKeys { it != "linesIncident" && it != "stationsIncident" })
            Files.writeString(withoutIncidents, Json.encodeToString(JsonElement.serializer(), phase1))
            val withTables = body(Fake(listOf(journey()), liveDelay = 0), estimates)
            val withoutTables = body(Fake(listOf(journey()), liveDelay = 0), DelayEstimateModel(withoutIncidents))
            assertEquals(withTables.toString(), withoutTables.toString())
            val leg = withTables.obj("primaryJourney")["legs"]!!.jsonArray[0].jsonObject
            assertEquals("3.0", leg.str("estimatedDepartureDelayMinutes"))
            assertEquals("LINE", leg.str("estimatedBasis"))
            assertEquals("2026-11-21T15:13:00Z", leg.str("projectedDeparture"))
            for (k in listOf("incidentExtraMinutes", "incidentCancellationRisk", "incidentSuppressedStopRisk", "incidentSamples", "incidentCount")) {
                assertEquals(JsonNull, leg[k], k)
            }
        } finally { Files.deleteIfExists(withoutIncidents) }
    }
}
