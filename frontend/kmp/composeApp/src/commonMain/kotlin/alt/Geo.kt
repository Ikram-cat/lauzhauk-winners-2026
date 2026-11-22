package alt

/**
 * "Use my current location" support for the FROM/TO fields.
 *
 * The web target asks the browser for a fix; on a success the field text becomes
 * "lat,lon" (6 decimals) and the backend resolves the coordinates to the nearest
 * stop server-side. Platforms without a position source report [geoSupported] = false
 * and their button is never rendered.
 */

/** True when this platform can ask for a device position (the web target). */
expect val geoSupported: Boolean

/**
 * Requests one position fix. [onResult] receives "lat,lon" with 6 decimals, or null when
 * the browser has no geolocation API, the user denies permission, or the request fails/times out.
 */
expect fun requestPosition(onResult: (String?) -> Unit)
