package com.cffplanb.backend.domain

import java.time.OffsetDateTime

/** What is publicly known about a disruption at the observation instant. */
data class DisruptionContext(
    val reasonCode: String?,
    val durationType: String?,
    val startedAt: OffsetDateTime,
    val announcedEndAt: OffsetDateTime?,
    val updatesSoFar: Int,
    val summaryEn: String?
)

/** Remaining duration in minutes, from the offline-trained model artifact. */
data class DurationEstimate(
    val p50Minutes: Double,
    val pessimisticMinutes: Double,
    val modelVersion: String,
    val pessimisticQuantile: Double,
    val pessimisticCoverage: Double?
)

/** Historical facts for the cause, from the training data. scope = REASON or ALL_REASONS (rare/unknown cause). */
data class DurationExplanation(
    val reasonCode: String?,
    val scope: String,
    val similarIncidents: Int,
    val medianDurationMinutes: Int,
    val announcementsCompared: Int,
    val medianEndMinusAnnouncedMinutes: Int?
)

enum class AdviceCode { TAKE_DETOUR, DETOUR_IF_DEADLINE, WAIT, UNKNOWN }

/** A station touched by a disruption. roles: DISRUPTION_POINT, LINE_FROM, LINE_TO (VIA semantics). */
data class AffectedStation(val uic: Int, val sloid: String?, val name: String?, val roles: List<String>)

/** A disruption active at some instant, with what is needed to match journeys and predict its end. */
data class ActiveDisruption(
    val incidentId: String,
    val summaryEn: String?,
    /** Null before the first public announcement: no prediction is made then. */
    val context: DisruptionContext?,
    val stations: List<AffectedStation>,
    val lines: List<String>,
    val dataStatus: String
)
