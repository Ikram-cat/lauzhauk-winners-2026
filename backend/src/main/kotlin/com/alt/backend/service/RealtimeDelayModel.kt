package com.alt.backend.service

import com.alt.backend.domain.Journey
import com.alt.backend.domain.LegMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

const val MIN_DELAY_SAMPLES = 10
/** Line rows are far sparser than station rows; the sampler emits them from 5 samples up. */
const val MIN_LINE_SAMPLES = 10
const val MAX_APPLIED_DELAY_MIN = 30.0

/** [source]: "line" | "station" | "none". minutes/samples are null exactly when source is "none". */
data class ExpectedDelay(val minutes: Double?, val samples: Int?, val source: String)

/** Journey-level: the worst measured transit leg. */
data class JourneyDelay(val minutes: Int, val samples: Int, val source: String)

data class DelayInfo(val modelVersion: String?, val portions: Int, val lines: Int, val status: String)

@Serializable internal data class DelayRow(
    val station: String, val hourBucket: Int, val serviceLabel: String? = null,
    val samples: Int = 0, val medianDelayMin: Double? = null
)
@Serializable internal data class DelayFile(
    val modelVersion: String? = null, val portions: List<DelayRow> = emptyList(), val lines: List<DelayRow> = emptyList()
)

private val zone = ZoneId.of("Europe/Zurich")
private fun label(s: String?) = s?.uppercase()?.filter { it.isLetterOrDigit() }.orEmpty()

/** Measured lateness per (line,) station and hour from the analytics artifact; path null/missing = everything unknown. Reloads on mtime change. */
class RealtimeDelayModel(private val path: Path?) {
    private class Loaded(val file: DelayFile, val stations: Map<Pair<String, Int>, DelayRow>, val lines: Map<Triple<String, String, Int>, DelayRow>)

    private val log = LoggerFactory.getLogger(RealtimeDelayModel::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var loaded: Loaded? = null
    @Volatile private var seenMtime: Long = Long.MIN_VALUE

    private fun refresh() {
        val p = path ?: return
        val mtime = try { if (Files.exists(p)) Files.getLastModifiedTime(p).toMillis() else -1L } catch (e: Exception) { -1L }
        if (mtime == seenMtime) return
        synchronized(this) {
            if (mtime == seenMtime) return
            seenMtime = mtime
            if (mtime == -1L) {
                loaded = null; log.warn("Delay artifact missing; expected delays stay unknown"); return }
            try {
                val f = json.decodeFromString<DelayFile>(Files.readString(p))
                val st = HashMap<Pair<String, Int>, DelayRow>(); val ln = HashMap<Triple<String, String, Int>, DelayRow>()
                for (r in f.portions) if (r.hourBucket in 0..23) for (s in stationForms(r.station)) st.merge(s to r.hourBucket, r) { a, b -> if (b.samples > a.samples) b else a }
                for (r in f.lines) if (r.hourBucket in 0..23 && label(r.serviceLabel).isNotEmpty())
                    for (s in stationForms(r.station)) ln.merge(Triple(label(r.serviceLabel), s, r.hourBucket), r) { a, b -> if (b.samples > a.samples) b else a }
                loaded = Loaded(f, st, ln)
                log.info("Delay artifact loaded: {} portions, {} lines", f.portions.size, f.lines.size)
            } catch (e: Exception) { loaded = null; log.warn("Delay artifact unreadable ({}); expected delays stay unknown", e.javaClass.simpleName) }
        }
    }

    fun expectedDelayMinutes(station: String, serviceLabel: String?, localHour: Int): ExpectedDelay {
        refresh()
        val l = loaded ?: return NONE_DELAY
        val forms = stationForms(station)
        fun usable(r: DelayRow?, min: Int = MIN_DELAY_SAMPLES) = r?.takeIf { it.samples >= min && it.medianDelayMin?.isNaN() == false }
        val lab = label(serviceLabel)
        val line = if (lab.isEmpty()) null else forms.firstNotNullOfOrNull { usable(l.lines[Triple(lab, it, localHour)], MIN_LINE_SAMPLES) }
        val row = line ?: forms.firstNotNullOfOrNull { usable(l.stations[it to localHour]) } ?: return NONE_DELAY
        return ExpectedDelay(row.medianDelayMin!!.coerceIn(0.0, MAX_APPLIED_DELAY_MIN), row.samples, if (line != null) "line" else "station")
    }

    /** Expected delay per transit leg (boarding station, local departure hour); walk legs are skipped. */
    fun legDelays(j: Journey): List<ExpectedDelay> =
        j.legs.filter { it.mode == LegMode.TRANSIT }.map { expectedDelayMinutes(it.origin, it.serviceLabel, it.departure.atZoneSameInstant(zone).hour) }

    /** A journey is as late as its worst measured leg; null when no leg has a measurement. */
    fun forJourney(j: Journey): JourneyDelay? =
        legDelays(j).filter { it.minutes != null }.maxByOrNull { it.minutes!! }?.let { JourneyDelay(Math.round(it.minutes!!).toInt(), it.samples!!, it.source) }

    fun info(): DelayInfo {
        refresh()
        val l = loaded ?: return DelayInfo(null, 0, 0, "missing")
        return DelayInfo(l.file.modelVersion, l.file.portions.size, l.file.lines.size, "ok")
    }

    companion object {
        private val NONE_DELAY = ExpectedDelay(null, null, "none")
        val NONE = RealtimeDelayModel(null)
    }
}
