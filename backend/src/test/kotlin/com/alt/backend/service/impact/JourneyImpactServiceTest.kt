package com.alt.backend.service.impact

import com.alt.backend.api.buildOutlook
import com.alt.backend.domain.*
import com.alt.backend.provider.ProviderResult
import com.alt.backend.provider.TransportProvider
import com.alt.backend.service.JourneySearchService
import com.alt.backend.service.duration.DurationArtifactLoader
import com.alt.backend.service.duration.NoDurationEstimator
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.*

private val AT: OffsetDateTime = OffsetDateTime.parse("2026-09-17T18:30:00+02:00")

private fun leg(from: String, to: String, fromSloid: String?, toSloid: String?, dep: Long, arr: Long, label: String?,
                via: List<String> = emptyList()) =
    JourneyLeg(LegMode.TRANSIT, from, to, AT.plusMinutes(dep), AT.plusMinutes(arr), label, null, null,
        fromSloid, toSloid, via)

private fun journey(id: String, vararg legs: JourneyLeg) =
    Journey(id, legs.first().departure, legs.last().arrival,
        java.time.Duration.between(legs.first().departure, legs.last().arrival).toMinutes().toInt(),
        legs.size - 1, legs.toList(), DataStatus.MOCK)

/** Planned IC 1 Lausanne -> Genève via Morges, and a lake alternative that avoids the Renens - Allaman line. */
private val lausanneGeneva = listOf(
    journey("ic1", leg("Lausanne", "Genève", "ch:1:sloid:1120", "ch:1:sloid:1008", 5, 40, "IC 1",
        via = listOf("ch:1:sloid:1037"))),
    journey("lake",
        leg("Lausanne-Ouchy", "Évian-les-Bains", null, null, 10, 45, "CGN boat"),
        leg("Évian-les-Bains", "Genève", null, "ch:1:sloid:1008", 60, 135, "L1"))
)

private class FakeProvider(private val journeys: List<Journey>) : TransportProvider {
    override suspend fun search(request: SearchRequest) = ProviderResult.Success(journeys)
}

private fun station(uic: Int, name: String, role: String) =
    AffectedStation(uic, "ch:1:sloid:${uic - 8500000}", name, listOf(role))

/** Synthetic disruption with the stations and lines VIA records for an interruption Renens VD - Allaman. */
private val renensLike = ActiveDisruption(
    incidentId = "synthetic", summaryEn = "Interrupted service: Renens VD - Morges",
    context = DisruptionContext("EG_011", "UNTIL_APPROX_DATE_TIME", AT.minusMinutes(68), AT.plusMinutes(120), 3,
        "Interrupted service: Renens VD - Morges"),
    stations = listOf(station(8501118, "Renens VD", "DISRUPTION_POINT"), station(8501035, "Allaman", "DISRUPTION_POINT"),
        station(8501037, "Morges", "DISRUPTION_POINT"), station(8501120, "Lausanne", "LINE_FROM"),
        station(8501008, "Genève", "LINE_TO")),
    lines = listOf("IC1", "IR15", "IR57"), dataStatus = "MOCK"
)

private class FixedSource(private val disruptions: List<ActiveDisruption>) : DisruptionSource {
    override val dataStatus = "MOCK"
    override fun active(at: OffsetDateTime) = disruptions
}

class JourneyImpactServiceTest {

    @Test
    fun `matching uses disruption points or two affected stations, by sloid or by name`() {
        val ic1 = JourneyDisruptionMatcher.match(lausanneGeneva[0], renensLike)!!
        assertEquals(listOf(8501120, 8501037, 8501008), ic1.stations.map { it.station.uic })
        assertEquals(listOf("IC1"), ic1.lines)
        assertNull(JourneyDisruptionMatcher.match(lausanneGeneva[1], renensLike), "lake route only reaches Genève")
        val toBern = journey("bern", leg("Lausanne", "Bern", "ch:1:sloid:1120", "ch:1:sloid:7000", 0, 66, "IC 1"))
        assertNull(JourneyDisruptionMatcher.match(toBern, renensLike), "only one line-end station")
        val mockNames = journey("mock", leg("Lausanne", "Geneve", null, null, 0, 40, "Demo IC"))
        assertTrue(JourneyDisruptionMatcher.match(mockNames, renensLike)!!.stations.all { it.by == "NAME" })
    }

    @Test
    fun `detour is the best unaffected alternative minus the planned arrival`() = runTest {
        val service = JourneyImpactService(JourneySearchService(FakeProvider(lausanneGeneva)), FixedSource(listOf(renensLike)))
        val result = service.check(SearchRequest("Lausanne", "Genève", AT), AT) as ImpactResult.Success
        assertEquals("ic1", result.planned!!.id)
        assertEquals(95, result.impacts.single().detourExtraMinutes)
        assertEquals("lake", result.impacts.single().alternative!!.id)
    }

    @Test
    fun `Renens replay touches Lausanne - Geneva and the advice uses the computed detour`() = runTest {
        val dir = File(System.getenv("DISRUPTION_TIMELINES_DIR") ?: "../analytics/output/timelines")
        assumeTrue(File(dir, "timeline-2195005188970.json").isFile, "timelines not generated")
        val source = ReplayTimelineDisruptionSource.load(dir.path)
        val service = JourneyImpactService(JourneySearchService(FakeProvider(lausanneGeneva)), source)
        val impact = (service.check(SearchRequest("Lausanne", "Genève", AT), AT) as ImpactResult.Success).impacts
            .single { it.match.disruption.incidentId == "2195005188970" }
        assertEquals(95, impact.detourExtraMinutes)
        assertTrue(impact.match.stations.any { "DISRUPTION_POINT" in it.station.roles }, "passes Morges")
        val artifact = File(System.getenv("DURATION_ARTIFACT_PATH") ?: "../analytics/output/duration_model.v1.json")
        val estimator = if (artifact.isFile) DurationArtifactLoader.load(artifact.path) else NoDurationEstimator
        val outlook = buildOutlook(estimator, impact.match.disruption.context!!, AT, impact.detourExtraMinutes)
        if (artifact.isFile) {
            assertEquals("AVAILABLE", outlook.status)
            assertNotEquals("UNKNOWN", outlook.advice.code)
            println("Renens 18:30, Lausanne -> Genève, detour +95 min: $outlook")
        }
    }
}
