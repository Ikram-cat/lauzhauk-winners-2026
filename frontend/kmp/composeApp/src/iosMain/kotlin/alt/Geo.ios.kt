package alt

// iOS: like the desktop target, no position source is wired yet (CoreLocation would need an
// NSLocationWhenInUseUsageDescription entry and a delegate). The "use my location" button is
// hidden and a request resolves immediately to "no fix".
actual val geoSupported: Boolean = false

actual fun requestPosition(onResult: (String?) -> Unit) {
    onResult(null)
}
