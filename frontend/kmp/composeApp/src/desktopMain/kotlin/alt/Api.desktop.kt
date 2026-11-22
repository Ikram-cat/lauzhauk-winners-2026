package alt

actual fun defaultApiBase(): String =
    System.getenv("ALT_API_BASE")?.takeIf { it.isNotBlank() } ?: PROD_API
