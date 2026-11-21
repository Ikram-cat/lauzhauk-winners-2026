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

class JourneySourceTest {
    @Test
    fun configuredClientQueriesAndDecodesTheV1Endpoint() {
        val query = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
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
            val journey = runBlocking { source.search("Lausanne", "Genève", null).single() }
            assertEquals("fixture", journey.id)
            assertEquals("MOCK", journey.dataStatus)
            assertEquals("4", journey.legs.single().departurePlatform)
            assertEquals("7", journey.legs.single().arrivalPlatform)
            assertEquals("TRANSIT", journey.legs.single().mode)
            assertEquals(setOf("origin=Lausanne", "destination=Genève"), query.get().split('&').toSet())
        } finally {
            source.close()
            server.stop(0)
        }
    }
}
