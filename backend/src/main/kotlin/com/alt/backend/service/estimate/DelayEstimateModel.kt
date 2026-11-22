package com.alt.backend.service.estimate

import com.alt.backend.service.MIN_DELAY_SAMPLES
import com.alt.backend.service.RealtimeDelayModel
import com.alt.backend.service.stationForms
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/** Minimum samples a bucket needs before the ladder accepts it (docs/PREDICTIVE_DELAYS_PLAN.md §2b). */
const val MIN_ESTIMATE_SAMPLES = 10
/** The applied estimate is a statistical median; clamp it so one stale bucket cannot wreck a projection. */
const val ESTIMATE_MIN_MIN = -10.0
const val ESTIMATE_MAX_MIN = 30.0

const val SOURCE_HISTORICAL_ACTUALS = "HISTORICAL_ACTUALS"
const val SOURCE_LIVE_SAMPLE = "LIVE_SAMPLE"
const val SOURCE_NONE = "NONE"

const val OVERLAP_ADDITIVE = "additive"
const val OVERLAP_KNOWN_WINS = "known_wins"

/** One side (departure or arrival) of a leg estimate; [median] is already clamped and signed. */
data class SideEstimate(val median: Double, val p90: Double?, val samples: Int, val source: String)

/** Statistical delay for a leg: departure and arrival are measured and looked up separately. */
data class LegEstimate(val departure: SideEstimate?, val arrival: SideEstimate?) {
    /** Departure source wins because the wire exposes a single value; NONE when both sides are unknown. */
    val source: String get() = departure?.source ?: arrival?.source ?: SOURCE_NONE
    val samples: Int? get() = departure?.samples ?: arrival?.samples
}

data class EstimateInfo(val modelVersion: String?, val lineBuckets: Int, val stationBuckets: Int, val status: String)

@Serializable internal data class EstimateStats(
    val median: Double? = null, val p90: Double? = null, val shareLate: Double? = null, val samples: Int = 0
)

/** One artifact bucket. [bucketType] is HOUR (hourBucket 0-23) or BLOCK4 (hourBucket = block start). */
@Serializable internal data class EstimateRow(
    val serviceLabel: String? = null, val station: String? = null, val weekdayClass: String, val hourBucket: Int,
    val bucketType: String = "HOUR", val dep: EstimateStats = EstimateStats(), val arr: EstimateStats = EstimateStats()
)

@Serializable internal data class EstimateFile(
    val modelVersion: String? = null, val generatedAt: String? = null,
    val lines: List<EstimateRow> = emptyList(), val stations: List<EstimateRow> = emptyList()
)

private const val ANY = "ANY"
private fun label(s: String?) = s?.uppercase()?.filter { it.isLetterOrDigit() }.orEmpty()

/** Calendar weekday class of the operating day; night rides keep their own operating day (§2b). */
fun weekdayClassOf(date: LocalDate): String = when (date.dayOfWeek.value) {
    6 -> "SAT"; 7 -> "SUN"; else -> "MON_FRI"
}

/**
 * Historical (Ist-Daten) per-leg delay estimates. Loads `delay_estimates_v2.json` with the same
 * mtime reload contract as [RealtimeDelayModel]; a missing/unreadable artifact leaves every
 * estimate unknown (null + NONE) instead of fabricating a zero.
 *
 * Lookup follows the frozen ladder: line@station weekday/hour -> line@station ANY/hour ->
 * line@station ANY/4h-block -> line weekday/hour -> line ANY/4h-block -> station weekday/hour ->
 * station ANY/hour -> station ANY/4h-block -> none. The first bucket with >= 10 samples wins.
 */
class DelayEstimateModel(
    private val path: Path?,
    /** Live sampler medians as a second, lower-priority source (LIVE_SAMPLE). */
    private val live: RealtimeDelayModel? = null
) {
    private data class Key(val a: String, val b: String, val weekday: String, val slot: Int)

    private class Loaded(
        val file: EstimateFile,
        val lineStationHour: Map<Key, EstimateRow>,
        val lineStationBlock: Map<Key, EstimateRow>,
        val lineHour: Map<Key, EstimateRow>,
        val lineBlock: Map<Key, EstimateRow>,
        val stationHour: Map<Key, EstimateRow>,
        val stationBlock: Map<Key, EstimateRow>
    )

    private val log = LoggerFactory.getLogger(DelayEstimateModel::class.java)
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
            loaded = null
            if (mtime == -1L) { log.warn("Delay estimate artifact missing; estimates stay unknown"); return }
            try {
                val f = json.decodeFromString<EstimateFile>(Files.readString(p))
                val lsh = HashMap<Key, EstimateRow>(); val lsb = HashMap<Key, EstimateRow>()
                val lh = HashMap<Key, EstimateRow>(); val lb = HashMap<Key, EstimateRow>()
                val sh = HashMap<Key, EstimateRow>(); val sb = HashMap<Key, EstimateRow>()
                for (r in f.lines) {
                    val lab = label(r.serviceLabel)
                    if (lab.isEmpty()) continue
                    val block = r.bucketType.equals("BLOCK4", ignoreCase = true)
                    if (block) {
                        lb.merge(Key(lab, "", r.weekdayClass, r.hourBucket), r) { a, b -> newer(a, b) }
                        if (!r.station.isNullOrBlank()) for (s in stationForms(r.station)) {
                            lsb.merge(Key(lab, s, r.weekdayClass, r.hourBucket), r) { a, b -> newer(a, b) }
                        }
                    } else {
                        lh.merge(Key(lab, "", r.weekdayClass, r.hourBucket), r) { a, b -> newer(a, b) }
                        if (!r.station.isNullOrBlank()) for (s in stationForms(r.station)) {
                            lsh.merge(Key(lab, s, r.weekdayClass, r.hourBucket), r) { a, b -> newer(a, b) }
                        }
                    }
                }
                for (r in f.stations) {
                    if (r.station.isNullOrBlank()) continue
                    val block = r.bucketType.equals("BLOCK4", ignoreCase = true)
                    val target = if (block) sb else sh
                    for (s in stationForms(r.station)) target.merge(Key("", s, r.weekdayClass, r.hourBucket), r) { a, b -> newer(a, b) }
                }
                loaded = Loaded(f, lsh, lsb, lh, lb, sh, sb)
                log.info("Delay estimate artifact loaded: {} line rows, {} station rows", f.lines.size, f.stations.size)
            } catch (e: Exception) {
                log.warn("Delay estimate artifact unreadable ({}); estimates stay unknown", e.javaClass.simpleName)
            }
        }
    }

    private fun newer(a: EstimateRow, b: EstimateRow) =
        if (b.dep.samples + b.arr.samples > a.dep.samples + a.arr.samples) b else a

    /** Statistical estimate for one leg; both sides are looked up and clamped independently. */
    fun estimate(serviceLabel: String?, station: String, weekdayClass: String, hour: Int): LegEstimate {
        refresh()
        val l = loaded ?: run {
            val side = applyLive(serviceLabel, station, hour)
            return LegEstimate(side, side)
        }
        val forms = stationForms(station)
        val lab = label(serviceLabel)
        val h = hour.coerceIn(0, 23)
        val block = (h / 4) * 4

        fun rowFor(side: (EstimateRow) -> EstimateStats): SideEstimate? {
            fun quant(r: EstimateRow?): SideEstimate? {
                val st = r?.let(side) ?: return null
                if (st.samples < MIN_ESTIMATE_SAMPLES || st.median == null || st.median!!.isNaN()) return null
                return SideEstimate(st.median!!.coerceIn(ESTIMATE_MIN_MIN, ESTIMATE_MAX_MIN), st.p90, st.samples, SOURCE_HISTORICAL_ACTUALS)
            }
            if (lab.isNotEmpty()) {
                for (f in forms) quant(l.lineStationHour[Key(lab, f, weekdayClass, h)])?.let { return it }
                for (f in forms) quant(l.lineStationHour[Key(lab, f, ANY, h)])?.let { return it }
                for (f in forms) quant(l.lineStationBlock[Key(lab, f, ANY, block)])?.let { return it }
                quant(l.lineHour[Key(lab, "", weekdayClass, h)])?.let { return it }
                quant(l.lineBlock[Key(lab, "", ANY, block)])?.let { return it }
            }
            for (f in forms) quant(l.stationHour[Key("", f, weekdayClass, h)])?.let { return it }
            for (f in forms) quant(l.stationHour[Key("", f, ANY, h)])?.let { return it }
            for (f in forms) quant(l.stationBlock[Key("", f, ANY, block)])?.let { return it }
            return null
        }

        val dep = rowFor { it.dep }
        val arr = rowFor { it.arr }
        if (dep != null || arr != null) return LegEstimate(dep, arr)
        // Nothing historical: the live sampler may still know this line/station for this hour.
        val liveSide = applyLive(serviceLabel, station, hour) ?: return LegEstimate(null, null)
        return LegEstimate(liveSide, liveSide)
    }

    private fun applyLive(serviceLabel: String?, station: String, hour: Int): SideEstimate? {
        val lv = live ?: return null
        val e = lv.expectedDelayMinutes(station, serviceLabel, hour)
        val minutes = e.minutes ?: return null
        if (e.source == "none") return null
        if ((e.samples ?: 0) < MIN_DELAY_SAMPLES) return null
        return SideEstimate(minutes.coerceIn(ESTIMATE_MIN_MIN, ESTIMATE_MAX_MIN), null, e.samples!!, SOURCE_LIVE_SAMPLE)
    }

    fun info(): EstimateInfo {
        refresh()
        val l = loaded ?: return EstimateInfo(null, 0, 0, "missing")
        return EstimateInfo(l.file.modelVersion, l.file.lines.size, l.file.stations.size, "ok")
    }

    companion object {
        val NONE = DelayEstimateModel(null)
    }
}
