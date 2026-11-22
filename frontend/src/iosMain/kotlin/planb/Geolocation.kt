package planb

// CoreLocation needs an NSLocationWhenInUseUsageDescription + a delegate-based permission flow;
// left as an honest stub (no Xcode/macOS available to build/test this target here).
internal actual suspend fun currentLocation(): LocationResult = LocationResult.Unavailable
