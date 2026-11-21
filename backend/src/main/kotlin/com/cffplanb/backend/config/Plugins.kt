package com.cffplanb.backend.config

import com.cffplanb.backend.api.ErrorDetailDto
import com.cffplanb.backend.api.ErrorResponseDto
import com.cffplanb.backend.api.configureRoutes
import com.cffplanb.backend.provider.MockTransportProvider
import com.cffplanb.backend.provider.OjpTransportProvider
import com.cffplanb.backend.service.JourneySearchService
import com.cffplanb.backend.service.duration.DurationArtifactLoader
import com.cffplanb.backend.service.impact.ReplayTimelineDisruptionSource
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
        allowMethod(HttpMethod.Post)
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

    val searchService = JourneySearchService(provider)
    val durationEstimator = DurationArtifactLoader.load(config.durationArtifactPath)

    val disruptionSource = ReplayTimelineDisruptionSource.load(config.disruptionTimelinesDir)

    configureRoutes(searchService, clock, durationEstimator, disruptionSource)
}
