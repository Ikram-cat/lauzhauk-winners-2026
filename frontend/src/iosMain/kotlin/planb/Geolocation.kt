package planb

/** iOS CoreLocation needs permission wiring through the app entry point; report unavailable for now. */
internal actual val platformHasGeolocation: Boolean get() = false

internal actual fun currentLocation(onResult: (Pair<Double, Double>?) -> Unit) {
    onResult(null)
}
