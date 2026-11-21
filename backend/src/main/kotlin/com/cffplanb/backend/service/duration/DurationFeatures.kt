package com.cffplanb.backend.service.duration

import com.cffplanb.backend.domain.DisruptionContext
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Kotlin port of analytics/sbb_duration/features.py `build_features`.
 * Categorical values are Strings, numeric values Doubles (NaN = missing).
 * golden_vectors.json checks both implementations agree.
 */
class DurationFeatures(derivation: Derivation, private val missingCategory: String = "NA") {
    private val zone: ZoneId = ZoneId.of(derivation.timeZone)
    private val textFlags: Map<String, Regex> = derivation.textFlags.mapValues { (_, flag) ->
        require(flag.field == "summaryEn") { "unsupported text flag field ${flag.field}" }
        Regex(flag.pattern)
    }

    fun build(ctx: DisruptionContext, observedAt: OffsetDateTime): Map<String, Any> {
        val local = observedAt.atZoneSameInstant(zone)
        val summary = ctx.summaryEn?.lowercase(Locale.ROOT) ?: ""
        val features = mutableMapOf<String, Any>(
            "reason_id_fk" to (ctx.reasonCode ?: missingCategory),
            "duration_type" to (ctx.durationType ?: missingCategory),
            "elapsed" to minutesBetween(ctx.startedAt, observedAt),
            "announced_remaining" to (ctx.announcedEndAt?.let { minutesBetween(observedAt, it) } ?: Double.NaN),
            "has_announcement" to if (ctx.announcedEndAt == null) 0.0 else 1.0,
            "hour" to local.hour.toDouble(),
            "weekday" to (local.dayOfWeek.value - 1).toDouble(), // Monday = 0
            "n_updates_so_far" to ctx.updatesSoFar.toDouble()
        )
        textFlags.forEach { (name, regex) -> features[name] = if (regex.containsMatchIn(summary)) 1.0 else 0.0 }
        return features
    }

    companion object {
        val KNOWN_FEATURES = setOf(
            "reason_id_fk", "duration_type", "elapsed", "announced_remaining", "has_announcement",
            "hour", "weekday", "n_updates_so_far", "is_interruption", "is_restriction"
        )

        /** Same arithmetic as pandas `Timedelta.total_seconds() / 60`. */
        fun minutesBetween(from: OffsetDateTime, to: OffsetDateTime): Double =
            Duration.between(from, to).toNanos().toDouble() / 1e9 / 60
    }
}
