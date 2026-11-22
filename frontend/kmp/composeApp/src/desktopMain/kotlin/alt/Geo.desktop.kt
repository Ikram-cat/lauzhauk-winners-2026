package alt

// No standard geolocation API on the JVM desktop target: the button is hidden and the
// request resolves immediately to "no fix".
actual val geoSupported: Boolean = false

actual fun requestPosition(onResult: (String?) -> Unit) {
    onResult(null)
}
