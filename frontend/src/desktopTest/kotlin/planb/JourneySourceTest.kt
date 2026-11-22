@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.time.Instant

class JourneySourceTest {
    @Test
    fun configuredClientQueriesAndDecodesTheContract() {
        val locationsQuery = AtomicReference<String>()
        val nearQuery = AtomicReference<String>()
        val journeysQuery = AtomicReference<String>()
        val envelope = """
            { "status": "DISRUPTION_DETECTED", "action": "REROUTE",
              "reasons": [ { "code": "PRIMARY_LEG_DELAYED", "serviceLabel": "IR 90", "delayMinutes": 22 } ],
              "disruption": { "legIndex": 0, "serviceLabel": "IR 90", "from": "Lausanne", "to": "Genève",
                              "scheduledDeparture": "2026-11-21T14:18:00+01:00", "estimatedDeparture": "2026-11-21T14:40:00+01:00",
                              "delayMinutes": 22, "cancelled": false, "basis": "REALTIME_ESTIMATE", "confidence": "HIGH", "severity": "SEVERE" },
              "primaryJourney": { "id": "fixture", "dataStatus": "LIVE_SCHEDULE", "verified": true, "maxOccupancy": "LOW",
                                  "schedule": { "departure": "2026-11-21T14:18:00+01:00", "arrival": "2026-11-21T15:04:00+01:00", "durationMinutes": 46, "transfers": 0 },
                                  "realtime": { "departureDelayMinutes": 22, "arrivalDelayMinutes": 22, "source": "OJP" },
                                  "fares": { "status": "PRICED", "selectedChf": 5.0, "product": "Streckenbillett", "source": "NOVA",
                                             "catalogChf": { "secondClassFull": 10.0, "secondClassHalfFare": 5.0 } },
                                  "legs": [ { "mode": "TRANSIT", "origin": "Lausanne", "destination": "Genève",
                                              "departure": "2026-11-21T14:18:00+01:00", "arrival": "2026-11-21T15:04:00+01:00",
                                              "departurePlatform": "4", "arrivalPlatform": "7", "serviceLabel": "IR 90" } ] },
              "recommendedJourney": { "id": "rec", "dataStatus": "LIVE_SCHEDULE",
                                       "schedule": { "departure": "2026-11-21T14:30:00+01:00", "arrival": "2026-11-21T15:30:00+01:00", "durationMinutes": 60, "transfers": 1 }, "legs": [] },
              "alternatives": [],
              "highlights": { "fastestId": "rec", "simplestId": "rec", "cheapestId": "rec" },
              "deadlineSummary": { "feasible": true, "latestFeasibleDeparture": "2026-11-21T14:30:00+01:00" },
              "meta": { "computedAt": "2026-11-21T14:10:00Z", "modelVersion": "fixture-model" } }
        """.trimIndent()

        fun HttpServer.respond(path: String, body: String, capture: AtomicReference<String>? = null) {
            createContext(path) { exchange ->
                exchange.requestURI.rawQuery?.let { capture?.set(URLDecoder.decode(it, Charsets.UTF_8)) }
                exchange.responseHeaders.set("Content-Type", "application/json")
                val bytes = body.encodeToByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.respond("/health", """{"status":"OK"}""")
        server.respond(
            "/api/model",
            """{"riskModel":{"version":"fixture-model","status":"OK","counts":{"keys":3}},"delayModel":{"version":"fixture-delays","status":"OK","counts":{"portions":5}}}"""
        )
        server.createContext("/api/locations") { exchange ->
            val raw = URLDecoder.decode(exchange.requestURI.rawQuery.orEmpty(), Charsets.UTF_8)
            if ("near=" in raw) {
                nearQuery.set(raw)
                exchange.respondJson("""{"locations":[{"id":"s1","name":"Lausanne","kind":"STOP"}]}""")
            } else {
                locationsQuery.set(raw)
                exchange.respondJson("""{"locations":[{"id":"location-1","name":"Rue de Bourg 1, Lausanne","kind":"address"}]}""")
            }
        }
        server.respond("/api/journeys", envelope, journeysQuery)
        server.start()
        val source = JourneySource("http://127.0.0.1:${server.address.port}")
        try {
            assertTrue(runBlocking { source.health() }, "OK and DEGRADED both count as reachable")
            assertEquals("fixture-model", runBlocking { source.model().modelVersion })
            val address = runBlocking { source.locations("Rue de Bourg").single() }
            assertEquals("address", address.kind)
            assertEquals("query=Rue de Bourg", locationsQuery.get())

            val nearest = runBlocking { source.locationsNear(46.5, 6.63).single() }
            assertEquals("STOP", nearest.kind)
            assertTrue("near=46.5" in nearQuery.get() && "6.63" in nearQuery.get(), nearQuery.get())

            val deadline = Instant.parse("2026-11-21T15:00:00Z")
            val response = runBlocking {
                source.search(address.name, "Genève", null, firstClass = true, halfFare = true, deadline = deadline)
            }
            assertEquals(setOf(
                "origin=${address.name}", "destination=Genève", "arrivalDeadline=$deadline",
                "preference=FASTEST", "fareClass=1", "discount=HALF_FARE", "limit=5"
            ), journeysQuery.get().split('&').toSet())

            assertEquals("DISRUPTION_DETECTED", response.situation)
            assertEquals("SEVERE", response.disruption?.severity)
            assertEquals("IR 90", response.disruption?.serviceLabel)
            assertEquals("rec", response.recommendation?.id)
            val primary = response.journeys.single { it.id == "fixture" }
            assertEquals("LIVE_SCHEDULE", primary.dataStatus)
            assertEquals(22, primary.arrivalDelayMinutes)
            assertEquals("OJP", primary.realtimeSource)
            assertEquals("LOW", primary.maxOccupancy)
            assertEquals(10.0, primary.fare(firstClass = false, halfFare = false))
            assertEquals(5.0, primary.fare(firstClass = false, halfFare = true))
            assertNull(primary.fare(firstClass = true, halfFare = true))
            assertEquals("4", primary.legs.single().departurePlatform)
            assertEquals("IR 90", primary.legs.single().serviceLabel)
            assertEquals("2026-11-21T14:30:00+01:00", response.latestFeasibleDeparture)
            assertFalse(response.noFeasibleRoute == true)
        } finally {
            server.stop(0)
            source.close()
        }
    }
}

private fun com.sun.net.httpserver.HttpExchange.respondJson(body: String) {
    responseHeaders.set("Content-Type", "application/json")
    val bytes = body.encodeToByteArray()
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
