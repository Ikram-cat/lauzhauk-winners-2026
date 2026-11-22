# ALT — Backend API

All endpoints are `GET` JSON, read-only, no authentication, no cookies.
Feature status lives in [FEATURES.md](FEATURES.md); full response shapes in
[docs/API_CONTRACT.md](../docs/API_CONTRACT.md).

**Base URL** = the service origin, no `/api` prefix:

- Production: `https://lauzhauk-winners-2026-production.up.railway.app`
- Local: `TRANSPORT_PROVIDER=mock PORT=8080 ./gradlew run` (mock needs no keys; `TRANSPORT_PROVIDER=ojp` wants `OJP_API_KEY`, optional `GTFS_RT_API_KEY`)

Percent-encode every value (accents, `:` in timestamps): `Gen%C3%A8ve`, `2026-11-21T23%3A00%3A00%2B01%3A00`.

## Endpoints

### `GET /health`
Feed status. No arguments.

### `GET /api/locations`
Station/address autocomplete, or nearest stops to a position.

| Argument | Required | Rules |
| --- | --- | --- |
| `query` | yes — unless `near` is given | text, ≤ 120 characters |
| `near` | yes — unless `query` is given | `lat,lon` (WGS84 decimal degrees, comma-separated, no spaces) |
| `radiusKm` | no | number, default 2, max 20 |
| `limit` | no | integer 1–20, default 8 |

### `GET /api/journeys`
Search + realtime disruption detection + reroute recommendation in one call.

| Argument | Required | Rules |
| --- | --- | --- |
| `origin` | yes | stop id, `lat,lon`, or free text, ≤ 120 characters |
| `destination` | yes | same as `origin` |
| `departure` | no | RFC 3339 with offset; default = now |
| `arrivalDeadline` | no | RFC 3339 with offset |
| `preference` | no | `FASTEST` \| `CHEAPEST` \| `SIMPLEST` (default `FASTEST`) |
| `fareClass` | no | `1` or `2` (default `2`) |
| `discount` | no | `NONE` \| `HALF_FARE` (default `NONE`) |
| `limit` | no | integer 1–10, default 3 |

Returns: `status` (`NO_DISRUPTION` \| `DISRUPTION_DETECTED` \| `UNKNOWN`), `action` (`REROUTE` \| `STAY` \| `NONE`), `reasons[]` (structured codes with the numbers that justify them), `disruption` (with `cause`/`severity` tokens), `primaryJourney`, `recommendedJourney`, `alternatives[]`, `highlights`, `deadlineSummary`, `meta`.

#### Realtime delay layers

`realtime` on the journey, and the same fields inline on every leg, carry two
separate delay layers. They are never summed into one field.

| Field | Meaning |
| --- | --- |
| `departureDelayMinutes` / `arrivalDelayMinutes` | Known live delay (OJP / GTFS-RT). Unchanged. |
| `estimatedDepartureDelayMinutes` / `estimatedArrivalDelayMinutes` | Predicted delay learned from measured historical actuals (Ist-Daten medians). Signed — early running is negative. |
| `estimatedSource` | `HISTORICAL_ACTUALS` \| `LIVE_SAMPLE` \| `NONE` |
| `estimatedSamples` | Measurements behind the estimate; `null` when there is none |
| `projectedDeparture` / `projectedArrival` | Scheduled time + known delay + estimated delay |

Journey level mirrors the legs: first transit leg for departure, last transit leg
for arrival. The estimated component is clamped to `[-10, +30]` minutes before it
is applied. `ESTIMATE_OVERLAP=known_wins` zeroes the estimated component on legs
that already carry a known delay; the default is additive.

```bash
curl "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&limit=1"
```

```json
"realtime": {
  "expectedDeparture": "2026-11-22T10:45:00+01:00",
  "departureDelayMinutes": 3,
  "expectedArrival": "2026-11-22T11:39:00+01:00",
  "arrivalDelayMinutes": 3,
  "source": "GTFS_RT",
  "sampleCount": 41,
  "estimatedDepartureDelayMinutes": 2,
  "estimatedArrivalDelayMinutes": -1,
  "estimatedSource": "HISTORICAL_ACTUALS",
  "estimatedSamples": 214,
  "projectedDeparture": "2026-11-22T10:47:00+01:00",
  "projectedArrival": "2026-11-22T11:41:00+01:00"
}
```

A leg with no known delay and no estimate is `null` in every one of these fields
and `estimatedSource` is `NONE` — never `0`. A zero means a measured on-time
train, nothing else.

These fields are additive and optional. They are documented ahead of the rollout:
the same base URL serves them the moment the delay-estimate model ships and the
service is redeployed (`railway up` from the repository root). Until that build is
live, clients must treat them as absent. No existing field changes name, type or
meaning.

### `GET /api/model`
Static artifact metadata (cacheable). No arguments.

### `POST /api/disruptions/outlook` · `POST /api/journeys/impact` *(proposal)*
Remaining-disruption duration estimates. JSON body; see
[docs/API_CONTRACT.md](../docs/API_CONTRACT.md#disruption-outlook-proposal). Answer
`UNAVAILABLE` unless `DURATION_ARTIFACT_PATH` is configured.

## Rules that hold everywhere

- Unknown arguments are ignored; a repeated known argument → 400.
- Every error is one envelope: `{"error":{"code","message","field"}}`
  - `INVALID_REQUEST` 400 (`field` = the offending argument)
  - `LOCATION_NOT_FOUND` 404 (`field` = `origin` or `destination`)
  - `UPSTREAM_TIMEOUT` 504 · `UPSTREAM_RATE_LIMITED` 503 · `UPSTREAM_ERROR` 502
- Never invented data: unknown numbers are `null`, not 0.

## Quick checks

```bash
curl "$BASE/health"
curl "$BASE/api/locations?query=Lausanne"
curl "$BASE/api/locations?near=46.5197,6.6323&limit=3"
curl "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&limit=3"
./scripts/smoke.sh "$BASE"   # 22 deploy checks
```
