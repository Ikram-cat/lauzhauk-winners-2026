package planb

internal sealed interface LocationResult {
    data class Found(val latitude: Double, val longitude: Double) : LocationResult
    data object PermissionDenied : LocationResult
    data object Unavailable : LocationResult
}

/** Platform-specific device coordinates for the "use my current location" button, resolved to a station via `/api/locations?near=`. */
internal expect suspend fun currentLocation(): LocationResult
