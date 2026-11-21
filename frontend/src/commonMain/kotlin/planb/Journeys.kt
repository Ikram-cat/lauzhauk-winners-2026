@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.math.roundToLong
import kotlin.time.Instant

internal val Zurich = TimeZone.of("Europe/Zurich")

internal fun two(value: Int) = value.toString().padStart(2, '0')
internal fun time(instant: Instant): String = instant.toLocalDateTime(Zurich).let {
    "${two(it.hour)}:${two(it.minute)}"
}

internal class SearchInputException(val feedback: Copy) : IllegalArgumentException()

internal fun swissDeparture(date: String, hour: String): Instant? {
    if (date.isBlank() && hour.isBlank()) return null
    val local = runCatching { LocalDateTime.parse("${date.trim()}T${hour.trim()}") }
        .getOrElse { throw SearchInputException(Copy.InvalidDate) }
    return listOf(UtcOffset(hours = 1), UtcOffset(hours = 2))
        .map { local.toInstant(it) }
        .singleOrNull { it.toLocalDateTime(Zurich) == local }
        ?: throw SearchInputException(Copy.AmbiguousDate)
}

@Serializable
internal data class Leg(
    val origin: String, val destination: String, val departure: String, val arrival: String,
    val serviceLabel: String? = null, val departurePlatform: String? = null,
    val arrivalPlatform: String? = null, val mode: String? = null
)

@Serializable
internal data class Journey(
    val id: String, val departure: String, val arrival: String, val durationMinutes: Int,
    val transfers: Int, val dataStatus: String, val legs: List<Leg>,
    val estimatedFareChf: Double? = null, val halfFareChf: Double? = null, val firstClassFareChf: Double? = null,
    val fareProduct: String? = null, val fareSource: String? = null, val fareStatus: String? = null,
    val fareChf: Double? = null, val firstClassHalfFareChf: Double? = null,
    val feasible: Boolean? = null, val lateArrivalProbability: Double? = null,
    val riskSource: String? = null, val riskConfidence: String? = null,
    val riskLabel: String? = null, val riskBasis: String? = null,
    val disruptionDurationMin: Int? = null, val reasons: List<String> = emptyList(),
    val why: String? = null
) {
    private val known get() = fareStatus != "UNAVAILABLE"
    /** Undiscounted fare for the class; null means unknown and must not be rendered. */
    fun fullFare(firstClass: Boolean) = (if (firstClass) firstClassFareChf else estimatedFareChf)?.takeIf { known }
    /** Price the user pays: backend `fareChf`, else the contract v2 field for the selection (older backends). */
    fun fare(firstClass: Boolean, halfFare: Boolean) = (fareChf ?: when {
        !halfFare -> fullFare(firstClass)
        firstClass -> firstClassHalfFareChf
        else -> halfFareChf
    })?.takeIf { known }
}

@Serializable
internal data class Disruption(
    val legIndex: Int? = null,
    val serviceLabel: String? = null,
    val from: String? = null,
    val to: String? = null,
    val scheduledDeparture: String? = null,
    val estimatedDeparture: String? = null,
    val delayMinutes: Int? = null,
    val cancelled: Boolean? = null,
    val basis: String? = null,
    val confidence: String? = null,
    val cause: String? = null
)

@Serializable
internal data class RedirectResponse(
    val situation: String = "NO_DISRUPTION",
    val disruption: Disruption? = null,
    val recommendation: Journey? = null,
    val alternatives: List<Journey> = emptyList(),
    val journeys: List<Journey> = emptyList(),
    val message: String? = null,
    val computedAt: String? = null,
    val modelVersion: String? = null,
    val latestFeasibleDeparture: String? = null,
    val noFeasibleRoute: Boolean? = null,
    val advice: String? = null,
    val recommendations: Recommendations? = null
) {
    val displayJourneys: List<Journey>
        get() = if (journeys.isNotEmpty()) journeys else listOfNotNull(recommendation) + alternatives
}

internal enum class Preference { FASTEST, CHEAPEST, SIMPLEST }

@Serializable
internal data class Recommendations(val fastest: String? = null, val cheapest: String? = null, val simplest: String? = null) {
    fun id(preference: Preference) = when (preference) {
        Preference.FASTEST -> fastest
        Preference.CHEAPEST -> cheapest
        Preference.SIMPLEST -> simplest
    }
}

@Serializable
internal data class JourneyResponse(
    val journeys: List<Journey>, val recommendations: Recommendations? = null,
    val latestFeasibleDeparture: String? = null, val noFeasibleRoute: Boolean? = null, val advice: String? = null
)

@Serializable
internal data class Location(val id: String, val name: String, val kind: String)

@Serializable
private data class LocationsResponse(val locations: List<Location>)

@Serializable
internal data class ModelInfo(
    val modelVersion: String? = null, val generatedAt: String? = null, val status: String,
    val gtfsRtEnabled: Boolean = false, val gtfsRtAgeSeconds: Long? = null,
    val gtfsRtTripUpdates: Int = 0, val gtfsRtCancelled: Int = 0
)

@Serializable
private data class AlternativesResponse(
    val alternatives: List<Journey>, val latestFeasibleDeparture: String? = null,
    val noFeasibleRoute: Boolean, val advice: String? = null
)

private val json = Json { ignoreUnknownKeys = true }

internal fun parseJourneys(body: String): JourneyResponse = validateJourneys(json.decodeFromString<JourneyResponse>(body))

internal fun parseRedirect(body: String): RedirectResponse = validateRedirect(json.decodeFromString<RedirectResponse>(body))

private fun validateRedirect(response: RedirectResponse): RedirectResponse = response.also { res ->
    res.recommendation?.let { journey ->
        Instant.parse(journey.departure)
        Instant.parse(journey.arrival)
        journey.legs.forEach { Instant.parse(it.departure); Instant.parse(it.arrival) }
    }
    res.alternatives.forEach { journey ->
        Instant.parse(journey.departure)
        Instant.parse(journey.arrival)
        journey.legs.forEach { Instant.parse(it.departure); Instant.parse(it.arrival) }
    }
    res.journeys.forEach { journey ->
        Instant.parse(journey.departure)
        Instant.parse(journey.arrival)
        journey.legs.forEach { Instant.parse(it.departure); Instant.parse(it.arrival) }
    }
    res.latestFeasibleDeparture?.let(Instant::parse)
}

private fun validateJourneys(response: JourneyResponse): JourneyResponse = response.also {
    response.journeys.forEach { journey ->
        Instant.parse(journey.departure)
        Instant.parse(journey.arrival)
        journey.legs.forEach { Instant.parse(it.departure); Instant.parse(it.arrival) }
    }
    response.latestFeasibleDeparture?.let(Instant::parse)
}

internal fun journeysUrl(base: String, origin: String, destination: String, departure: Instant?, preference: Preference,
                         firstClass: Boolean = false, halfFare: Boolean = false, deadline: Instant? = null) =
    URLBuilder(base.trimEnd('/') + "/api/journeys").apply {
        parameters.append("origin", origin)
        parameters.append("destination", destination)
        departure?.let { parameters.append("departure", it.toString()) }
        deadline?.let { parameters.append("arrivalDeadline", it.toString()) }
        parameters.append("preference", preference.name)
        parameters.append("fareClass", if (firstClass) "1" else "2")
        parameters.append("discount", if (halfFare) "HALF_FARE" else "NONE")
    }.buildString()

internal fun redirectUrl(base: String, origin: String, destination: String, departure: Instant?, preference: Preference,
                         firstClass: Boolean = false, halfFare: Boolean = false, deadline: Instant? = null) =
    URLBuilder(base.trimEnd('/') + "/api/redirect").apply {
        parameters.append("origin", origin)
        parameters.append("destination", destination)
        departure?.let { parameters.append("departure", it.toString()) }
        deadline?.let { parameters.append("arrivalDeadline", it.toString()) }
        parameters.append("preference", preference.name)
        parameters.append("fareClass", if (firstClass) "1" else "2")
        parameters.append("discount", if (halfFare) "HALF_FARE" else "NONE")
    }.buildString()

internal fun chf(value: Double): String {
    val cents = (value * 100).roundToLong()
    return "CHF ${cents / 100}.${two((cents % 100).toInt())}"
}

@Serializable
private data class HealthResponse(val status: String)

@Serializable
private data class ErrorResponse(val error: ErrorDetail)

@Serializable
private data class ErrorDetail(val code: String)

internal data class JourneyOptions(val directOnly: Boolean = false, val arriveBy: Instant? = null) {
    fun applyTo(journeys: List<Journey>): List<Journey> = journeys.filter { journey ->
        (!directOnly || journey.transfers == 0) &&
            (arriveBy == null || Instant.parse(journey.arrival) <= arriveBy)
    }
}

internal val Journey.provenance: Copy
    get() = when (dataStatus) {
        "MOCK" -> Copy.Demo
        "LIVE_SCHEDULE" -> Copy.ProviderSchedule
        else -> Copy.UnknownSource
    }

internal data class Service(
    val destination: String, val offset: Int, val platform: String,
    val duration: Int, val label: String, val delay: Int = 0
)

internal val services = listOf(
    Service("Genève", 8, "3", 39, "IR 90"),
    Service("Bern", 17, "7", 68, "IC 1", 5),
    Service("Zürich HB", 31, "1", 132, "IC 5"),
    Service("Fribourg", 12, "5", 25, "IR 15"),
    Service("Biel/Bienne", 20, "4", 75, "IR 15"),
    Service("Neuchâtel", 14, "4", 65, "IR 15"),
    Service("Lugano", 6, "2", 160, "IC 5"),
    Service("Basel SBB", 25, "6", 100, "IC 1"),
    Service("Luzern", 9, "4", 95, "IR 15"),
    Service("Sion", 5, "2", 55, "IR 90"),
    Service("Montreux", 3, "1", 20, "IR 90"),
    Service("Yverdon-les-Bains", 10, "3", 25, "IR 15"),
    Service("Nyon", 4, "2", 15, "IR 90")
)

internal class JourneySource(private val base: String) {
    private val client = if (base.isBlank()) null else HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    }
    val demo get() = client == null
    fun close() { client?.close() }

    suspend fun health(): Boolean =
        client?.get(base.trimEnd('/') + "/health")?.let { response ->
            response.status.value == 200 && json.decodeFromString<HealthResponse>(response.bodyAsText()).status == "ok"
        } ?: false

    suspend fun locations(query: String): List<Location> =
        json.decodeFromString<LocationsResponse>(request("/api/locations", "query" to query, retry = false)).locations

    suspend fun model(): ModelInfo = json.decodeFromString(request("/api/model", retry = false))

    suspend fun alternatives(from: String, to: String, notBefore: Instant, deadline: Instant?): JourneyResponse {
        val response = json.decodeFromString<AlternativesResponse>(request(
            "/api/alternatives", "from" to from, "to" to to,
            "notBefore" to notBefore.toString(), "arrivalDeadline" to deadline?.toString()
        ))
        return validateJourneys(JourneyResponse(response.alternatives, latestFeasibleDeparture = response.latestFeasibleDeparture,
            noFeasibleRoute = response.noFeasibleRoute, advice = response.advice))
    }

    suspend fun search(
        origin: String, destination: String, departure: Instant?, preference: Preference = Preference.FASTEST,
        firstClass: Boolean = false, halfFare: Boolean = false, deadline: Instant? = null
    ): RedirectResponse {
        if (demo) return demoRedirect(origin, destination, departure ?: Clock.System.now(), preference)
        val redirectUri = redirectUrl(base, origin, destination, departure, preference, firstClass, halfFare, deadline)
        val body = fetch(redirectUri, retry = true, allow404 = true)
        if (body != null) {
            return parseRedirect(body)
        }
        val legacyUrl = journeysUrl(base, origin, destination, departure, preference, firstClass, halfFare, deadline)
        val legacyBody = fetch(legacyUrl, retry = true) ?: throw SearchInputException(Copy.ServiceUnavailable)
        val legacyResponse = parseJourneys(legacyBody)
        val all = legacyResponse.journeys
        return RedirectResponse(
            situation = "UNKNOWN",
            recommendation = all.firstOrNull(),
            alternatives = all.drop(1).take(3),
            journeys = all,
            latestFeasibleDeparture = legacyResponse.latestFeasibleDeparture,
            noFeasibleRoute = legacyResponse.noFeasibleRoute,
            advice = legacyResponse.advice
        )
    }

    private suspend fun request(path: String, vararg params: Pair<String, String?>, retry: Boolean = true): String =
        fetch(URLBuilder(base.trimEnd('/') + path).apply {
            for ((key, value) in params) if (value != null) parameters.append(key, value)
        }.buildString(), retry) ?: throw SearchInputException(Copy.ServiceUnavailable)

    private suspend fun fetch(url: String, retry: Boolean, allow404: Boolean = false): String? {
        val http = checkNotNull(client)
        var attempt = 0
        while (true) {
            val response = http.get(url)
            val status = response.status.value
            val body = response.bodyAsText()
            if (status in 200..299) return body
            if (allow404 && status == 404) return null
            val code = runCatching { json.decodeFromString<ErrorResponse>(body).error.code }.getOrNull()
            val rateLimited = code == "UPSTREAM_RATE_LIMITED"
            val timeout = status == 504 || code == "UPSTREAM_TIMEOUT"
            if (retry && attempt < (if (rateLimited) 2 else 1) && (rateLimited || timeout || status == 502)) {
                attempt++
                delay(if (rateLimited) 2_000 else 250)
                continue
            }
            throw SearchInputException(when {
                status == 400 -> Copy.BadRequest
                rateLimited -> Copy.ProviderBusy
                timeout -> Copy.ProviderTimeout
                else -> Copy.ServiceUnavailable
            })
        }
    }
}

private suspend fun demoRedirect(
    origin: String, destination: String, start: Instant, preference: Preference = Preference.FASTEST
): RedirectResponse {
    val demoJourneys = demoSearch(origin, destination, start)
    if (demoJourneys.isEmpty()) {
        return RedirectResponse(
            situation = "NO_DISRUPTION",
            recommendation = null,
            alternatives = emptyList(),
            journeys = emptyList()
        )
    }
    fun key(value: String) = value.lowercase().replace("è", "e").replace("ü", "u")
    val fromLausanne = key(origin) == "lausanne"
    val other = if (fromLausanne) destination else origin
    val service = services.firstOrNull { key(it.destination) == key(other) }
    val situation = "DISRUPTION_DETECTED"
    val disruption = Disruption(
        serviceLabel = service?.label ?: "RE 33",
        from = origin,
        to = destination,
        scheduledDeparture = start.toString(),
        delayMinutes = if ((service?.delay ?: 0) > 0) service?.delay else 22,
        cancelled = false,
        confidence = "high",
        cause = "Panne de signalisation sur le tronçon Renens - Morges"
    )
    val why = when (preference) {
        Preference.SIMPLEST -> "Itinéraire le plus simple sans correspondance"
        else -> "Itinéraire le plus rapide malgré la perturbation (+22 min)"
    }
    val rec = demoJourneys.first().copy(why = why)
    val alts = demoJourneys.drop(1).take(3)
    val msg = "Attention : perturbation détectée. Vous trouverez ci-dessous les meilleures alternatives pour votre trajet."
    return RedirectResponse(
        situation = situation,
        disruption = disruption,
        recommendation = rec,
        alternatives = alts,
        journeys = demoJourneys,
        message = msg
    )
}

private suspend fun demoSearch(origin: String, destination: String, start: Instant): List<Journey> {
    delay(320)
    fun key(value: String) = value.lowercase().replace("è", "e").replace("ü", "u")
    val fromLausanne = key(origin) == "lausanne"
    if (!fromLausanne && key(destination) != "lausanne") return emptyList()
    val other = if (fromLausanne) destination else origin
    val service = services.firstOrNull { key(it.destination) == key(other) } ?: return emptyList()
    return List(2) { index ->
        val dep = start + (service.offset + index * 28).minutes
        val duration = service.duration + index * 8
        val arr = dep + duration.minutes
        Journey(
            "demo-$index", dep.toString(), arr.toString(), duration, 0, "MOCK",
            listOf(Leg(origin, destination, dep.toString(), arr.toString(), service.label,
                service.platform.takeIf { fromLausanne }, mode = "TRANSIT"))
        )
    }
}
