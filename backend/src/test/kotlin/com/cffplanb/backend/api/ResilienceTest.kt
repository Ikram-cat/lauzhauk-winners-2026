package com.cffplanb.backend.api

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.cffplanb.backend.config.AppConfig
import com.cffplanb.backend.config.configureApp
import com.cffplanb.backend.domain.SearchRequest
import com.cffplanb.backend.provider.*
import com.cffplanb.backend.service.JourneySearchService
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

class ResilienceTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)
    private val t0 = OffsetDateTime.of(2026, 11, 21, 15, 0, 0, 0, ZoneOffset.UTC)
    private fun req(o: String = "A") = SearchRequest(o, "B", t0)

    private class Counting(var result: ProviderResult = ProviderResult.Success(emptyList())) : TransportProvider {
        var searches = 0; var locs = 0
        override suspend fun search(request: SearchRequest): ProviderResult { searches++; return result }
        override suspend fun searchLocations(query: String): LocationsResult { locs++; return LocationsResult.Success(emptyList()) }
    }

    @Test fun `cache hit skips provider and expires after 60s`() = runBlocking {
        var now = 0L
        val p = Counting(); val r = ResilientProvider(p, nowMs = { now })
        r.search(req()); r.search(req("a ")); assertEquals(1, p.searches) // normalised key
        now = 59_999; r.search(req()); assertEquals(1, p.searches)
        now = 60_001; r.search(req()); assertEquals(2, p.searches)
        r.searchLocations("Bern"); r.searchLocations(" bern"); assertEquals(1, p.locs)
        now += 599_000; r.searchLocations("bern"); assertEquals(1, p.locs)
        now += 2_000; r.searchLocations("bern"); assertEquals(2, p.locs)
    }

    @Test fun `failures are not cached and CACHE_ENABLED=false disables caching`() = runBlocking {
        val p = Counting(ProviderResult.Timeout("t")); val r = ResilientProvider(p)
        r.search(req()); r.search(req()); assertEquals(2, p.searches)
        val q = Counting(); val off = ResilientProvider(q, cacheEnabled = false)
        off.search(req()); off.search(req()); assertEquals(2, q.searches)
    }

    @Test fun `breaker opens at 5 consecutive failures then closes after 30s`() = runBlocking {
        var now = 0L
        val p = Counting(ProviderResult.Failure("x")); val r = ResilientProvider(p, nowMs = { now })
        repeat(5) { now += 1000; r.search(req()) }
        assertEquals(5, p.searches)
        val fast = r.search(req()); assertEquals(5, p.searches)
        assertEquals(ProviderResult.Failure(ResilientProvider.BREAKER_MESSAGE), fast)
        assertIs<LocationsResult.Failure>(r.searchLocations("x")); assertEquals(0, p.locs)
        now += 30_001; p.result = ProviderResult.Success(emptyList())
        assertIs<ProviderResult.Success>(r.search(req())); assertEquals(6, p.searches)
    }

    @Test fun `failures spread over more than 60s or broken by a success do not open it`() = runBlocking {
        var now = 0L
        val p = Counting(ProviderResult.Timeout("x")); val r = ResilientProvider(p, cacheEnabled = false, nowMs = { now })
        repeat(4) { r.search(req()) }
        now = 61_000; r.search(req()) // window restarted: failure #1
        p.result = ProviderResult.Success(emptyList()); r.search(req())
        p.result = ProviderResult.Timeout("x"); repeat(4) { r.search(req()) }
        r.search(req())
        assertEquals(11, p.searches)
    }

    @Test fun `breaker logs one line per transition without query text`() = runBlocking {
        val app = ListAppender<ILoggingEvent>().also { it.start() }
        val lg = LoggerFactory.getLogger(ResilientProvider::class.java) as Logger; lg.addAppender(app)
        try {
            var now = 0L
            val r = ResilientProvider(Counting(ProviderResult.Failure("x")), nowMs = { now })
            repeat(7) { r.search(req("SecretStation$it")) }
            now = 31_000; r.search(req("SecretStation"))
            val lines = app.list.map { it.formattedMessage }
            assertEquals(2, lines.size, lines.toString())
            assertTrue(lines.none { it.contains("SecretStation") })
        } finally { lg.detachAppender(app) }
    }

    @Test fun `health is 200 with a failing provider and does not touch it`() {
        val p = Counting(ProviderResult.Failure("down"))
        testApplication {
            application {
                install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
                configureRoutes(JourneySearchService(ResilientProvider(p)), clock)
            }
            repeat(6) { client.get("/api/journeys?origin=A&destination=B") }
            val h = client.get("/health")
            assertEquals(HttpStatusCode.OK, h.status); assertEquals("""{"status":"ok"}""", h.bodyAsText())
            assertEquals(5, p.searches) // 6th call failed fast
        }
    }

    @Test fun `unreadable artifact never changes HTTP status`() {
        val bad = java.nio.file.Files.createTempFile("bad", ".json").also { java.nio.file.Files.writeString(it, "{nope"); it.toFile().deleteOnExit() }
        testApplication {
            application { configureApp(clock, AppConfig(transportProvider = "mock", riskArtifactPath = bad.toString())) }
            assertEquals(HttpStatusCode.OK, client.get("/api/journeys?origin=Lausanne&destination=Bern").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/model").status)
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
        }
    }

    @Test fun `bundled artifact loads without RISK_ARTIFACT_PATH`() = testApplication {
        application { configureApp(clock, AppConfig(transportProvider = "mock", riskArtifactPath = null)) }
        val m = Json.parseToJsonElement(client.get("/api/model").bodyAsText()).jsonObject
        assertTrue(m["corridorsLoaded"]!!.jsonPrimitive.content.toInt() > 0, m.toString())
    }

    private val token = "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v2W3x4Y5z6_-abcdefghijkl"
    private val tokenPattern = Regex("[A-Za-z0-9_-]{40,}")

    private fun ojp(status: HttpStatusCode, throwTimeout: Boolean = false) = OjpTransportProvider(token, clock, HttpClient(MockEngine {
        if (throwTimeout) throw java.io.IOException("boom Bearer $token")
        respond("UPSTREAM-SECRET-BODY $token", status)
    }))

    @Test fun `upstream failures map to documented statuses and leak neither body nor token`() {
        val app = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger; root.addAppender(app)
        val payloads = mutableListOf<String>()
        try {
            val cases = listOf(
                Triple(ojp(HttpStatusCode.TooManyRequests), 503, "UPSTREAM_RATE_LIMITED"),
                Triple(ojp(HttpStatusCode.InternalServerError), 502, "UPSTREAM_ERROR"),
                Triple(ojp(HttpStatusCode.Unauthorized), 502, "UPSTREAM_ERROR"),
                Triple(ojp(HttpStatusCode.OK, throwTimeout = true), 502, "UPSTREAM_ERROR"),
                Triple(object : TransportProvider { override suspend fun search(request: SearchRequest) = ProviderResult.Timeout("OJP did not respond within 8000ms") }, 504, "UPSTREAM_TIMEOUT"),
            )
            for ((prov, status, code) in cases) testApplication {
                application {
                    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
                    configureRoutes(JourneySearchService(ResilientProvider(prov)), clock)
                }
                for (path in listOf("/api/journeys?origin=A&destination=B", "/api/alternatives?from=A&to=B&notBefore=2026-11-21T15%3A00%3A00Z")) {
                    val r = client.get(path); val body = r.bodyAsText(); payloads += body
                    assertEquals(status, r.status.value, path)
                    assertEquals(code, Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                }
            }
        } finally { root.detachAppender(app) }
        val logs = app.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        (payloads + logs).forEach {
            assertFalse(it.contains("UPSTREAM-SECRET-BODY"), it); assertFalse(it.contains(token), it)
            assertNull(tokenPattern.find(it), "token-like string in: $it")
        }
        assertTrue(payloads.isNotEmpty() && logs.isNotEmpty())
    }
}
