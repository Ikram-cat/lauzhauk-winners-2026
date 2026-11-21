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

data class RiskEstimate(val predictedDelayMinutes: Int, val lateArrivalProbability: Double)

data class ModelInfo(
    val modelVersion: String?,
    val generatedAt: String?,
    val coverage: kotlinx.serialization.json.JsonElement?,
    val keysLoaded: Int,
    val status: String // ok | missing | stale
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
internal data class ArtifactFile(
    val modelVersion: String? = null,
    val generatedAt: String? = null,
    val coverage: kotlinx.serialization.json.JsonElement? = null,
    val byLineHour: List<ArtifactRow> = emptyList()
)

private class Loaded(val file: ArtifactFile, val rows: Map<Pair<String, Int>, ArtifactRow>)

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
                loaded = Loaded(file, rows)
                log.info("Risk artifact loaded: {} keys", rows.size)
            } catch (e: Exception) {
                loaded = null
                if (loggedFailureFor != mtime) log.warn("Risk artifact unreadable ({}); risk values stay unknown", e.javaClass.simpleName)
                loggedFailureFor = mtime
            }
        }
    }

    /**
     * Every transit leg must match (key + Zurich departure hour) with non-low confidence, else unknown.
     * Delays add up; probability combines as 1-prod(1-p), which assumes independent legs.
     */
    override fun estimate(journey: Journey): RiskEstimate? {
        refresh()
        val l = loaded ?: return null
        val transit = journey.legs.filter { it.mode == LegMode.TRANSIT }
        if (transit.isEmpty()) return null
        var delay = 0.0
        var ok = 1.0
        for (leg in transit) {
            val label = leg.serviceLabel
            if (label.isNullOrBlank()) { log.debug("risk: leg without serviceLabel"); return null }
            val hour = leg.departure.atZoneSameInstant(zone).hour
            val row = l.rows[normalizeRiskKey(label) to hour]
            if (row == null) { log.debug("risk: no artifact row for key={} hour={}", normalizeRiskKey(label), hour); return null }
            val p = row.lateProbability; val d = row.expectedDelayMin
            val conf = row.confidence?.lowercase()
            if (p == null || d == null || p.isNaN() || d.isNaN() || conf == null || conf == "low" || conf !in setOf("medium", "high")) {
                log.debug("risk: row unusable for key={} hour={} confidence={}", normalizeRiskKey(label), hour, conf); return null
            }
            delay += d
            ok *= 1 - p.coerceIn(0.0, 1.0)
        }
        return RiskEstimate(Math.round(delay).toInt().coerceAtLeast(0), (1 - ok).coerceIn(0.0, 1.0))
    }

    override fun info(): ModelInfo {
        refresh()
        val l = loaded ?: return ModelInfo(null, null, null, 0, "missing")
        val gen = runCatching { Instant.parse(l.file.generatedAt) }.getOrNull()
        val stale = gen == null || Duration.between(gen, clock.instant()) > staleAfter
        return ModelInfo(l.file.modelVersion, l.file.generatedAt, l.file.coverage, l.rows.size, if (stale) "stale" else "ok")
    }
}
