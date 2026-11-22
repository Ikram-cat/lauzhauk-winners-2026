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
import kotlinx.serialization.json.JsonElement
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

internal enum class Preference { FASTEST, CHEAPEST, SIMPLEST }

// --- Live contract (Teddy MSG-0027/0028/0032/0033, api-consolidation @ cee0657) ---
// Only 4 endpoints exist: /health, /api/locations, /api/journeys, /api/model.
// /api/redirect and /api/alternatives are gone (404); the rescue/"Plan B" flow is just another
// /api/journeys call with origin = the stuck station and departure = now.

@Serializable
internal data class Occupancy(val firstClass: String? = null, val secondClass: String? = null)

@Serializable
internal data class Leg(
    val mode: String, val origin: String, val destination: String,
    val departure: String, val arrival: String,
    val expectedDeparture: String? = null, val departureDelayMinutes: Int? = null,
    val expectedArrival: String? = null, val arrivalDelayMinutes: Int? = null,
    val serviceLabel: String? = null, val departurePlatform: String? = null,
    val arrivalPlatform: String? = null, val occupancy: Occupancy? = null
)

@Serializable
internal data class Schedule(val departure: String, val arrival: String, val durationMinutes: Int, val transfers: Int)

@Serializable
internal data class Realtime(
    val expectedDeparture: String? = null, val departureDelayMinutes: Int? = null,
    val expectedArrival: String? = null, val arrivalDelayMinutes: Int? = null,
    val source: String? = null, val sampleCount: Int? = null
)

@Serializable
internal data class Risk(
    val corridorIncidentRate: Double? = null, val typicalIncidentDurationMinutes: Int? = null,
    val source: String? = null, val basis: String? = null, val confidence: String? = null, val level: String? = null
)

@Serializable
internal data class CatalogChf(
    val secondClassFull: Double? = null, val secondClassHalfFare: Double? = null,
    val firstClassFull: Double? = null, val firstClassHalfFare: Double? = null
)

@Serializable
internal data class Fares(
    val status: String? = null, val selectedChf: Double? = null,
    val product: String? = null, val source: String? = null, val catalogChf: CatalogChf? = null
)

@Serializable
internal data class Journey(
    val id: String, val dataStatus: String, val verified: Boolean = false,
    val feasible: Boolean? = null, val maxOccupancy: String? = null,
    val schedule: Schedule, val realtime: Realtime? = null, val risk: Risk? = null,
    val fares: Fares? = null, val legs: List<Leg> = emptyList()
) {
    val scheduleDeparture get() = Instant.parse(schedule.departure)
    val scheduleArrival get() = Instant.parse(schedule.arrival)
    /** Prefer the live/expected time whenever the detector reported one; else the plain timetable time. */
    val effectiveDeparture get() = realtime?.expectedDeparture?.let(Instant::parse) ?: scheduleDeparture
    val effectiveArrival get() = realtime?.expectedArrival?.let(Instant::parse) ?: scheduleArrival
    val durationMinutes get() = schedule.durationMinutes
    val transfers get() = schedule.transfers
    val isLive get() = realtime?.expectedDeparture != null || realtime?.expectedArrival != null

    private val known get() = fares?.status == "PRICED"
    /** Catalog (undiscounted/discounted) fare for a class; null means unknown and must not be rendered. */
    fun catalogFare(firstClass: Boolean, halfFare: Boolean) = (when {
        firstClass && halfFare -> fares?.catalogChf?.firstClassHalfFare
        firstClass -> fares?.catalogChf?.firstClassFull
        halfFare -> fares?.catalogChf?.secondClassHalfFare
        else -> fares?.catalogChf?.secondClassFull
    })?.takeIf { known }
}

@Serializable
internal data class PlaceRef(val input: String? = null, val resolvedName: String? = null, val id: String? = null)

@Serializable
internal data class RequestEcho(
    val origin: PlaceRef? = null, val destination: PlaceRef? = null, val departure: String? = null,
    val arrivalDeadline: String? = null, val preference: String = "FASTEST",
    val fareClass: Int = 2, val discount: String = "NONE"
)

@Serializable
internal data class Reason(val code: String, val savedMinutes: Int? = null)

/** reasons[].code is a stable backend enum; all wording is composed client-side (MSG-0027). */
internal fun Reason.text(language: Language): String {
    val base = when (code) {
        "PRIMARY_LEG_DELAYED" -> Copy.ReasonPrimaryLegDelayed
        "PRIMARY_LEG_CANCELLED" -> Copy.ReasonPrimaryLegCancelled
        "SUPPRESSED_STOP" -> Copy.ReasonSuppressedStop
        "EARLIER_ARRIVAL" -> Copy.ReasonEarlierArrival
        "FEWER_TRANSFERS" -> Copy.ReasonFewerTransfers
        "CHEAPER" -> Copy.ReasonCheaper
        "DEADLINE_MET" -> Copy.ReasonDeadlineMet
        "DEADLINE_MISSED" -> Copy.ReasonDeadlineMissed
        "REALTIME_UNAVAILABLE" -> Copy.ReasonRealtimeUnavailable
        "STAY_FASTEST" -> Copy.ReasonStayFastest
        "EXPECTED_FASTER" -> return "${language.text(Copy.ReasonExpectedFasterBy)}${savedMinutes ?: 0} min"
        else -> return code
    }
    return language.text(base)
}

internal fun occupancyLabel(level: String?, language: Language): String? = when (level) {
    "LOW" -> language.text(Copy.OccupancyLow)
    "MEDIUM" -> language.text(Copy.OccupancyMedium)
    "HIGH" -> language.text(Copy.OccupancyHigh)
    else -> null
}

@Serializable
internal data class Disruption(
    val legIndex: Int? = null, val serviceLabel: String? = null, val from: String? = null, val to: String? = null,
    val scheduledDeparture: String? = null, val estimatedDeparture: String? = null, val delayMinutes: Int? = null,
    val cancelled: Boolean? = null, val basis: String? = null, val confidence: String? = null,
    val severity: String? = null, val cause: String? = null
)

@Serializable
internal data class Highlights(val fastestId: String? = null, val simplestId: String? = null, val cheapestId: String? = null) {
    fun id(preference: Preference) = when (preference) {
        Preference.FASTEST -> fastestId
        Preference.SIMPLEST -> simplestId
        Preference.CHEAPEST -> cheapestId
    }
}

@Serializable
internal data class ResponseMeta(val computedAt: String? = null, val modelVersion: String? = null)

@Serializable
internal data class JourneyResponse(
    val request: RequestEcho? = null,
    val status: String = "NO_DISRUPTION",
    val action: String = "NONE",
    val reasons: List<Reason> = emptyList(),
    val disruption: Disruption? = null,
    val primaryJourney: Journey? = null,
    val recommendedJourney: Journey? = null,
    val alternatives: List<Journey> = emptyList(),
    val highlights: Highlights? = null,
    val deadlineSummary: JsonElement? = null,
    val meta: ResponseMeta? = null
) {
    /** All journeys worth showing the user, recommendation first, never duplicated. */
    val displayJourneys: List<Journey> get() = listOfNotNull(recommendedJourney ?: primaryJourney) + alternatives
}

@Serializable
internal data class Coordinates(val lat: Double, val lon: Double)

@Serializable
internal data class Location(val id: String, val name: String, val kind: String, val coordinates: Coordinates? = null)

@Serializable
private data class LocationsResponse(val locations: List<Location>)

@Serializable
internal data class ModelCoverage(val from: String? = null, val to: String? = null, val windowDays: Int? = null, val scope: String? = null)

@Serializable
internal data class ModelCounts(val keys: Int? = null, val corridors: Int? = null, val baselineHours: Int? = null, val byLineHourCells: Int? = null)

@Serializable
internal data class RiskModelInfo(
    val version: String? = null, val generatedAt: String? = null, val source: String? = null, val status: String? = null,
    val coverage: ModelCoverage? = null, val counts: ModelCounts? = null
)

@Serializable
internal data class DelayModelCounts(val portions: Int? = null, val lines: Int? = null)

@Serializable
internal data class DelayModelInfo(val version: String? = null, val status: String? = null, val counts: DelayModelCounts? = null)

@Serializable
internal data class ModelInfo(val riskModel: RiskModelInfo? = null, val delayModel: DelayModelInfo? = null)

private val json = Json { ignoreUnknownKeys = true }

internal fun parseJourneys(body: String): JourneyResponse = validateJourneys(json.decodeFromString<JourneyResponse>(body))

private fun Journey.validate(): Journey = also {
    Instant.parse(schedule.departure)
    Instant.parse(schedule.arrival)
    realtime?.expectedDeparture?.let(Instant::parse)
    realtime?.expectedArrival?.let(Instant::parse)
    legs.forEach {
        Instant.parse(it.departure)
        Instant.parse(it.arrival)
        it.expectedDeparture?.let(Instant::parse)
        it.expectedArrival?.let(Instant::parse)
    }
}

private fun validateJourneys(response: JourneyResponse): JourneyResponse = response.also { res ->
    res.primaryJourney?.validate()
    res.recommendedJourney?.validate()
    res.alternatives.forEach { it.validate() }
}

internal fun journeysUrl(
    base: String, origin: String, destination: String, departure: Instant?, preference: Preference,
    firstClass: Boolean = false, halfFare: Boolean = false, deadline: Instant? = null, limit: Int? = null
) =
    URLBuilder(base.trimEnd('/') + "/api/journeys").apply {
        parameters.append("origin", origin)
        parameters.append("destination", destination)
        departure?.let { parameters.append("departure", it.toString()) }
        deadline?.let { parameters.append("arrivalDeadline", it.toString()) }
        parameters.append("preference", preference.name)
        parameters.append("fareClass", if (firstClass) "1" else "2")
        parameters.append("discount", if (halfFare) "HALF_FARE" else "NONE")
        limit?.let { parameters.append("limit", it.toString()) }
    }.buildString()

internal fun chf(value: Double): String {
    val cents = (value * 100).roundToLong()
    return "CHF ${cents / 100}.${two((cents % 100).toInt())}"
}

@Serializable
private data class HealthFeeds(val ojp: String? = null, val nova: String? = null)

@Serializable
private data class HealthResponse(val status: String, val feeds: HealthFeeds? = null)

@Serializable
private data class ErrorResponse(val error: ErrorDetail)

@Serializable
private data class ErrorDetail(val code: String)

internal data class JourneyOptions(val directOnly: Boolean = false, val arriveBy: Instant? = null) {
    fun applyTo(journeys: List<Journey>): List<Journey> = journeys.filter { journey ->
        (!directOnly || journey.transfers == 0) &&
            (arriveBy == null || journey.scheduleArrival <= arriveBy)
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
            response.status.value == 200 && json.decodeFromString<HealthResponse>(response.bodyAsText()).status.equals("ok", ignoreCase = true)
        } ?: false

    suspend fun locations(query: String? = null, near: Pair<Double, Double>? = null, radiusKm: Double? = null, limit: Int? = null): List<Location> =
        json.decodeFromString<LocationsResponse>(request(
            "/api/locations",
            "query" to query,
            "near" to near?.let { (lat, lon) -> "$lat,$lon" },
            "radiusKm" to radiusKm?.toString(),
            "limit" to limit?.toString(),
            retry = false
        )).locations

    suspend fun model(): ModelInfo = json.decodeFromString(request("/api/model", retry = false))

    /** Planning AND the stranded/"Plan B" rescue case: pass origin = the stuck station, departure = now. */
    suspend fun search(
        origin: String, destination: String, departure: Instant?, preference: Preference = Preference.FASTEST,
        firstClass: Boolean = false, halfFare: Boolean = false, deadline: Instant? = null, limit: Int? = null
    ): JourneyResponse {
        if (demo) return demoSearch(origin, destination, departure ?: Clock.System.now(), preference)
        val url = journeysUrl(base, origin, destination, departure, preference, firstClass, halfFare, deadline, limit)
        return parseJourneys(fetch(url, retry = true))
    }

    private suspend fun request(path: String, vararg params: Pair<String, String?>, retry: Boolean = true): String =
        fetch(URLBuilder(base.trimEnd('/') + path).apply {
            for ((key, value) in params) if (value != null) parameters.append(key, value)
        }.buildString(), retry)

    private suspend fun fetch(url: String, retry: Boolean): String {
        val http = checkNotNull(client)
        var attempt = 0
        while (true) {
            val response = http.get(url)
            val status = response.status.value
            val body = response.bodyAsText()
            if (status in 200..299) return body
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
                status == 404 -> Copy.NoFeasibleRoute
                rateLimited -> Copy.ProviderBusy
                timeout -> Copy.ProviderTimeout
                else -> Copy.ServiceUnavailable
            })
        }
    }
}

private suspend fun demoSearch(
    origin: String, destination: String, start: Instant, preference: Preference = Preference.FASTEST
): JourneyResponse {
    val demoJourneys = demoJourneys(origin, destination, start)
    if (demoJourneys.isEmpty()) {
        return JourneyResponse(status = "NO_DISRUPTION", action = "NONE")
    }
    fun key(value: String) = value.lowercase().replace("è", "e").replace("ü", "u")
    val fromLausanne = key(origin) == "lausanne"
    val other = if (fromLausanne) destination else origin
    val service = services.firstOrNull { key(it.destination) == key(other) }
    val disrupted = (service?.delay ?: 0) > 0
    val primary = demoJourneys.first()
    return if (disrupted) {
        val disruption = Disruption(
            legIndex = 0,
            serviceLabel = service?.label ?: "RE 33",
            from = origin, to = destination,
            scheduledDeparture = start.toString(),
            delayMinutes = service?.delay ?: 22,
            cancelled = false,
            basis = "REALTIME_ESTIMATE",
            confidence = "HIGH",
            cause = "Panne de signalisation sur le tronçon Renens - Morges"
        )
        val recommended = demoJourneys.getOrElse(1) { primary }
        JourneyResponse(
            status = "DISRUPTION_DETECTED",
            action = if (recommended.id == primary.id) "STAY" else "REROUTE",
            reasons = listOf(
                Reason(if (preference == Preference.SIMPLEST) "FEWER_TRANSFERS" else "EARLIER_ARRIVAL")
            ),
            disruption = disruption,
            primaryJourney = primary,
            recommendedJourney = recommended,
            alternatives = demoJourneys.filterNot { it.id == recommended.id }.take(3),
            highlights = Highlights(primary.id, demoJourneys.minByOrNull { it.transfers }?.id, primary.id)
        )
    } else {
        JourneyResponse(
            status = "NO_DISRUPTION",
            action = "STAY",
            reasons = listOf(Reason("STAY_FASTEST")),
            primaryJourney = primary,
            recommendedJourney = primary,
            alternatives = demoJourneys.drop(1).take(3),
            highlights = Highlights(primary.id, demoJourneys.minByOrNull { it.transfers }?.id, primary.id)
        )
    }
}

private fun demoJourneys(origin: String, destination: String, start: Instant): List<Journey> {
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
            id = "demo-$index",
            dataStatus = "MOCK",
            verified = false,
            schedule = Schedule(dep.toString(), arr.toString(), duration, 0),
            fares = Fares(
                status = "DISABLED"
            ),
            legs = listOf(Leg("TRANSIT", origin, destination, dep.toString(), arr.toString(), serviceLabel = service.label,
                departurePlatform = service.platform.takeIf { fromLausanne }))
        )
    }
}
