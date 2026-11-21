package com.cffplanb.backend.service

import com.cffplanb.backend.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

class RiskEstimatorTest {
    private val fixture: Path = Path.of(javaClass.getResource("/predictions_fixture.json")!!.toURI())
    private val clock = Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC)

    // 16:10Z = 17:10 Zurich in November
    private fun journey(vararg labels: String?): Journey {
        val dep = OffsetDateTime.parse("2026-11-21T16:10:00Z")
        val legs = labels.map {
            JourneyLeg(LegMode.TRANSIT, "A", "B", dep, dep.plusMinutes(30), it, null, null)
        }
        return Journey("j", dep, dep.plusMinutes(30), 30, 0, legs, DataStatus.LIVE_SCHEDULE)
    }

    private fun est(p: Path = fixture) = ArtifactRiskEstimator(p, clock)

    @Test fun `parses and joins with normalisation`() {
        for (l in listOf("IC5", "IC 5", " ic5 ", "ojp:IC 5")) {
            val r = est().estimate(journey(l))
            assertNotNull(r, l)
            assertEquals(3, r.predictedDelayMinutes)
            assertEquals(0.25, r.lateArrivalProbability)
        }
        assertEquals(3, est().info().keysLoaded)
        assertEquals("ok", est().info().status)
    }

    @Test fun `unknown stays null`() {
        assertNull(est().estimate(journey("IC6")))
        assertNull(est().estimate(journey(null)))
        assertNull(est().estimate(journey("IC5", "IC6"))) // any unmatched leg -> unknown
    }

    @Test fun `low confidence is null`() = assertNull(est().estimate(journey("S1")))

    @Test fun `wrong hour is null`() {
        val j = journey("IC5")
        val shifted = j.copy(legs = j.legs.map { it.copy(departure = it.departure.plusHours(3)) })
        assertNull(est().estimate(shifted))
    }

    @Test fun `probability clamped to unit interval`() =
        assertEquals(1.0, est().estimate(journey("RE9"))!!.lateArrivalProbability)

    @Test fun `missing artifact is unknown and status missing`() {
        val e = est(Path.of("/nonexistent/predictions.json"))
        assertNull(e.estimate(journey("IC5")))
        assertEquals("missing", e.info().status)
    }

    @Test fun `malformed artifact never throws`() {
        val f = Files.createTempFile("pred", ".json").also { Files.writeString(it, "{not json") }
        val e = est(f)
        assertNull(e.estimate(journey("IC5")))
        assertEquals("missing", e.info().status)
    }

    @Test fun `stale artifact reports stale`() {
        val late = ArtifactRiskEstimator(fixture, Clock.fixed(Instant.parse("2026-12-31T00:00:00Z"), ZoneOffset.UTC))
        assertEquals("stale", late.info().status)
    }

    @Test fun `reloads when mtime changes`() {
        val f = Files.createTempFile("pred", ".json").also { Files.writeString(it, "{}") }
        val e = est(f)
        assertNull(e.estimate(journey("IC5")))
        Files.copy(fixture, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000))
        assertNotNull(e.estimate(journey("IC5")))
    }

    @Test fun `real analytics artifact parses when present`() {
        val real = listOf("../analytics/artifacts/predictions.json", "analytics/artifacts/predictions.json")
            .map { Path.of(it) }.firstOrNull { Files.exists(it) } ?: return
        assertNotEquals("missing", ArtifactRiskEstimator(real, clock).info().status)
    }

    @Test fun `NoRiskEstimator is unknown`() = assertNull(NoRiskEstimator.estimate(journey("IC5")))
}
