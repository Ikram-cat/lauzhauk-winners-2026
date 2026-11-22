@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class JourneySourceTest {
    @Test
    fun configuredClientQueriesAndDecodesTheBackend() {
        val query = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        for ((path, json) in mapOf(
            "/api/locations" to """{"locations":[{"id":"ch:1:sloid:1","name":"Rue de Bourg 1, Lausanne","kind":"ADDRESS","coordinates":null}]}""",
            "/api/model" to """{"riskModel":{"version":"fixture-model","status":"OK"},"delayModel":{"version":"fixture-delay","status":"OK"}}"""
        )) server.createContext(path) { exchange ->
            exchange.requestURI.rawQuery?.let { query.set(URLDecoder.decode(it, Charsets.UTF_8)) }
            val body = json.encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/health") { exchange ->
            val body = """{"status":"OK","feeds":{"ojp":"OK","nova":"OK"}}""".encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/api/journeys") { exchange ->
            query.set(URLDecoder.decode(exchange.requestURI.rawQuery, Charsets.UTF_8))
            val body = """
                {"request":{"origin":{"input":"Lausanne","resolvedName":"Lausanne"},"destination":{"input":"Genève","resolvedName":"Genève"},
                "departure":"2026-11-21T14:00:00+01:00","arrivalDeadline":null,"preference":"FASTEST"},
                "status":"NO_DISRUPTION","action":"STAY","reasons":[{"code":"STAY_FASTEST"}],"disruption":null,
                "primaryJourney":{"id":"fixture","dataStatus":"LIVE_SCHEDULE","verified":true,
                "schedule":{"departure":"2026-11-21T14:18:00+01:00","arrival":"2026-11-21T15:04:00+01:00","durationMinutes":46,"transfers":0},
                "fares":{"status":"PRICED","selectedChf":10.0,"catalogChf":{"secondClassFull":10.0,"secondClassHalfFare":5.0,"firstClassFull":17.0,"firstClassHalfFare":8.5}},
                "legs":[{"mode":"TRANSIT","origin":"Lausanne","destination":"Genève","departure":"2026-11-21T14:18:00+01:00",
                "arrival":"2026-11-21T15:04:00+01:00","departurePlatform":"4","arrivalPlatform":"7"}]},
                "recommendedJourney":{"id":"fixture","dataStatus":"LIVE_SCHEDULE","verified":true,
                "schedule":{"departure":"2026-11-21T14:18:00+01:00","arrival":"2026-11-21T15:04:00+01:00","durationMinutes":46,"transfers":0},
                "fares":{"status":"PRICED","selectedChf":10.0,"catalogChf":{"secondClassFull":10.0,"secondClassHalfFare":5.0,"firstClassFull":17.0,"firstClassHalfFare":8.5}},
                "legs":[{"mode":"TRANSIT","origin":"Lausanne","destination":"Genève","departure":"2026-11-21T14:18:00+01:00",
                "arrival":"2026-11-21T15:04:00+01:00","departurePlatform":"4","arrivalPlatform":"7"}]},
                "alternatives":[],"highlights":{"fastestId":"fixture","simplestId":"fixture","cheapestId":"fixture"},
                "deadlineSummary":null,"meta":{"computedAt":"2026-11-21T14:00:00Z","modelVersion":"fixture-model"}}
            """.trimIndent().encodeToByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val source = JourneySource("http://127.0.0.1:${server.address.port}")
        try {
            assertTrue(runBlocking { source.health() })
            assertEquals("fixture-model", runBlocking { source.model().riskModel?.version })
            val address = runBlocking { source.locations(query = "Rue de Bourg").single() }
            assertEquals("ADDRESS", address.kind)
            assertEquals("query=Rue de Bourg", query.get())
            val deadline = Instant.parse("2026-11-21T15:00:00Z")
            val response = runBlocking {
                source.search(address.name, "Genève", null, firstClass = true, halfFare = true, deadline = deadline)
            }
            val journey = response.recommendedJourney!!
            assertEquals("fixture", journey.id)
            assertEquals("LIVE_SCHEDULE", journey.dataStatus)
            assertEquals("4", journey.legs.single().departurePlatform)
            assertEquals("7", journey.legs.single().arrivalPlatform)
            assertEquals("TRANSIT", journey.legs.single().mode)
            assertEquals("STAY", response.action)
            assertEquals(journey.id, response.primaryJourney?.id) // STAY => same journey as primary
            assertEquals(17.0, journey.catalogFare(firstClass = true, halfFare = false))
            assertEquals(setOf("origin=${address.name}", "destination=Genève", "arrivalDeadline=$deadline",
                "preference=FASTEST", "fareClass=1", "discount=HALF_FARE"), query.get().split('&').toSet())

            // Rescue/"Plan B": same /api/journeys call, origin = the stuck station, departure = now.
            val notBefore = Instant.parse("2026-11-21T14:00:00Z")
            runBlocking { source.search("Morges", "Genève", notBefore, deadline = deadline) }
            assertEquals(setOf("origin=Morges", "destination=Genève", "departure=$notBefore", "arrivalDeadline=$deadline",
                "preference=FASTEST", "fareClass=2", "discount=NONE"), query.get().split('&').toSet())
        } finally {
            source.close()
            server.stop(0)
        }
    }

    @Test
    fun decodesDisruptionDetectedWithRerouteAction() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/journeys") { exchange ->
            val body = """
                {
                  "status": "DISRUPTION_DETECTED",
                  "action": "REROUTE",
                  "reasons": [{"code": "EARLIER_ARRIVAL"}],
                  "disruption": {
                    "legIndex": 0, "serviceLabel": "RE33", "from": "Renens VD", "to": "Genève",
                    "scheduledDeparture": "2026-11-21T20:33:00Z", "delayMinutes": 22, "cancelled": false,
                    "basis": "REALTIME_ESTIMATE", "confidence": "HIGH", "severity": "SEVERE", "cause": "Panne de signalisation"
                  },
                  "primaryJourney": {"id": "orig-1", "dataStatus": "LIVE_SCHEDULE", "verified": true,
                    "schedule": {"departure": "2026-11-21T20:33:00Z", "arrival": "2026-11-21T21:30:00Z", "durationMinutes": 57, "transfers": 0}, "legs": []},
                  "recommendedJourney": {"id": "rec-1", "dataStatus": "LIVE_SCHEDULE", "verified": true,
                    "schedule": {"departure": "2026-11-21T20:40:00Z", "arrival": "2026-11-21T21:20:00Z", "durationMinutes": 40, "transfers": 0}, "legs": []},
                  "alternatives": [],
                  "highlights": {"fastestId": "rec-1", "simplestId": "rec-1", "cheapestId": "rec-1"},
                  "deadlineSummary": null,
                  "meta": {"computedAt": "2026-11-21T20:34:00Z", "modelVersion": "fixture-model"}
                }
            """.trimIndent().encodeToByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val source = JourneySource("http://127.0.0.1:${server.address.port}")
        try {
            val response = runBlocking { source.search("Renens VD", "Genève", null) }
            assertEquals("DISRUPTION_DETECTED", response.status)
            assertEquals("REROUTE", response.action)
            assertEquals("RE33", response.disruption?.serviceLabel)
            assertEquals(22, response.disruption?.delayMinutes)
            assertEquals(false, response.disruption?.cancelled)
            assertEquals("Panne de signalisation", response.disruption?.cause)
            assertEquals("SEVERE", response.disruption?.severity)
            assertEquals("rec-1", response.recommendedJourney?.id)
            assertEquals("orig-1", response.primaryJourney?.id)
            assertEquals(true, response.primaryJourney?.id != response.recommendedJourney?.id) // REROUTE => they differ
            assertEquals("EARLIER_ARRIVAL", response.reasons.first().code)
        } finally {
            source.close()
            server.stop(0)
        }
    }
}
