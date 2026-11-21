package com.cffplanb.backend.provider

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable private data class NovaFareClass(val full: Double? = null, val halfFare: Double? = null)
@Serializable private data class NovaFareResponse(val currency: String? = null, val product: String? = null, val fares: Map<String, NovaFareClass> = emptyMap())

/** SBB NOVA prices via transport.opendata.ch: resolve one connection, then price it. Keyless. */
class NovaFareProvider(
    private val baseUrl: String = "https://transport.opendata.ch",
    private val client: HttpClient = HttpClient(CIO) { engine { requestTimeout = TIMEOUT_MS } },
    private val timeoutMs: Long = TIMEOUT_MS
) : FareProvider {
    private val log = LoggerFactory.getLogger(NovaFareProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun quote(origin: String, destination: String, departure: OffsetDateTime): FareQuote? = try {
        val local = departure.atZoneSameInstant(ZURICH)
        val conn = call("connections") {
            client.get("$baseUrl/v1/connections") {
                parameter("from", origin); parameter("to", destination); parameter("limit", 1)
                parameter("date", local.format(DateTimeFormatter.ISO_LOCAL_DATE))
                parameter("time", local.format(DateTimeFormatter.ofPattern("HH:mm")))
            }
        }?.let { json.parseToJsonElement(it).jsonObject["connections"]?.jsonArray?.firstOrNull() }
        if (conn == null) null else call("fare") {
            client.post("$baseUrl/v1/fare") { contentType(ContentType.Application.Json); setBody(conn.toString()) }
        }?.let { parse(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Fare lookup failed: {}", e.javaClass.simpleName); null
    }

    /** Body on 2xx, else null with one warning line (status only, never the body). */
    private suspend fun call(step: String, block: suspend () -> HttpResponse): String? {
        val r = withTimeoutOrNull(timeoutMs) { block().let { it.status to it.bodyAsText() } }
        when {
            r == null -> log.warn("Fare {} timed out after {} ms", step, timeoutMs)
            !r.first.isSuccess() -> log.warn("Fare {} returned HTTP {}", step, r.first.value)
            else -> return r.second
        }
        return null
    }

    private fun parse(body: String): FareQuote? {
        val r = json.decodeFromString<NovaFareResponse>(body)
        val second = r.fares["2nd"]
        val full = second?.full; val half = second?.halfFare
        if (r.currency != "CHF" || full == null || half == null) { log.warn("Fare response missing CHF 2nd-class prices"); return null }
        return FareQuote(full, half, r.fares["1st"]?.full, r.product ?: "Streckenbillett", "NOVA", r.fares["1st"]?.halfFare)
    }

    companion object {
        const val TIMEOUT_MS = 3_000L
        private val ZURICH = ZoneId.of("Europe/Zurich")
    }
}
