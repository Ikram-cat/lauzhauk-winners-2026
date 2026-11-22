package com.alt.backend.service.estimate

import com.alt.backend.service.RealtimeDelayModel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import kotlin.test.*

/** The frozen fallback ladder, clamps, signs, thresholds and the mtime reload contract (§2b). */
class DelayEstimateModelTest {
    private fun fixturePath(name: String = "delay_estimates_fixture.json"): Path =
        Path.of(javaClass.classLoader.getResource(name)!!.toURI())

    private val model = DelayEstimateModel(fixturePath())

    private fun dep(model: DelayEstimateModel, station: String, label: String?, wd: String, hour: Int) =
        model.estimate(label, station, wd, hour).departure

    @Test fun `ladder step 1 line at station weekday hour wins`() {
        val e = dep(model, "Lausanne", "IR 90", "SAT", 16)
        assertEquals(3.0, e!!.median); assertEquals(6.0, e.p90); assertEquals(40, e.samples)
        assertEquals(SOURCE_HISTORICAL_ACTUALS, e.source)
    }

    @Test fun `ladder step 2 falls back to pooled ANY weekday when the weekday row is thin`() {
        assertEquals(4.0, dep(model, "Lausanne", "IC 5", "SAT", 16)!!.median) // SAT row has 9 samples: rejected
    }

    @Test fun `ladder step 3 falls back to ANY weekday 4h block`() {
        assertEquals(-5.0, dep(model, "Bern", "S 1", "MON_FRI", 16)!!.median)
        assertEquals(-5.0, dep(model, "Bern", "S 1", "SUN", 17)!!.median) // 17:xx pools into the 16-19 block
    }

    @Test fun `ladder steps 4 and 5 pool stations under the line`() {
        assertEquals(6.0, dep(model, "Lausanne", "RE 7", "SAT", 16)!!.median)   // line, weekday, hour
        assertEquals(5.0, dep(model, "Lausanne", "RE 8", "SAT", 16)!!.median)   // line, ANY, block
    }

    @Test fun `ladder steps 6 7 8 fall back to the station`() {
        assertEquals(1.5, dep(model, "Zürich HB", "ZZ 9", "SAT", 16)!!.median)      // station, weekday, hour
        assertEquals(1.0, dep(model, "Zürich HB", "ZZ 9", "MON_FRI", 16)!!.median)  // station, ANY, hour
        assertEquals(2.0, dep(model, "Brig", "ZZ 9", "MON_FRI", 17)!!.median)       // station, ANY, block
    }

    @Test fun `step 9 unknown stays null and never fabricates a zero`() {
        assertNull(dep(model, "Nowhere", "ZZ 9", "SAT", 16))
        assertNull(dep(model, "Lausanne", "IR 90", "SAT", 3))
        assertEquals(SOURCE_NONE, model.estimate("ZZ 9", "Nowhere", "SAT", 16).source)
        assertNull(model.estimate("ZZ 9", "Nowhere", "SAT", 16).samples)
    }

    @Test fun `the applied median is clamped to minus ten plus thirty`() {
        assertEquals(30.0, dep(model, "Olten", "S 9", "ANY", 16)!!.median)   // measured +45
        assertEquals(-10.0, dep(model, "Olten", "S 8", "ANY", 17)!!.median)  // measured -40
    }

    @Test fun `negative medians survive unclamped inside the window`() {
        assertEquals(-5.0, dep(model, "Bern", "S 1", "ANY", 16)!!.median)
    }

    @Test fun `departure and arrival are looked up separately`() {
        val e = model.estimate("IR 90", "Genève", "SAT", 17)
        assertEquals(2.0, e.arrival!!.median)   // arrival looked up at the destination
        assertNull(e.departure)                 // no departure row for Genève at 17
        assertEquals(35, e.samples)
    }

    @Test fun `weekday classes follow the calendar and pool nights under their operating day`() {
        assertEquals("MON_FRI", weekdayClassOf(LocalDate.parse("2026-11-20")))
        assertEquals("SAT", weekdayClassOf(LocalDate.parse("2026-11-21")))
        assertEquals("SUN", weekdayClassOf(LocalDate.parse("2026-11-22")))
    }

    @Test fun `missing artifact leaves everything unknown`() {
        val m = DelayEstimateModel(Path.of("/nonexistent/delay_estimates.json"))
        assertEquals("missing", m.info().status)
        assertEquals(0, m.info().lineBuckets); assertEquals(0, m.info().stationBuckets)
        assertNull(m.estimate("IR 90", "Lausanne", "SAT", 16).departure)
        assertEquals(SOURCE_NONE, m.estimate("IR 90", "Lausanne", "SAT", 16).source)
    }

    @Test fun `info reports the artifact counts`() {
        assertEquals("ok", model.info().status)
        assertEquals("fixture-estimates-1", model.info().modelVersion)
        assertEquals(9, model.info().lineBuckets)
        assertEquals(3, model.info().stationBuckets)
    }

    @Test fun `the live sampler only fills gaps the historical artifact misses`() {
        val live = RealtimeDelayModel(fixturePath("realtime_delays_fixture.json"))
        val m = DelayEstimateModel(fixturePath(), live)
        // Lausanne/IR 90 hour 16 is in the historical artifact: HISTORICAL_ACTUALS wins...
        assertEquals(SOURCE_HISTORICAL_ACTUALS, m.estimate("IR 90", "Lausanne", "SAT", 16).departure!!.source)
        // ...Renens has no historical bucket for a line, but the sampler knows the station.
        val gap = m.estimate("ZZ 9", "Renens", "SAT", 16).departure
        assertEquals(SOURCE_LIVE_SAMPLE, gap!!.source)
        assertEquals(4.0, gap.median)
        // Without the live table the same gap stays unknown.
        assertNull(dep(model, "Renens", "ZZ 9", "SAT", 16))
    }

    @Test fun `artifact reloads when the file mtime changes`() {
        val tmp = Files.createTempFile("delay_estimates", ".json")
        try {
            Files.copy(fixturePath(), tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val m = DelayEstimateModel(tmp)
            assertEquals(3.0, dep(m, "Lausanne", "IR 90", "SAT", 16)!!.median)
            val updated = Files.readString(fixturePath()).replace("\"median\": 3.0, \"p90\": 6.0", "\"median\": 8.0, \"p90\": 6.0")
            Files.writeString(tmp, updated, StandardOpenOption.TRUNCATE_EXISTING)
            Files.setLastModifiedTime(tmp, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))
            assertEquals(8.0, dep(m, "Lausanne", "IR 90", "SAT", 16)!!.median)
        } finally { Files.deleteIfExists(tmp) }
    }
}
