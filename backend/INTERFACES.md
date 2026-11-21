# Backend interfaces

## External interfaces

| Boundary | Contract |
| --- | --- |
| Browser → Ktor | `GET /health` and `GET /api/journeys` follow [API_CONTRACT.md](../docs/API_CONTRACT.md), including query names, optional fields and HTTP errors. API DTOs belong to `api`. |
| Ktor → transport provider | A configured provider adapter calls the validated upstream connection endpoint and maps its response to provider-neutral `Journey` and `Leg` types. Upstream credentials, raw error bodies, and raw provider DTOs remain inside the backend. |
| Runtime → Ktor | `PORT` sets the listen port and `CORS_ALLOWED_ORIGINS` specifies allowed origins. The operational contract is in [deployment](../docs/DEPLOYMENT.md). |
| Analytics → Ktor, later | An optional static artifact loads through `RiskEstimator` after schema and data-use review. See [analytics interfaces](../analytics/INTERFACES.md). |

## Proposed internal seams

These are conceptual Kotlin signatures for the first implementation. Only the JSON contract is shared with the browser.

| Seam | Input and output |
| --- | --- |
| `JourneySearchService.search(request)` | Takes validated stations and effective earliest departure, obtains valid provider journeys, and returns eligible journeys in recommendation order. |
| `TransportProvider.search(request)` | Takes a provider-neutral search and returns normalized `List<Journey>` on success. Failures are distinguishable as timeout, rate-limit, and other upstream failures for HTTP mapping. |
| `RecommendationService.rank(journeys, preferences, deadline)` | Returns ranked journeys. The MVP uses `FASTEST` and a null deadline, ordering eligible journeys by arrival instant, then transfers, departure instant, and ID. |
| `RiskEstimator.estimate(journey, context)` | Returns nullable `RiskEstimate`. The MVP returns unknown. Later estimation needs an approved artifact and an exact, validated match for the journey context. |
| `PlacesProvider.near(station, constraints)`, later | Returns sourced places near a station for rescue actions. Feature and public API remain deferred. |

The domain models carry scheduled, offset-aware times, trip identifiers, transfer counts, ordered legs, optional platform details, and provenance. The `api` mapper owns wire serialization and field names. Provider capabilities should be measured and documented before exposing new public fields.
