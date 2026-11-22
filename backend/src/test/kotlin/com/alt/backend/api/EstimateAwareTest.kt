package com.alt.backend.api

import com.alt.backend.domain.*
import com.alt.backend.provider.*
import com.alt.backend.service.*
import com.alt.backend.service.estimate.DelayEstimateModel
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
 * Additive estimate layer on the wire: the six new nullable fields exist, projected = scheduled + known + estimated,
 * and every pre-existing field keeps its exact key set and meaning.
 * Local time on the fixture day is Zurich +1, so 15:10Z departs in local hour 16 (SAT) and 16:00Z arrives in hour 17.
 */
class EstimateAwareTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)
    private fun t(hm: String) = OffsetDateTime.parse("2026-11-21T${hm}:00Z")
    private val estimates = DelayEstimateModel(Path.of(javaClass.classLoader.getResource("delay_estimates_fixture.json")!!.toURI()))

    /** Known live delay +2 on both sides (provider estimate), as OJP would report it. */
    private fun journey(id: String, from: String, to: String, dep: String, arr: String, knownDep: String? = null, knownArr: String? = null): Journey {
        val leg = JourneyLeg(LegMode.TRANSIT, from, to, t(dep), t(arr), "IR 90", null, null,
            expectedDeparture = knownDep?.let { t(it) }, expectedArrival = knownArr?.let { t(it) })
        return Journey(id, leg.departure, leg.arrival, Duration.between(leg.departure, leg.arrival).toMinutes().toInt(), 0, listOf(leg), DataStatus.LIVE_SCHEDULE)
    }

    private class Fake(val js: List<Journey>) : TransportProvider {
        override suspend fun search(request: SearchRequest): ProviderResult = ProviderResult.Success(js)
        override suspend fun stopEvents(stop: String, from: OffsetDateTime, realtime: Boolean) = StopEventsResult.Failure("n/a")
    }

    private fun api(js: List<Journey>, m: DelayEstimateModel = estimates, block: (JsonObject) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(Fake(js)), clock, estimates = m)
        }
        block(Json.parseToJsonElement(client.get("/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve").bodyAsText()).jsonObject)
    }

    private fun JsonObject.str(k: String) = get(k)!!.jsonPrimitive.content
    private fun JsonObject.obj(k: String) = get(k)!!.jsonObject

    @Test fun `projected departure and arrival add scheduled plus known plus estimated`() = api(listOf(journey("J", "Lausanne", "Genève", "15:10", "16:00", "15:12", "16:02"))) { j ->
        val p = j.obj("primaryJourney")
        val rt = p.obj("realtime")
        val leg = p["legs"]!!.jsonArray[0].jsonObject
        assertEquals("3.0", leg.str("estimatedDepartureDelayMinutes"))   // IR 90 @ Lausanne, SAT, local h16
        assertEquals("2.0", leg.str("estimatedArrivalDelayMinutes"))     // IR 90 @ Genève, SAT, local h17
        assertEquals("HISTORICAL_ACTUALS", leg.str("estimatedSource"))
        assertEquals("40", leg.str("estimatedSamples"))
        assertEquals("2", leg.str("departureDelayMinutes"))            // known layer untouched
        assertEquals("2", leg.str("arrivalDelayMinutes"))
        assertEquals("2026-11-21T15:12:00Z", leg.str("expectedDeparture"))
        assertEquals("2026-11-21T15:15:00Z", leg.str("projectedDeparture")) // 15:10 + 2 + 3
        assertEquals("2026-11-21T16:04:00Z", leg.str("projectedArrival"))   // 16:00 + 2 + 2
        assertEquals("2026-11-21T15:15:00Z", rt.str("projectedDeparture"))
        assertEquals("2026-11-21T16:04:00Z", rt.str("projectedArrival"))
        assertEquals("3.0", rt.str("estimatedDepartureDelayMinutes"))
        assertEquals("2.0", rt.str("estimatedArrivalDelayMinutes"))
        assertEquals("HISTORICAL_ACTUALS", rt.str("estimatedSource"))
        assertEquals("OJP", rt.str("source")) // the known layer still reports the provider source
    }

    @Test fun `estimates are null-safe without an artifact and never fabricate a delay`() = api(listOf(journey("J", "Nowhere", "Elsewhere", "15:10", "16:00")), DelayEstimateModel.NONE) { j ->
        val rt = j.obj("primaryJourney").obj("realtime")
        for (k in listOf("estimatedDepartureDelayMinutes", "estimatedArrivalDelayMinutes", "estimatedSamples",
                "projectedDeparture", "projectedArrival", "expectedDeparture", "expectedArrival")) assertEquals(JsonNull, rt[k], k)
        assertEquals("NONE", rt.str("estimatedSource"))
        assertEquals("NONE", rt.str("source"))
    }

    @Test fun `every pre-existing leg and realtime field keeps its exact key set`() = api(listOf(journey("J", "Lausanne", "Genève", "15:10", "16:00", "15:12", "16:02"))) { j ->
        val p = j.obj("primaryJourney")
        assertEquals(setOf("expectedDeparture", "departureDelayMinutes", "expectedArrival", "arrivalDelayMinutes", "source", "sampleCount",
            "estimatedDepartureDelayMinutes", "estimatedArrivalDelayMinutes", "estimatedSource", "estimatedSamples", "projectedDeparture", "projectedArrival"),
            p.obj("realtime").keys)
        assertEquals(setOf("mode", "origin", "destination", "departure", "arrival", "expectedDeparture", "departureDelayMinutes", "expectedArrival", "arrivalDelayMinutes",
            "serviceLabel", "departurePlatform", "arrivalPlatform", "occupancy",
            "estimatedDepartureDelayMinutes", "estimatedArrivalDelayMinutes", "estimatedSource", "estimatedSamples", "projectedDeparture", "projectedArrival"),
            p["legs"]!!.jsonArray[0].jsonObject.keys)
    }

    @Test fun `known_wins overlap keeps the estimate visible but out of the projection`() = testApplication {
        val search = JourneySearchService(Fake(listOf(journey("J", "Lausanne", "Genève", "15:10", "16:00", "15:12", "16:02"))))
        val planner = JourneyPlanService(search, DisruptionDetector(search::stopEvents), clock, RealtimeDelayModel.NONE, estimates, "known_wins")
        val first = search.search(SearchRequest("Lausanne", "Genève", t("15:00"), 2, Discount.NONE)) as SearchResult.Success
        val plan = kotlinx.coroutines.runBlocking { planner.plan(first.primaryRequest(), Preference.FASTEST, null, first) }
        val leg = plan.realtime.getValue("J").legs[0]
        assertEquals(3.0, leg.estimatedDepartureDelayMinutes) // reported for transparency
        assertEquals(t("15:12"), leg.projectedDeparture)     // but only the known +2 is applied
        assertEquals(t("16:02"), leg.projectedArrival)
    }

    @Test fun `health keeps 200 and gains an additive estimates feed`() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(Fake(emptyList())), clock, estimates = estimates)
        }
        val r = client.get("/health")
        assertEquals(io.ktor.http.HttpStatusCode.OK, r.status)
        val feeds = Json.parseToJsonElement(r.bodyAsText()).jsonObject.obj("feeds")
        assertEquals(setOf("ojp", "nova", "gtfsRt", "estimates"), feeds.keys)
        val est = feeds.obj("estimates")
        assertEquals(setOf("status", "lineBuckets", "stationBuckets"), est.keys)
        assertEquals("OK", est.str("status")); assertEquals("9", est.str("lineBuckets")); assertEquals("3", est.str("stationBuckets"))
    }

    private fun SearchResult.Success.primaryRequest() = SearchRequest("Lausanne", "Genève", t("15:00"), 2, Discount.NONE)
}
