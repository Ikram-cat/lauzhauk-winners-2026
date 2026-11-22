package com.cffplanb.backend.service.duration

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/** Loads the duration artifact at startup. Any problem leaves estimates unknown and logs one warning. */
object DurationArtifactLoader {
    private val log = LoggerFactory.getLogger(DurationArtifactLoader::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): TreeEnsembleDurationEstimator =
        TreeEnsembleDurationEstimator(json.decodeFromString(DurationArtifact.serializer(), text))

    fun load(path: String?): DisruptionDurationEstimator {
        if (path.isNullOrBlank()) {
            log.warn("DURATION_ARTIFACT_PATH is not set: disruption duration estimates are unavailable")
            return NoDurationEstimator
        }
        val file = File(path)
        if (!file.isFile) {
            log.warn("Duration artifact not found at {}: disruption duration estimates are unavailable", path)
            return NoDurationEstimator
        }
        return try {
            parse(file.readText(Charsets.UTF_8)).also { log.info("Loaded duration artifact from {}", path) }
        } catch (e: Exception) {
            log.warn("Duration artifact at {} rejected ({}: {}): estimates are unavailable",
                path, e.javaClass.simpleName, e.message?.take(200))
            NoDurationEstimator
        }
    }
}
