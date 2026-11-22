package planb

// Real device geolocation needs an Activity-level permission request (ActivityResultContracts)
// that the shared Composable layer doesn't have a Context for; left as an honest stub rather than
// building unverifiable permission plumbing (no Android SDK available to build/test this target here).
internal actual suspend fun currentLocation(): LocationResult = LocationResult.Unavailable
