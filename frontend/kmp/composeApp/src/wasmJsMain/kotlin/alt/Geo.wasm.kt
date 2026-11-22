package alt

// Kotlin/Wasm has no geolocation of its own; the browser's navigator.geolocation is the source.
// getCurrentPosition is async, so the JS shim hands the result straight back into Kotlin.

@JsFun(
    """
    (onResult) => {
      const done = (v) => { try { onResult(v); } catch (e) {} };
      if (typeof navigator === 'undefined' || !navigator.geolocation) { done(null); return; }
      try {
        navigator.geolocation.getCurrentPosition(
          (pos) => {
            const lat = pos.coords.latitude;
            const lon = pos.coords.longitude;
            done(lat.toFixed(6) + ',' + lon.toFixed(6));
          },
          (err) => { done(null); },
          { enableHighAccuracy: false, maximumAge: 60000, timeout: 8000 }
        );
      } catch (e) { done(null); }
    }
    """
)
private external fun jsRequestPosition(onResult: (String?) -> Unit)

actual val geoSupported: Boolean = true

actual fun requestPosition(onResult: (String?) -> Unit) {
    jsRequestPosition(onResult)
}
