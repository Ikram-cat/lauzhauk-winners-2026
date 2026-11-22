package com.alt.backend.service.impact

import com.alt.backend.domain.ActiveDisruption
import com.alt.backend.domain.AffectedStation
import com.alt.backend.domain.DisruptionContext
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.time.OffsetDateTime

/** Disruptions active at an instant. */
interface DisruptionSource {
    val dataStatus: String
    fun active(at: OffsetDateTime): List<ActiveDisruption>
}

/** No disruption feed configured. */
object EmptyDisruptionSource : DisruptionSource {
    override val dataStatus = "NONE"
    override fun active(at: OffsetDateTime): List<ActiveDisruption> = emptyList()
}

/**
 * Replays analytics/output/timelines/timeline-*.json (historical, MOCK): an incident is active from
 * its start to its real end, with the state of its last moment at or before the requested instant.
 */
class ReplayTimelineDisruptionSource(private val timelines: List<JsonObject>) : DisruptionSource {
    override val dataStatus = "MOCK"

    override fun active(at: OffsetDateTime): List<ActiveDisruption> = timelines.mapNotNull { timeline ->
        val incident = timeline.obj("incident")
        val start = incident.time("startedAt") ?: return@mapNotNull null
        val end = incident.time("actualEndAt") ?: incident.time("publicEndAt")
        if (at.isBefore(start) || (end != null && !at.isBefore(end))) return@mapNotNull null
        val moments = timeline["moments"]!!.jsonArray.map { it.jsonObject }.filter { !it.time("at")!!.isAfter(at) }
        if (moments.isEmpty()) return@mapNotNull null
        val affected = moments.lastOrNull { it["affected"] !is JsonNull }?.obj("affected")
        val public = moments.last()["public"]?.takeIf { it !is JsonNull }?.jsonObject
        ActiveDisruption(
            incidentId = incident.str("id")!!,
            summaryEn = public?.str("summaryEn") ?: incident.str("summaryEn"),
            context = public?.let {
                DisruptionContext(
                    reasonCode = incident.str("reasonCode"),
                    durationType = it.str("durationType"),
                    startedAt = start,
                    announcedEndAt = it.time("announcedEndAt"),
                    updatesSoFar = it["updatesSoFar"]?.jsonPrimitive?.intOrNull ?: 0,
                    summaryEn = it.str("summaryEn")
                )
            },
            stations = affected?.get("stations")?.jsonArray?.map { s ->
                val o = s.jsonObject
                AffectedStation(o["uic"]!!.jsonPrimitive.int, o.str("sloid"), o.str("name"),
                    o["roles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
            } ?: emptyList(),
            lines = affected?.get("lines")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            dataStatus = dataStatus
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReplayTimelineDisruptionSource::class.java)

        /** Loads every timeline-*.json in dir; unset or empty dir -> EmptyDisruptionSource. */
        fun load(dir: String?): DisruptionSource {
            if (dir.isNullOrBlank()) return EmptyDisruptionSource
            val files = File(dir).listFiles { f -> f.name.startsWith("timeline-") && f.name.endsWith(".json") }
            if (files.isNullOrEmpty()) {
                log.warn("No timeline-*.json in {}: no disruptions for journey impact", dir)
                return EmptyDisruptionSource
            }
            return try {
                ReplayTimelineDisruptionSource(files.sorted().map { Json.parseToJsonElement(it.readText()).jsonObject })
                    .also { log.info("Loaded {} replay disruption timelines (MOCK) from {}", files.size, dir) }
            } catch (e: Exception) {
                log.warn("Replay timelines in {} rejected ({}): no disruptions", dir, e.javaClass.simpleName)
                EmptyDisruptionSource
            }
        }
    }
}

private fun JsonObject.obj(key: String) = this[key]!!.jsonObject
private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.time(key: String) = str(key)?.let(OffsetDateTime::parse)
