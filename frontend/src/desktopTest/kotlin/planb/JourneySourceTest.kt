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
            "/api/locations" to """{"locations":[{"id":"location-1","name":"Rue de Bourg 1, Lausanne","kind":"address"}]}""",
            "/api/model" to """{"status":"ok","modelVersion":"fixture-model"}""",
            "/api/alternatives" to """{"alternatives":[],"noFeasibleRoute":true}"""
        )) server.createContext(path) { exchange ->
            exchange.requestURI.rawQuery?.let { query.set(URLDecoder.decode(it, Charsets.UTF_8)) }
            val body = json.encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/health") { exchange ->
            val body = """{"status":"ok"}""".encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/api/journeys") { exchange ->
            query.set(URLDecoder.decode(exchange.requestURI.rawQuery, Charsets.UTF_8))
            val body = """
                {"request":{"origin":"Lausanne","destination":"Genève",
                "earliestDeparture":"2026-11-21T14:00:00+01:00","arrivalDeadline":null,"preference":"FASTEST"},
                "journeys":[{"id":"fixture","departure":"2026-11-21T14:18:00+01:00",
                "arrival":"2026-11-21T15:04:00+01:00","durationMinutes":46,
                "transfers":0,"dataStatus":"MOCK","legs":[{"mode":"TRANSIT","origin":"Lausanne",
                "destination":"Genève","departure":"2026-11-21T14:18:00+01:00",
                "arrival":"2026-11-21T15:04:00+01:00","departurePlatform":"4","arrivalPlatform":"7"}]}]}
            """.trimIndent().encodeToByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val source = JourneySource("http://127.0.0.1:${server.address.port}")
        try {
            assertTrue(runBlocking { source.health() })
            assertEquals("fixture-model", runBlocking { source.model().modelVersion })
            val address = runBlocking { source.locations("Rue de Bourg").single() }
            assertEquals("address", address.kind)
            assertEquals("query=Rue de Bourg", query.get())
            val deadline = Instant.parse("2026-11-21T15:00:00Z")
            val journey = runBlocking {
                source.search(address.name, "Genève", null, firstClass = true, halfFare = true, deadline = deadline).journeys.single()
            }
            assertEquals("fixture", journey.id)
            assertEquals("MOCK", journey.dataStatus)
            assertEquals("4", journey.legs.single().departurePlatform)
            assertEquals("7", journey.legs.single().arrivalPlatform)
            assertEquals("TRANSIT", journey.legs.single().mode)
            assertEquals(setOf("origin=${address.name}", "destination=Genève", "arrivalDeadline=$deadline",
                "preference=FASTEST", "fareClass=1", "discount=HALF_FARE"), query.get().split('&').toSet())
            val notBefore = Instant.parse("2026-11-21T14:00:00Z")
            val fallback = runBlocking { source.alternatives("Morges", "Genève", notBefore, deadline) }
            assertEquals(true, fallback.noFeasibleRoute)
            assertEquals(setOf("from=Morges", "to=Genève", "notBefore=$notBefore", "arrivalDeadline=$deadline"),
                query.get().split('&').toSet())
        } finally {
            source.close()
            server.stop(0)
        }
    }
}
