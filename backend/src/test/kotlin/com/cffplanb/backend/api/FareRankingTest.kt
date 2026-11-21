package com.cffplanb.backend.api

import com.cffplanb.backend.domain.*
import com.cffplanb.backend.provider.*
import com.cffplanb.backend.service.FareCache
import com.cffplanb.backend.service.JourneySearchService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class FareRankingTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T08:00:00Z"), ZoneOffset.UTC)
    private fun t(hhmm: String) = OffsetDateTime.parse("2026-11-21T$hhmm:00Z")
    private fun leg(o: String, d: String, dep: String, arr: String, mode: LegMode = LegMode.TRANSIT) =
        JourneyLeg(mode, o, d, t(dep), t(arr), "IC", null, null)
    private fun journey(id: String, dep: String, arr: String, transfers: Int, legs: List<JourneyLeg>) =
        Journey(id, t(dep), t(arr), 0, transfers, legs, DataStatus.MOCK)

    // FASTEST [j2, j1, j4, j3], CHEAPEST [j3, j1, j2, j4], SIMPLEST [j2, j4, j3, j1]
    private val journeys = listOf(
        journey("j1", "10:00", "11:00", 1, listOf(leg("A", "M", "10:00", "10:30"), leg("M", "B", "10:35", "11:00"))),
        journey("j2", "10:10", "11:00", 0, listOf(leg("A", "B", "10:10", "11:00"))),
        journey("j3", "10:00", "11:30", 0, listOf(leg("C", "C2", "10:00", "10:05", LegMode.WALK), leg("C2", "B", "10:05", "11:30"))),
        journey("j4", "10:00", "11:20", 0, listOf(leg("D", "B", "10:00", "11:20"))),
    )
    private val transport = object : TransportProvider {
        override suspend fun search(request: SearchRequest) = ProviderResult.Success(journeys)
    }

    private class FakeFares : FareProvider {
        var calls = 0
        val prices = mapOf("A" to 50.0, "C" to 30.0) // D unpriced
        override suspend fun quote(origin: String, destination: String, departure: OffsetDateTime): FareQuote? {
            calls++
            return prices[origin]?.let { FareQuote(it, it / 2, it * 1.6, "Streckenbillett", "NOVA") }
        }
    }

    private fun test(fares: FareCache?, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(transport, fares), clock)
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(pref: String?): Pair<HttpStatusCode, JsonObject> {
        val r = client.get("/api/journeys?origin=A&destination=B&departure=2026-11-21T09:00:00Z" + (pref?.let { "&preference=$it" } ?: ""))
        return r.status to Json.parseToJsonElement(r.bodyAsText()).jsonObject
    }
    private fun JsonObject.ids() = this["journeys"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    private fun JsonObject.journey(id: String) = this["journeys"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == id }

    @Test fun `each preference orders journeys with documented tie-breaks`() = test(FareCache(FakeFares())) {
        assertEquals(listOf("j2", "j1", "j4", "j3"), get(null).second.ids())
        assertEquals(listOf("j2", "j1", "j4", "j3"), get("FASTEST").second.ids())
        assertEquals(listOf("j3", "j1", "j2", "j4"), get("CHEAPEST").second.ids()) // j1/j2 tie on fare+arrival -> id; null fare last
        assertEquals(listOf("j2", "j4", "j3", "j1"), get("simplest").second.ids())
        assertEquals("SIMPLEST", get("simplest").second["request"]!!.jsonObject["preference"]!!.jsonPrimitive.content)
    }

    @Test fun `recommendations are independent of preference`() = test(FareCache(FakeFares())) {
        for (p in listOf("FASTEST", "CHEAPEST", "SIMPLEST")) {
            val rec = get(p).second["recommendations"]!!.jsonObject
            assertEquals(listOf("j2", "j3", "j2"), listOf("fastest", "cheapest", "simplest").map { rec[it]!!.jsonPrimitive.content })
        }
    }

    @Test fun `fare fields populated or unavailable`() = test(FareCache(FakeFares())) {
        val body = get(null).second
        val j1 = body.journey("j1")
        assertEquals(50.0, j1["estimatedFareChf"]!!.jsonPrimitive.double)
        assertEquals(25.0, j1["halfFareChf"]!!.jsonPrimitive.double)
        assertEquals(80.0, j1["firstClassFareChf"]!!.jsonPrimitive.double)
        assertEquals("Streckenbillett", j1["fareProduct"]!!.jsonPrimitive.content)
        assertEquals("NOVA", j1["fareSource"]!!.jsonPrimitive.content)
        assertEquals("PRICED", j1["fareStatus"]!!.jsonPrimitive.content)
        val j4 = body.journey("j4")
        assertEquals("UNAVAILABLE", j4["fareStatus"]!!.jsonPrimitive.content)
        for (f in listOf("estimatedFareChf", "halfFareChf", "firstClassFareChf", "fareProduct", "fareSource")) assertEquals(JsonNull, j4[f])
    }

    @Test fun `fares disabled leaves every fare field null and cheapest unset`() = test(null) {
        val body = get("CHEAPEST").second
        assertEquals(JsonNull, body.journey("j1")["fareStatus"])
        assertEquals(listOf("j1", "j2", "j4", "j3"), body.ids()) // all null -> arrival, id
        assertEquals("j1", body["recommendations"]!!.jsonObject["cheapest"]!!.jsonPrimitive.content)
    }

    @Test fun `one quote per distinct od pair and cache avoids repeat calls`() {
        val fake = FakeFares()
        test(FareCache(fake)) {
            get(null); assertEquals(3, fake.calls) // A|B, C|B, D|B
            get("CHEAPEST"); assertEquals(3, fake.calls) // positive and negative entries served from cache
        }
    }

    @Test fun `invalid preference is 400 INVALID_REQUEST`() = test(FareCache(FakeFares())) {
        val (status, body) = get("BOGUS")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("INVALID_REQUEST", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }
}
