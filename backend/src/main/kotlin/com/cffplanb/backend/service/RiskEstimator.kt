package com.cffplanb.backend.service

import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.domain.LegMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** source: corridor | baseline | line (legacy byLineHour). label: matched corridor / baseline hour. */
data class RiskEstimate(
    val predictedDelayMinutes: Int?, // only a real per-traveller delay estimate; null for duration-based rows
    val lateArrivalProbability: Double,
    val source: String? = null,
    val confidence: String? = null,
    val label: String? = null,
    val disruptionDurationMin: Int? = null, // median disruption length, NOT a traveller delay
    val basis: String? = null // corridor_duration | corridor_delay | baseline_duration | baseline_delay | line_delay
)

data class ModelInfo(
    val modelVersion: String?,
    val generatedAt: String?,
    val coverage: kotlinx.serialization.json.JsonElement?,
    val keysLoaded: Int,
    val status: String, // ok | missing | stale
    val corridorsLoaded: Int = 0,
    val baselineLoaded: Int = 0,
    val byLineHourCells: Int = 0,
    val corridorSample: List<String> = emptyList()
)

interface RiskEstimator {
    /** Null = unknown. Never a fabricated zero. */
    fun estimate(journey: Journey): RiskEstimate?
    fun info(): ModelInfo
}

object NoRiskEstimator : RiskEstimator {
    override fun estimate(journey: Journey): RiskEstimate? = null
    override fun info() = ModelInfo(null, null, null, 0, "missing")
}

@Serializable
internal data class ArtifactRow(
    val key: String,
    val hourBucket: Int,
    val lateProbability: Double? = null,
    val expectedDelayMin: Double? = null,
    val sampleSize: Int? = null,
    val confidence: String? = null
)

@Serializable
internal data class CorridorRow(
    val from: String,
    val to: String,
    val corridor: String? = null,
    val via: List<String> = emptyList(),
    val disruptionProbabilityPerDay: Double? = null,
    val expectedDelayMin: Double? = null,
    val delayEstimateMin: Double? = null,
    val sampleSize: Int? = null,
    val confidence: String? = null
)

@Serializable
internal data class BaselineRow(
    val hourBucket: Int,
    val disruptionProbabilityPerHour: Double? = null,
    val expectedDelayMin: Double? = null,
    val delayEstimateMin: Double? = null,
    val sampleSize: Int? = null
)

@Serializable
internal data class ArtifactFile(
    val modelVersion: String? = null,
    val generatedAt: String? = null,
    val coverage: kotlinx.serialization.json.JsonElement? = null,
    val byLineHour: List<ArtifactRow> = emptyList(),
    val corridors: List<CorridorRow> = emptyList(),
    val hourBaseline: List<BaselineRow> = emptyList(),
    val stationAliases: Map<String, String> = emptyMap()
)

private class Corr(val row: CorridorRow, val stops: List<Set<String>>)

private class Loaded(
    val file: ArtifactFile,
    val rows: Map<Pair<String, Int>, ArtifactRow>,
    val corridors: List<Corr>,
    val baseline: Map<Int, BaselineRow>,
    val aliases: Map<String, String>
)

private val ACCENTS = Regex("\\p{M}+")
private val WS = Regex("\\s+")
private val PARENTHESISED = Regex("^(.*?)\\s*\\((.*)\\)$")
private val HUB_SUFFIX = Regex("[ ,]+(hb|gare|bahnhof|bf)$")

private fun fold(s: String): String =
    java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD).replace(ACCENTS, "")
        .lowercase().replace(WS, " ").trim()

/**
 * Comparable forms of a station name: full name plus the part before ",", "/", "-" and a trailing
 * "hb|gare|bahnhof" stripped, each passed through the alias map. Accents/case only folded for comparison.
 * shortcut: any shared form matches, so "Basel SBB" vs "Basel Bad Bf" stay distinct but "Biel-Bienne" ~ "Biel"; fine for corridors.
 */
internal fun stationForms(raw: String, aliases: Map<String, String> = emptyMap()): Set<String> {
    val full = fold(raw)
    val out = LinkedHashSet<String>()
    fun add(x: String) {
        val t = x.trim().trim(',', '-').trim()
        if (t.isEmpty()) return
        out += t
        out += t.replace(HUB_SUFFIX, "").trim()
    }
    add(full)
    PARENTHESISED.find(full)?.let { add(it.groupValues[1]); add(it.groupValues[2]) } // "genf (geneve)"
    for (sep in listOf(",", "/", " - ", "-")) if (sep in full) add(full.substringBefore(sep))
    return out.flatMap { listOf(it, aliases[it] ?: it) }.filter { it.isNotEmpty() }.toSet()
}

/** delayEstimateMin (real delay) wins; otherwise expectedDelayMin is a disruption duration. */
private fun durationOrDelay(prefix: String, delay: Double?, duration: Double?): Triple<Int?, Int?, String>? {
    fun r(x: Double) = Math.round(x).toInt().coerceAtLeast(0)
    if (delay != null && !delay.isNaN()) return Triple(r(delay), null, "${prefix}_delay")
    if (duration != null && !duration.isNaN()) return Triple(null, r(duration), "${prefix}_duration")
    return null
}

private fun confidenceFor(n: Int?) = when { n == null -> "low"; n >= 20 -> "high"; n >= 8 -> "medium"; else -> "low" }

/** Normalises "ojp:IC 5", " ic5 " -> "IC5". Keys that differ beyond this are not matched. */
internal fun normalizeRiskKey(raw: String): String =
    raw.trim().removePrefix("ojp:").removePrefix("OJP:").filterNot { it.isWhitespace() }.uppercase()

class ArtifactRiskEstimator(
    private val path: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val staleAfter: Duration = Duration.ofDays(7)
) : RiskEstimator {
    private val log = LoggerFactory.getLogger(ArtifactRiskEstimator::class.java)
    private val zone = ZoneId.of("Europe/Zurich")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var loaded: Loaded? = null
    @Volatile private var seenMtime: Long = Long.MIN_VALUE
    @Volatile private var loggedFailureFor: Long? = null

    private fun refresh() {
        val mtime = try {
            if (Files.exists(path)) Files.getLastModifiedTime(path).toMillis() else -1L
        } catch (e: Exception) { -1L }
        if (mtime == seenMtime) return
        synchronized(this) {
            if (mtime == seenMtime) return
            seenMtime = mtime
            if (mtime == -1L) {
                loaded = null
                log.warn("Risk artifact missing; risk values stay unknown")
                return
            }
            try {
                val file = json.decodeFromString<ArtifactFile>(Files.readString(path))
                val rows = HashMap<Pair<String, Int>, ArtifactRow>()
                for (r in file.byLineHour) {
                    if (r.hourBucket !in 0..23) continue
                    val k = normalizeRiskKey(r.key) to r.hourBucket
                    val prev = rows[k]
                    if (prev == null || (r.sampleSize ?: 0) > (prev.sampleSize ?: 0)) rows[k] = r
                }
                val aliases = file.stationAliases.entries.associate { fold(it.key) to fold(it.value) }
                val corr = file.corridors.map { c ->
                    Corr(c, (listOf(c.from) + c.via + c.to).map { stationForms(it, aliases) })
                }
                val base = file.hourBaseline.filter { it.hourBucket in 0..23 }.associateBy { it.hourBucket }
                loaded = Loaded(file, rows, corr, base, aliases)
                log.info("Risk artifact loaded: {} line keys, {} corridors, {} baseline hours", rows.size, corr.size, base.size)
            } catch (e: Exception) {
                loaded = null
                if (loggedFailureFor != mtime) log.warn("Risk artifact unreadable ({}); risk values stay unknown", e.javaClass.simpleName)
                loggedFailureFor = mtime
            }
        }
    }

    private fun matches(c: Corr, o: Set<String>, d: Set<String>): Boolean {
        val s = c.stops
        val first = s.first(); val last = s.last()
        if ((o.any { it in first } && d.any { it in last }) || (o.any { it in last } && d.any { it in first })) return true
        // multi-stop: both stations on the corridor, in order (either direction)
        val i = s.indexOfFirst { st -> o.any { it in st } }
        val j = s.indexOfFirst { st -> d.any { it in st } }
        return i >= 0 && j >= 0 && i != j
    }

    /** Order: corridor (worst matched transit leg) -> legacy byLineHour -> hourBaseline -> null. */
    override fun estimate(journey: Journey): RiskEstimate? {
        refresh()
        val l = loaded ?: return null
        val transit = journey.legs.filter { it.mode == LegMode.TRANSIT }
        if (transit.isEmpty()) return null

        var worst: RiskEstimate? = null
        for (leg in transit) {
            val o = stationForms(leg.origin, l.aliases); val d = stationForms(leg.destination, l.aliases)
            for (c in l.corridors) {
                val p = c.row.disruptionProbabilityPerDay
                val t = durationOrDelay("corridor", c.row.delayEstimateMin, c.row.expectedDelayMin)
                if (p == null || t == null || p.isNaN() || !matches(c, o, d)) continue
                if (worst == null || p > worst.lateArrivalProbability) {
                    worst = RiskEstimate(t.first, p.coerceIn(0.0, 1.0), "corridor",
                        c.row.confidence?.lowercase()?.takeIf { it in setOf("low", "medium", "high") } ?: confidenceFor(c.row.sampleSize),
                        c.row.corridor ?: "${c.row.from} - ${c.row.to}", t.second, t.third)
                }
            }
        }
        if (worst != null) return worst

        legacyLine(l, transit)?.let { return it }

        val hour = journey.departure.atZoneSameInstant(zone).hour
        val b = l.baseline[hour] ?: return null
        val p = b.disruptionProbabilityPerHour
        val t = durationOrDelay("baseline", b.delayEstimateMin, b.expectedDelayMin)
        if (p == null || t == null || p.isNaN()) return null
        return RiskEstimate(t.first, p.coerceIn(0.0, 1.0), "baseline", confidenceFor(b.sampleSize), "hour $hour", t.second, t.third)
    }

    /**
     * Legacy: every transit leg must match (serviceLabel + Zurich departure hour) with non-low confidence.
     * Delays add up; probability combines as 1-prod(1-p), which assumes independent legs.
     */
    private fun legacyLine(l: Loaded, transit: List<com.cffplanb.backend.domain.JourneyLeg>): RiskEstimate? {
        var delay = 0.0
        var ok = 1.0
        var conf = "high"
        for (leg in transit) {
            val label = leg.serviceLabel
            if (label.isNullOrBlank()) return null
            val hour = leg.departure.atZoneSameInstant(zone).hour
            val row = l.rows[normalizeRiskKey(label) to hour] ?: return null
            val p = row.lateProbability; val d = row.expectedDelayMin
            val c = row.confidence?.lowercase()
            if (p == null || d == null || p.isNaN() || d.isNaN() || c != "medium" && c != "high") return null
            if (c == "medium") conf = "medium"
            delay += d
            ok *= 1 - p.coerceIn(0.0, 1.0)
        }
        return RiskEstimate(Math.round(delay).toInt().coerceAtLeast(0), (1 - ok).coerceIn(0.0, 1.0), "line", conf, transit.mapNotNull { it.serviceLabel }.joinToString("+"), null, "line_delay")
    }

    override fun info(): ModelInfo {
        refresh()
        val l = loaded ?: return ModelInfo(null, null, null, 0, "missing")
        val gen = runCatching { Instant.parse(l.file.generatedAt) }.getOrNull()
        val stale = gen == null || Duration.between(gen, clock.instant()) > staleAfter
        return ModelInfo(l.file.modelVersion, l.file.generatedAt, l.file.coverage, l.rows.size, if (stale) "stale" else "ok",
            l.corridors.size, l.baseline.size, l.rows.size, l.corridors.map { it.row.corridor ?: "${it.row.from} - ${it.row.to}" }.distinct().sorted().take(5))
    }
}
