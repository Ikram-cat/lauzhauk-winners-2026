# CFF Plan B — Backend

Ktor (Kotlin/JVM) API that answers one question: **a traveller asks for a trip, and if something
on that trip is broken, what is the single best alternative?** It returns structured data only —
no generated sentences — so the client owns the wording.

Live sources: Swiss **Open Journey Planner (OJP 2.0)** for timetables and realtime stop events,
**GTFS-Realtime** for network-wide cancellations and skipped stops, **NOVA** for CFF fares.
Offline assets: a historical disruption-risk model and a measured realtime-delay model.

---

## Quick start

```bash
export JAVA_HOME=~/.local/jdk/jdk-21.0.13.1+2      # JDK 21 (no system javac on this machine)
export PATH="$JAVA_HOME/bin:$PATH"

cd backend
./gradlew test                                     # 169 tests, no network needed
TRANSPORT_PROVIDER=mock PORT=8080 ./gradlew run     # offline demo data, no keys required
```

Live mode, with keys (never commit them):

```bash
set -a; . ~/lauzhack-ojp.env; . ~/lauzhack-gtfsrt.env; set +a
OJP_API_KEY=$OJP_API_KEY GTFS_RT_API_KEY=$GTFS_RT_API_KEY \
  TRANSPORT_PROVIDER=ojp PORT=8080 ./gradlew run
curl -s "localhost:8080/api/redirect?origin=Lausanne&destination=Gen%C3%A8ve&limit=3"
```

Container:

```bash
podman build -t planb-backend ./backend
podman run --rm -p 8080:8080 -e PORT=8080 -e TRANSPORT_PROVIDER=mock planb-backend
```

Deploy verification (10 checks against any base URL):

```bash
./scripts/smoke.sh https://your-service.up.railway.app
```

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` | `8080` | Listen port, bound on `0.0.0.0`. |
| `TRANSPORT_PROVIDER` | `mock` | `ojp` for live timetables, `mock` for labelled demo data, `demo` for the MOCK journeys of the disruption replays (Lausanne → Genève and Zürich Stadelhofen → Stettbach, each with an alternative avoiding the section; the team mock for any other request). |
| `OJP_API_KEY` | — | Bearer token for opentransportdata. Required when `TRANSPORT_PROVIDER=ojp`. |
| `GTFS_RT_API_KEY` | — | Enables network-wide realtime. Absent → feature silently disabled. |
| `GTFS_RT_URL` | `https://api.opentransportdata.swiss/la/gtfs-rt` | Feed URL (302 redirect is followed). |
| `GTFS_RT_ENABLED` | on when a key exists | `false` forces the feed off. |
| `GTFS_SA_API_KEY` | — | Separate key subscribed to GTFS-RT Service Alerts. Enables `liveAlerts` (LIVE, no prediction) in `POST /api/journeys/impact`; absent → `DISABLED`. |
| `GTFS_SA_URL` | `https://api.opentransportdata.swiss/la/gtfs-sa` | Service Alerts feed URL. |
| `CORS_ALLOWED_ORIGINS` | empty | Comma-separated exact browser origins, no trailing slash. |
| `CACHE_ENABLED` | `true` | 60 s search cache, 10 min location cache, circuit breaker. |
| `RISK_SOURCE` | `artifact` | `none` disables historical risk. |
| `RISK_ARTIFACT_PATH` | bundled resource | Override path for `predictions.json`. |
| `DELAY_ARTIFACT_PATH` | bundled resource | Override path for `realtime_delays.json`. |
| `FARE_BASE_URL` | `https://transport.opendata.ch` | Fare provider base; blank disables fares. |
| `DURATION_ARTIFACT_PATH` | unset | Path to `analytics/output/duration_model.v1.json` (or `.eval.json` for replays). Unset or invalid: one warning, `/api/disruptions/outlook` answers `status: UNAVAILABLE`. |
| `DISRUPTION_TIMELINES_DIR` | unset | Directory of analytics `timeline-*.json`, replayed as active disruptions (`MOCK`) by `POST /api/journeys/impact`. Unset: no journey is affected. |

## API

Base URL is the service origin, **no `/api` in the base**. All endpoints below are `GET`, read-only,
UTF-8 JSON, no authentication, no cookies. Percent-encode every value, including accents and the
`+` of an offset (`Gen%C3%A8ve`, `2026-11-21T23%3A00%3A00%2B01%3A00`).

Timestamps are offset-aware instants (`Z` or `+01:00`); compare as instants, display in
`Europe/Zurich`. Unknown or duplicate query parameters are rejected with `400`.

### `GET /health`

Liveness only; never touches a provider or an artifact.

```json
{ "status": "ok" }
```

### `GET /api/locations`

Stop and address autocomplete, proxied from OJP and cached 10 minutes.

| Parameter | Required | Rules |
| --- | --- | --- |
| `query` | yes | 1–120 code points, trimmed |

```json
{ "locations": [ { "id": "ch:1:sloid:7000", "name": "Lausanne", "kind": "stop", "x": 6.63, "y": 46.52 } ] }
```

`kind` is `stop` or `address`; `x`/`y` may be null. Send the `name` back as `origin`/`destination`
— the backend resolves text to stops itself, the `id` is display metadata.

### `GET /api/journeys`

The planning call.

| Parameter | Required | Default | Rules |
| --- | --- | --- | --- |
| `origin` | yes | — | trimmed, 1–120 code points, non-blank |
| `destination` | yes | — | same as origin |
| `departure` | no | server now | RFC 3339 with `Z` or numeric offset |
| `arrivalDeadline` | no | null | RFC 3339; inclusive (`arrival <= deadline`) |
| `preference` | no | `FASTEST` | `FASTEST` \| `CHEAPEST` \| `SIMPLEST` (case-insensitive) |
| `fareClass` | no | `2` | `1` or `2` |
| `discount` | no | `NONE` | `NONE` \| `HALF_FARE` |
| `limit` | no | `3` | integer 1–10 |
| `includeText` | no | `false` | `true` adds `advice`, `message`, `why`, `reasons` |

```json
{
  "request": {
    "origin": "Lausanne", "destination": "Genève",
    "earliestDeparture": "2026-11-21T22:00:00Z", "arrivalDeadline": null,
    "preference": "FASTEST"
  },
  "recommendations": { "fastest": { /* journey */ }, "simplest": { /* journey */ } },
  "journeys": [
    {
      "id": "ID-A415CE24-59CE-41E1-88B2-18FBE9F5276C",
      "departure": "2026-11-21T22:00:00Z", "arrival": "2026-11-21T22:53:00Z",
      "durationMinutes": 53, "transfers": 0, "dataStatus": "LIVE_SCHEDULE",
      "expectedArrival": "2026-11-21T22:53:00Z", "expectedDelayMin": 0,
      "delaySource": "station", "delaySamples": 17,
      "lateArrivalProbability": 0.0273, "riskSource": "corridor", "riskConfidence": "high",
      "disruptionDurationMin": 196, "fareChf": 23.8, "fareStatus": "PRICED",
      "legs": [
        { "mode": "TRANSIT", "origin": "Lausanne", "destination": "Genève",
          "departure": "2026-11-21T22:00:00Z", "arrival": "2026-11-21T22:53:00Z",
          "serviceLabel": "IR90", "departurePlatform": "8", "arrivalPlatform": "2" }
      ]
    }
  ],
  "latestFeasibleDeparture": null,
  "noFeasibleRoute": null
}
```

`recommendations.fastest` and `recommendations.simplest` are full journey objects, chosen
independently of `preference`; either may be `null` when no route exists. `journeys` holds the top
`limit` options in the order implied by `preference` (fastest: expected arrival; cheapest: fare,
unpriced last; simplest: fewest changes). **The client must not re-sort.** Without `arrivalDeadline`
the deadline fields are null/absent.

### `GET /api/redirect`

Same parameters and validation as `/api/journeys`, plus disruption detection. This is the product
call: it says whether the route is broken and which single alternative to take.

```json
{
  "request": { "…": null },
  "situation": "NO_DISRUPTION | DISRUPTION_DETECTED | UNKNOWN",
  "disruption": {
    "legIndex": 1,
    "serviceLabel": "RE33",
    "from": "Renens VD",
    "to": "Genève",
    "scheduledDeparture": "2026-11-21T20:33:00Z",
    "estimatedDeparture": "2026-11-21T20:55:00Z",
    "delayMinutes": 22,
    "cancelled": false,
    "basis": "REALTIME_ESTIMATE | CANCELLED_FLAG | SUPPRESSED_STOP | MISSING_FROM_REALTIME",
    "confidence": "high | medium | low",
    "cause": null
  },
  "recommendation": { /* journey */ },
  "alternatives": [ { /* journey */ } ],
  "recommendations": { "fastest": { /* journey */ }, "simplest": { /* journey */ } },
  "journeys": [ { /* journey */ } ],
  "latestFeasibleDeparture": null,
  "noFeasibleRoute": false,
  "computedAt": "2026-11-21T22:05:00Z",
  "modelVersion": "2026-11-duration-v1"
}
```

`disruption` is `null` when the situation is `NO_DISRUPTION`. `legIndex` is a 0-based index into
`recommendation.legs`. Detection order: a cancelled GTFS trip → `CANCELLED_FLAG`; a skipped stop →
`SUPPRESSED_STOP`; a measured delay ≥ 3 minutes → `REALTIME_ESTIMATE`; otherwise OJP stop events as
fallback. Delays under 3 minutes are not disruptions. If detection cannot run (timeout, provider
error) the answer is `UNKNOWN` with normal journeys — never a fabricated "all good".

Rerouting rule: a replacement is recommended only when it arrives at least 5 minutes earlier than
the delayed original; otherwise the answer is to stay. A cancellation always reroutes.

### `GET /api/alternatives`

Rescue search from an intermediate stop after a traveller is stranded.

| Parameter | Required | Rules |
| --- | --- | --- |
| `from`, `to` | yes | trimmed, 1–120 code points |
| `notBefore` | yes | RFC 3339 with offset; no default |
| `arrivalDeadline` | no | RFC 3339, inclusive |

```json
{
  "request": { "from": "Genève", "to": "Lausanne", "notBefore": "2026-11-21T22:00:00Z", "arrivalDeadline": null },
  "alternatives": [ { /* journey */ } ],
  "latestFeasibleDeparture": null,
  "noFeasibleRoute": false,
  "advice": "Leave by 22:20 to arrive before 23:30; highest risk is on corridor Genève - Lausanne."
}
```

`/api/alternatives` is the only endpoint that always returns a text field (`advice`); it does not
accept `includeText` or `limit`.

### `GET /api/model`

Provenance of the artifacts actually loaded by the running instance. Static; call once.

```json
{
  "modelVersion": "2026-11-duration-v1", "generatedAt": "2026-11-21T18:03:53Z",
  "coverage": { "from": "…", "to": "…", "scope": "SBB extern-liveticker incidents" },
  "source": "artifact", "status": "ok",
  "keysLoaded": 15, "corridorsLoaded": 142, "baselineLoaded": 24, "byLineHourCells": 15,
  "gtfsRtEnabled": true, "gtfsRtAgeSeconds": 60, "gtfsRtTripUpdates": 6862, "gtfsRtCancelled": 74,
  "delayModelVersion": "rt-20261121-2200", "delayPortions": 24, "delayLines": 4,
  "delayArtifactStatus": "ok"
}
```

### Journey object

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | string | Opaque stable identifier. |
| `departure`, `arrival` | offset timestamp | Scheduled endpoints. Never adjusted. |
| `durationMinutes` | int | Scheduled elapsed minutes. |
| `transfers` | int | Vehicle changes; walking alone adds none. |
| `legs` | array | Ordered legs, see below. |
| `dataStatus` | `LIVE_SCHEDULE` \| `MOCK` | `MOCK` must be labelled as demo data in any UI. |
| `feasible` | bool \| null | Only with `arrivalDeadline`. |
| `expectedArrival` | offset timestamp \| null | Scheduled arrival + worst measured leg delay. |
| `expectedDelayMin` | int \| null | Applied delay in minutes; null = unknown. |
| `delaySource` | `line` \| `station` \| `none` | Which measured table produced it. |
| `delaySamples` | int \| null | Sample count behind the delay estimate. |
| `lateArrivalProbability` | 0..1 \| null | Historical frequency: share of days the corridor had an active disruption. |
| `disruptionDurationMin` | int \| null | Typical disruption length on that corridor. **Not the traveller's delay.** |
| `predictedDelayMinutes` | int \| null | Reserved for a validated per-train delay; null today. |
| `riskSource`, `riskBasis`, `riskConfidence`, `riskLabel` | string \| null | Provenance of the risk number (`corridor`, `baseline`; `very_low`…`high`). |
| `fareChf` | number \| null | Price for the requested class + discount. |
| `halfFareChf`, `firstClassFareChf`, `firstClassHalfFareChf` | number \| null | Other quote variants. |
| `fareProduct`, `fareSource`, `fareStatus` | string \| null | `fareStatus`: `PRICED` \| `UNAVAILABLE`. Show "unavailable", never 0. |
| `estimatedFareChf` | number \| null | Reserved; null today. |

Leg object: `mode` (`TRANSIT` | `WALK`), `origin`, `destination`, `departure`, `arrival`,
`serviceLabel`, `departurePlatform`, `arrivalPlatform` (last three nullable). Walk legs may use raw
coordinates as origin/destination.

### Errors

Every failure uses one envelope:

```json
{ "error": { "code": "INVALID_REQUEST", "message": "Departure must include a UTC offset." } }
```

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | Bad parameter: missing, blank, too long, duplicate, unknown, malformed timestamp, `limit` out of range. |
| 502 | `UPSTREAM_ERROR` | Provider or stop resolution failed. |
| 503 | `UPSTREAM_RATE_LIMITED` | Provider throttling. |
| 504 | `UPSTREAM_TIMEOUT` | Provider exceeded the 8 s bound. |

Provider bodies and credentials never appear in responses or logs.

## Disruption duration and journey impact (proposal, role C)

Two `POST` routes, specified in [API_CONTRACT.md](../docs/API_CONTRACT.md#disruption-outlook-proposal), use the offline duration model from `analytics/sbb_duration`:

| Endpoint | Purpose |
| --- | --- |
| `POST /api/disruptions/outlook` | Remaining-duration estimate (P50 and calibrated pessimistic), official remaining time, `advice.code` (`TAKE_DETOUR`, `DETOUR_IF_DEADLINE`, `WAIT`, `UNKNOWN`) and a per-cause `explanation`. |
| `POST /api/journeys/impact` | Checks the best journey against the active disruptions, computes `detourExtraMinutes` from the best unaffected journey of the same search, returns the outlook. |

```bash
cd analytics && python -m sbb_duration.train && python -m sbb_duration.export && python -m sbb_duration.sources && cd ..
python analytics/scripts/run_demo.py --offline     # backend with the eval artifact, replayed timelines, TRANSPORT_PROVIDER=demo
curl -X POST localhost:8080/api/journeys/impact -H "Content-Type: application/json" \
  -d '{"origin":"Lausanne","destination":"Geneve","observedAt":"2026-09-17T18:31:00+02:00"}'
```

- `DurationGoldenVectorsTest` checks the Kotlin tree evaluator against `../analytics/output/golden_vectors.json` (100 cases, 1e-6). The replay tests read `../analytics/output/timelines/`. Both are skipped when the outputs have not been generated locally.
- Code: `service/duration/` (artifact DTOs, loader, features, tree evaluator), `service/impact/` (timeline replay source, journey matcher, impact service), `service/AdviceService.kt`, `api/DisruptionRoutes.kt`, `api/JourneyImpactRoutes.kt`, `provider/DemoScenarioTransportProvider.kt`.
- The artifact and timelines are not committed and the Docker image does not include them. Until the data-use review, a deployed backend answers `UNAVAILABLE` and has no replayed disruptions. There is no live disruption feed for these routes; see [analytics README, Live mode](../analytics/README.md#live-mode-alerts-only-no-prediction).

## Layout

```
backend/
  src/main/kotlin/com/cffplanb/backend/
    api/        routes, DTOs, request validation, error mapping
    service/    search orchestration, ranking, risk + delay models, redirect logic
    provider/   OJP client, mock provider, NOVA fares, resilient wrapper (cache, breaker)
    gtfs/       GTFS-Realtime fetch, protobuf decoding, trip index
    config/     env config, plugins (JSON, CORS, logging)
  src/main/resources/
    risk/predictions.json       historical disruption risk (corridors, hour baseline)
    realtime/realtime_delays.json  measured lateness (station/hour, line/hour)
```

## Honesty rules baked into the code

- Unmeasured means `null`, never `0`.
- `disruptionDurationMin` is a disruption length, not a traveller delay; `predictedDelayMinutes`
  stays null until a validated per-train estimate exists.
- Risk probabilities are frequencies over a 953-day window, not per-train chances.
- Demo data is always `MOCK`; live data is always `LIVE_SCHEDULE`.
- Artifacts that are missing or unreadable degrade the response, never crash it.
