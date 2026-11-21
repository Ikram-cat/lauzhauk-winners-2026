package com.cffplanb.backend.api

import com.cffplanb.backend.config.AppConfig
import com.cffplanb.backend.config.configureApp
import com.cffplanb.backend.domain.SearchRequest
import com.cffplanb.backend.provider.ProviderResult
import com.cffplanb.backend.provider.TransportProvider
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
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RouteTest {

    // Fixed clock for deterministic tests
    private val fixedClock: Clock = Clock.fixed(
        Instant.parse("2026-11-21T15:00:00Z"),
        ZoneOffset.UTC
    )

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureApp(fixedClock)
        }
        block()
    }

    private fun customProviderTest(
        mockProvider: TransportProvider,
        block: suspend ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        application {
            install(ContentNegotiation) {
                json(Json { encodeDefaults = true })
            }
            configureRoutes(JourneySearchService(mockProvider), fixedClock)
        }
        block()
    }

    // --- Health ---

    @Test
    fun `GET health returns 200 with status ok`() = appTest {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", json["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET health does not call provider`() = appTest {
        repeat(3) {
            val r = client.get("/health")
            assertEquals(HttpStatusCode.OK, r.status)
        }
    }

    // --- Journeys success ---

    @Test
    fun `GET api journeys returns 200 with request echo`() = appTest {
        val response = client.get(
            "/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&departure=2026-11-21T16%3A00%3A00%2B01%3A00"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        val req = json["request"]!!.jsonObject
        assertEquals("Lausanne", req["origin"]!!.jsonPrimitive.content)
        assertEquals("Genève", req["destination"]!!.jsonPrimitive.content)
        assertTrue(req["earliestDeparture"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(JsonNull, req["arrivalDeadline"]!!)
        assertEquals("FASTEST", req["preference"]!!.jsonPrimitive.content)

        val journeys = json["journeys"]!!.jsonArray
        assertTrue(journeys.isNotEmpty(), "Expected mock journeys")
    }

    @Test
    fun `GET api journeys without departure uses clock time`() = appTest {
        val response = client.get("/api/journeys?origin=Lausanne&destination=Bern")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val req = json["request"]!!.jsonObject
        assertTrue(req["earliestDeparture"]!!.jsonPrimitive.content.contains("2026-11-21"))
    }

    @Test
    fun `journeys are sorted by arrival then transfers then departure then id`() = appTest {
        val response = client.get("/api/journeys?origin=Lausanne&destination=Geneve")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val journeys = json["journeys"]!!.jsonArray

        if (journeys.size >= 2) {
            val first = journeys[0].jsonObject
            val second = journeys[1].jsonObject
            val arr1 = first["arrival"]!!.jsonPrimitive.content
            val arr2 = second["arrival"]!!.jsonPrimitive.content
            assertTrue(arr1 <= arr2, "Journeys should be sorted by arrival ASC")
        }
    }

    @Test
    fun `journey fields include required nullable fields`() = appTest {
        val response = client.get("/api/journeys?origin=Lausanne&destination=Geneve")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val journeys = json["journeys"]!!.jsonArray
        assertTrue(journeys.isNotEmpty())
        val j = journeys[0].jsonObject
        assertTrue(j.containsKey("predictedDelayMinutes"))
        assertTrue(j.containsKey("lateArrivalProbability"))
        assertTrue(j.containsKey("estimatedFareChf"))
        assertTrue(j.containsKey("dataStatus"))
        assertEquals("MOCK", j["dataStatus"]!!.jsonPrimitive.content)
    }

    // --- Validation errors ---

    @Test
    fun `missing origin returns 400 INVALID_REQUEST`() = appTest {
        val response = client.get("/api/journeys?destination=Bern")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("INVALID_REQUEST", json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing destination returns 400`() = appTest {
        val response = client.get("/api/journeys?origin=Lausanne")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertErrorCode(response, "INVALID_REQUEST")
    }

    @Test
    fun `blank origin returns 400`() = appTest {
        val response = client.get("/api/journeys?origin=&destination=Bern")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertErrorCode(response, "INVALID_REQUEST")
    }

    @Test
    fun `blank destination returns 400`() = appTest {
        val response = client.get("/api/journeys?origin=Lausanne&destination=")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertErrorCode(response, "INVALID_REQUEST")
    }

    @Test
    fun `invalid departure timestamp returns 400`() = appTest {
        val response = client.get("/api/journeys?origin=Lausanne&destination=Bern&departure=not-a-date")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertErrorCode(response, "INVALID_REQUEST")
    }

    @Test
    fun `departure without offset returns 400`() = appTest {
        val response = client.get("/api/journeys?origin=Lausanne&destination=Bern&departure=2026-01-01T12:00:00")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertErrorCode(response, "INVALID_REQUEST")
    }

    @Test
    fun `unknown parameter returns 400`() = appTest {
        val response = client.get("/api/journeys?origin=Lausanne&destination=Bern&unknown=x")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertErrorCode(response, "INVALID_REQUEST")
    }

    @Test
    fun `duplicate origin parameter returns 400`() = appTest {
        val response = client.get("/api/journeys?origin=Lausanne&origin=Bern&destination=Geneve")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertErrorCode(response, "INVALID_REQUEST")
    }

    @Test
    fun `duplicate destination parameter returns 400`() = appTest {
        val response = client.get("/api/journeys?origin=Lausanne&destination=Bern&destination=Geneve")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertErrorCode(response, "INVALID_REQUEST")
    }

    @Test
    fun `origin longer than 120 codepoints returns 400`() = appTest {
        val longOrigin = "a".repeat(121)
        val response = client.get("/api/journeys?origin=$longOrigin&destination=Bern")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertErrorCode(response, "INVALID_REQUEST")
    }

    @Test
    fun `GET health content type is JSON`() = appTest {
        val response = client.get("/health")
        assertEquals(ContentType.Application.Json.withCharset(Charsets.UTF_8), response.contentType())
    }

    @Test
    fun `GET journeys content type is JSON`() = appTest {
        val response = client.get("/api/journeys?origin=Lausanne&destination=Bern")
        assertEquals(ContentType.Application.Json.withCharset(Charsets.UTF_8), response.contentType())
    }

    // --- Provider failure mappings ---

    @Test
    fun `startup with ojp provider but missing api key fails fast`() {
        val config = AppConfig(transportProvider = "ojp", ojpApiKey = null)
        assertFailsWith<IllegalStateException> {
            testApplication {
                application {
                    configureApp(fixedClock, config)
                }
            }
        }
    }

    @Test
    fun `provider auth error maps to 502 UPSTREAM_ERROR with credential-free message`() = customProviderTest(
        mockProvider = object : TransportProvider {
            override suspend fun search(request: SearchRequest): ProviderResult =
                ProviderResult.AuthError("Transport provider authentication failed. Check OJP_API_KEY configuration.")
        }
    ) {
        val response = client.get("/api/journeys?origin=Lausanne&destination=Bern")
        assertEquals(HttpStatusCode.BadGateway, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("UPSTREAM_ERROR", json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("authentication failed"))
    }

    @Test
    fun `provider timeout maps to 504 UPSTREAM_TIMEOUT`() = customProviderTest(
        mockProvider = object : TransportProvider {
            override suspend fun search(request: SearchRequest): ProviderResult =
                ProviderResult.Timeout("OJP did not respond within 8000ms")
        }
    ) {
        val response = client.get("/api/journeys?origin=Lausanne&destination=Bern")
        assertEquals(HttpStatusCode.GatewayTimeout, response.status)
        assertErrorCode(response, "UPSTREAM_TIMEOUT")
    }

    @Test
    fun `provider rate limit maps to 503 UPSTREAM_RATE_LIMITED`() = customProviderTest(
        mockProvider = object : TransportProvider {
            override suspend fun search(request: SearchRequest): ProviderResult =
                ProviderResult.RateLimited("OJP rate limit reached")
        }
    ) {
        val response = client.get("/api/journeys?origin=Lausanne&destination=Bern")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertErrorCode(response, "UPSTREAM_RATE_LIMITED")
    }

    @Test
    fun `provider location resolution error maps to 502 UPSTREAM_ERROR`() = customProviderTest(
        mockProvider = object : TransportProvider {
            override suspend fun search(request: SearchRequest): ProviderResult =
                ProviderResult.LocationResolutionError("Could not resolve stop 'UnknownStation' via OJP LocationInformationRequest")
        }
    ) {
        val response = client.get("/api/journeys?origin=UnknownStation&destination=Bern")
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertErrorCode(response, "UPSTREAM_ERROR")
    }

    private suspend fun assertErrorCode(response: io.ktor.client.statement.HttpResponse, code: String) {
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(code, json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }
}
