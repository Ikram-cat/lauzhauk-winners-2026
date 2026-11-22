package alt

actual fun defaultApiBase(): String = js("(globalThis.ALT_API_BASE || '')")
