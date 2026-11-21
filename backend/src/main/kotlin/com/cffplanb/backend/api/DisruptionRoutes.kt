package com.cffplanb.backend.api

import com.cffplanb.backend.domain.DisruptionContext
import com.cffplanb.backend.service.AdviceService
import com.cffplanb.backend.service.duration.DisruptionDurationEstimator
import com.cffplanb.backend.service.duration.DurationFeatures
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private val RFC3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME

private class InvalidRequest(message: String) : Exception(message)

private fun parseInstant(name: String, value: String): OffsetDateTime = try {
    OffsetDateTime.parse(value, RFC3339)
} catch (e: DateTimeParseException) {
    throw InvalidRequest("Invalid $name timestamp. Must be RFC 3339 with Z or numeric UTC offset.")
}

/** POST /api/disruptions/outlook: remaining-duration estimate and wait-or-detour advice. Proposal, see API_CONTRACT. */
fun Route.disruptionRoutes(estimator: DisruptionDurationEstimator, clock: Clock) {
    post("/api/disruptions/outlook") {
        val request = try {
            call.receive<DisruptionOutlookRequestDto>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest,
                ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", "Malformed or incomplete JSON body")))
            return@post
        }

        val (ctx, observedAt) = try {
            val input = request.disruption
            val observedAt = request.observedAt?.let { parseInstant("observedAt", it) } ?: OffsetDateTime.now(clock)
            val ctx = DisruptionContext(
                reasonCode = input.reasonCode,
                durationType = input.durationType,
                startedAt = parseInstant("disruption.startedAt", input.startedAt),
                announcedEndAt = input.announcedEndAt?.let { parseInstant("disruption.announcedEndAt", it) },
                updatesSoFar = input.updatesSoFar,
                summaryEn = input.summaryEn
            )
            if (ctx.startedAt.isAfter(observedAt)) throw InvalidRequest("disruption.startedAt is after observedAt")
            if (ctx.updatesSoFar < 0) throw InvalidRequest("disruption.updatesSoFar must be >= 0")
            if ((request.detourExtraMinutes ?: 0) < 0) throw InvalidRequest("detourExtraMinutes must be >= 0")
            ctx to observedAt
        } catch (e: InvalidRequest) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto(ErrorDetailDto("INVALID_REQUEST", e.message!!)))
            return@post
        }

        val estimate = estimator.estimate(ctx, observedAt)
        val official = ctx.announcedEndAt?.let { max(DurationFeatures.minutesBetween(observedAt, it), 0.0).roundToInt() }
        val remaining = estimate?.let {
            RemainingMinutesDto(p50 = it.p50Minutes.roundToInt(), pessimistic = ceil(it.pessimisticMinutes).toInt())
        }
        call.respond(
            HttpStatusCode.OK,
            DisruptionOutlookResponseDto(
                status = if (estimate == null) "UNAVAILABLE" else "AVAILABLE",
                remainingMinutes = remaining,
                expectedEndAt = remaining?.let {
                    ExpectedEndDto(
                        p50 = RFC3339.format(observedAt.plusMinutes(it.p50.toLong())),
                        pessimistic = RFC3339.format(observedAt.plusMinutes(it.pessimistic.toLong()))
                    )
                },
                officialRemainingMinutes = official,
                advice = AdviceDto(AdviceService.advise(estimate, request.detourExtraMinutes).name, request.detourExtraMinutes),
                provenance = estimate?.let {
                    DurationProvenanceDto("ESTIMATED", it.modelVersion, it.pessimisticQuantile, it.pessimisticCoverage)
                },
                explanation = estimator.explain(ctx.reasonCode)?.let {
                    ExplanationDto(it.reasonCode, it.scope, it.similarIncidents, it.medianDurationMinutes,
                        it.announcementsCompared, it.medianEndMinusAnnouncedMinutes)
                }
            )
        )
    }
}
