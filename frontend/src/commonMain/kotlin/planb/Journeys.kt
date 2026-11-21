@file:OptIn(kotlin.time.ExperimentalTime::class)

package planb

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
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
    val transfers: Int, val dataStatus: String, val legs: List<Leg>
)

@Serializable
private data class JourneyResponse(val journeys: List<Journey>)

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
    Service("Zürich HB", 31, "1", 132, "IC 5")
)

internal class JourneySource(private val base: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = if (base.isBlank()) null else HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    }
    val demo get() = client == null
    fun close() { client?.close() }

    suspend fun health(): Boolean {
        val http = client ?: return false
        val response = http.get(base.trimEnd('/') + "/health")
        return response.status.value == 200 && json.decodeFromString<HealthResponse>(response.bodyAsText()).status == "ok"
    }

    suspend fun search(origin: String, destination: String, departure: Instant?): List<Journey> {
        val http = client ?: return demoSearch(origin, destination, departure ?: Clock.System.now())
        val response = http.get(base.trimEnd('/') + "/api/journeys") {
            parameter("origin", origin)
            parameter("destination", destination)
            departure?.let { parameter("departure", it.toString()) }
        }
        if (response.status.value !in 200..299) {
            val body = response.bodyAsText()
            val code = runCatching { json.decodeFromString<ErrorResponse>(body).error.code }.getOrNull()
            throw SearchInputException(when {
                response.status.value == 400 || code == "INVALID_REQUEST" -> Copy.BadRequest
                response.status.value == 504 || code == "UPSTREAM_TIMEOUT" -> Copy.ProviderTimeout
                code == "UPSTREAM_RATE_LIMITED" -> Copy.ProviderBusy
                else -> Copy.ServiceUnavailable
            })
        }
        return json.decodeFromString<JourneyResponse>(response.bodyAsText()).journeys.also { journeys ->
            journeys.forEach { journey ->
                Instant.parse(journey.departure)
                Instant.parse(journey.arrival)
                journey.legs.forEach { Instant.parse(it.departure); Instant.parse(it.arrival) }
            }
        }
    }
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
