package com.alt.backend.api

import com.alt.backend.config.AppConfig
import com.alt.backend.config.configureApp
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

/** Advances one second per read so createdAt-based ordering is deterministic in tests. */
internal class TickingClock(private var base: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = base.also { base = base.plusSeconds(1) }
}

/** Shared error-envelope assertion (RouteTest's own is class-private). */
internal suspend fun assertEnvelope(r: HttpResponse, status: HttpStatusCode, code: String, field: String?) {
    assertEquals(status, r.status)
    val e = parse(r.bodyAsText()).o("error")
    assertEquals(code, e.str("code"))
    assertTrue(e.str("message").isNotBlank())
    if (field == null) assertEquals(JsonNull, e["field"]) else assertEquals(field, e.str("field"))
}

class AuthRoutesTest {
    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { configureApp(TickingClock(Instant.parse("2026-11-21T15:00:00Z")), AppConfig(transportProvider = "mock", fareBaseUrl = "", databaseUrl = null)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.register(email: String = "alice@example.ch", password: String = "correct-horse-1", displayName: String? = null): HttpResponse =
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$password"""" + (displayName?.let { ""","displayName":"$it"""" } ?: "") + "}")
        }

    private suspend fun ApplicationTestBuilder.login(email: String = "alice@example.ch", password: String = "correct-horse-1"): HttpResponse =
        client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$password"}""")
        }

    @Test
    fun `register returns user token and expiry`() = appTest {
        val j = parse(register().bodyAsText())
        assertEquals("OK", j.str("status"))
        assertEquals("alice@example.ch", j.o("user").str("email"))
        assertEquals(36, j.o("user").str("id").length)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(j.str("token")), "token must be 64 hex chars")
        assertTrue(j.str("expiresAt").startsWith("2026-12-21"), "expiry is 30 days out")
        assertEquals(JsonNull, j.o("user")["displayName"])
    }

    @Test
    fun `register normalizes email and keeps displayName`() = appTest {
        val j = parse(register(email = "  Alice@Example.CH ", displayName = "Alice").bodyAsText())
        assertEquals("alice@example.ch", j.o("user").str("email"))
        assertEquals("Alice", j.o("user").str("displayName"))
    }

    @Test
    fun `register rejects duplicate email`() = appTest {
        register()
        assertEnvelope(register(), HttpStatusCode.BadRequest, "INVALID_REQUEST", "email")
    }

    @Test
    fun `register validates email and password`() = appTest {
        assertEnvelope(register(email = "not-an-email"), HttpStatusCode.BadRequest, "INVALID_REQUEST", "email")
        assertEnvelope(register(password = "short"), HttpStatusCode.BadRequest, "INVALID_REQUEST", "password")
        assertEnvelope(register(password = "x".repeat(200)), HttpStatusCode.BadRequest, "INVALID_REQUEST", "password")
        assertEnvelope(register(displayName = "n".repeat(90)), HttpStatusCode.BadRequest, "INVALID_REQUEST", "displayName")
    }

    @Test
    fun `register rejects malformed body`() = appTest {
        val r = client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("not json") }
        assertEnvelope(r, HttpStatusCode.BadRequest, "INVALID_REQUEST", null)
    }

    @Test
    fun `login issues a working token`() = appTest {
        register()
        val j = parse(login().bodyAsText())
        assertEquals("OK", j.str("status"))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(j.str("token")))
        val me = parse(client.get("/api/auth/me") { header(HttpHeaders.Authorization, "Bearer ${j.str("token")}") }.bodyAsText())
        assertEquals("OK", me.str("status"))
        assertEquals("alice@example.ch", me.o("user").str("email"))
    }

    @Test
    fun `login failures are identical for unknown email and wrong password`() = appTest {
        register()
        val wrong = login(password = "wrong-password-9")
        val unknown = login(email = "nobody@example.ch")
        assertEnvelope(wrong, HttpStatusCode.Unauthorized, "UNAUTHORIZED", null)
        assertEnvelope(unknown, HttpStatusCode.Unauthorized, "UNAUTHORIZED", null)
        assertEquals(
            parse(wrong.bodyAsText()).o("error").str("message"),
            parse(unknown.bodyAsText()).o("error").str("message")
        )
    }

    @Test
    fun `me requires a valid token`() = appTest {
        register()
        assertEnvelope(client.get("/api/auth/me"), HttpStatusCode.Unauthorized, "UNAUTHORIZED", null)
        assertEnvelope(client.get("/api/auth/me") { header(HttpHeaders.Authorization, "Bearer ${"ab".repeat(32)}") }, HttpStatusCode.Unauthorized, "UNAUTHORIZED", null)
        assertEnvelope(client.get("/api/auth/me") { header(HttpHeaders.Authorization, "Basic not-a-token") }, HttpStatusCode.Unauthorized, "UNAUTHORIZED", null)
    }

    @Test
    fun `logout revokes only the current token`() = appTest {
        val t1 = parse(register().bodyAsText()).str("token")
        val t2 = parse(login().bodyAsText()).str("token")
        val out = client.post("/api/auth/logout") { header(HttpHeaders.Authorization, "Bearer $t1") }
        assertEquals(HttpStatusCode.OK, out.status)
        assertEquals("OK", parse(out.bodyAsText()).str("status"))
        assertEnvelope(client.get("/api/auth/me") { header(HttpHeaders.Authorization, "Bearer $t1") }, HttpStatusCode.Unauthorized, "UNAUTHORIZED", null)
        assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { header(HttpHeaders.Authorization, "Bearer $t2") }.status)
        assertEnvelope(client.post("/api/auth/logout") { header(HttpHeaders.Authorization, "Bearer $t1") }, HttpStatusCode.Unauthorized, "UNAUTHORIZED", null)
    }
}
