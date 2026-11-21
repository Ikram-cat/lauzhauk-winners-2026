package com.cffplanb.backend.config

import com.cffplanb.backend.api.ErrorDetailDto
import com.cffplanb.backend.api.ErrorResponseDto
import com.cffplanb.backend.api.configureRoutes
import com.cffplanb.backend.provider.MockTransportProvider
import com.cffplanb.backend.provider.NovaFareProvider
import com.cffplanb.backend.provider.OjpTransportProvider
import com.cffplanb.backend.provider.ResilientProvider
import com.cffplanb.backend.service.*
import java.nio.file.Files
import java.nio.file.Path
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock

fun Application.configureApp(clock: Clock, config: AppConfig = AppConfig.fromEnv()) {
    val log = LoggerFactory.getLogger("com.cffplanb.backend.config.Plugins")
    // JSON Content Negotiation
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    // CORS
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        if (config.corsAllowedOrigins.isEmpty()) {
            anyHost()
        } else {
            config.corsAllowedOrigins.forEach { origin ->
                allowHost(origin.removePrefix("https://").removePrefix("http://"),
                    schemes = if (origin.startsWith("https://")) listOf("https") else listOf("http", "https"))
            }
        }
    }

    // Status pages for unhandled exceptions
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled exception: ${cause.javaClass.simpleName}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponseDto(ErrorDetailDto("INTERNAL_ERROR", "An unexpected error occurred"))
            )
        }
    }

    // Build provider
    val provider = when (config.transportProvider.lowercase()) {
        "ojp" -> {
            val apiKey = config.ojpApiKey?.trim().takeUnless { it.isNullOrEmpty() }
                ?: error("OJP_API_KEY environment variable is required when TRANSPORT_PROVIDER=ojp")
            log.info("Using OJP transport provider")
            OjpTransportProvider(apiKey = apiKey, clock = clock)
        }
        else -> {
            log.info("Using Mock transport provider")
            MockTransportProvider()
        }
    }

    val fares = config.fareBaseUrl.trim().takeIf { it.isNotEmpty() }?.let { FareCache(NovaFareProvider(it.trimEnd('/'))) }
    log.info("Fare lookup {}", if (fares != null) "enabled (NOVA via transport.opendata.ch)" else "disabled")
    val searchService = JourneySearchService(ResilientProvider(provider, cacheEnabled = config.cacheEnabled), fares)

    val risk: RiskEstimator = if (config.riskSource.lowercase() == "none") NoRiskEstimator else {
        // Explicit RISK_ARTIFACT_PATH (if it exists) wins; otherwise the bundled classpath resource, copied to a temp file.
        val explicit = config.riskArtifactPath?.takeIf { Files.exists(Path.of(it)) }?.let { Path.of(it) }
        val path = explicit ?: ArtifactRiskEstimator::class.java.getResourceAsStream("/risk/predictions.json")?.use {
            Files.createTempFile("predictions", ".json").also { f -> Files.copy(it, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING); f.toFile().deleteOnExit() }
        } ?: Path.of(config.riskArtifactPath ?: "analytics/artifacts/predictions.json")
        ArtifactRiskEstimator(path, clock)
    }

    val ri = risk.info()
    log.info("Risk artifact status={} corridorsLoaded={} modelVersion={}", ri.status, ri.corridorsLoaded, ri.modelVersion)
    configureRoutes(searchService, clock, risk)
}
