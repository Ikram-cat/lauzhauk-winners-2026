package com.cffplanb.backend.service.duration

import kotlinx.serialization.Serializable

/**
 * Subset of analytics/output/duration_model.v1.json needed for inference.
 * Produced by `python -m sbb_duration.export`; unknown keys are ignored.
 */
@Serializable
data class DurationArtifact(
    val schemaVersion: Int,
    val modelVersion: String,
    val features: List<FeatureSpec>,
    val derivation: Derivation,
    val models: Map<String, QuantileModel>,
    val metrics: ArtifactMetrics? = null,
    val explanations: ExplanationTable? = null
)

@Serializable
data class FeatureSpec(
    val name: String,
    val type: String,
    val categories: List<String> = emptyList(),
    val missingCategory: String? = null
)

@Serializable
data class Derivation(val timeZone: String, val textFlags: Map<String, TextFlag>)

@Serializable
data class TextFlag(val field: String, val lowercase: Boolean, val pattern: String)

@Serializable
data class QuantileModel(
    val quantile: Double,
    val baseline: Double,
    val trees: List<List<TreeNode>>,
    /** Split-conformal offset on the log1p scale (pessimistic model); 0 when absent. */
    val calibrationOffset: Double = 0.0
)

/** A leaf has `leaf`; a split has `feature`, `missingLeft`, `left`, `right` and `threshold` or `leftCategories`. */
@Serializable
data class TreeNode(
    val leaf: Double? = null,
    val feature: Int = -1,
    val threshold: Double? = null,
    val leftCategories: List<Int>? = null,
    val missingLeft: Boolean = false,
    val left: Int = -1,
    val right: Int = -1
)

/** Per-cause facts from the training data; see analytics/sbb_duration/explain.py. */
@Serializable
data class ExplanationTable(val minIncidents: Int, val all: ExplanationRow, val byReason: Map<String, ExplanationRow>)

@Serializable
data class ExplanationRow(
    val similarIncidents: Int,
    val medianDurationMinutes: Int,
    val announcementsCompared: Int,
    val medianEndMinusAnnouncedMinutes: Int? = null
)

@Serializable
data class ArtifactMetrics(val test: TestMetrics? = null)

@Serializable
data class TestMetrics(val pessimisticCoverage: Double? = null)
