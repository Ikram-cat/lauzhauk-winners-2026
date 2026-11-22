package com.alt.backend.api

import com.alt.backend.config.AppConfig
import com.alt.backend.config.configureApp
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonNull
import java.time.Instant
import kotlin.test.*

class PlansRoutesTest {
    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { configureApp(TickingClock(Instant.parse("2026-11-21T15:00:00Z")), AppConfig(transportProvider = "mock", fareBaseUrl = "", databaseUrl = null)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.registerAndGet(email: String = "plan@example.ch"): String =
        parse(client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"correct-horse-1"}""")
        }.bodyAsText()).str("token")

    private suspend fun ApplicationTestBuilder.createPlan(
        token: String?, origin: String = "Lausanne", destination: String = "Genève",
        departure: String = "2026-11-21T16:00:00+01:00", journey: String? = """{"id":"j1","legs":[]}"""
    ): HttpResponse = client.post("/api/plans") {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody("""{"origin":{"name":"$origin","id":"ch:1:sloid:7000"},"destination":{"name":"$destination"},"departure":"$departure"""" + (journey?.let { ""","journey":$it""" } ?: "") + "}")
    }

    @Test
    fun `plan create and list roundtrip with snapshot`() = appTest {
        val t = registerAndGet()
        val r = createPlan(t)
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(ContentType.Application.Json.withCharset(Charsets.UTF_8), r.contentType())
        val p = parse(r.bodyAsText())
        assertEquals("Lausanne", p.o("origin").str("name"))
        assertEquals("ch:1:sloid:7000", p.o("origin").str("id"))
        assertEquals("Genève", p.o("destination").str("name"))
        assertEquals("2026-11-21T16:00:00+01:00", p.str("departure"))
        assertEquals("j1", p.o("journey").str("id"))
        assertTrue(p.str("createdAt").startsWith("2026-11-21"))
        val list = parse(client.get("/api/plans") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText())
        assertEquals(1, list.a("plans").size)
        assertEquals(p.str("id"), list.a("plans")[0].obj().str("id"))
        assertEquals("j1", list.a("plans")[0].obj().o("journey").str("id"))
    }

    @Test
    fun `plans require a bearer token`() = appTest {
        registerAndGet()
        assertEnvelope(client.post("/api/plans") { contentType(ContentType.Application.Json); setBody("{}") }, HttpStatusCode.Unauthorized, "UNAUTHORIZED", null)
        assertEnvelope(client.get("/api/plans"), HttpStatusCode.Unauthorized, "UNAUTHORIZED", null)
        assertEnvelope(client.delete("/api/plans/00000000-0000-0000-0000-000000000000"), HttpStatusCode.Unauthorized, "UNAUTHORIZED", null)
    }

    @Test
    fun `plan validation errors carry fields`() = appTest {
        val t = registerAndGet()
        val auth = { r: HttpRequestBuilder -> r.header(HttpHeaders.Authorization, "Bearer $t") }
        assertEnvelope(client.post("/api/plans") {
            auth(this); contentType(ContentType.Application.Json)
            setBody("""{"destination":{"name":"B"},"departure":"2026-11-21T16:00:00+01:00"}""")
        }, HttpStatusCode.BadRequest, "INVALID_REQUEST", "origin")
        assertEnvelope(client.post("/api/plans") {
            auth(this); contentType(ContentType.Application.Json)
            setBody("""{"origin":{"name":"A"},"destination":{"name":"B"},"departure":"tomorrow"}""")
        }, HttpStatusCode.BadRequest, "INVALID_REQUEST", "departure")
        assertEnvelope(client.post("/api/plans") {
            auth(this); contentType(ContentType.Application.Json)
            setBody("""{"origin":{"name":"A"},"destination":{"name":"B"},"departure":"2026-11-21T16:00:00+01:00","journey":"${"a".repeat(70000)}"}""")
        }, HttpStatusCode.BadRequest, "INVALID_REQUEST", "journey")
        assertEnvelope(client.post("/api/plans") {
            auth(this); contentType(ContentType.Application.Json); setBody("not json")
        }, HttpStatusCode.BadRequest, "INVALID_REQUEST", null)
    }

    @Test
    fun `plan delete is ownership scoped`() = appTest {
        val ta = registerAndGet("a@example.ch")
        val planId = parse(createPlan(ta).bodyAsText()).str("id")
        val tb = registerAndGet("b@example.ch")
        assertEnvelope(client.delete("/api/plans/$planId") { header(HttpHeaders.Authorization, "Bearer $tb") }, HttpStatusCode.NotFound, "NOT_FOUND", null)
        assertEquals(HttpStatusCode.OK, client.delete("/api/plans/$planId") { header(HttpHeaders.Authorization, "Bearer $ta") }.status)
        assertEnvelope(client.delete("/api/plans/$planId") { header(HttpHeaders.Authorization, "Bearer $ta") }, HttpStatusCode.NotFound, "NOT_FOUND", null)
        assertEnvelope(client.delete("/api/plans/not-a-uuid") { header(HttpHeaders.Authorization, "Bearer $ta") }, HttpStatusCode.NotFound, "NOT_FOUND", null)
        assertEquals(0, parse(client.get("/api/plans") { header(HttpHeaders.Authorization, "Bearer $ta") }.bodyAsText()).a("plans").size)
    }

    @Test
    fun `plan list limit clamps and rejects bad values`() = appTest {
        val t = registerAndGet()
        createPlan(t); createPlan(t); createPlan(t)
        val plans = parse(client.get("/api/plans?limit=2") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText())
        assertEquals(2, plans.a("plans").size)
        assertEnvelope(client.get("/api/plans?limit=0") { header(HttpHeaders.Authorization, "Bearer $t") }, HttpStatusCode.BadRequest, "INVALID_REQUEST", "limit")
        assertEnvelope(client.get("/api/plans?limit=101") { header(HttpHeaders.Authorization, "Bearer $t") }, HttpStatusCode.BadRequest, "INVALID_REQUEST", "limit")
        assertEnvelope(client.get("/api/plans?limit=1&limit=2") { header(HttpHeaders.Authorization, "Bearer $t") }, HttpStatusCode.BadRequest, "INVALID_REQUEST", "limit")
    }

    @Test
    fun `plans list newest first`() = appTest {
        val t = registerAndGet()
        createPlan(t, origin = "Bern", departure = "2026-11-22T08:00:00+01:00")
        createPlan(t, origin = "Basel", departure = "2026-11-23T08:00:00+01:00")
        val plans = parse(client.get("/api/plans") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText()).a("plans")
        assertEquals(listOf("Basel", "Bern"), plans.map { it.obj().o("origin").str("name") })
    }

    @Test
    fun `plans omit optional fields as null`() = appTest {
        val t = registerAndGet()
        val p = parse(createPlan(t, journey = null).bodyAsText())
        assertEquals("ch:1:sloid:7000", p.o("origin").str("id"))
        assertEquals(JsonNull, p["label"])
        assertEquals(JsonNull, p["journey"])
    }
}
