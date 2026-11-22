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

class TravelsRoutesTest {
    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { configureApp(TickingClock(Instant.parse("2026-11-21T15:00:00Z")), AppConfig(transportProvider = "mock", fareBaseUrl = "", databaseUrl = null)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.registerAndGet(email: String = "travel@example.ch"): String =
        parse(client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"correct-horse-1"}""")
        }.bodyAsText()).str("token")

    private suspend fun ApplicationTestBuilder.createPlan(token: String): String =
        parse(client.post("/api/plans") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"origin":{"name":"Lausanne"},"destination":{"name":"Genève"},"departure":"2026-11-21T16:00:00+01:00"}""")
        }.bodyAsText()).str("id")

    private suspend fun ApplicationTestBuilder.postTravel(token: String?, body: String): HttpResponse =
        client.post("/api/travels") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `travel from a saved plan copies its fields`() = appTest {
        val t = registerAndGet()
        val planId = createPlan(t)
        val r = postTravel(t, """{"planId":"$planId","rating":4,"note":"smooth","delayMinutes":0}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val tv = parse(r.bodyAsText())
        assertEquals(planId, tv.str("planId"))
        assertEquals("Lausanne", tv.o("origin").str("name"))
        assertEquals("Genève", tv.o("destination").str("name"))
        assertEquals("2026-11-21T16:00:00+01:00", tv.str("departure"))
        assertEquals("4", tv.str("rating"))
        assertEquals("smooth", tv.str("note"))
        assertEquals("0", tv.str("delayMinutes"))
        assertTrue(tv.str("createdAt").startsWith("2026-11-21"))
        val list = parse(client.get("/api/travels") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText())
        assertEquals(1, list.a("travels").size)
        assertEquals(tv.str("id"), list.a("travels")[0].obj().str("id"))
    }

    @Test
    fun `standalone travel works without a plan`() = appTest {
        val t = registerAndGet()
        val r = postTravel(t, """{"origin":{"name":"Bern"},"destination":{"name":"Basel"},"departure":"2026-11-22T08:00:00+01:00","arrival":"2026-11-22T10:00:00+01:00","rating":5}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val tv = parse(r.bodyAsText())
        assertEquals(JsonNull, tv["planId"])
        assertEquals("Bern", tv.o("origin").str("name"))
        assertEquals("Basel", tv.o("destination").str("name"))
        assertEquals("2026-11-22T10:00:00+01:00", tv.str("arrival"))
        assertEquals("5", tv.str("rating"))
    }

    @Test
    fun `travel validation errors carry fields`() = appTest {
        val t = registerAndGet()
        assertEnvelope(postTravel(t, """{"origin":{"name":"Bern"},"destination":{"name":"Basel"},"departure":"2026-11-22T08:00:00+01:00","rating":6}"""), HttpStatusCode.BadRequest, "INVALID_REQUEST", "rating")
        assertEnvelope(postTravel(t, """{"origin":{"name":"Bern"},"destination":{"name":"Basel"},"departure":"2026-11-22T08:00:00+01:00","note":"${"n".repeat(600)}"}"""), HttpStatusCode.BadRequest, "INVALID_REQUEST", "note")
        assertEnvelope(postTravel(t, """{"origin":{"name":"Bern"},"destination":{"name":"Basel"},"departure":"2026-11-22T08:00:00+01:00","delayMinutes":-5}"""), HttpStatusCode.BadRequest, "INVALID_REQUEST", "delayMinutes")
        assertEnvelope(postTravel(t, """{"destination":{"name":"Basel"},"departure":"2026-11-22T08:00:00+01:00"}"""), HttpStatusCode.BadRequest, "INVALID_REQUEST", "origin")
        assertEnvelope(postTravel(t, """{"planId":"xyz"}"""), HttpStatusCode.BadRequest, "INVALID_REQUEST", "planId")
        assertEnvelope(postTravel(t, """{"planId":"00000000-0000-0000-0000-000000000000"}"""), HttpStatusCode.BadRequest, "INVALID_REQUEST", "planId")
        assertEnvelope(postTravel(t, "not json"), HttpStatusCode.BadRequest, "INVALID_REQUEST", null)
    }

    @Test
    fun `travel from another users plan is not found`() = appTest {
        val ta = registerAndGet("owner@example.ch")
        val planId = createPlan(ta)
        val tb = registerAndGet("guest@example.ch")
        assertEnvelope(postTravel(tb, """{"planId":"$planId"}"""), HttpStatusCode.BadRequest, "INVALID_REQUEST", "planId")
    }

    @Test
    fun `travels list newest first with limit`() = appTest {
        val t = registerAndGet()
        postTravel(t, """{"origin":{"name":"Bern"},"destination":{"name":"Basel"},"departure":"2026-11-22T08:00:00+01:00","note":"first"}""")
        postTravel(t, """{"origin":{"name":"Zurich"},"destination":{"name":"Lugano"},"departure":"2026-11-23T08:00:00+01:00","note":"second"}""")
        postTravel(t, """{"origin":{"name":"Geneva"},"destination":{"name":"Visp"},"departure":"2026-11-24T08:00:00+01:00","note":"third"}""")
        val travels = parse(client.get("/api/travels") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText()).a("travels")
        assertEquals(listOf("third", "second", "first"), travels.map { it.obj().str("note") })
        val limited = parse(client.get("/api/travels?limit=2") { header(HttpHeaders.Authorization, "Bearer $t") }.bodyAsText()).a("travels")
        assertEquals(listOf("third", "second"), limited.map { it.obj().str("note") })
        assertEnvelope(client.get("/api/travels?limit=0") { header(HttpHeaders.Authorization, "Bearer $t") }, HttpStatusCode.BadRequest, "INVALID_REQUEST", "limit")
    }

    @Test
    fun `travels require auth`() = appTest {
        registerAndGet()
        assertEnvelope(postTravel(null, """{"origin":{"name":"A"},"destination":{"name":"B"},"departure":"2026-11-22T08:00:00+01:00"}"""), HttpStatusCode.Unauthorized, "UNAUTHORIZED", null)
        assertEnvelope(client.get("/api/travels"), HttpStatusCode.Unauthorized, "UNAUTHORIZED", null)
    }
}
