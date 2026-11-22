package com.alt.backend.config

import com.alt.backend.api.ErrorDetailDto
import com.alt.backend.api.ErrorResponseDto
import com.alt.backend.api.accountRoutes
import com.alt.backend.api.configureRoutes
import com.alt.backend.provider.DemoScenarioTransportProvider
import com.alt.backend.gtfs.GtfsRealtimeSource
import kotlinx.coroutines.launch
import com.alt.backend.provider.MockFareProvider
import com.alt.backend.provider.MockTransportProvider
import com.alt.backend.provider.NovaFareProvider
import com.alt.backend.provider.OjpTransportProvider
import com.alt.backend.service.JourneySearchService
import com.alt.backend.service.duration.DurationArtifactLoader
import com.alt.backend.service.impact.ReplayTimelineDisruptionSource
import com.alt.backend.service.impact.ServiceAlertsSource
import com.alt.backend.auth.Authenticator
import com.alt.backend.auth.TokenService
import com.alt.backend.db.Persistence
import com.alt.backend.provider.ResilientProvider
import io.ktor.server.routing.routing
import com.alt.backend.service.*
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
    val log = LoggerFactory.getLogger("com.alt.backend.config.Plugins")
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
        allowHeader(HttpHeaders.Authorization)
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
        // DB failures on the account routes map to 503 (the error envelope, never raw SQL)
        exception<java.sql.SQLException> { call, _ ->
            log.error("Database unavailable")
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponseDto(ErrorDetailDto("DATABASE_ERROR", "The database is unavailable"))
            )
        }
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
        "demo" -> {
            log.info("Using demo scenario transport provider (MOCK journeys for the disruption replays)")
            DemoScenarioTransportProvider()
        }
        else -> {
            log.info("Using Mock transport provider")
            MockTransportProvider()
        }
    }

    val fareUrl = config.fareBaseUrl.trim()
    val fares = when (fareUrl) { "" -> null; "mock" -> FareCache(MockFareProvider); else -> FareCache(NovaFareProvider(fareUrl.trimEnd('/'))) }
    log.info("Fare lookup {}", when (fareUrl) { "" -> "disabled"; "mock" -> "enabled (MOCK demo prices)"; else -> "enabled (NOVA via transport.opendata.ch)" })
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
    val gtfs = if (config.gtfsRtEnabled && !config.gtfsRtApiKey.isNullOrBlank()) {
        log.info("GTFS-RT enabled (1 fetch per 60 s)")
        GtfsRealtimeSource(GtfsRealtimeSource.httpFetch(config.gtfsRtUrl, config.gtfsRtApiKey.trim())).also { src ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { src.current() } // warm up so the first search does not pay for the 4.8 MB fetch
        }
    } else { log.info("GTFS-RT disabled; using OJP stop events only"); null }
    // Same precedence as the risk artifact; no bundled resource and no env file = delay model stays "missing".
    val delayPath = config.delayArtifactPath?.takeIf { Files.exists(Path.of(it)) }?.let { Path.of(it) }
        ?: RealtimeDelayModel::class.java.getResourceAsStream("/realtime/realtime_delays.json")?.use {
            Files.createTempFile("realtime_delays", ".json").also { f -> Files.copy(it, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING); f.toFile().deleteOnExit() }
        } ?: config.delayArtifactPath?.let { Path.of(it) }
    val delay = RealtimeDelayModel(delayPath)
    log.info("Delay artifact status={} modelVersion={}", delay.info().status, delay.info().modelVersion)
    val durationEstimator = DurationArtifactLoader.load(config.durationArtifactPath)
    val disruptionSource = ReplayTimelineDisruptionSource.load(config.disruptionTimelinesDir)
    val serviceAlerts = config.gtfsSaApiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { key ->
        log.info("GTFS-RT Service Alerts enabled for live alerts (1 fetch per 60 s)")
        ServiceAlertsSource(ServiceAlertsSource.httpFetch(config.gtfsSaUrl, key)).also { src ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { src.current() }
        }
    } ?: run { log.info("GTFS-RT Service Alerts disabled (no GTFS_SA_API_KEY)"); null }
    // Accounts, saved plans and travel history (H2 in-memory unless DATABASE_URL is set)
    val database = Persistence.connect(Persistence.parseDatabaseUrl(config.databaseUrl, config.databaseUser, config.databasePassword))
    val accountTokens = TokenService(database, clock)
    val accountUsers = UserService(database, clock)
    val accountPlans = PlanService(database, clock)
    val accountTravels = TravelService(database, clock)
    routing {
        accountRoutes(accountUsers, accountTokens, accountPlans, accountTravels, Authenticator(accountTokens, accountUsers))
    }

    configureRoutes(searchService, clock, risk, gtfs, delay,
        durationEstimator = durationEstimator, disruptionSource = disruptionSource, serviceAlerts = serviceAlerts)
}
