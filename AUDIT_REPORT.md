# AUDIT REPORT — ALT backend + analytics

Branch `feat/audit` (from `main` 5e699fc). Scope: backend, analytics, scripts, docs audited and fixed; frontend and shared audited, report-only.
Depth: backend fully read (routes, OJP provider, resilience/caches, GTFS-RT/alerts, estimate models, planner, detector, demo provider). Analytics and scripts got a security pass only (secrets, XML, subprocess, file handling); no pipeline logic review. Frontend got a spot check of `innerHTML`/escaping and secrets.

## Findings

| # | Sev | Area | Location | Description | Status |
|---|-----|------|----------|-------------|--------|
| 1 | MED | Security | `OjpTransportProvider.newDocumentBuilder` | XML parsed with default `DocumentBuilderFactory` (DOCTYPE/external entities allowed). Upstream is trusted, but a hostile or compromised response could XXE or entity-expand. | FIXED: secure processing, `disallow-doctype-decl`, no XInclude. Test added. |
| 2 | MED | Reliability / Security | `OjpTransportProvider.stopCache` | Cache keyed by user free text, never evicted: OJP fuzzy-matches anything, so unique queries grow memory without bound. | FIXED: cleared at 2000 entries (same crude pattern as `suggestCache`). |
| 3 | MED | Reliability | Ktor query decoding (`/api/journeys`, `/api/locations`) | A malformed `%` escape throws `URLDecodeException` outside the `ParamError` try, so it returned `500 INTERNAL_ERROR` (logged with stack trace). | FIXED: `StatusPages` maps it to `400 INVALID_REQUEST`. Covered by a unit test of the handler (the test client cannot send a bad escape; end-to-end not exercised). |
| 4 | LOW | Security | `AppConfig` (data class) | Generated `toString()` would print OJP/GTFS keys if the config were ever logged. | FIXED: redacting override. |
| 5 | LOW | Privacy | `OjpTransportProvider.resolveText` | Logged the user's search text at INFO, against AGENTS "personal query inputs out of routine logs". | FIXED: DEBUG, id only. |
| 6 | LOW | Reliability | `RealtimeDelayModel`, `DelayEstimateModel` `refresh()` | `loaded = null` before parsing opened a window on every mtime change where concurrent requests saw all delays/estimates as unknown. (`ArtifactRiskEstimator` already did it right.) | FIXED: old model serves until the new one is built; null only on missing/unreadable. |
| 7 | LOW | Simplicity | `GtfsRealtimeSource`, `ServiceAlerts` | Deprecated `HttpResponse.readBytes()`; only compiler warnings in the build. | FIXED: `readRawBytes()`. |
| 8 | MED | Coherence (docs) | `docs/API_CONTRACT.md` "Additional endpoints" | Said unknown params return 400 (tests assert they are ignored), `/api/locations` returns `x,y` (actually `coordinates{lat,lon}`), `/api/model` flat (actually `riskModel`/`delayModel`), and listed `/api/alternatives` (route removed; `smoke.sh` expects 404). | FIXED for those four rows. The rest of that section: see DECISION 1. |
| 9 | MED | Reliability | `ServiceAlertsSource.current` (`ServiceAlerts.kt:184`) | Holds a mutex across the whole fetch (client timeout 30 s). Once the 60 s TTL lapses, every `POST /api/journeys/impact` queues behind a slow feed for up to 30 s. `GtfsRealtimeSource` avoids this with background refresh. | DECISION 2 |
| 10 | MED | Security | `JourneyImpactRoutes.kt:36`, `DisruptionRoutes.kt:34` | POST bodies have no size cap; `journeyId`, `reasonCode`, `summaryEn` are unbounded strings. | DECISION 3 |
| 11 | MED | Security | `Plugins.kt:52` | CORS falls back to `anyHost()` when `CORS_ALLOWED_ORIGINS` is empty. AGENTS says validate exact origins. | DECISION 4 |
| 12 | LOW | Security | `OjpTransportProvider.kt:118,222` | Public `502` message names the env var (`Check OJP_API_KEY configuration`). | DECISION 5 (wire text) |
| 13 | LOW | Coherence | `JourneyImpactRoutes.kt:45` vs `Routes.kt` `Parameters.text` | Impact route checks `String.length` (UTF-16), journeys/locations check code points. | Not changed (would alter accept/reject edge). |
| 14 | LOW | Coherence | `JourneyPlanService.kt:88` | `ESTIMATE_OVERLAP` read from `System.getenv` in a default parameter, bypassing `AppConfig`. | Not changed. |
| 15 | LOW | Simplicity | `Plugins.kt:~100-125` | Three copies of "explicit path, else copy classpath resource to a temp file". | Not changed (works; extraction is cosmetic). |
| 16 | LOW | Reliability | `ResilientProvider.cached` | No in-flight coalescing: concurrent identical searches each hit OJP before the first result is cached. | Not changed. |
| 17 | LOW | Reliability | `OjpTransportProvider.escapeXml` | Control characters (e.g. `\u0000`) in user text are not stripped, so OJP rejects the request and the user sees `502` instead of `400`. | Not changed. |
| 18 | LOW | Analytics | `ojp_validate.py:75` | `open(p).read()` without close. Stdlib `ElementTree` is used for upstream XML (no external entities); acceptable. | Not changed. |
| 19 | INFO | Security | whole repo + history | No keys or bearer tokens committed (grepped tree and `git log -S`); `.env*` ignored, only `.env.example` tracked; keys only in `Authorization` headers; error paths log status or exception class only, never bodies. | OK |
| 20 | INFO | Propriety | routes / DTOs | Unknown stays `null` (estimates, fares, risk), demo journeys carry `dataStatus: MOCK`, impact route forces `MOCK` when the source is replay, error envelope is uniform, scheduled times are never mutated (projections are separate fields). No violations found. | OK |
| 21 | INFO | Frontend (report-only) | `frontend/web/app.mjs` | API strings go through `esc()` before `innerHTML`; no secrets in the bundle. | OK |

## DECISIONS

1. **Reconcile the rest of the "Additional journey fields" section of `API_CONTRACT.md`.** It still describes a flat journey (`riskSource`, `estimatedFareChf`, `fareChf`, `reasons` as strings) while the wire is nested (`risk{}`, `fares{}`, `reasons` as objects). Rewriting it is a contract authoring change. *Recommend:* integration owner regenerates that section from the DTOs in `api/Dtos.kt`.
2. **Make `ServiceAlertsSource` non-blocking** (serve the stale snapshot while one background fetch runs, as `GtfsRealtimeSource` does), or at least bound the fetch with `withTimeoutOrNull(~5 s)`. Either changes when `liveAlerts` flips to `UNAVAILABLE`. *Recommend:* stale-while-revalidate.
3. **Cap request bodies and string fields** on the two POST routes (e.g. 64 KiB, `journeyId`/`reasonCode` ≤ 120, `summaryEn` ≤ 2000) returning `400 INVALID_REQUEST`. *Recommend:* yes; additive validation, but it is new 400 behavior.
4. **CORS `anyHost()` fallback.** Safe only while the API is public, read-only and cookieless. *Recommend:* require `CORS_ALLOWED_ORIGINS` outside local dev (fail fast or default to none) and set it on Railway before the demo.
5. **Generic auth-failure message** (drop the env var name from the public error). *Recommend:* yes, and keep the detail in the server log, which already records the HTTP status.

## PHASE 2 NOTES — demo-perturbation provider wrapper

Invariants the wrapper must respect:

1. **Placement vs `ResilientProvider`.** `ResilientProvider` caches successful `search` (key: origin, destination, minute, TTL 60 s) and `stopEvents` (stop, minute, `realtime` flag). If the wrapper sits *inside* it, a perturbed result is cached and served to later requests that did **not** send `demoPerturbation`. Wrap *outside* the cache (around `ResilientProvider`, at the `JourneySearchService` boundary), or the key must include the perturbation. Random perturbations also must not feed the circuit breaker: an injected `Failure` or `Timeout` inside would count toward the 5-failure trip and take the real feed down.
2. **Zero impact when absent.** The param must be threaded per request, not global state. `TransportProvider` has no request context today: the route must pick a wrapped or unwrapped `JourneySearchService` (or pass the setting into the search call). Do not read it from a shared `@Volatile`.
3. **Delegate the whole seam.** `TransportProvider` has six methods: `search`, `searchLocations`, `searchNearby`, `resolvePlace`, `feedStatus`, `stopEvents`. Defaults exist for all but `search` (`stopEvents` defaults to `Failure`, `resolvePlace` echoes input). A wrapper that only overrides `search` silently drops `stopEvents`/`resolvePlace`/`feedStatus` of the real provider. `/health` must keep reporting the real `feedStatus()`.
4. **Honesty labeling.** Perturbed output must be visibly synthetic: `Journey.dataStatus = MOCK` for every journey touched, and the `/api/journeys` and impact responses already derive `MOCK` from it. Never emit injected delays as `LIVE`/`GTFS_RT` data, and never write them into `departureDelayMinutes` as if measured without the `MOCK` label. Unknown stays `null`, not 0.
5. **Scheduled times are never mutated.** Express a delay through `expectedDeparture`/`expectedArrival` (leg level) or `StopEvent.estimated`, and a cancellation through `StopEvent.cancelled` or by omitting the event from the live board. Keep `scheduled`/`departure`/`arrival` intact.
6. **What the detector expects** (`DisruptionDetector`): it queries `stopEvents` twice per leg (timetable board `realtime=false`, live board `realtime=true`) on a 5-minute grid. The leg is matched by normalized label + scheduled time within 5 min (plan) and 2 min (live counterpart). Delay is `estimated - scheduled`, and is a hit only at `>= MIN_DELAY_MINUTES` (3). "Planned but missing from the live board" means **cancelled**. A perturbation must therefore perturb the *live* board consistently with whatever `search` returns, and leave the timetable board alone, or `plannedFor` returns `Unknown`. A `Failure` on the live board gives UNKNOWN, not a hit.
7. **Determinism.** Inject a seeded `Random` (or take the seed from the param) and the injected `Clock`; no `Instant.now()`. Tests must be reproducible.
8. **Error envelope.** Injected failures must be real `ProviderResult` variants (`Timeout`, `RateLimited`, `Failure`) so routes map them to `504/503/502`; never throw (the route would answer `500`). Preserve coroutine cancellation if the wrapper delays: use `delay`, never `Thread.sleep`, and rethrow `CancellationException`. Keep any injected delay well under `JourneySearchService`'s 5 s fare budget and the detector's 6 s deadline.
9. **Validation of the new param.** Follow the existing pattern in `Routes.kt`: add it to `JOURNEY_PARAMS` (duplicate rejection), validate an enum/numeric range, `400 INVALID_REQUEST` with `field`. The contract change is additive: document it in `API_CONTRACT.md`.
10. **Test patterns to copy.** `RouteTest.customProviderTest(provider)` (route level with a fake `TransportProvider`), `DisruptionDetector` is constructed from a lambda `fetch` and exercised with pure `classifyLeg*` functions (`DelayAwareTest`/detector tests), `ResilientProvider(nowMs = …)` for time control, `DemoScenarioTransportProviderTest` for MOCK labeling assertions, and `testApplication` + `configureApp(fixedClock, AppConfig(...))`.

## Evidence

Backend, fresh runs (`cd backend && JAVA_HOME=$HOME/.local/jdk/jdk-21.0.13.1+2 ./gradlew --no-daemon test --rerun-tasks`, then `grep -ho '<testcase ' build/test-results/test/*.xml | wc -l` and `grep -hoE '<failure|<error' … | wc -l`):

| Run | Tests | Failures |
|---|---|---|
| Baseline (before changes) | 213 | 0 |
| After fixes (final) | 215 | 0 |

The two added tests: DOCTYPE refusal (`OjpProviderTest`) and `URLDecodeException` to 400 (`RouteTest`). One intermediate run failed (my first draft of the 400 test could not send a bad escape through the Ktor test client); it was rewritten and the final run is green.

Analytics, from repo root (`~/code/venv-analytics/bin/python -m pytest analytics -q`): 41 passed, 4 skipped, unchanged (no analytics files modified).

Secret scans: `git grep` for JWT/Bearer-shaped strings over the tracked tree, `git log --all -S'eyJ'` over history: no hits.

Not verified: live OJP/GTFS behavior (no keys used), the Railway deployment (out of scope), `scripts/smoke.sh` against a running server, and the end-to-end bad-escape request path.
