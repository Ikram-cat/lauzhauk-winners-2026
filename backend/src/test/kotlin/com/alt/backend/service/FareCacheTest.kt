package com.alt.backend.service

import com.alt.backend.provider.FareProvider
import com.alt.backend.provider.FareQuote
import com.alt.backend.provider.NovaFareProvider
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.*

class FareCacheTest {
    private val dep = OffsetDateTime.parse("2026-11-22T09:00:00+01:00")
    private val q = FareQuote(42.0, 21.0, 72.0, "Streckenbillett", "NOVA", 36.0)

    @Test fun `key is trimmed and lowercased, negative results expire after 5 min, positive after 6 h`() = runTest {
        var now = 0L; var calls = 0; var answer: FareQuote? = null
        val cache = FareCache(object : FareProvider {
            override suspend fun quote(origin: String, destination: String, departure: OffsetDateTime) = answer.also { calls++ }
        }, nowMs = { now })
        assertNull(cache.quote("Bern", "Basel", dep)); assertNull(cache.quote(" bern ", "BASEL", dep)); assertEquals(1, calls)
        now += 300_001; answer = q
        assertEquals(q, cache.quote("Bern", "Basel", dep)); assertEquals(2, calls)
        now += 6 * 3_600_000L - 1; cache.quote("Bern", "Basel", dep); assertEquals(2, calls)
        now += 2; cache.quote("Bern", "Basel", dep); assertEquals(3, calls)
    }

    @Test fun `size is bounded`() = runTest {
        var calls = 0
        val cache = FareCache(object : FareProvider {
            override suspend fun quote(origin: String, destination: String, departure: OffsetDateTime) = q.also { calls++ }
        }, maxEntries = 2)
        cache.quote("a", "x", dep); cache.quote("b", "x", dep); cache.quote("c", "x", dep) // evicts a
        cache.quote("c", "x", dep); assertEquals(3, calls)
        cache.quote("a", "x", dep); assertEquals(4, calls)
    }

    @Test fun `nova adapter parses fare and posts the first connection`() = runBlocking {
        var posted = ""
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Get) {
                assertEquals("2026-11-22", req.url.parameters["date"]); assertEquals("09:00", req.url.parameters["time"])
                respond("""{"connections":[{"from":{"x":1}},{"from":{"x":2}}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                posted = String((req.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes())
                respond("""{"currency":"CHF","product":"Streckenbillett","fares":{"2nd":{"full":42.0,"halfFare":21.0},"1st":{"full":72.0,"halfFare":36.0}}}""")
            }
        }
        assertEquals(q, NovaFareProvider("http://f", HttpClient(engine)).quote("Bern", "Basel", dep))
        assertEquals("""{"from":{"x":1}}""", posted)
    }

    @Test fun `nova adapter returns null on 503 and on garbage`() = runBlocking {
        val fail = MockEngine { req -> if (req.method == HttpMethod.Get) respond("""{"connections":[{}]}""") else respond("quota", HttpStatusCode.ServiceUnavailable) }
        assertNull(NovaFareProvider("http://f", HttpClient(fail)).quote("a", "b", dep))
        val garbage = MockEngine { respond("<html>") }
        assertNull(NovaFareProvider("http://f", HttpClient(garbage)).quote("a", "b", dep))
        val empty = MockEngine { respond("""{"connections":[]}""") }
        assertNull(NovaFareProvider("http://f", HttpClient(empty)).quote("a", "b", dep))
    }
}
