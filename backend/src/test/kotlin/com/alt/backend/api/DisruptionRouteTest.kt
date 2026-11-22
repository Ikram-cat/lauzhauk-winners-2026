package com.alt.backend.api

import com.alt.backend.config.AppConfig
import com.alt.backend.config.configureApp
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class DisruptionRouteTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-11-21T07:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `outlook is UNAVAILABLE without artifact and rejects an invalid timestamp`() = testApplication {
        application { configureApp(fixedClock, AppConfig(transportProvider = "mock", durationArtifactPath = null)) }

        val ok = client.post("/api/disruptions/outlook") {
            contentType(ContentType.Application.Json)
            setBody("""{"disruption":{"reasonCode":"EG_008","durationType":"UNTIL",
                "startedAt":"2026-11-21T07:40:00+01:00","announcedEndAt":"2026-11-21T09:00:00+01:00",
                "updatesSoFar":2,"summaryEn":"Interruption between Lausanne and Genève"},
                "observedAt":"2026-11-21T08:00:00+01:00","detourExtraMinutes":35}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        val body = Json.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertEquals("UNAVAILABLE", body["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["remainingMinutes"])
        assertEquals(JsonNull, body["provenance"])
        assertEquals(JsonNull, body["explanation"])
        assertEquals(60, body["officialRemainingMinutes"]!!.jsonPrimitive.int)
        assertEquals("UNKNOWN", body["advice"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val bad = client.post("/api/disruptions/outlook") {
            contentType(ContentType.Application.Json)
            setBody("""{"disruption":{"startedAt":"not-a-date"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val error = Json.parseToJsonElement(bad.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("INVALID_REQUEST", error["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `journey impact without disruption feed returns the journey, unaffected`() = testApplication {
        application { configureApp(fixedClock, AppConfig(transportProvider = "mock", durationArtifactPath = null,
            disruptionTimelinesDir = null)) }
        val response = client.post("/api/journeys/impact") {
            contentType(ContentType.Application.Json)
            setBody("""{"origin":"Lausanne","destination":"Genève","observedAt":"2026-09-17T18:31:00+02:00"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["affected"]!!.jsonPrimitive.boolean)
        assertEquals("MOCK", body["dataStatus"]!!.jsonPrimitive.content)
        assertEquals(0, body["impacts"]!!.jsonArray.size)
        val live = body["liveAlerts"]!!.jsonObject
        assertEquals("LIVE", live["dataStatus"]!!.jsonPrimitive.content)
        assertEquals("DISABLED", live["status"]!!.jsonPrimitive.content)
    }

    private suspend fun ApplicationTestBuilder.rejected(path: String, body: String, field: String?) {
        val r = client.post(path) { contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val e = Json.parseToJsonElement(r.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("INVALID_REQUEST", e["code"]!!.jsonPrimitive.content)
        assertEquals(field, e["field"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `POST bodies and string fields are bounded`() = testApplication {
        application { configureApp(fixedClock, AppConfig(transportProvider = "mock", durationArtifactPath = null, disruptionTimelinesDir = null)) }
        val big = "x".repeat(65 * 1024)
        rejected("/api/journeys/impact", """{"origin":"Lausanne","destination":"Genève","journeyId":"$big"}""", "body")
        rejected("/api/disruptions/outlook", """{"disruption":{"startedAt":"2026-11-21T07:00:00Z","summaryEn":"$big"}}""", "body")
        rejected("/api/journeys/impact", """{"origin":"Lausanne","destination":"Genève","journeyId":"${"j".repeat(121)}"}""", "journeyId")
        rejected("/api/journeys/impact", """{"origin":"${"o".repeat(121)}","destination":"Genève"}""", "origin")
        rejected("/api/disruptions/outlook", """{"disruption":{"startedAt":"2026-11-21T07:00:00Z","reasonCode":"${"r".repeat(121)}"}}""", "disruption.reasonCode")
        rejected("/api/disruptions/outlook", """{"disruption":{"startedAt":"2026-11-21T07:00:00Z","summaryEn":"${"s".repeat(2001)}"}}""", "disruption.summaryEn")
        rejected("/api/disruptions/outlook", "{not json", null)
        val ok = client.post("/api/disruptions/outlook") {
            contentType(ContentType.Application.Json)
            setBody("""{"disruption":{"startedAt":"2026-11-21T07:00:00Z","reasonCode":"${"r".repeat(120)}","summaryEn":"${"s".repeat(2000)}"},"observedAt":"2026-11-21T08:00:00Z"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
    }
}
