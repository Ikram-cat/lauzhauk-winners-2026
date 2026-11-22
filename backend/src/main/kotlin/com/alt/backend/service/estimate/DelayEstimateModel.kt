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

/** Which ladder family answered the lookup (docs/API_CONTRACT.md, incident-conditioned delays). */
const val BASIS_LINE = "LINE"
const val BASIS_STATION = "STATION"
const val BASIS_LINE_INCIDENT = "LINE_INCIDENT"
const val BASIS_STATION_INCIDENT = "STATION_INCIDENT"
const val BASIS_NONE = "NONE"

/** One side (departure or arrival) of a leg estimate; [median] is already clamped and signed. */
data class SideEstimate(
    val median: Double, val p90: Double?, val samples: Int, val source: String, val basis: String = BASIS_NONE
)

/** Conditioned layer for one leg, present only while a live disruption touches it. */
data class IncidentEstimate(
    val extraMinutes: Double?, val cancellationRisk: Double?, val suppressedStopRisk: Double?,
    val samples: Int?, val incidentCount: Int?
)

/** An ACTIVE disruption found by the live detection on this leg's line/corridor; enables the conditioned ladder. */
data class IncidentContext(val serviceLabel: String?, val station: String? = null, val active: Boolean = true)

/** Statistical delay for a leg: departure and arrival are measured and looked up separately. */
data class LegEstimate(
    val departure: SideEstimate?, val arrival: SideEstimate?, val incident: IncidentEstimate? = null
) {
    /** Departure source wins because the wire exposes a single value; NONE when both sides are unknown. */
    val source: String get() = departure?.source ?: arrival?.source ?: SOURCE_NONE
    val samples: Int? get() = departure?.samples ?: arrival?.samples
    /** Which ladder family answered (departure side first, then arrival). */
    val basis: String get() = departure?.basis ?: arrival?.basis ?: BASIS_NONE
}

data class EstimateInfo(
    val modelVersion: String?, val lineBuckets: Int, val stationBuckets: Int, val status: String,
    /** Conditioned (incident) bucket rows in the artifact: linesIncident + stationsIncident. */
    val incidentBuckets: Int = 0
)

@Serializable internal data class EstimateStats(
    val median: Double? = null, val p90: Double? = null, val shareLate: Double? = null, val samples: Int = 0
)

/** One artifact bucket. [bucketType] is HOUR (hourBucket 0-23) or BLOCK4 (hourBucket = block start). */
@Serializable internal data class EstimateRow(
    val serviceLabel: String? = null, val station: String? = null, val weekdayClass: String, val hourBucket: Int,
    val bucketType: String = "HOUR", val dep: EstimateStats = EstimateStats(), val arr: EstimateStats = EstimateStats()
)

/** A Phase-2 conditioned bucket: ANY weekday, 4h block, plus the risk shares over matching planned stops. */
@Serializable internal data class IncidentRow(
    val serviceLabel: String? = null, val station: String? = null, val weekdayClass: String = "ANY",
    val hourBucket: Int, val bucketType: String = "BLOCK4",
    val dep: EstimateStats = EstimateStats(), val arr: EstimateStats = EstimateStats(),
    val incidentCount: Int = 0, val cancellationShare: Double = 0.0, val suppressionShare: Double = 0.0
)

@Serializable internal data class EstimateFile(
    val modelVersion: String? = null, val generatedAt: String? = null,
    val lines: List<EstimateRow> = emptyList(), val stations: List<EstimateRow> = emptyList(),
    val linesIncident: List<IncidentRow> = emptyList(), val stationsIncident: List<IncidentRow> = emptyList()
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
        val stationBlock: Map<Key, EstimateRow>,
        val lineIncident: Map<Key, IncidentRow>,
        val stationIncident: Map<Key, IncidentRow>
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
                val lineIncident = HashMap<Key, IncidentRow>()
                for (r in f.linesIncident) {
                    val lab = label(r.serviceLabel)
                    if (lab.isEmpty()) continue
                    val key = Key(lab, "", ANY, r.hourBucket)
                    lineIncident.merge(key, r) { a, b -> newerIncident(a, b) }
                }
                val stationIncident = HashMap<Key, IncidentRow>()
                for (r in f.stationsIncident) {
                    if (r.station.isNullOrBlank()) continue
                    for (s in stationForms(r.station)) {
                        stationIncident.merge(Key("", s, ANY, r.hourBucket), r) { a, b -> newerIncident(a, b) }
                    }
                }
                loaded = Loaded(f, lsh, lsb, lh, lb, sh, sb, lineIncident, stationIncident)
                log.info("Delay estimate artifact loaded: {} line rows, {} station rows, {} incident rows",
                    f.lines.size, f.stations.size, f.linesIncident.size + f.stationsIncident.size)
            } catch (e: Exception) {
                log.warn("Delay estimate artifact unreadable ({}); estimates stay unknown", e.javaClass.simpleName)
            }
        }
    }

    private fun newer(a: EstimateRow, b: EstimateRow) =
        if (b.dep.samples + b.arr.samples > a.dep.samples + a.arr.samples) b else a

    private fun newerIncident(a: IncidentRow, b: IncidentRow) =
        if (b.dep.samples + b.arr.samples > a.dep.samples + a.arr.samples) b else a

    /**
     * Statistical estimate for one leg; both sides are looked up and clamped independently.
     * When [incident] is set (an ACTIVE live disruption touches the leg) the CONDITIONED ladder answers
     * first — linesIncident, then stationsIncident — and falls back to the unconditional ladder when the
     * conditioned bucket is missing or thin. Without an incident this is exactly the Phase-1 behaviour.
     */
    fun estimate(
        serviceLabel: String?, station: String, weekdayClass: String, hour: Int,
        incident: IncidentContext? = null
    ): LegEstimate {
        refresh()
        val l = loaded ?: run {
            val side = applyLive(serviceLabel, station, hour)
            return LegEstimate(side, side)
        }
        val forms = stationForms(station)
        val lab = label(serviceLabel)
        val cond = incident?.takeIf { it.active }
        val incLab = if (cond == null) "" else label(cond.serviceLabel ?: serviceLabel).ifEmpty { lab }
        val h = hour.coerceIn(0, 23)
        val block = (h / 4) * 4

        fun unconditional(side: (EstimateRow) -> EstimateStats): SideEstimate? {
            fun q(r: EstimateRow?, basis: String): SideEstimate? {
                val st = r?.let(side) ?: return null
                if (st.samples < MIN_ESTIMATE_SAMPLES || st.median == null || st.median!!.isNaN()) return null
                return SideEstimate(st.median!!.coerceIn(ESTIMATE_MIN_MIN, ESTIMATE_MAX_MIN), st.p90, st.samples, SOURCE_HISTORICAL_ACTUALS, basis)
            }
            if (lab.isNotEmpty()) {
                for (f in forms) q(l.lineStationHour[Key(lab, f, weekdayClass, h)], BASIS_LINE)?.let { return it }
                for (f in forms) q(l.lineStationHour[Key(lab, f, ANY, h)], BASIS_LINE)?.let { return it }
                for (f in forms) q(l.lineStationBlock[Key(lab, f, ANY, block)], BASIS_LINE)?.let { return it }
                q(l.lineHour[Key(lab, "", weekdayClass, h)], BASIS_LINE)?.let { return it }
                q(l.lineBlock[Key(lab, "", ANY, block)], BASIS_LINE)?.let { return it }
            }
            for (f in forms) q(l.stationHour[Key("", f, weekdayClass, h)], BASIS_STATION)?.let { return it }
            for (f in forms) q(l.stationHour[Key("", f, ANY, h)], BASIS_STATION)?.let { return it }
            for (f in forms) q(l.stationBlock[Key("", f, ANY, block)], BASIS_STATION)?.let { return it }
            return null
        }

        // Conditioned lookup (four-hour blocks only, ANY weekday): line first, then station.
        fun conditioned(side: (IncidentRow) -> EstimateStats, formsForStation: Collection<String>): Pair<SideEstimate, IncidentRow>? {
            fun q(r: IncidentRow?, basis: String): Pair<SideEstimate, IncidentRow>? {
                if (r == null) return null
                val st = side(r)
                if (st.samples < MIN_ESTIMATE_SAMPLES || st.median == null || st.median!!.isNaN()) return null
                return SideEstimate(st.median!!.coerceIn(ESTIMATE_MIN_MIN, ESTIMATE_MAX_MIN), st.p90, st.samples,
                    SOURCE_HISTORICAL_ACTUALS, basis) to r
            }
            if (incLab.isNotEmpty()) q(l.lineIncident[Key(incLab, "", ANY, block)], BASIS_LINE_INCIDENT)?.let { return it }
            for (f in formsForStation) q(l.stationIncident[Key("", f, ANY, block)], BASIS_STATION_INCIDENT)?.let { return it }
            return null
        }

        val uncondDep = unconditional { it.dep }
        val uncondArr = unconditional { it.arr }
        if (cond != null) {
            val condDep = conditioned({ it.dep }, forms)
            val condArr = conditioned({ it.arr }, forms)
            val dep = condDep?.first ?: uncondDep
            val arr = condArr?.first ?: uncondArr
            val chosen = condDep ?: condArr
            val incidentEstimate = chosen?.let { (_, row) ->
                val base = (if (condDep != null) uncondDep else uncondArr)?.median
                IncidentEstimate(
                    extraMinutes = base?.let { Math.round((chosen.first.median - it) * 10) / 10.0 },
                    cancellationRisk = Math.round(row.cancellationShare * 1000) / 1000.0,
                    suppressedStopRisk = Math.round(row.suppressionShare * 1000) / 1000.0,
                    samples = chosen.first.samples,
                    incidentCount = row.incidentCount
                )
            }
            if (dep != null || arr != null) return LegEstimate(dep, arr, incidentEstimate)
        } else if (uncondDep != null || uncondArr != null) {
            return LegEstimate(uncondDep, uncondArr)
        }
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
        return SideEstimate(minutes.coerceIn(ESTIMATE_MIN_MIN, ESTIMATE_MAX_MIN), null, e.samples!!, SOURCE_LIVE_SAMPLE, BASIS_STATION)
    }

    fun info(): EstimateInfo {
        refresh()
        val l = loaded ?: return EstimateInfo(null, 0, 0, "missing")
        return EstimateInfo(l.file.modelVersion, l.file.lines.size, l.file.stations.size, "ok",
            l.file.linesIncident.size + l.file.stationsIncident.size)
    }

    companion object {
        val NONE = DelayEstimateModel(null)
    }
}
