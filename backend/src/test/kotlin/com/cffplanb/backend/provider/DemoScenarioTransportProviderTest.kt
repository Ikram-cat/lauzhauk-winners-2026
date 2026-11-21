package com.cffplanb.backend.provider

import com.cffplanb.backend.api.buildOutlook
import com.cffplanb.backend.domain.*
import com.cffplanb.backend.service.JourneySearchService
import com.cffplanb.backend.service.duration.DurationArtifactLoader
import com.cffplanb.backend.service.impact.DisruptionSource
import com.cffplanb.backend.service.impact.ImpactResult
import com.cffplanb.backend.service.impact.JourneyImpactService
import com.cffplanb.backend.service.impact.ReplayTimelineDisruptionSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.*

private fun station(uic: Int, name: String, role: String) =
    AffectedStation(uic, "ch:1:sloid:${uic - 8500000}", name, listOf(role))

private fun disruption(id: String, stations: List<AffectedStation>) = ActiveDisruption(
    id, "synthetic", null, stations, emptyList(), "MOCK")

private class Fixed(private val d: List<ActiveDisruption>) : DisruptionSource {
    override val dataStatus = "MOCK"
    override fun active(at: OffsetDateTime) = d
}

class DemoScenarioTransportProviderTest {
    private val at = OffsetDateTime.parse("2026-09-17T18:31:00+02:00")
    private val renens = disruption("renens", listOf(
        station(8501118, "Renens VD", "DISRUPTION_POINT"), station(8501037, "Morges", "DISRUPTION_POINT"),
        station(8501035, "Allaman", "DISRUPTION_POINT"), station(8501120, "Lausanne", "LINE_FROM"),
        station(8501008, "Genève", "LINE_TO")))
    private val stadelhofen = disruption("stadelhofen", listOf(
        station(8503003, "Zürich Stadelhofen", "DISRUPTION_POINT"), station(8503147, "Stettbach", "DISRUPTION_POINT")))

    private suspend fun impact(origin: String, destination: String, source: DisruptionSource, observedAt: OffsetDateTime = at) =
        (JourneyImpactService(JourneySearchService(DemoScenarioTransportProvider()), source)
            .check(SearchRequest(origin, destination, observedAt), observedAt) as ImpactResult.Success)

    @Test
    fun `demo journeys are MOCK and give a real detour for both demo incidents`() = runTest {
        val lausanne = impact("Lausanne", "Geneve", Fixed(listOf(renens)))
        assertEquals("demo-renens-ic1", lausanne.planned!!.id)
        assertTrue(lausanne.planned!!.dataStatus == DataStatus.MOCK)
        val i = lausanne.impacts.single()
        assertEquals("demo-renens-lake", i.alternative!!.id)
        assertEquals(100, i.detourExtraMinutes)

        val zurich = impact("Zürich Stadelhofen", "Stettbach", Fixed(listOf(stadelhofen)))
        assertEquals("demo-stadelhofen-tram", zurich.impacts.single().alternative!!.id)
        assertEquals(23, zurich.impacts.single().detourExtraMinutes)
    }

    @Test
    fun `other requests fall back on the team mock unchanged`() = runTest {
        val request = SearchRequest("Bern", "Basel", at)
        assertEquals(
            (MockTransportProvider().search(request) as ProviderResult.Success).journeys,
            (DemoScenarioTransportProvider().search(request) as ProviderResult.Success).journeys)
    }

    @Test
    fun `advice is never UNKNOWN at the demo instants of the replays`() = runTest {
        val out = File("../analytics/output")
        val artifact = File(out, "duration_model.v1.eval.json")
        assumeTrue(artifact.isFile && File(out, "timelines/timeline-2195005188970.json").isFile, "outputs not generated")
        val estimator = DurationArtifactLoader.load(artifact.path)
        val source = ReplayTimelineDisruptionSource.load(File(out, "timelines").path)
        val cases = listOf("Lausanne" to "Genève") * listOf("17:44", "18:31", "19:09", "20:03", "20:30", "21:00")
            .map { "2026-09-17T$it:00+02:00" } +
            listOf("Zürich Stadelhofen" to "Stettbach") * listOf("15:53", "18:40", "20:00").map { "2026-09-25T$it:00+02:00" }
        for ((od, time) in cases) {
            val observedAt = OffsetDateTime.parse(time)
            val result = impact(od.first, od.second, source, observedAt)
            val i = result.impacts.single()
            val outlook = buildOutlook(estimator, i.match.disruption.context!!, observedAt, i.detourExtraMinutes)
            assertNotNull(i.detourExtraMinutes, "$od $time")
            assertNotEquals("UNKNOWN", outlook.advice.code, "$od $time")
        }
    }

    private operator fun <A> List<A>.times(times: List<String>) = flatMap { od -> times.map { od to it } }
}
