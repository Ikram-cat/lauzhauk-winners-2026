package com.cffplanb.backend.config

data class AppConfig(
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080,
    val corsAllowedOrigins: List<String> = System.getenv("CORS_ALLOWED_ORIGINS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList(),
    val transportProvider: String = System.getenv("TRANSPORT_PROVIDER") ?: "mock",
    val ojpApiKey: String? = System.getenv("OJP_API_KEY")
) {
    companion object {
        fun fromEnv(): AppConfig = AppConfig()
    }
}
