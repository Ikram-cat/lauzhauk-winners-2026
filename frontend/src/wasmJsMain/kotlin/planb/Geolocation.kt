package planb

import kotlin.js.JsNumber
import kotlin.js.js

/** Browser geolocation through the __planbLocate bridge defined in index.html: reports (lat, lon) or nulls. */
private val locate: ((JsNumber?, JsNumber?) -> Unit) -> Unit = js("window.__planbLocate")

internal actual val platformHasGeolocation: Boolean get() = true

internal actual fun currentLocation(onResult: (Pair<Double, Double>?) -> Unit) {
    locate { latitude, longitude ->
        onResult(
            if (latitude != null && longitude != null) {
                Pair(latitude.toDouble(), longitude.toDouble())
            } else null
        )
    }
}
