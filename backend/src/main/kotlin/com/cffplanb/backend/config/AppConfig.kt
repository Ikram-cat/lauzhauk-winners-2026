package com.cffplanb.backend.config

data class AppConfig(
    val corsAllowedOrigins: List<String> = System.getenv("CORS_ALLOWED_ORIGINS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList(),
    val transportProvider: String = System.getenv("TRANSPORT_PROVIDER") ?: "mock",
    val ojpApiKey: String? = System.getenv("OJP_API_KEY"),
    val riskSource: String = System.getenv("RISK_SOURCE") ?: "artifact",
    val riskArtifactPath: String? = System.getenv("RISK_ARTIFACT_PATH"),
    val delayArtifactPath: String? = System.getenv("DELAY_ARTIFACT_PATH"),
    // Blank disables fare lookup (used by the test task to stay offline).
    val fareBaseUrl: String = System.getenv("FARE_BASE_URL") ?: "https://transport.opendata.ch",
    val gtfsRtApiKey: String? = System.getenv("GTFS_RT_API_KEY"),
    val gtfsRtUrl: String = System.getenv("GTFS_RT_URL")?.takeIf { it.isNotBlank() } ?: "https://api.opentransportdata.swiss/la/gtfs-rt",
    // Default: on exactly when a key is present; GTFS_RT_ENABLED=false forces it off.
    val gtfsRtEnabled: Boolean = !System.getenv("GTFS_RT_ENABLED").equals("false", ignoreCase = true) && !System.getenv("GTFS_RT_API_KEY").isNullOrBlank(),
    val cacheEnabled: Boolean = !System.getenv("CACHE_ENABLED").equals("false", ignoreCase = true)
) {
    companion object {
        fun fromEnv(): AppConfig = AppConfig()
    }
}
