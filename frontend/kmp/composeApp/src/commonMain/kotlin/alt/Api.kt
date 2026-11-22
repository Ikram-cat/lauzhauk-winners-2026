package alt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun defaultApiBase(): String

const val PROD_API = "https://lauzhauk-winners-2026-production.up.railway.app"

val appJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

class ApiException(val code: String, val userMessage: String) : Exception("$code: $userMessage")

class AltApi(val base: String) {
    private val http = HttpClient {
        install(ContentNegotiation) { json(appJson) }
        expectSuccess = false
    }

    private fun url(path: String): String = base.trimEnd('/') + path

    suspend fun locations(query: String, limit: Int = 8): List<Location> {
        val text = getText("/api/locations") {
            parameter("query", query)
            parameter("limit", limit)
        }
        return appJson.decodeFromString<LocationsResponse>(text).locations
    }

    suspend fun search(q: SearchQuery): JourneyResponse {
        val text = getText("/api/journeys") {
            parameter("origin", q.origin)
            parameter("destination", q.destination)
            q.departure?.let { parameter("departure", it) }
            q.arrivalDeadline?.let { parameter("arrivalDeadline", it) }
            parameter("preference", q.preference)
            parameter("fareClass", if (q.firstClass) 1 else 2)
            parameter("discount", if (q.halfFare) "HALF_FARE" else "NONE")
            parameter("limit", 7)
            q.demoPerturbation?.let { parameter("demoPerturbation", it) }
        }
        return appJson.decodeFromString<JourneyResponse>(text)
    }

    private suspend fun getText(path: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): String {
        val resp = http.get(url(path)) { block() }
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            val parsed = runCatching { appJson.decodeFromString<ErrorEnvelope>(body).error }.getOrNull()
            throw ApiException(
                parsed?.code ?: "HTTP_${resp.status.value}",
                parsed?.message ?: "Request failed (${resp.status.value})",
            )
        }
        return body
    }
}

data class SearchQuery(
    val origin: String,
    val destination: String,
    val departure: String?,
    val arrivalDeadline: String?,
    val preference: String,
    val firstClass: Boolean,
    val halfFare: Boolean,
    val demoPerturbation: Long? = null,
)
