package alt

// Fast path: no device location lookup on Android yet. The button stays hidden and the
// request resolves immediately to "no fix", matching desktop's behavior.
actual val geoSupported: Boolean = false

actual fun requestPosition(onResult: (String?) -> Unit) {
    onResult(null)
}
