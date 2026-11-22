package com.alt.backend.api

import com.alt.backend.auth.Authenticator
import com.alt.backend.auth.TokenService
import com.alt.backend.service.PlaceRef
import com.alt.backend.service.PlanRecord
import com.alt.backend.service.PlanService
import com.alt.backend.service.TravelRecord
import com.alt.backend.service.TravelService
import com.alt.backend.service.UserRecord
import com.alt.backend.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

private class FieldError(val field: String, message: String) : Exception(message)

/** House pattern (JourneyImpactRoutes): malformed body -> null -> 400 INVALID_REQUEST; cancellation is rethrown. */
private suspend inline fun <reified T : Any> ApplicationCall.bodyOrNull(): T? = try {
    receive<T>()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

private suspend fun ApplicationCall.fail(status: HttpStatusCode, code: String, message: String, field: String? = null) =
    respond(status, ErrorResponseDto(ErrorDetailDto(code, message, field)))

private fun ApplicationCall.rejectDuplicates(known: Set<String>) {
    val keys = request.local.uri.substringAfter("?", "").split("&").filter { it.isNotEmpty() }
        .map { java.net.URLDecoder.decode(it.substringBefore("="), "UTF-8") }
    keys.firstOrNull { it in known && keys.count { k -> k == it } > 1 }?.let { throw FieldError(it, "Duplicate parameter: $it") }
}

private fun Parameters.limit(max: Int, default: Int): Int =
    this["limit"]?.let { v ->
        v.trim().toIntOrNull()?.takeIf { it in 1..max } ?: throw FieldError("limit", "Parameter 'limit' must be an integer from 1 to $max")
    } ?: default

/** Validates an RFC 3339 timestamp with offset and returns it exactly as sent (offset preserved). */
private fun timestampOf(field: String, value: String): String = try {
    OffsetDateTime.parse(value, RFC3339_FORMATTER)
    value
} catch (e: DateTimeParseException) {
    throw FieldError(field, "Invalid $field timestamp. Must be RFC 3339 with Z or numeric UTC offset.")
}

private fun placeOf(field: String, dto: PlaceRefDto?): PlaceRef {
    val p = dto ?: throw FieldError(field, "Field '$field' is required")
    val name = p.name?.trim() ?: throw FieldError(field, "Field '$field.name' is required")
    if (name.isEmpty()) throw FieldError(field, "Field '$field.name' must not be blank")
    if (name.codePointCount(0, name.length) > 120) throw FieldError(field, "Field '$field.name' must not exceed 120 code points")
    return PlaceRef(p.input?.trim()?.takeIf { it.isNotEmpty() }, name, p.id?.trim()?.takeIf { it.isNotEmpty() })
}

private fun userDto(u: UserRecord) = UserDto(u.id.toString(), u.email, u.displayName)
private fun placeDto(p: PlaceRef) = PlaceRefDto(p.input, p.name, p.id)
private fun rfc(instant: Instant) = RFC3339_FORMATTER.format(instant.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS))

private fun planDto(p: PlanRecord) = PlanDto(
    p.id.toString(), p.label, placeDto(p.origin), placeDto(p.destination), p.departure,
    p.journeyJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }, rfc(p.createdAt)
)

private fun travelDto(t: TravelRecord) = TravelDto(
    t.id.toString(), t.planId?.toString(), PlaceRefDto(name = t.originName), PlaceRefDto(name = t.destinationName),
    t.departure, t.arrivalPlanned, t.rating, t.note, t.delayMinutes, rfc(t.createdAt)
)

private suspend fun ApplicationCall.authenticated(auth: Authenticator): UserRecord? {
    val user = auth.user(this)
    if (user == null) {
        fail(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing, invalid or expired token.")
        return null
    }
    return user
}

/**
 * POST /api/auth/{register,login,logout}, GET /api/auth/me — bearer-token accounts.
 * GET+POST /api/plans, DELETE /api/plans/{id} — saved trips.
 * GET+POST /api/travels — past-travel log. All strict structured JSON.
 */
fun Route.accountRoutes(
    users: UserService,
    tokens: TokenService,
    plans: PlanService,
    travels: TravelService,
    auth: Authenticator
) {
    route("/api/auth") {
        post("/register") {
            val body = call.bodyOrNull<RegisterRequestDto>()
                ?: return@post call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Body must be a JSON object with email and password.")
            try {
                val email = body.email?.trim()?.lowercase() ?: throw FieldError("email", "Email is required")
                if (email.isEmpty()) throw FieldError("email", "Email must not be blank")
                if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) throw FieldError("email", "Email must be a valid address")
                val password = body.password ?: throw FieldError("password", "Password is required")
                if (password.length < 8 || password.length > 128) throw FieldError("password", "Password must be 8 to 128 characters")
                val displayName = body.displayName?.trim()?.takeIf { it.isNotEmpty() }?.also {
                    if (it.codePointCount(0, it.length) > 80) throw FieldError("displayName", "Display name must not exceed 80 code points")
                }
                when (val r = users.register(email, password, displayName)) {
                    is UserService.RegisterResult.Ok -> {
                        val t = tokens.issue(r.user.id)
                        call.respond(HttpStatusCode.OK, AuthResponseDto("OK", userDto(r.user), t.token, rfc(t.expiresAt)))
                    }
                    UserService.RegisterResult.EmailTaken ->
                        call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Email already registered.", "email")
                }
            } catch (e: FieldError) {
                call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", e.message!!, e.field)
            }
        }

        post("/login") {
            val body = call.bodyOrNull<LoginRequestDto>()
                ?: return@post call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Body must be a JSON object with email and password.")
            try {
                val email = body.email?.trim()?.lowercase() ?: throw FieldError("email", "Email is required")
                val password = body.password ?: throw FieldError("password", "Password is required")
                if (email.isEmpty()) throw FieldError("email", "Email must not be blank")
                if (password.isEmpty()) throw FieldError("password", "Password must not be blank")
                val user = users.login(email, password)
                    ?: return@post call.fail(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid email or password.")
                val t = tokens.issue(user.id)
                call.respond(HttpStatusCode.OK, AuthResponseDto("OK", userDto(user), t.token, rfc(t.expiresAt)))
            } catch (e: FieldError) {
                call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", e.message!!, e.field)
            }
        }

        get("/me") {
            val user = call.authenticated(auth) ?: return@get
            call.respond(HttpStatusCode.OK, MeResponseDto("OK", userDto(user)))
        }

        post("/logout") {
            call.authenticated(auth) ?: return@post
            val raw = auth.rawToken(call) ?: return@post call.fail(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing, invalid or expired token.")
            tokens.revoke(raw)
            call.respond(HttpStatusCode.OK, StatusResponseDto("OK"))
        }
    }

    route("/api/plans") {
        post {
            val user = call.authenticated(auth) ?: return@post
            val body = call.bodyOrNull<PlanCreateRequestDto>()
                ?: return@post call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Body must be a JSON object with origin, destination and departure.")
            try {
                val origin = placeOf("origin", body.origin)
                val destination = placeOf("destination", body.destination)
                val departure = body.departure?.let { timestampOf("departure", it) } ?: throw FieldError("departure", "Departure is required")
                val label = body.label?.trim()?.takeIf { it.isNotEmpty() }?.also {
                    if (it.codePointCount(0, it.length) > 80) throw FieldError("label", "Label must not exceed 80 code points")
                }
                val snapshot = body.journey?.toString()?.also {
                    if (it.length > 65536) throw FieldError("journey", "Journey snapshot must not exceed 65536 characters")
                }
                val plan = plans.create(user.id, origin, destination, departure, label, snapshot)
                call.respond(HttpStatusCode.OK, planDto(plan))
            } catch (e: FieldError) {
                call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", e.message!!, e.field)
            }
        }

        get {
            val user = call.authenticated(auth) ?: return@get
            try {
                call.rejectDuplicates(setOf("limit"))
                val limit = call.request.queryParameters.limit(100, 20)
                call.respond(HttpStatusCode.OK, PlansResponseDto(plans.list(user.id, limit).map { planDto(it) }))
            } catch (e: FieldError) {
                call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", e.message!!, e.field)
            }
        }

        delete("/{id}") {
            val user = call.authenticated(auth) ?: return@delete
            val planId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.fail(HttpStatusCode.NotFound, "NOT_FOUND", "Plan not found.")
            if (plans.delete(user.id, planId)) call.respond(HttpStatusCode.OK, StatusResponseDto("OK"))
            else call.fail(HttpStatusCode.NotFound, "NOT_FOUND", "Plan not found.")
        }
    }

    route("/api/travels") {
        post {
            val user = call.authenticated(auth) ?: return@post
            val body = call.bodyOrNull<TravelCreateRequestDto>()
                ?: return@post call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Body must be a JSON object describing a saved plan or a standalone travel.")
            try {
                val rating = body.rating?.also { if (it !in 1..5) throw FieldError("rating", "Rating must be an integer from 1 to 5") }
                val note = body.note?.trim()?.takeIf { it.isNotEmpty() }?.also {
                    if (it.codePointCount(0, it.length) > 500) throw FieldError("note", "Note must not exceed 500 code points")
                }
                val delayMinutes = body.delayMinutes?.also { if (it < 0) throw FieldError("delayMinutes", "Delay minutes must not be negative") }
                val travel = if (body.planId != null) {
                    val planId = runCatching { UUID.fromString(body.planId.trim()) }.getOrNull()
                        ?: throw FieldError("planId", "Plan id must be a UUID")
                    val plan = plans.findOwned(user.id, planId) ?: throw FieldError("planId", "Plan not found.")
                    val arrival = body.arrival?.let { timestampOf("arrival", it) }
                    travels.create(user.id, plan.id, plan.origin.name, plan.destination.name, plan.departure, arrival, rating, note, delayMinutes)
                } else {
                    val origin = placeOf("origin", body.origin)
                    val destination = placeOf("destination", body.destination)
                    val departure = body.departure?.let { timestampOf("departure", it) } ?: throw FieldError("departure", "Departure is required")
                    val arrival = body.arrival?.let { timestampOf("arrival", it) }
                    travels.create(user.id, null, origin.name, destination.name, departure, arrival, rating, note, delayMinutes)
                }
                call.respond(HttpStatusCode.OK, travelDto(travel))
            } catch (e: FieldError) {
                call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", e.message!!, e.field)
            }
        }

        get {
            val user = call.authenticated(auth) ?: return@get
            try {
                call.rejectDuplicates(setOf("limit"))
                val limit = call.request.queryParameters.limit(100, 20)
                call.respond(HttpStatusCode.OK, TravelsResponseDto(travels.list(user.id, limit).map { travelDto(it) }))
            } catch (e: FieldError) {
                call.fail(HttpStatusCode.BadRequest, "INVALID_REQUEST", e.message!!, e.field)
            }
        }
    }
}
