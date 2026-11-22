package planb

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@JsFun("""
(onSuccess, onError) => {
    if (!navigator.geolocation) { onError(-1); return; }
    navigator.geolocation.getCurrentPosition(
        (pos) => { onSuccess(pos.coords.latitude, pos.coords.longitude); },
        (err) => { onError(err.code); },
        { timeout: 8000 }
    );
}
""")
private external fun jsRequestCurrentPosition(
    onSuccess: (Double, Double) -> Unit,
    onError: (Int) -> Unit
)

internal actual suspend fun currentLocation(): LocationResult = suspendCancellableCoroutine { continuation ->
    jsRequestCurrentPosition(
        onSuccess = { lat, lon -> continuation.resume(LocationResult.Found(lat, lon)) },
        onError = { code -> continuation.resume(if (code == 1) LocationResult.PermissionDenied else LocationResult.Unavailable) }
    )
}
