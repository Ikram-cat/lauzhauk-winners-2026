package planb

internal actual val platformHasGeolocation: Boolean get() = false

internal actual fun currentLocation(onResult: (Pair<Double, Double>?) -> Unit) {
    onResult(null)
}
