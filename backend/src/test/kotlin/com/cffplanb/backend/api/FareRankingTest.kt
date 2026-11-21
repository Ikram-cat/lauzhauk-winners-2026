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
        // 1st class flips A/C order; C lacks 1st-class Half-Fare; D unpriced
        val prices = mapOf("A" to FareQuote(50.0, 25.0, 80.0, "Streckenbillett", "NOVA", 40.0),
            "C" to FareQuote(30.0, 15.0, 90.0, "Streckenbillett", "NOVA", null))
        override suspend fun quote(origin: String, destination: String, departure: OffsetDateTime): FareQuote? {
            calls++
            return prices[origin]
        }
    }

    private fun test(fares: FareCache?, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            configureRoutes(JourneySearchService(transport, fares), clock)
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(pref: String?, extra: String = ""): Pair<HttpStatusCode, JsonObject> {
        val r = client.get("/api/journeys?origin=A&destination=B&departure=2026-11-21T09:00:00Z" + (pref?.let { "&preference=$it" } ?: "") + "&limit=10" + extra)
        return r.status to Json.parseToJsonElement(r.bodyAsText()).jsonObject
    }
    /** primary first, then the alternatives: the whole ranked list when primary == recommended (always, here: no disruption). */
    private fun JsonObject.ids() = listOf(o("primaryJourney").str("id")) + a("alternatives").map { it.obj().str("id") }
    private fun JsonObject.journey(id: String) = (listOf(o("primaryJourney")) + a("alternatives").map { it.obj() }).first { it.str("id") == id }
    private fun JsonObject.fares(id: String) = journey(id).o("fares")

    @Test fun `each preference orders journeys with documented tie-breaks`() = test(FareCache(FakeFares())) {
        assertEquals(listOf("j2", "j1", "j4", "j3"), get(null).second.ids())
        assertEquals(listOf("j2", "j1", "j4", "j3"), get("FASTEST").second.ids())
        assertEquals(listOf("j3", "j1", "j2", "j4"), get("CHEAPEST").second.ids()) // j1/j2 tie on fare+arrival -> id; null fare last
        assertEquals(listOf("j2", "j4", "j3", "j1"), get("simplest").second.ids())
        assertEquals("SIMPLEST", get("simplest").second.o("request").str("preference"))
    }

    @Test fun `highlights are independent of preference`() = test(FareCache(FakeFares())) {
        for (p in listOf("FASTEST", "CHEAPEST", "SIMPLEST")) {
            val h = get(p).second.o("highlights")
            assertEquals(listOf("j2", "j3", "j2"), listOf("fastestId", "cheapestId", "simplestId").map { h.str(it) })
        }
    }

    @Test fun `fare fields populated or unavailable`() = test(FareCache(FakeFares())) {
        val body = get(null).second
        val f = body.fares("j1")
        assertEquals("PRICED", f.str("status")); assertEquals(50.0, f["selectedChf"]!!.jsonPrimitive.double)
        assertEquals("Streckenbillett", f.str("product")); assertEquals("NOVA", f.str("source"))
        val c = f.o("catalogChf")
        assertEquals(listOf(50.0, 25.0, 80.0, 40.0), listOf("secondClassFull", "secondClassHalfFare", "firstClassFull", "firstClassHalfFare").map { c[it]!!.jsonPrimitive.double })
        val j4 = body.fares("j4")
        assertEquals("UNAVAILABLE", j4.str("status"))
        for (k in listOf("selectedChf", "product", "source", "catalogChf")) assertEquals(JsonNull, j4[k], k)
    }

    @Test fun `fares disabled is DISABLED with nulls and cheapest unset`() = test(null) {
        val body = get("CHEAPEST").second
        assertEquals("DISABLED", body.fares("j1").str("status")); assertEquals(JsonNull, body.fares("j1")["selectedChf"])
        assertEquals(listOf("j1", "j2", "j4", "j3"), body.ids()) // all null -> arrival, id
        assertEquals(JsonNull, body.o("highlights")["cheapestId"])
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
        assertEquals("INVALID_REQUEST", body.o("error").str("code")); assertEquals("preference", body.o("error").str("field"))
    }

    private fun JsonObject.fare(id: String) = fares(id)["selectedChf"]!!.jsonPrimitive.doubleOrNull

    @Test fun `each class and discount selects its price and ranks cheapest by it`() = test(FareCache(FakeFares())) {
        // query suffix -> (j1 selectedChf, j3 selectedChf, CHEAPEST order)
        val cases = mapOf(
            "" to Triple(50.0, 30.0, listOf("j3", "j1", "j2", "j4")),
            "&fareClass=2&discount=HALF_FARE" to Triple(25.0, 15.0, listOf("j3", "j1", "j2", "j4")),
            "&fareClass=1" to Triple(80.0, 90.0, listOf("j1", "j2", "j3", "j4")),
            "&fareClass=1&discount=half_fare" to Triple(40.0, null, listOf("j1", "j2", "j4", "j3")), // j3/j4 null -> arrival
        )
        for ((q, exp) in cases) {
            val (status, body) = get("CHEAPEST", q)
            assertEquals(HttpStatusCode.OK, status, q)
            assertEquals(exp.first, body.fare("j1"), q)
            assertEquals(exp.second, body.fare("j3"), q)
            assertEquals(exp.third, body.ids(), q)
            assertEquals(exp.third.first(), body.o("highlights").str("cheapestId"), q)
            assertEquals(listOf("j2", "j1", "j4", "j3"), get("FASTEST", q).second.ids(), q) // other orders ignore fares
            val c = body.fares("j1").o("catalogChf") // catalog numbers stay as-is whatever the selection
            assertEquals(listOf(50.0, 25.0, 80.0, 40.0), listOf("secondClassFull", "secondClassHalfFare", "firstClassFull", "firstClassHalfFare").map { c[it]!!.jsonPrimitive.double }, q)
        }
        val req = get(null, "&fareClass=1&discount=Half_Fare").second.o("request")
        assertEquals(1, req["fareClass"]!!.jsonPrimitive.int); assertEquals("HALF_FARE", req.str("discount"))
        val def = get(null).second.o("request")
        assertEquals(2, def["fareClass"]!!.jsonPrimitive.int); assertEquals("NONE", def.str("discount"))
    }

    @Test fun `missing selected number is null and UNAVAILABLE but the catalog stays`() = test(FareCache(FakeFares())) {
        val j3 = get(null, "&fareClass=1&discount=HALF_FARE").second.fares("j3")
        assertEquals(JsonNull, j3["selectedChf"]); assertEquals(JsonNull, j3.o("catalogChf")["firstClassHalfFare"])
        assertEquals("UNAVAILABLE", j3.str("status"))
        assertEquals(30.0, j3.o("catalogChf")["secondClassFull"]!!.jsonPrimitive.double)
        assertEquals("PRICED", get(null, "&fareClass=1&discount=HALF_FARE").second.fares("j1").str("status"))
    }

    @Test fun `invalid fareClass or discount is 400 with exact message and field`() = test(FareCache(FakeFares())) {
        for ((q, msg, field) in listOf(
            Triple("&fareClass=3", "Parameter 'fareClass' must be 1 or 2", "fareClass"),
            Triple("&fareClass=first", "Parameter 'fareClass' must be 1 or 2", "fareClass"),
            Triple("&fareClass=", "Parameter 'fareClass' must be 1 or 2", "fareClass"),
            Triple("&discount=GA", "Parameter 'discount' must be one of NONE, HALF_FARE", "discount"),
            Triple("&discount=", "Parameter 'discount' must be one of NONE, HALF_FARE", "discount"),
            Triple("&fareClass=1&fareClass=2", "Duplicate parameter: fareClass", "fareClass"),
        )) {
            val (status, body) = get(null, q)
            assertEquals(HttpStatusCode.BadRequest, status, q)
            assertEquals("INVALID_REQUEST", body.o("error").str("code"), q)
            assertEquals(msg, body.o("error").str("message"), q); assertEquals(field, body.o("error").str("field"), q)
        }
    }

    @Test fun `fare cache is shared across class and discount`() {
        val fake = FakeFares()
        test(FareCache(fake)) {
            get(null); get(null, "&fareClass=1&discount=HALF_FARE"); get(null, "&discount=HALF_FARE")
            assertEquals(3, fake.calls)
        }
    }
}
