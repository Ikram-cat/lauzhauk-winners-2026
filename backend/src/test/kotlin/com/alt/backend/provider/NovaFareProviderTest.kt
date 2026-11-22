package com.alt.backend.provider

import com.alt.backend.domain.Discount
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class NovaFareProviderTest {
    // Captured 2026-11-21 from POST transport.opendata.ch/v1/fare (Lausanne -> Zürich HB)
    private val fareBody = javaClass.getResource("/nova_fare_fixture.json")!!.readText()

    private fun provider(fare: String) = NovaFareProvider("http://x", HttpClient(MockEngine { req ->
        val body = if (req.url.encodedPath.endsWith("/connections")) """{"connections":[{"from":{}}]}""" else fare
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }))

    @Test fun `parses all four prices from a real response`() = runBlocking {
        val q = provider(fareBody).quote("Lausanne", "Zürich", OffsetDateTime.parse("2026-11-21T09:00:00Z"))!!
        assertEquals(FareQuote(77.0, 38.5, 131.0, "Streckenbillett", "NOVA", 65.5), q)
        assertEquals(listOf(77.0, 38.5, 131.0, 65.5), listOf(2 to Discount.NONE, 2 to Discount.HALF_FARE, 1 to Discount.NONE, 1 to Discount.HALF_FARE).map { q.price(it.first, it.second) })
    }

    @Test fun `missing 1st class leaves 1st prices null`() = runBlocking {
        val q = provider("""{"currency":"CHF","product":"Streckenbillett","fares":{"2nd":{"full":10.0,"halfFare":5.0}}}""")
            .quote("A", "B", OffsetDateTime.parse("2026-11-21T09:00:00Z"))!!
        assertEquals(null, q.full1st); assertEquals(null, q.half1st); assertEquals(null, q.price(1, Discount.HALF_FARE))
    }
}
