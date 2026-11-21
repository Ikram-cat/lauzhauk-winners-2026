package com.cffplanb.backend.service.duration

import com.cffplanb.backend.domain.DisruptionContext
import com.cffplanb.backend.domain.DurationEstimate
import java.time.OffsetDateTime
import kotlin.math.expm1
import kotlin.math.max

/** Remaining-duration estimate for a disruption, or null when no model is available. */
interface DisruptionDurationEstimator {
    fun estimate(ctx: DisruptionContext, observedAt: OffsetDateTime): DurationEstimate?
}

/** Used when the artifact is absent or unsupported: estimates stay unknown. */
object NoDurationEstimator : DisruptionDurationEstimator {
    override fun estimate(ctx: DisruptionContext, observedAt: OffsetDateTime): DurationEstimate? = null
}

/**
 * Evaluates the exported gradient-boosted trees, mirroring analytics/sbb_duration/artifact.py:
 * raw = baseline + sum of reached leaves (log1p scale), then
 * p50 = max(expm1(raw50), 0) and pessimistic = max(expm1(rawPessimistic), p50).
 */
class TreeEnsembleDurationEstimator(private val artifact: DurationArtifact) : DisruptionDurationEstimator {
    private val p50Model: QuantileModel
    private val pessimisticModel: QuantileModel
    private val featureBuilder: DurationFeatures

    init {
        require(artifact.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "unsupported schemaVersion ${artifact.schemaVersion}"
        }
        p50Model = requireNotNull(artifact.models["p50"]) { "missing model p50" }
        pessimisticModel = requireNotNull(artifact.models["pessimistic"]) { "missing model pessimistic" }
        val unknown = artifact.features.map { it.name } - DurationFeatures.KNOWN_FEATURES
        require(unknown.isEmpty()) { "unknown features $unknown" }
        val missingCategory = artifact.features.firstNotNullOfOrNull { it.missingCategory } ?: "NA"
        featureBuilder = DurationFeatures(artifact.derivation, missingCategory)
    }

    fun features(ctx: DisruptionContext, observedAt: OffsetDateTime): Map<String, Any> =
        featureBuilder.build(ctx, observedAt)

    /** Raw (log1p-scale) P50 and pessimistic outputs. */
    fun predictRaw(features: Map<String, Any>): Pair<Double, Double> {
        val row = encode(features)
        return score(p50Model, row) to score(pessimisticModel, row)
    }

    override fun estimate(ctx: DisruptionContext, observedAt: OffsetDateTime): DurationEstimate {
        val (raw50, rawPessimistic) = predictRaw(features(ctx, observedAt))
        val p50 = max(expm1(raw50), 0.0)
        return DurationEstimate(
            p50Minutes = p50,
            pessimisticMinutes = max(expm1(rawPessimistic), p50),
            modelVersion = artifact.modelVersion,
            pessimisticQuantile = pessimisticModel.quantile,
            pessimisticCoverage = artifact.metrics?.test?.pessimisticCoverage
        )
    }

    /** Model row in artifact feature order. Categories become codes; unknown or missing -> NaN. */
    private fun encode(features: Map<String, Any>): DoubleArray = DoubleArray(artifact.features.size) { i ->
        val spec = artifact.features[i]
        val value = features[spec.name]
        if (spec.type == "categorical") {
            val code = spec.categories.indexOf(value as? String)
            if (code < 0) Double.NaN else code.toDouble()
        } else {
            (value as? Double) ?: Double.NaN
        }
    }

    private fun score(model: QuantileModel, row: DoubleArray): Double =
        model.baseline + model.trees.sumOf { leafValue(it, row) }

    private fun leafValue(nodes: List<TreeNode>, row: DoubleArray): Double {
        var node = nodes[0]
        while (true) {
            node.leaf?.let { return it }
            val x = row[node.feature]
            val categories = node.leftCategories
            val goLeft = when {
                x.isNaN() -> node.missingLeft
                categories != null -> x.toInt() in categories
                else -> x <= node.threshold!!
            }
            node = nodes[if (goLeft) node.left else node.right]
        }
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}
