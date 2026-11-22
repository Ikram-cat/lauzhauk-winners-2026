package com.alt.backend.config

data class AppConfig(
    val corsAllowedOrigins: List<String> = System.getenv("CORS_ALLOWED_ORIGINS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList(),
    val transportProvider: String = System.getenv("TRANSPORT_PROVIDER") ?: "mock",
    val ojpApiKey: String? = System.getenv("OJP_API_KEY"),
    /** Path to analytics duration_model.v1.json; unset or invalid -> duration estimates unavailable. */
    val durationArtifactPath: String? = System.getenv("DURATION_ARTIFACT_PATH"),
    /** Directory of analytics timeline-*.json replayed as active disruptions (MOCK); unset -> none. */
    val disruptionTimelinesDir: String? = System.getenv("DISRUPTION_TIMELINES_DIR"),
    val riskSource: String = System.getenv("RISK_SOURCE") ?: "artifact",
    val riskArtifactPath: String? = System.getenv("RISK_ARTIFACT_PATH"),
    val delayArtifactPath: String? = System.getenv("DELAY_ARTIFACT_PATH"),
    /** Path to delay_estimates_v2.json; unset or invalid -> historical estimates unavailable (NONE). */
    val estimateArtifactPath: String? = System.getenv("ESTIMATE_ARTIFACT_PATH"),
    // Blank disables fare lookup (used by the test task to stay offline).
    val fareBaseUrl: String = System.getenv("FARE_BASE_URL") ?: "https://transport.opendata.ch",
    val gtfsRtApiKey: String? = System.getenv("GTFS_RT_API_KEY"),
    /** GTFS-RT Service Alerts key (la/gtfs-sa); unset -> live alerts DISABLED in /api/journeys/impact. */
    val gtfsSaApiKey: String? = System.getenv("GTFS_SA_API_KEY"),
    val gtfsSaUrl: String = System.getenv("GTFS_SA_URL")?.takeIf { it.isNotBlank() } ?: "https://api.opentransportdata.swiss/la/gtfs-sa",
    val gtfsRtUrl: String = System.getenv("GTFS_RT_URL")?.takeIf { it.isNotBlank() } ?: "https://api.opentransportdata.swiss/la/gtfs-rt",
    // Default: on exactly when a key is present; GTFS_RT_ENABLED=false forces it off.
    val gtfsRtEnabled: Boolean = !System.getenv("GTFS_RT_ENABLED").equals("false", ignoreCase = true) && !System.getenv("GTFS_RT_API_KEY").isNullOrBlank(),
    val cacheEnabled: Boolean = !System.getenv("CACHE_ENABLED").equals("false", ignoreCase = true)
) {
    /** The generated data-class toString would print every API key. */
    override fun toString() = "AppConfig(transportProvider=$transportProvider, riskSource=$riskSource, cacheEnabled=$cacheEnabled, keys=redacted)"

    companion object {
        fun fromEnv(): AppConfig = AppConfig()
    }
}
