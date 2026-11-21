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
./gradlew test                                     # 171 tests, no network needed
TRANSPORT_PROVIDER=mock PORT=8080 ./gradlew run     # offline demo data, no keys required
```

Live mode, with keys (never commit them):

```bash
set -a; . ~/lauzhack-ojp.env; . ~/lauzhack-gtfsrt.env; set +a
OJP_API_KEY=$OJP_API_KEY GTFS_RT_API_KEY=$GTFS_RT_API_KEY \
  TRANSPORT_PROVIDER=ojp PORT=8080 ./gradlew run
curl -s "localhost:8080/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&limit=3"
```

Container:

```bash
podman build -t planb-backend ./backend
podman run --rm -p 8080:8080 -e PORT=8080 -e TRANSPORT_PROVIDER=mock planb-backend
```

Deploy verification (18 checks, covering all four endpoints and the 400/404 paths, against any base URL):

```bash
./scripts/smoke.sh https://your-service.up.railway.app
```

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` | `8080` | Listen port, bound on `0.0.0.0`. |
| `TRANSPORT_PROVIDER` | `mock` | `ojp` for live timetables, `mock` for labelled demo data. |
| `OJP_API_KEY` | — | Bearer token for opentransportdata. Required when `TRANSPORT_PROVIDER=ojp`. |
| `GTFS_RT_API_KEY` | — | Enables network-wide realtime. Absent → feature silently disabled. |
| `GTFS_RT_URL` | `https://api.opentransportdata.swiss/la/gtfs-rt` | Feed URL (302 redirect is followed). |
| `GTFS_RT_ENABLED` | on when a key exists | `false` forces the feed off. |
| `CORS_ALLOWED_ORIGINS` | empty | Comma-separated exact browser origins, no trailing slash. |
| `CACHE_ENABLED` | `true` | 60 s search cache, 10 min location cache, circuit breaker. |
| `RISK_SOURCE` | `artifact` | `none` disables historical risk. |
| `RISK_ARTIFACT_PATH` | bundled resource | Override path for `predictions.json`. |
| `DELAY_ARTIFACT_PATH` | bundled resource | Override path for `realtime_delays.json`. |
| `FARE_BASE_URL` | `https://transport.opendata.ch` | Fare provider base; blank disables fares. |

## API

Four endpoints, all `GET`, read-only, UTF-8 JSON, no authentication, no cookies. Base URL is the
service origin, **no `/api` in the base**. Percent-encode every value, including accents and the `+`
of an offset (`Gen%C3%A8ve`, `2026-11-21T23%3A00%3A00%2B01%3A00`).

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Liveness and the state of the live feeds. |
| `GET /api/locations` | Stop and address autocomplete. |
| `GET /api/journeys` | Planner, disruption detector and rescue rerouter in one call. |
| `GET /api/model` | Static metadata of the loaded artifacts. Cacheable. |

`/api/redirect` and `/api/alternatives` no longer exist (404); `/api/journeys` replaces both and
always runs disruption detection.

Conventions that hold everywhere:

- Timestamps are offset-aware instants (`Z` or `+01:00`); compare as instants, display in `Europe/Zurich`.
- **No generated prose anywhere.** Every string is an enum token, an id, a place name from the
  provider, or a timestamp. The client owns the wording.
- **Unknown is `null`.** A number is `0` only when the source explicitly reports `0`.
- **Unknown query parameters are ignored** (so tracking or forward-compatible parameters never break a
  request). Invalid values, missing required parameters and a repeated known parameter are `400`.
- Enum parameters are case-insensitive; responses always use the uppercase token.

### `GET /health`

Never calls a provider and never waits: every value derives from configuration and the last known outcome.

```json
{ "status": "OK",
  "feeds": { "ojp": "OK", "nova": "OK",
    "gtfsRt": { "enabled": true, "status": "OK", "ageSeconds": 42, "activeTripUpdates": 6862, "cancelledTrips": 74 } } }
```

| Field | Meaning |
| --- | --- |
| `status` | `OK` when every enabled feed is `OK` or `DEGRADED`; `DEGRADED` when any enabled feed is `DOWN`. Always HTTP 200. |
| `feeds.ojp` | Timetable/realtime provider. `DISABLED` in mock mode; `DEGRADED` after recent failures; `DOWN` while the circuit breaker is open. |
| `feeds.nova` | Fare provider. `DISABLED` when `FARE_BASE_URL` is blank; `DEGRADED` after 1–2 failed lookups in a row; `DOWN` after 3+. |
| `feeds.gtfsRt.status` | `DISABLED` without a key; `DOWN` while no snapshot has been fetched; `DEGRADED` when the snapshot is older than 120 s; else `OK`. |
| `feeds.gtfsRt.enabled` | Whether a key is configured. |
| `ageSeconds`, `activeTripUpdates`, `cancelledTrips` | Age and size of the cached GTFS-RT snapshot; `null` when there is none. |

Feed statuses are `OK`, `DEGRADED`, `DOWN`, `DISABLED`. The GTFS-RT snapshot refreshes on demand (at
most once per 60 s, plus a warm-up at start), so an idle server shows it ageing.

### `GET /api/locations`

Autocomplete, proxied from OJP and cached 10 minutes.

| Parameter | Required | Default | Rules |
| --- | --- | --- | --- |
| `query` | yes | — | trimmed, 1–120 code points |
| `limit` | no | `8` | integer 1–20 |
| `near` | no | — | `<lat>,<lon>` WGS84 decimal degrees, no spaces. Coordinate search: `STOP`s only, closest first. 400 with field `near` if malformed or out of range |
| `radiusKm` | no | `2` | non-negative number, clamped to 20; only with `near`. 400 with field `radiusKm` otherwise |

Either `query` or `near` is required. With both, the nearby stops are also filtered by a case-insensitive
name substring of `query`. Example: `curl "localhost:8080/api/locations?near=46.5197,6.6323&limit=3"`.

```json
{ "locations": [ { "id": "ch:1:sloid:1120", "name": "Lausanne", "kind": "STOP",
                   "coordinates": { "lat": 46.5168, "lon": 6.6291 } } ] }
```

`kind` is `STOP` or `ADDRESS`; `coordinates` is `null` when the provider has no position. Pass the
`id` (or the `name`) back as `origin`/`destination`.

### `GET /api/journeys`

| Parameter | Required | Default | Rules |
| --- | --- | --- | --- |
| `origin`, `destination` | yes | — | a stop id (`ch:1:sloid:…`), `lat,lon`, or text; trimmed, 1–120 code points |
| `departure` | no | server now | RFC 3339 with `Z` or numeric offset |
| `arrivalDeadline` | no | `null` | RFC 3339; inclusive (`arrival <= deadline`) |
| `preference` | no | `FASTEST` | `FASTEST` \| `CHEAPEST` \| `SIMPLEST` |
| `fareClass` | no | `2` | `1` or `2` |
| `discount` | no | `NONE` | `NONE` \| `HALF_FARE` |
| `limit` | no | `3` | integer 1–10; caps `alternatives` |

A stop id or a `lat,lon` pair is used directly, with no text resolution; a stop id is first checked
against its departure board, so an id that is not a real stop is `404`. Text is resolved through OJP,
and text that OJP matches only weakly (its own score below 0.5) is `404` instead of a guess.

```json
{
  "request": {
    "origin":      { "input": "ch:1:sloid:7000", "resolvedName": "Bern", "id": "ch:1:sloid:7000" },
    "destination": { "input": "Genève", "resolvedName": "Genève", "id": "ch:1:sloid:1008" },
    "departure": "2026-11-21T20:15:00Z", "arrivalDeadline": "2026-11-21T21:45:00Z",
    "preference": "FASTEST", "fareClass": 2, "discount": "HALF_FARE"
  },
  "status": "DISRUPTION_DETECTED",
  "action": "REROUTE",
  "reasons": [ { "code": "PRIMARY_LEG_DELAYED", "serviceLabel": "RE33", "delayMinutes": 22 },
               { "code": "EARLIER_ARRIVAL", "savedMinutes": 17 },
               { "code": "DEADLINE_MET", "marginMinutes": 31 } ],
  "disruption": { "legIndex": 1, "serviceLabel": "RE33", "from": "Renens VD", "to": "Genève",
                  "scheduledDeparture": "2026-11-21T20:33:00Z", "estimatedDeparture": "2026-11-21T20:55:00Z",
                  "delayMinutes": 22, "cancelled": false, "basis": "REALTIME_ESTIMATE",
                  "confidence": "HIGH", "cause": null },
  "primaryJourney": { /* Journey */ },
  "recommendedJourney": { /* Journey */ },
  "alternatives": [ /* Journey */ ],
  "highlights": { "fastestId": "ID-9910A", "simplestId": "ID-9910A", "cheapestId": null },
  "deadlineSummary": { "feasible": true, "latestFeasibleDeparture": "2026-11-21T20:50:00Z" },
  "meta": { "computedAt": "2026-11-21T20:16:02Z", "modelVersion": "2026-11-duration-v1" }
}
```

Top-level fields:

| Field | Values and meaning |
| --- | --- |
| `request` | Echo of the resolved request. `resolvedName`/`id` are `null` when the provider cannot say (coordinates, mock). |
| `status` | `NO_DISRUPTION` (every leg verified, none disrupted) \| `DISRUPTION_DETECTED` \| `UNKNOWN` (detection failed or could not verify the primary journey, or there is no route). Never a fabricated `NO_DISRUPTION`. |
| `action` | `REROUTE` take `recommendedJourney` \| `STAY` keep `primaryJourney` \| `NONE` no route exists. |
| `reasons` | Array of objects, no prose. Only the numbers that justify each `code` are present. |
| `disruption` | The worst disruption on `primaryJourney`, or `null`. `legIndex` indexes `primaryJourney.legs`. |
| `primaryJourney` | Best journey for `preference` (journeys that meet the deadline first). `null` when `action` is `NONE`. |
| `recommendedJourney` | What to take. Equals `primaryJourney` exactly on `STAY`. `null` on `NONE`, and when a cancelled primary has no replacement (`action` `REROUTE`). |
| `alternatives` | Other candidates, sorted by `preference`, excluding `recommendedJourney`, at most `limit`. A delayed primary stays listed after a `REROUTE`; a cancelled one never does. |
| `highlights` | Ids of the best fastest / simplest / cheapest among the journeys in the payload; `cheapestId` is `null` without fares. |
| `deadlineSummary` | `null` without `arrivalDeadline`. `feasible`: some candidate arrives in time; `latestFeasibleDeparture`: latest scheduled departure that still does. |
| `meta` | `computedAt` and the risk `modelVersion`. |

`reasons[].code`:

| Code | Fields | When |
| --- | --- | --- |
| `PRIMARY_LEG_DELAYED` | `serviceLabel`, `delayMinutes` | A leg of the primary journey is at least 3 min late. |
| `PRIMARY_LEG_CANCELLED` | `serviceLabel` | A leg of the primary journey is cancelled. |
| `SUPPRESSED_STOP` | `serviceLabel` | The primary trip skips the boarding stop. |
| `EARLIER_DEPARTURE_CANCELLED` | `serviceLabel` | The train the traveller wanted (not part of the primary journey) is cancelled; the primary journey is already a replacement. |
| `EARLIER_ARRIVAL` | `savedMinutes` | The replacement arrives that many minutes before the primary's expected arrival. |
| `EXPECTED_FASTER` | `savedMinutes` | On `STAY` with `preference=FASTEST`: nothing is broken, but another candidate is expected (live estimate or measured chronic delay) to arrive that many minutes earlier; it becomes `recommendedJourney`. |
| `FEWER_TRANSFERS` | `savedTransfers` | The replacement has fewer changes. |
| `CHEAPER` | `savedChf` | The replacement is cheaper for the requested class and discount. |
| `DEADLINE_MET` / `DEADLINE_MISSED` | `marginMinutes` | The recommended journey's expected arrival is that many minutes before / after the deadline. |
| `REALTIME_UNAVAILABLE` | — | Status is `UNKNOWN`. |
| `STAY_FASTEST` | — | On `STAY`, no candidate is expected to arrive earlier. |

Detection runs on the top four candidates by `preference` (the primary plus the next three), so a broken
alternative is caught before it can be recommended; deeper candidates carry `verified: false`:

1. A cancelled GTFS-RT trip → `CANCELLED_FLAG`; a skipped boarding stop → `SUPPRESSED_STOP`; a
   measured boarding delay of **3 min or more** → `REALTIME_ESTIMATE`.
2. Where GTFS-RT has no confident match, OJP stop events decide (same 3 min rule; a planned trip
   missing from the live board is `CANCELLED_FLAG`).
3. Delays under 3 minutes are not disruptions (they still appear in `realtime`).

`action` rules: `REROUTE` when the primary has a cancelled leg or a skipped stop, or when a replacement
arrives at least **5 min** earlier than `primaryJourney.realtime.expectedArrival`. Otherwise `STAY`,
with `recommendedJourney` equal to `primaryJourney` (under `preference=FASTEST` a candidate expected
to arrive 5+ min earlier may take the recommendation, with reason `EXPECTED_FASTER`). `NONE` when no route exists. If realtime
detection fails or times out, `status` is `UNKNOWN`, `action` is `STAY`, the scheduled journeys are
returned and every `realtime` value is `null`.

`disruption` fields: `basis` is `CANCELLED_FLAG` \| `SUPPRESSED_STOP` \| `REALTIME_ESTIMATE` \|
`MISSING_FROM_REALTIME`; `confidence` is `HIGH` \| `MEDIUM` \| `LOW`; `delayMinutes` and
`estimatedDeparture` are `null` for a cancellation; `cause` and `severity` come from a SIRI PtSituation
affecting the same line or boarding stop (structured tokens, no prose), `null` when the feed has none.

#### Journey object

Used by `primaryJourney`, `recommendedJourney` and every `alternatives[]` entry.

```json
{
  "id": "ID-9910A",
  "dataStatus": "LIVE_SCHEDULE",
  "verified": true,
  "feasible": false,
  "maxOccupancy": "MEDIUM",
  "schedule": { "departure": "2026-11-21T20:15:00Z", "arrival": "2026-11-21T21:13:00Z", "durationMinutes": 58, "transfers": 1 },
  "realtime": { "expectedDeparture": null, "departureDelayMinutes": null,
                "expectedArrival": "2026-11-21T21:35:00Z", "arrivalDelayMinutes": 22,
                "source": "GTFS_RT", "sampleCount": null },
  "risk": { "corridorIncidentRate": 0.0412, "typicalIncidentDurationMinutes": 145,
            "source": "CORRIDOR", "basis": "DAY_WINDOW_953", "confidence": "HIGH", "level": "MEDIUM" },
  "fares": { "status": "PRICED", "selectedChf": 12.6, "product": "Streckenbillett", "source": "NOVA",
             "catalogChf": { "secondClassFull": 25.2, "secondClassHalfFare": 12.6, "firstClassFull": 44.0, "firstClassHalfFare": 22.0 } },
  "legs": [
    { "mode": "TRANSIT", "origin": "Lausanne", "destination": "Renens VD",
      "departure": "2026-11-21T20:15:00Z", "arrival": "2026-11-21T20:21:00Z",
      "expectedDeparture": "2026-11-21T20:16:00Z", "departureDelayMinutes": 1,
      "expectedArrival": null, "arrivalDelayMinutes": null,
      "serviceLabel": "S1", "departurePlatform": "5", "arrivalPlatform": "2",
      "occupancy": { "firstClass": "LOW", "secondClass": "MEDIUM" } }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `id` | Opaque identifier, unique within one response. |
| `dataStatus` | `LIVE_SCHEDULE` or `MOCK`; label `MOCK` as demo data in any UI. |
| `verified` | Whether the detector checked this journey against live data; `false` means its realtime values are expectations, not a live verdict. |
| `feasible` | `null` without `arrivalDeadline`; else whether the expected arrival (scheduled if unknown) meets it. |
| `maxOccupancy` | Highest `LOW` < `MEDIUM` < `HIGH` over the transit legs for the requested `fareClass`; `null` when no leg reports one. |
| `schedule` | Scheduled values. **Never mutated** by realtime. |
| `realtime.source` | `GTFS_RT` or `OJP` (live), `DELAY_MODEL` (statistical median for that station/line and hour, with `sampleCount`), `NONE` (everything `null`). The model is used only when nothing live is known. |
| `realtime.expectedDeparture`, `departureDelayMinutes` | From the first transit leg. |
| `realtime.expectedArrival`, `arrivalDelayMinutes` | From the final transit leg, else the worst delay of any transit leg. |
| `risk.corridorIncidentRate` | Share of days in the history window on which the corridor had an active unplanned disruption (a 953-day frequency, not a per-train chance). `null` when unknown. |
| `risk.typicalIncidentDurationMinutes` | Median incident length. **Not the traveller's delay.** |
| `risk.source` | `CORRIDOR`, `BASELINE` (nationwide hour baseline — weak), `NONE`. |
| `risk.basis` | `DAY_WINDOW_<days>` for a corridor, `HOURLY_BASELINE` for the baseline, `null` when unknown. |
| `risk.confidence` | `HIGH` \| `MEDIUM` \| `LOW` \| `VERY_LOW` \| `null`. |
| `risk.level` | Coarse band of `corridorIncidentRate`: `< 0.02` `LOW`, `< 0.06` `MEDIUM`, else `HIGH`; `null` when unknown. |
| `fares.status` | `PRICED` (price for the requested class and discount known), `UNAVAILABLE` (lookup failed, or the quote lacks that number), `DISABLED` (fare lookup off). Show "unavailable", never 0. |
| `fares.selectedChf` | Price for the requested `fareClass` and `discount`. |
| `fares.catalogChf` | All four prices; each may be `null`; the object is `null` when there is no quote. |
| `legs[]` | `mode` `TRANSIT` \| `WALK`; `serviceLabel`, platforms and times as the provider gave them. Walk legs may use raw coordinates as origin/destination. |
| `legs[].expected*`, `*DelayMinutes` | Per-leg realtime. A leg value is set only where a source reported it (OJP estimate, or the measured boarding delay from GTFS-RT/OJP stop events); otherwise `null`. |
| `legs[].occupancy` | `null` for walk legs. Transit legs: `firstClass`/`secondClass` = `LOW` (many seats), `MEDIUM` (few seats), `HIGH` (standing room only, full), or `null` (unknown). |

### `GET /api/model`

Static metadata of the artifacts the running instance loaded. Carries `Cache-Control: public, max-age=300`.
It has no live counters; those are in `/health`.

```json
{ "riskModel": { "version": "2026-11-duration-v1", "generatedAt": "2026-11-21T18:28:47Z", "source": "ARTIFACT", "status": "OK",
    "coverage": { "from": "2024-03-03", "to": "2026-10-11", "windowDays": 953, "scope": "SBB extern-liveticker incidents, all of Switzerland" },
    "counts": { "keys": 15, "corridors": 142, "baselineHours": 24, "byLineHourCells": 15 } },
  "delayModel": { "version": "rt-20261121-2200", "status": "OK", "counts": { "portions": 24, "lines": 4 } } }
```

`status` is `OK`, `STALE` (risk artifact older than its freshness window) or `MISSING` (not loaded or
unreadable; the other fields are then `null`/`0`). `riskModel.source` is `NONE` when `RISK_SOURCE=none`,
else `ARTIFACT`. `coverage` is `null` when the artifact has none.

### Errors

Every failure uses one envelope; `field` names the offending query parameter, or is `null`.

```json
{ "error": { "code": "INVALID_REQUEST", "message": "Parameter 'limit' must be an integer from 1 to 10", "field": "limit" } }
```

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | Missing, blank, too long, duplicate, malformed or out-of-range parameter (`field` is set). |
| 404 | `LOCATION_NOT_FOUND` | `origin` or `destination` resolves to nothing (`field` is `origin` or `destination`). |
| 502 | `UPSTREAM_ERROR` | Provider failed or answered unusably. |
| 503 | `UPSTREAM_RATE_LIMITED` | Provider throttling. |
| 504 | `UPSTREAM_TIMEOUT` | Provider exceeded its time bound (8 s). |

Provider bodies, query text and credentials never appear in responses or logs.

## Layout

```
backend/
  src/main/kotlin/com/cffplanb/backend/
    api/        routes, DTOs, request validation, error mapping
    service/    search orchestration, ranking, risk + delay models, disruption detection and rerouting
    provider/   OJP client, mock provider, NOVA fares, resilient wrapper (cache, breaker)
    gtfs/       GTFS-Realtime fetch, protobuf decoding, trip index
    config/     env config, plugins (JSON, CORS, logging)
  src/main/resources/
    risk/predictions.json       historical disruption risk (corridors, hour baseline)
    realtime/realtime_delays.json  measured lateness (station/hour, line/hour)
```

## Honesty rules baked into the code

- Unmeasured means `null`, never `0`.
- `typicalIncidentDurationMinutes` is an incident length, not a traveller delay.
- `corridorIncidentRate` is a frequency over the history window (953 days), not a per-train chance.
- `scheduled` values are never changed by realtime; expected values live beside them.
- Detection that cannot run is `UNKNOWN`, never `NO_DISRUPTION`.
- Demo data is always `MOCK`; live data is always `LIVE_SCHEDULE`.
- Artifacts that are missing or unreadable degrade the response, never crash it.
