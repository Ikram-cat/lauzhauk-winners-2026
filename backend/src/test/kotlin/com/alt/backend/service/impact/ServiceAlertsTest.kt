package com.alt.backend.service.impact

import com.alt.backend.domain.SearchRequest
import com.alt.backend.provider.DemoScenarioTransportProvider
import com.alt.backend.provider.ProviderResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.*

/** Hand-encoded GTFS-RT FeedMessage with official field numbers (same as analytics/tests/test_gtfs_alerts.py). */
private object Pb {
    fun varint(n: Long): ByteArray {
        val out = ByteArrayOutputStream(); var v = n
        while (true) { val b = (v and 0x7F).toInt(); v = v ushr 7; out.write(if (v != 0L) b or 0x80 else b); if (v == 0L) return out.toByteArray() }
    }
    fun f(number: Int, value: Long) = varint((number shl 3).toLong()) + varint(value)
    fun f(number: Int, value: ByteArray) = varint(((number shl 3) or 2).toLong()) + varint(value.size.toLong()) + value
    fun f(number: Int, value: String) = f(number, value.encodeToByteArray())
    fun text(lang: String, s: String) = f(1, f(1, s) + f(2, lang))
}

class ServiceAlertsTest {
    private val start = Instant.parse("2026-11-22T07:00:00Z")
    private val end = Instant.parse("2026-11-22T09:00:00Z")

    private fun feed(): ByteArray {
        val alert = Pb.f(1, Pb.f(1, start.epochSecond) + Pb.f(2, end.epochSecond)) +
            Pb.f(5, Pb.f(1, "11") + Pb.f(5, "ch:1:sloid:1037:0:3")) +
            Pb.f(6, 3) + Pb.f(7, 1) +
            Pb.f(10, Pb.text("en", "Interrupted service: Renens VD - Morges") + Pb.text("fr", "Interruption")) +
            Pb.f(11, Pb.text("en", "Replacement buses."))
        return Pb.f(1, Pb.f(1, "2.0") + Pb.f(3, start.epochSecond)) +
            Pb.f(2, Pb.f(1, "uuid-1") + Pb.f(5, alert)) +
            Pb.f(2, Pb.f(1, "gone") + Pb.f(2, 1) + Pb.f(5, alert))
    }

    @Test
    fun `decoder reads periods, stops, cause, effect and texts and skips deleted entities`() {
        val s = ServiceAlertsDecoder.parse(feed(), start)
        assertEquals(start, s.feedTimestamp)
        val a = s.alerts.single()
        assertEquals("uuid-1", a.id)
        assertEquals(listOf(start to end), a.periods)
        assertEquals(listOf("ch:1:sloid:1037:0:3"), a.stopIds)
        assertEquals("TECHNICAL_PROBLEM" to "NO_SERVICE", a.cause to a.effect)
        assertEquals(mapOf("en" to "Interrupted service: Renens VD - Morges", "fr" to "Interruption"), a.header)
        assertEquals("Replacement buses.", a.description["en"])
    }

    @Test
    fun `duplicates with the same title, either direction, and the same stations are merged`() {
        fun alert(id: String, title: String, agency: String) =
            ServiceAlert(id, listOf(start to end), emptyList(), listOf(agency), "CONSTRUCTION", null, mapOf("en" to title), emptyMap())
        val morges = listOf(0 to "ch:1:sloid:1037")
        val matches = listOf(
            AlertMatch(alert("a", "Interrupted service: Renens VD - Allaman", "11"), morges),
            AlertMatch(alert("b", "Interrupted service: Renens VD - Allaman", "29"), morges),
            AlertMatch(alert("c", "Interrupted service: Allaman - Renens VD", "11"), morges),
            AlertMatch(alert("d", "Limited service: Renens VD - Allaman", "11"), morges),
            AlertMatch(alert("e", "Interrupted service: Renens VD - Allaman", "11"), listOf(0 to "ch:1:sloid:1120")))
        val groups = ServiceAlertGrouper.group(matches)
        assertEquals(3, groups.size)
        val merged = groups.single { it.alerts.size == 3 }
        assertEquals(listOf("a", "b", "c"), merged.alerts.map { it.id })
        assertEquals(listOf("11", "29"), merged.operators)
        assertEquals(listOf("Renens VD - Allaman", "Allaman - Renens VD"), merged.directions)
    }

    @Test
    fun `an active alert at Morges touches the IC 1 Lausanne - Geneva leg, not outside its period`() = runTest {
        val at = OffsetDateTime.parse("2026-11-22T08:00:00Z")
        val journeys = (DemoScenarioTransportProvider().search(SearchRequest("Lausanne", "Genève", at)) as ProviderResult.Success).journeys
        val ic1 = journeys.first { it.id == "demo-renens-ic1" }
        val lake = journeys.first { it.id == "demo-renens-lake" }
        val snapshot = ServiceAlertsDecoder.parse(feed(), start)
        val match = ServiceAlertMatcher.match(ic1, snapshot, at.toInstant()).single()
        assertEquals(listOf(0 to "ch:1:sloid:1037"), match.stops)
        assertTrue(ServiceAlertMatcher.match(lake, snapshot, at.toInstant()).isEmpty(), "lake route does not call at Morges")
        assertTrue(ServiceAlertMatcher.match(ic1, snapshot, end).isEmpty(), "period end is exclusive")
    }

    @Test
    fun `an expired snapshot is served at once while one background fetch refreshes it`() = runBlocking {
        var now = 1_000_000L
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val src = ServiceAlertsSource({ fetches++; if (fetches > 1) gate.await(); feed() }, { now })
        val first = assertNotNull(src.current())
        now += 61_000 // past the TTL, still inside the stale window
        val stale = withTimeout(2_000) { src.current() } // returns although the refresh is blocked
        assertSame(first, stale)
        withTimeout(2_000) { src.current() } // a second caller must not start a second fetch
        gate.complete(Unit); src.settle()
        assertEquals(2, fetches)
        assertNotSame(first, src.current())
    }
}
