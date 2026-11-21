# Frontend interfaces

## HTTP dependency

The browser depends on the Ktor HTTP API defined in [API_CONTRACT.md](../docs/API_CONTRACT.md). Use the contract examples as fixtures, including the `MOCK` response, and review any shape changes with the backend owner.

| Interface | Frontend behavior |
| --- | --- |
| API origin | Set the public `api-base-url` HTML meta tag before building Web or pass `API_BASE_URL` to the desktop process. See the [frontend README](README.md). |
| `GET /api/journeys` | Percent-encode station names and the optional offset-aware `departure` value with a URL builder. Omitting `departure` uses the server’s current instant. |
| `GET /health` | Show API reachability in API mode. Health checks do not report timetable availability. |
| Successful journey response | Keep the backend's order. Render scheduled departure and arrival, duration, transfers, and leg mode. Show departure and arrival platforms when supplied. The departure board shows first transit departures from the filtered results and uses each itinerary's provenance. |
| Successful empty response | Show a no-results state and let the user revise the query. |
| API error response | Use the stable error code to distinguish validation, retryable provider failures, and unexpected errors. Handle connection and CORS failures in the client’s network error state. |
| Provenance and nullable fields | Display recognized provenance status, and treat an unfamiliar status as unknown. Ignore unknown response fields. Interpret null or omitted fare, risk, delay, or platform values as unknown. |

## Internal boundary

`SearchForm` emits form edits and submit events. A presenter validates required input, translates date/time input into an offset-aware departure where supplied, and calls `JourneyApiClient`. The client converts an API response into typed success or failure values. The presenter maps those results into the state consumed by the result components. For an ambiguous or invalid Swiss local time at a daylight-saving transition, require an unambiguous selection or show a validation error before sending it.

The frontend applies the direct-only and arrival-cutoff options locally to returned itineraries. Requests still use only origin, destination, and optional departure. The browser has no runtime dependency on analytics or the transport provider. Disruption and AI features need separate backend contracts.
