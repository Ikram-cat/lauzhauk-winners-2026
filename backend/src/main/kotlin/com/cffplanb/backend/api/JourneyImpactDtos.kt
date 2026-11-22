package com.cffplanb.backend.api

import kotlinx.serialization.Serializable

@Serializable
data class JourneyImpactRequestDto(
    val origin: String,
    val destination: String,
    val departure: String? = null,
    val observedAt: String? = null,
    val journeyId: String? = null
)

@Serializable
data class MatchedStationDto(
    val legIndex: Int,
    val uic: Int,
    val sloid: String?,
    val name: String?,
    val roles: List<String>,
    val matchedBy: String
)

@Serializable
data class AlternativeDto(val status: String, val journey: ImpactJourneyDto?)

@Serializable
data class JourneyDisruptionImpactDto(
    val incidentId: String,
    val summaryEn: String?,
    val matchedStations: List<MatchedStationDto>,
    val matchedLines: List<String>,
    val detourExtraMinutes: Int?,
    val alternative: AlternativeDto,
    val outlook: DisruptionOutlookResponseDto
)

@Serializable
data class JourneyImpactResponseDto(
    val dataStatus: String,
    val observedAt: String,
    val journey: ImpactJourneyDto?,
    val affected: Boolean,
    val impacts: List<JourneyDisruptionImpactDto>,
    val liveAlerts: LiveAlertsDto = LiveAlertsDto.disabled()
)

@Serializable
data class ActivePeriodDto(val start: String?, val end: String?)

@Serializable
data class LiveAlertStopDto(val legIndex: Int, val sloid: String, val uic: Int?)

/**
 * Live GTFS-RT Service Alerts touching the journey, merged when they share the title (direction ignored)
 * and the matched stations. No duration is predicted for live alerts.
 */
@Serializable
data class LiveAlertDto(
    /** UUIDs of the merged feed alerts. */
    val ids: List<String>,
    /** GTFS agency ids (operators) named by the merged alerts, e.g. "11" = SBB. */
    val operators: List<String>,
    /** Route parts of the merged titles, one per direction seen, e.g. "Renens VD - Allaman". */
    val directions: List<String>,
    val headerText: Map<String, String>,
    val descriptionText: Map<String, String>,
    val cause: String?,
    val effect: String?,
    val activePeriods: List<ActivePeriodDto>,
    val matchedStations: List<LiveAlertStopDto>,
    val prediction: RemainingMinutesDto? = null,
    val predictionReason: String = LiveAlertsDto.NO_PREDICTION_REASON
)

@Serializable
data class LiveAlertsDto(
    val dataStatus: String = "LIVE",
    /** OK, DISABLED (no GTFS_SA_API_KEY) or UNAVAILABLE (feed not reachable). */
    val status: String,
    val source: String = "GTFS-RT Service Alerts, opentransportdata.swiss la/gtfs-sa",
    val fetchedAt: String? = null,
    val feedTimestamp: String? = null,
    val alerts: List<LiveAlertDto> = emptyList()
) {
    companion object {
        const val NO_PREDICTION_REASON = "No duration is predicted for live alerts: the alert cause has no mapping to " +
            "the SBB cause codes the model was trained on, active_period is a validity window rather than a verified " +
            "announced end, and live alerts cannot yet be validated against known ends."
        fun disabled() = LiveAlertsDto(status = "DISABLED")
    }
}

/** Plain journey shape of /api/journeys/impact (scheduled times and stop sloids), independent of /api/journeys. */
@Serializable
data class ImpactLegDto(
    val mode: String, val origin: String, val destination: String, val departure: String, val arrival: String,
    val serviceLabel: String?, val originSloid: String?, val destinationSloid: String?
)

@Serializable
data class ImpactJourneyDto(
    val id: String, val departure: String, val arrival: String, val durationMinutes: Int, val transfers: Int,
    val dataStatus: String, val legs: List<ImpactLegDto>
)

internal fun com.cffplanb.backend.domain.Journey.toImpactDto() = ImpactJourneyDto(
    id, RFC3339_FORMATTER.format(departure), RFC3339_FORMATTER.format(arrival), durationMinutes, transfers, dataStatus.name,
    legs.map { l ->
        ImpactLegDto(l.mode.name, l.origin, l.destination, RFC3339_FORMATTER.format(l.departure), RFC3339_FORMATTER.format(l.arrival),
            l.serviceLabel, l.originSloid, l.destinationSloid)
    })
