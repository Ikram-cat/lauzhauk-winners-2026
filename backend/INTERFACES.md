# Backend interfaces

## External interfaces

| Boundary | Contract |
| --- | --- |
| Browser → Ktor | `GET /health` and `GET /api/journeys` follow [API_CONTRACT.md](../docs/API_CONTRACT.md), including query names, optional fields and HTTP errors. API DTOs belong to `api`. |
| Browser → Ktor | Account routes (`/api/auth/*`, `/api/plans`, `/api/travels`) follow the accounts section of [API_CONTRACT.md](../docs/API_CONTRACT.md): JSON bodies, `Authorization: Bearer` tokens, the same error envelope. CORS additionally allows the `Authorization` header. |
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
| `DisruptionDurationEstimator.estimate(ctx, observedAt)` / `.explain(reasonCode)` | Nullable remaining-duration estimate (P50 and pessimistic) and per-cause explanation. `TreeEnsembleDurationEstimator` evaluates the analytics artifact; `NoDurationEstimator` is used when none is loaded. |
| `DisruptionSource.active(at)` | Disruptions active at an instant, with stations (UIC, sloid, roles), lines and the context for prediction. `ReplayTimelineDisruptionSource` replays analytics timelines (`MOCK`); `EmptyDisruptionSource` is the default. A live SIRI-SX source is not implemented (see the analytics README). |
| `JourneyImpactService.check(request, observedAt, journeyId)` | Matches the best journey against active disruptions (`JourneyDisruptionMatcher`) and computes `detourExtraMinutes` from the best unaffected journey of the same search. |
| `PlacesProvider.near(station, constraints)`, later | Returns sourced places near a station for rescue actions. Feature and public API remain deferred. |
| `Persistence.connect(JdbcConfig)` | Parses `DATABASE_URL` (`postgres://`, `postgresql://`, `jdbc:`) or starts an isolated H2 in-memory database when unset; creates missing tables at startup. PostgreSQL in production, H2 in dev and tests. |
| `UserService.register(email, password, displayName)` / `.login` / `.byId` | Email+password accounts (BCrypt, lowercased emails) returning `UserRecord`; register distinguishes a taken email, login does not distinguish unknown email from wrong password. |
| `TokenService.issue(userId)` / `.resolve(raw)` / `.revoke(raw)` | Opaque 64-hex bearer tokens; only SHA-256 digests are stored; 30-day expiry; resolution opportunistically purges expired rows. |
| `PlanService.create/list/findOwned/delete` | Saved journeys per user, ownership-scoped lookups, newest first, optional opaque journey snapshot (<= 64 KiB). |
| `TravelService.create/list` | Past-travel log entries, standalone or copied from a saved plan; deleting a plan keeps the entries. |

The domain models carry scheduled, offset-aware times, trip identifiers, transfer counts, ordered legs, optional platform details, and provenance. The `api` mapper owns wire serialization and field names. Provider capabilities should be measured and documented before exposing new public fields.
