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
    /** Path to analytics duration_model.v1.json; unset or invalid -> duration estimates unavailable. */
    val durationArtifactPath: String? = System.getenv("DURATION_ARTIFACT_PATH"),
    /** Directory of analytics timeline-*.json replayed as active disruptions (MOCK); unset -> none. */
    val disruptionTimelinesDir: String? = System.getenv("DISRUPTION_TIMELINES_DIR")
) {
    companion object {
        fun fromEnv(): AppConfig = AppConfig()
    }
}
