package com.cffplanb.backend.service

import com.cffplanb.backend.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

class CorridorRiskTest {
    private val fixture: Path = Path.of(javaClass.getResource("/corridor_fixture.json")!!.toURI())
    private val clock = Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC)
    private val est = ArtifactRiskEstimator(fixture, clock)

    // 16:10Z = 17:10 Zurich -> baseline hour 17
    private fun journey(vararg stops: Pair<String, String>): Journey {
        val dep = OffsetDateTime.parse("2026-11-21T16:10:00Z")
        val legs = stops.map { (o, d) -> JourneyLeg(LegMode.TRANSIT, o, d, dep, dep.plusMinutes(30), null, null, null) }
        return Journey("j", dep, dep.plusMinutes(30), 30, 0, legs, DataStatus.LIVE_SCHEDULE)
    }

    @Test fun `normalisation handles accents aliases and compound names`() {
        val al = mapOf("genf" to "geneve")
        assertTrue("geneve" in stationForms("Genève-Cornavin", al))
        assertTrue("geneve" in stationForms("GENEVE", al))
        assertTrue("geneve" in stationForms("Genf", al))
        assertTrue("geneve" in stationForms("Genf (Genève)"))
        assertTrue("lausanne" in stationForms("Lausanne, gare"))
        assertTrue("zurich" in stationForms("  Zürich   HB "))
    }

    @Test fun `forward and reverse corridor match`() {
        val f = est.estimate(journey("Lausanne" to "Genève"))!!
        assertEquals("corridor", f.source); assertEquals("high", f.confidence); assertEquals("Lausanne - Genève", f.label)
        assertNull(f.predictedDelayMinutes); assertEquals(30, f.disruptionDurationMin); assertEquals("corridor_duration", f.basis); assertEquals(0.05, f.lateArrivalProbability)
        assertEquals("corridor", est.estimate(journey("Genève" to "Lausanne"))!!.source)
        assertEquals("corridor", est.estimate(journey("Genf" to "Lausanne, gare"))!!.source)
    }

    @Test fun `real delay estimate populates predictedDelayMinutes`() {
        val r = est.estimate(journey("Aarau" to "Olten"))!!
        assertEquals(7, r.predictedDelayMinutes); assertNull(r.disruptionDurationMin); assertEquals("corridor_delay", r.basis)
    }

    @Test fun `multi-stop via match`() {
        val r = est.estimate(journey("Montreux" to "Sion"))!!
        assertEquals("Lausanne - Brig", r.label); assertEquals("low", r.confidence)
    }

    @Test fun `worst leg wins`() {
        val r = est.estimate(journey("Lausanne" to "Genève", "Bern" to "Zürich"))!!
        assertEquals("Bern - Zürich HB", r.label); assertEquals(0.2, r.lateArrivalProbability)
    }

    @Test fun `baseline fallback then unknown`() {
        val r = est.estimate(journey("Chur" to "Davos"))!!
        assertEquals("baseline", r.source); assertEquals(0.02, r.lateArrivalProbability); assertNull(r.predictedDelayMinutes); assertEquals(20, r.disruptionDurationMin); assertEquals("baseline_duration", r.basis)
        val j = journey("Chur" to "Davos")
        assertNull(est.estimate(j.copy(departure = j.departure.plusHours(3), legs = j.legs.map { it.copy(departure = it.departure.plusHours(3)) })))
    }

    @Test fun `model info counts and sample`() {
        val i = est.info()
        assertEquals(4, i.corridorsLoaded); assertEquals(1, i.baselineLoaded)
        assertEquals(listOf("Aarau - Olten", "Bern - Zürich HB", "Lausanne - Brig", "Lausanne - Genève"), i.corridorSample)
    }

    @Test fun `real artifact corridors and baseline parse when present`() {
        val real = listOf("../analytics/artifacts/predictions.json", "analytics/artifacts/predictions.json")
            .map { Path.of(it) }.firstOrNull { Files.exists(it) } ?: return
        val txt = Files.readString(real)
        val i = ArtifactRiskEstimator(real, clock).info()
        if ("\"corridors\"" in txt) assertTrue(i.corridorsLoaded > 0)
        if ("\"hourBaseline\"" in txt) assertEquals(24, i.baselineLoaded)
    }
}
