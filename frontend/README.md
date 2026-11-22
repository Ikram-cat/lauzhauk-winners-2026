# ALT web frontend

Run the browser preview from the repository root with Node.js 22 or later.

```powershell
node frontend/web/preview.mjs
```

Open [the local preview](http://127.0.0.1:8790/). The preview serves the web files and forwards the location and journey endpoints to the Kotlin API documented in [backend/README.md](../backend/README.md). Set `ALT_API_BASE` to use a different backend origin and `ALT_PREVIEW_PORT` to use a different local port.

The browser frontend uses the same JSON API as the app. A hosted build needs to serve the static files in `web/` and forward `/api/locations` and `/api/journeys` on the same origin. Geolocation requires HTTPS or a loopback origin.

A search makes one journey request for its selected mode. The UI keeps the backend order and reveals cached alternatives in groups of seven. Chronological browsing sorts the returned alternatives by departure. Theme and language preferences persist in the browser. Journey and location selections are held in memory for the current session.

The bundled `web/swiss-boundary.geojson` uses the [Swiss national boundary from ©swisstopo](https://geo.ld.admin.ch/boundaries/country/CH), distributed under the [swisstopo open geodata terms](https://www.swisstopo.admin.ch/en/terms-of-use-free-geodata-and-geoservices). The planner loads it once to verify location coordinates against the border. It does not send the coordinates to another service for country verification.
