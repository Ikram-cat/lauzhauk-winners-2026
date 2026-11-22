package alt

import platform.Foundation.NSProcessInfo

/** ALT_API_BASE from the launch environment (the UI test sets it), else the deployed backend. */
actual fun defaultApiBase(): String =
    (NSProcessInfo.processInfo.environment["ALT_API_BASE"] as? String)?.takeIf { it.isNotBlank() } ?: PROD_API
