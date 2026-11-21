package com.cffplanb.backend.service.duration

import com.cffplanb.backend.domain.DisruptionContext
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin features + tree evaluation reproduce the Python/sklearn outputs on the 100 golden cases.
 * The files are generated locally by `python -m sbb_duration.train && python -m sbb_duration.export`
 * in analytics/ and are not committed; the test is skipped when they are absent.
 */
class DurationGoldenVectorsTest {
    private val artifactFile = File(System.getenv("DURATION_ARTIFACT_PATH") ?: "../analytics/output/duration_model.v1.json")
    private val goldenFile = File(System.getenv("DURATION_GOLDEN_PATH") ?: "../analytics/output/golden_vectors.json")

    @Test
    fun `kotlin evaluator matches the 100 golden cases`() {
        assumeTrue(artifactFile.isFile && goldenFile.isFile, "duration artifact or golden vectors not generated")
        val estimator = DurationArtifactLoader.parse(artifactFile.readText())
        val golden = Json.parseToJsonElement(goldenFile.readText()).jsonObject
        val tolerance = golden["tolerance"]!!.jsonPrimitive.double
        val cases = golden["cases"]!!.jsonArray
        assertEquals(100, cases.size)

        for (case in cases.map { it.jsonObject }) {
            val id = case["case"]!!.jsonPrimitive.content
            val input = case["input"]!!.jsonObject
            fun str(key: String) = input[key]?.jsonPrimitive?.contentOrNull
            val ctx = DisruptionContext(
                reasonCode = str("reasonCode"),
                durationType = str("durationType"),
                startedAt = OffsetDateTime.parse(str("startedAt")),
                announcedEndAt = str("announcedEndAt")?.let(OffsetDateTime::parse),
                updatesSoFar = input["updatesSoFar"]!!.jsonPrimitive.int,
                summaryEn = str("summaryEn")
            )
            val observedAt = OffsetDateTime.parse(str("observedAt"))

            val features = estimator.features(ctx, observedAt)
            for ((name, expected) in case["features"]!!.jsonObject) {
                val actual = features[name]
                when {
                    expected is JsonNull -> assertTrue((actual as Double).isNaN(), "$id $name should be missing")
                    expected.jsonPrimitive.isString -> assertEquals(expected.jsonPrimitive.content, actual, "$id $name")
                    else -> assertClose(expected.jsonPrimitive.double, actual as Double, 1e-9, "$id $name")
                }
            }

            val estimate = estimator.estimate(ctx, observedAt)
            val expected = case["expected"]!!.jsonObject
            assertClose(expected["p50Minutes"]!!.jsonPrimitive.double, estimate.p50Minutes, tolerance, "$id p50")
            assertClose(expected["pessimisticMinutes"]!!.jsonPrimitive.double, estimate.pessimisticMinutes, tolerance,
                "$id pessimistic")
            assertTrue(estimate.pessimisticMinutes >= estimate.p50Minutes, "$id pessimistic >= p50")
        }
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double, message: String) =
        assertTrue(abs(expected - actual) <= tolerance, "$message: expected $expected, got $actual")
}
