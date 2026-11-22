# ALT web frontend

Run the browser preview from the repository root with Node.js 22 or later.

```powershell
node frontend/web/preview.mjs
```

Open [the local preview](http://127.0.0.1:8790/). The preview serves the web files and forwards the location and journey endpoints to the Kotlin API documented in [backend/README.md](../backend/README.md). Set `ALT_API_BASE` to use a different backend origin and `ALT_PREVIEW_PORT` to use a different local port.

The Railway image serves the contents of `frontend/web/` at the same origin as the Kotlin API. The repository-root `railway.json` selects the backend Dockerfile. The browser uses relative `/api/locations` and `/api/journeys` URLs in both Railway and the local preview. Geolocation requires HTTPS or a loopback origin.

A search makes one journey request for its selected mode. The UI keeps the backend order and reveals cached alternatives in groups of seven. Chronological browsing sorts the returned alternatives by departure. Theme and language preferences persist in the browser. Journey and location selections are held in memory for the current session.

The bundled `web/swiss-boundary.geojson` uses the [Swiss national boundary from ©swisstopo](https://geo.ld.admin.ch/boundaries/country/CH), distributed under the [swisstopo open geodata terms](https://www.swisstopo.admin.ch/en/terms-of-use-free-geodata-and-geoservices). The planner loads it once to verify location coordinates against the border. It does not send the coordinates to another service for country verification.

`web/rail-stations.json` is a compact [SBB DiDok service-point](https://data.sbb.ch/explore/dataset/dienststellen-gemass-opentransportdataswiss/) export. Its source query selects Swiss passenger stops marked `TRAIN` with a timetable and a position. The browser loads it on demand and computes proximity locally. To refresh it, fetch the `source` URL recorded in the file and retain each station’s SLOID, official name, latitude and longitude.
