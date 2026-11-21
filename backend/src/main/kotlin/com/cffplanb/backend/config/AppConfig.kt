package com.cffplanb.backend.config

data class AppConfig(
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080,
    val corsAllowedOrigins: List<String> = System.getenv("CORS_ALLOWED_ORIGINS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList(),
    val transportProvider: String = System.getenv("TRANSPORT_PROVIDER") ?: "mock",
    val ojpApiKey: String? = System.getenv("OJP_API_KEY"),
    val riskSource: String = System.getenv("RISK_SOURCE") ?: "artifact",
    val riskArtifactPath: String? = System.getenv("RISK_ARTIFACT_PATH"),
    // Blank disables fare lookup (used by the test task to stay offline).
    val fareBaseUrl: String = System.getenv("FARE_BASE_URL") ?: "https://transport.opendata.ch",
    val cacheEnabled: Boolean = !System.getenv("CACHE_ENABLED").equals("false", ignoreCase = true)
) {
    companion object {
        fun fromEnv(): AppConfig = AppConfig()
    }
}
