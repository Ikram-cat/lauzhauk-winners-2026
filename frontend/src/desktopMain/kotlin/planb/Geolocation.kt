package planb

// No standard geolocation API on the JVM desktop target.
internal actual suspend fun currentLocation(): LocationResult = LocationResult.Unavailable
