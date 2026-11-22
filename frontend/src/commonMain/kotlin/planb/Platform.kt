package planb

/**
 * Device geolocation for the "use my location" button. Real on the browser target;
 * desktop, Android and iOS currently report unavailable (wiring them up is a design-owner task).
 */
internal expect val platformHasGeolocation: Boolean

internal expect fun currentLocation(onResult: (Pair<Double, Double>?) -> Unit)
