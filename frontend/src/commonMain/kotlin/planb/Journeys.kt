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

// ---- Internal app models (the UI contract; mapped from the wire in parseEnvelope) ----

@Serializable
internal data class Leg(
    val origin: String, val destination: String, val departure: String, val arrival: String,
    val serviceLabel: String? = null, val departurePlatform: String? = null,
    val arrivalPlatform: String? = null, val mode: String? = null,
    val departureDelayMinutes: Int? = null, val arrivalDelayMinutes: Int? = null
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
    val why: String? = null,
    val maxOccupancy: String? = null, val arrivalDelayMinutes: Int? = null, val realtimeSource: String? = null,
    val verified: Boolean? = null
) {
    private val known get() = fareStatus != "UNAVAILABLE"
    /** Undiscounted fare for the class; null means unknown and must not be rendered. */
    fun fullFare(firstClass: Boolean) = (if (firstClass) firstClassFareChf else estimatedFareChf)?.takeIf { known }

    /**
     * Price the user pays for this class and discount. The catalog carries all four combinations, so it decides;
     * the backend's selectedChf is only a fallback for the default 2nd/none selection it was computed for.
     */
    fun fare(firstClass: Boolean, halfFare: Boolean): Double? {
        if (!known) return null
        val fromCatalog = if (firstClass) {
            if (halfFare) firstClassHalfFareChf else firstClassFareChf
        } else {
            if (halfFare) halfFareChf else estimatedFareChf
        }
        return fromCatalog ?: fareChf?.takeIf { !firstClass && !halfFare }
    }
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
    val cause: String? = null,
    val severity: String? = null
)

/** Structured reason from the backend; the client owns the wording. */
@Serializable
internal data class ReasonWire(
    val code: String? = null, val serviceLabel: String? = null, val delayMinutes: Int? = null,
    val savedMinutes: Int? = null, val savedTransfers: Int? = null, val savedChf: Double? = null,
    val marginMinutes: Int? = null
)

@Serializable
internal data class RedirectResponse(
    val situation: String = "NO_DISRUPTION",
    val disruption: Disruption? = null,
    val recommendation: Journey? = null,
    val alternatives: List<Journey> = emptyList(),
    val journeys: List<Journey> = emptyList(),
    val reasonCodes: List<ReasonWire> = emptyList(),
    val message: String? = null,
    val computedAt: String? = null,
    val modelVersion: String? = null,
    val latestFeasibleDeparture: String? = null,
    val noFeasibleRoute: Boolean? = null
) {
    val displayJourneys: List<Journey>
        get() = if (journeys.isNotEmpty()) journeys else listOfNotNull(recommendation) + alternatives
}

internal enum class Preference { FASTEST, CHEAPEST, SIMPLEST }

@Serializable
internal data class Location(val id: String, val name: String, val kind: String)

@Serializable
private data class LocationsResponse(val locations: List<Location> = emptyList())

/** Metadata of the loaded models, for the data-provenance line on the cards. */
internal data class ModelInfo(val modelVersion: String? = null, val status: String = "OK")

// ---- Wire DTOs: the four-endpoint contract (status/action/reasons/journeys/highlights) ----

@Serializable
private data class WireSchedule(
    val departure: String, val arrival: String,
    val durationMinutes: Int = 0, val transfers: Int = 0
)

@Serializable
private data class WireRealtime(
    val expectedDeparture: String? = null, val departureDelayMinutes: Int? = null,
    val expectedArrival: String? = null, val arrivalDelayMinutes: Int? = null,
    val source: String? = null, val sampleCount: Int? = null
)

@Serializable
private data class WireRisk(
    val corridorIncidentRate: Double? = null, val typicalIncidentDurationMinutes: Int? = null,
    val source: String? = null, val basis: String? = null, val confidence: String? = null, val level: String? = null
)

@Serializable
private data class WireCatalogFares(
    val secondClassFull: Double? = null, val secondClassHalfFare: Double? = null,
    val firstClassFull: Double? = null, val firstClassHalfFare: Double? = null
)

@Serializable
private data class WireFares(
    val status: String? = null, val selectedChf: Double? = null, val product: String? = null,
    val source: String? = null, val catalogChf: WireCatalogFares? = null
)

@Serializable
private data class WireLeg(
    val mode: String? = null, val origin: String = "", val destination: String = "",
    val departure: String = "", val arrival: String = "",
    val expectedDeparture: String? = null, val departureDelayMinutes: Int? = null,
    val expectedArrival: String? = null, val arrivalDelayMinutes: Int? = null,
    val serviceLabel: String? = null, val departurePlatform: String? = null,
    val arrivalPlatform: String? = null
)

@Serializable
private data class WireJourney(
    val id: String, val dataStatus: String? = null, val verified: Boolean? = null,
    val feasible: Boolean? = null, val maxOccupancy: String? = null,
    val schedule: WireSchedule? = null, val realtime: WireRealtime? = null, val risk: WireRisk? = null,
    val fares: WireFares? = null, val legs: List<WireLeg> = emptyList()
)

@Serializable
private data class WireDisruption(
    val legIndex: Int? = null, val serviceLabel: String? = null, val from: String? = null, val to: String? = null,
    val scheduledDeparture: String? = null, val estimatedDeparture: String? = null,
    val delayMinutes: Int? = null, val cancelled: Boolean? = null, val basis: String? = null,
    val confidence: String? = null, val cause: String? = null, val severity: String? = null
)

@Serializable
private data class WireEnvelope(
    val status: String? = null, val action: String? = null,
    val reasons: List<ReasonWire> = emptyList(),
    val disruption: WireDisruption? = null,
    val primaryJourney: WireJourney? = null, val recommendedJourney: WireJourney? = null,
    val alternatives: List<WireJourney> = emptyList(),
    val highlights: WireHighlights? = null,
    val deadlineSummary: WireDeadline? = null,
    val meta: WireMeta? = null
)

@Serializable
private data class WireHighlights(
    val fastestId: String? = null, val simplestId: String? = null, val cheapestId: String? = null
)

@Serializable
private data class WireDeadline(val feasible: Boolean? = null, val latestFeasibleDeparture: String? = null)

@Serializable
private data class WireMeta(val computedAt: String? = null, val modelVersion: String? = null)

@Serializable
private data class WireModelResponse(
    val riskModel: WireModelPart? = null, val delayModel: WireModelPart? = null
)

@Serializable
private data class WireModelPart(
    val version: String? = null, val status: String? = null,
    val counts: Map<String, Int> = emptyMap(), val coverage: Map<String, String> = emptyMap()
)

private val json = Json { ignoreUnknownKeys = true }

/** Decodes and validates one /api/journeys response and maps it to the internal model the UI renders. */
internal fun parseEnvelope(body: String): RedirectResponse =
    mapEnvelope(json.decodeFromString<WireEnvelope>(body))

private fun mapEnvelope(env: WireEnvelope): RedirectResponse {
    val primary = env.primaryJourney?.toJourney()
    val recommended = env.recommendedJourney?.toJourney()
    val alternatives = env.alternatives.map { it.toJourney() }
    val journeys = (listOfNotNull(primary, recommended) + alternatives).distinctBy { it.id }
    return RedirectResponse(
        situation = env.status ?: "UNKNOWN",
        disruption = env.disruption?.let { d ->
            Disruption(
                legIndex = d.legIndex, serviceLabel = d.serviceLabel, from = d.from, to = d.to,
                scheduledDeparture = d.scheduledDeparture, estimatedDeparture = d.estimatedDeparture,
                delayMinutes = d.delayMinutes, cancelled = d.cancelled, basis = d.basis,
                confidence = d.confidence?.lowercase(), cause = d.cause, severity = d.severity
            )
        },
        recommendation = recommended,
        alternatives = alternatives,
        journeys = journeys,
        reasonCodes = env.reasons.filter { !it.code.isNullOrBlank() },
        computedAt = env.meta?.computedAt,
        modelVersion = env.meta?.modelVersion,
        latestFeasibleDeparture = env.deadlineSummary?.latestFeasibleDeparture,
        noFeasibleRoute = env.deadlineSummary?.feasible == false
    ).also(::validateRedirect)
}

private fun validateRedirect(response: RedirectResponse): RedirectResponse = response.also { res ->
    res.recommendation?.let(::validateJourney)
    res.alternatives.forEach(::validateJourney)
    res.journeys.forEach(::validateJourney)
    res.latestFeasibleDeparture?.let(Instant::parse)
    res.disruption?.let { d ->
        d.scheduledDeparture?.let(Instant::parse)
        d.estimatedDeparture?.let(Instant::parse)
    }
}

private fun validateJourney(journey: Journey) {
    Instant.parse(journey.departure)
    Instant.parse(journey.arrival)
    journey.legs.forEach {
        Instant.parse(it.departure)
        Instant.parse(it.arrival)
    }
}

/** Wire journey -> internal model. Fares and risk keep their own display semantics; corridor label comes from the legs. */
private fun WireJourney.toJourney(): Journey {
    val schedule = schedule ?: throw SearchInputException(Copy.ServiceUnavailable)
    val legs = legs.map { l ->
        Leg(
            origin = l.origin, destination = l.destination, departure = l.departure, arrival = l.arrival,
            serviceLabel = l.serviceLabel, departurePlatform = l.departurePlatform,
            arrivalPlatform = l.arrivalPlatform, mode = l.mode,
            departureDelayMinutes = l.departureDelayMinutes, arrivalDelayMinutes = l.arrivalDelayMinutes
        )
    }
    val corridorLabel = legs.firstOrNull { it.serviceLabel != null && it.mode == "TRANSIT" }
        ?.let { "${it.origin} - ${it.destination}" }
    return Journey(
        id = id,
        departure = schedule.departure, arrival = schedule.arrival,
        durationMinutes = schedule.durationMinutes, transfers = schedule.transfers,
        dataStatus = dataStatus ?: "MOCK",
        legs = legs,
        estimatedFareChf = fares?.catalogChf?.secondClassFull,
        halfFareChf = fares?.catalogChf?.secondClassHalfFare,
        firstClassFareChf = fares?.catalogChf?.firstClassFull,
        firstClassHalfFareChf = fares?.catalogChf?.firstClassHalfFare,
        fareProduct = fares?.product, fareSource = fares?.source, fareStatus = fares?.status,
        fareChf = fares?.selectedChf,
        feasible = feasible,
        lateArrivalProbability = risk?.corridorIncidentRate,
        riskSource = risk?.source?.lowercase(),
        riskConfidence = risk?.confidence?.lowercase(),
        riskLabel = risk?.source?.lowercase()?.let { if (it == "corridor") corridorLabel else null },
        riskBasis = risk?.basis,
        disruptionDurationMin = risk?.typicalIncidentDurationMinutes,
        maxOccupancy = maxOccupancy,
        arrivalDelayMinutes = realtime?.arrivalDelayMinutes,
        realtimeSource = realtime?.source?.uppercase(),
        verified = verified
    )
}

/** Client-owned wording for the recommendation, from the structured reasons (no server prose exists). */
internal fun RedirectResponse.withWhy(language: Language): RedirectResponse {
    val rec = recommendation ?: return this
    val text = language::text
    val why = reasonCodes.firstNotNullOfOrNull { reason ->
        when (reason.code) {
            "EARLIER_ARRIVAL" -> reason.savedMinutes?.let { text(Copy.AvoidsDisruption) + "$it min" }
            "EXPECTED_FASTER" -> reason.savedMinutes?.let { text(Copy.ExpectedFaster) + "$it min" }
            else -> null
        }
    } ?: if (reasonCodes.any { it.code == "FEWER_TRANSFERS" }) text(Copy.FewerTransfersWhy) else null
    if (why == rec.why || why == null) return this
    return copy(recommendation = rec.copy(why = why))
}

internal fun journeysUrl(
    base: String, origin: String, destination: String, departure: Instant?, preference: Preference,
    firstClass: Boolean = false, halfFare: Boolean = false, deadline: Instant? = null, limit: Int = 5
) = URLBuilder(base.trimEnd('/') + "/api/journeys").apply {
    parameters.append("origin", origin)
    parameters.append("destination", destination)
    departure?.let { parameters.append("departure", it.toString()) }
    deadline?.let { parameters.append("arrivalDeadline", it.toString()) }
    parameters.append("preference", preference.name)
    parameters.append("fareClass", if (firstClass) "1" else "2")
    parameters.append("discount", if (halfFare) "HALF_FARE" else "NONE")
    parameters.append("limit", limit.toString())
}.buildString()

internal fun nearUrl(base: String, latitude: Double, longitude: Double, limit: Int = 3) =
    URLBuilder(base.trimEnd('/') + "/api/locations").apply {
        parameters.append("near", "$latitude,$longitude")
        parameters.append("limit", limit.toString())
    }.buildString()

internal fun chf(value: Double): String {
    val cents = (value * 100).roundToLong()
    return "CHF ${cents / 100}.${two((cents % 100).toInt())}"
}

@Serializable
private data class HealthResponse(val status: String? = null)

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

internal class JourneySource(private val base: String) {
    private val client = if (base.isBlank()) null else HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    }
    val demo get() = client == null
    fun close() { client?.close() }

    /** The backend reports OK or DEGRADED while it is serving; both mean reachable. */
    suspend fun health(): Boolean =
        client?.get(base.trimEnd('/') + "/health")?.let { response ->
            response.status.value == 200 &&
                json.decodeFromString<HealthResponse>(response.bodyAsText()).status?.lowercase() in setOf("ok", "degraded")
        } ?: false

    suspend fun locations(query: String): List<Location> =
        json.decodeFromString<LocationsResponse>(request("/api/locations", "query" to query, retry = false)).locations

    /** Nearest stops to the device position, for the "use my location" button. */
    suspend fun locationsNear(latitude: Double, longitude: Double, limit: Int = 3): List<Location> =
        json.decodeFromString<LocationsResponse>(request("/api/locations", "near" to "$latitude,$longitude", "limit" to limit.toString(), retry = false)).locations

    suspend fun model(): ModelInfo {
        val response = json.decodeFromString<WireModelResponse>(request("/api/model", retry = false))
        return ModelInfo(
            modelVersion = response.riskModel?.version ?: response.delayModel?.version,
            status = (response.riskModel?.status ?: response.delayModel?.status ?: "OK").uppercase()
        )
    }

    suspend fun search(
        origin: String, destination: String, departure: Instant?, preference: Preference = Preference.FASTEST,
        firstClass: Boolean = false, halfFare: Boolean = false, deadline: Instant? = null
    ): RedirectResponse {
        if (demo) return demoRedirect(origin, destination, departure ?: Clock.System.now(), preference)
        val body = request(
            "/api/journeys",
            "origin" to origin, "destination" to destination,
            "departure" to departure?.toString(), "arrivalDeadline" to deadline?.toString(),
            "preference" to preference.name,
            "fareClass" to if (firstClass) "1" else "2",
            "discount" to if (halfFare) "HALF_FARE" else "NONE",
            "limit" to "5",
            retry = true
        )
        return parseEnvelope(body)
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

// ---- Demo mode (no base URL): local scripted journeys, unaffected by the wire contract ----

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
