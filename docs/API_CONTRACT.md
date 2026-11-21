# HTTP API contract

This is the v1 implementation baseline for the browser and Ktor API. The frontend and backend owners review this contract together at kickoff. The routes and examples describe planned behavior. Provider selection, field mapping and runtime behavior still require verification. [Master plan](MASTER_PLAN.md) · [Architecture](ARCHITECTURE.md)

## Requests and responses

Requests use HTTP locally and HTTPS in public deployments. The API returns UTF-8 JSON with `Content-Type: application/json`. Field names are lower camel case and enum values are uppercase. Clients should ignore additional response fields and handle unrecognized enum values without crashing or claiming a known provenance.

| Method and path | Purpose | Success |
| --- | --- | --- |
| `GET /health` | Check that the API can serve requests. The handler makes no provider request. | `200` with `HealthResponse` |
| `GET /api/journeys` | Find eligible timetable itineraries for an origin and destination. | `200` with `JourneySearchResponse` |

### Health response

```json
{
  "status": "ok"
}
```

### Journey search query

| Parameter | Required | Validation and behavior |
| --- | --- | --- |
| `origin` | Yes | Station or stop search text. Trim surrounding whitespace and accept 1–120 Unicode code points. |
| `destination` | Yes | Same rules as `origin`. |
| `departure` | No | Earliest eligible scheduled departure. Accept an RFC 3339 timestamp with `Z` or an explicit numeric UTC offset. An omitted value uses the request-time instant from the backend clock. |

Encode each query value, including spaces, accented characters and any literal `+` in an offset. Empty required values, invalid timestamps, duplicate parameters and unsupported parameters return `400 INVALID_REQUEST`. The API expects stop search text rather than an app-specific station identifier. How the provider resolves that text belongs to the transport adapter.

Use this path for a fixed departure in a local fixture check.

```text
/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&departure=2026-11-21T16%3A00%3A00%2B01%3A00
```

Use a currently valid departure, or omit `departure`, when verifying the live provider.

### Journey search response

`JourneySearchResponse` has two required fields, `request` and `journeys`. `request` describes the effective search and contains `origin` and `destination` as trimmed strings, `earliestDeparture` as an offset timestamp, and `preference` as `FASTEST`. `arrivalDeadline` is null in v1. `journeys` is an ordered array of `Journey` objects and can be empty.

| Journey field | Type | Meaning |
| --- | --- | --- |
| `id` | Nonempty string | Opaque stable itinerary identifier derived from provider identity or a deterministic adapter mapping. |
| `departure`, `arrival` | Offset timestamp strings | Scheduled first departure and last arrival. |
| `durationMinutes` | Nonnegative integer | Elapsed time between the scheduled endpoints, in whole minutes rounded down. |
| `transfers` | Nonnegative integer | Vehicle changes in the itinerary. Walking segments alone add zero transfers. |
| `legs` | Nonempty array of `JourneyLeg` | Ordered segments of the accepted itinerary. |
| `dataStatus` | `LIVE_SCHEDULE` or `MOCK` | Provider-backed schedule or explicit test/demo source. `LIVE_SCHEDULE` carries no guarantee of live disruption, delay or platform updates. |
| `predictedDelayMinutes` | Integer or null | Reserved for a validated delay estimate. Null in v1. |
| `lateArrivalProbability` | Number in `[0, 1]` or null | Reserved for a calibrated probability against a known deadline. Null in v1. |
| `estimatedFareChf` | Nonnegative number or null | Reserved for sourced comparable fares in CHF. Null in v1. |

| Journey leg field | Type | Meaning |
| --- | --- | --- |
| `mode` | `TRANSIT` or `WALK` | Normalized leg type. |
| `origin`, `destination` | Nonempty strings | Stop or endpoint display names. |
| `departure`, `arrival` | Offset timestamp strings | Scheduled times at the leg endpoints. |
| `serviceLabel` | String or null | Provider-supplied service label when available. |
| `departurePlatform`, `arrivalPlatform` | Strings or null | Platform labels as supplied. Preserve letters and leading zeroes. |

Nullable fields may be omitted by a serializer. Clients interpret a missing nullable field like `null`. All other fields are required. Compare timestamps as instants and display them in `Europe/Zurich`. A leg's arrival is at or after its departure, and every leg after the first starts at or after the previous leg's arrival. The journey endpoints match the first and last leg times as instants.

Accept provider itineraries departing at or after `earliestDeparture`. Use their validated connections and exclude known canceled journeys from the normal results when the adapter has confirmed cancellation data. Sort by earliest scheduled arrival, then fewer transfers, then earlier departure, then lexicographic ID. The first entry is the recommendation. Return `200` with `journeys: []` when a valid provider response has no eligible itineraries. Report provider failures through the error envelope.

### Example success fixture

This illustrative response is suitable for client development and contract checks. Its timestamps, service label and platforms are synthetic. Keep `MOCK` on every demo result and make the provenance visible in the UI.

```json
{
  "request": {
    "origin": "Lausanne",
    "destination": "Genève",
    "earliestDeparture": "2026-11-21T16:00:00+01:00",
    "arrivalDeadline": null,
    "preference": "FASTEST"
  },
  "journeys": [
    {
      "id": "mock-direct-1",
      "departure": "2026-11-21T16:10:00+01:00",
      "arrival": "2026-11-21T17:00:00+01:00",
      "durationMinutes": 50,
      "transfers": 0,
      "legs": [
        {
          "mode": "TRANSIT",
          "origin": "Lausanne",
          "destination": "Genève",
          "departure": "2026-11-21T16:10:00+01:00",
          "arrival": "2026-11-21T17:00:00+01:00",
          "serviceLabel": "Demo rail service",
          "departurePlatform": "3A",
          "arrivalPlatform": null
        }
      ],
      "predictedDelayMinutes": null,
      "lateArrivalProbability": null,
      "estimatedFareChf": null,
      "dataStatus": "MOCK"
    }
  ]
}
```

### Example empty search

This illustrates the empty response shape. It makes no claim about service on the example route or date.

```json
{
  "request": {
    "origin": "Lausanne",
    "destination": "Genève",
    "earliestDeparture": "2026-11-21T16:00:00+01:00",
    "arrivalDeadline": null,
    "preference": "FASTEST"
  },
  "journeys": []
}
```

### Error response

`ErrorResponse` requires an `error` object containing string fields `code` and `message`. The code determines client behavior. Message text is safe for display and can change. Keep raw upstream responses, stack traces, credentials and internal URLs out of the response.

| Status | Code | Trigger |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | Missing or invalid query parameter. |
| `502` | `UPSTREAM_ERROR` | Provider call failed or returned an unusable response. |
| `503` | `UPSTREAM_RATE_LIMITED` | The provider reports throttling. |
| `504` | `UPSTREAM_TIMEOUT` | The provider did not respond within the configured timeout. |
| `500` | `INTERNAL_ERROR` | Unexpected application failure. |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "Departure must include a UTC offset."
  }
}
```

The frontend handles network or CORS failures as request failures even when there is no JSON body. Demo fallback is an explicit user or environment choice and retains its demo label.

## Changes and future endpoints

Use the same baseline JSON examples in frontend decoding, backend serialization and integration smoke checks. For additive output fields, define default behavior for clients that encounter missing or unfamiliar values. Any change to required fields, types, null semantics, ranking or supported queries needs review from frontend, backend and integration owners. Use a coordinated migration or new version for breaking changes. [Shared interfaces](../shared/INTERFACES.md) · [Development workflow](DEVELOPMENT_WORKFLOW.md)

Deadline and preference requests are future contract work. `POST /api/recommendations` and `GET /api/places?stationId=…` are suggested later interfaces and have no v1 schema. Define their eligibility, provenance and failure behavior before implementation. [Roadmap](ROADMAP.md)


## Challenge extensions in design

The [challenge plan](CHALLENGES.md) requires reviewing any extraction or disruption endpoints before implementation. Agree on image size and media types, timeout and fallback, candidate fields and unknowns, and retention behavior for screenshot extraction. For disruption recovery, agree on affected leg identifiers, source/time, unknown-match behavior, deadline impact, and explicit next-action or no-feasible-option outcomes. Existing v1 examples remain the search baseline.

## Implemented additive extensions (v1-compatible)

These fields and endpoints were added during implementation. All are additive: a client written against the tables above keeps working, and clients must treat every new field as optional.

### Additional endpoints

| Method and path | Purpose | Success |
| --- | --- | --- |
| `GET /api/locations?query=<text>` | Stop/address autocomplete for the search box, proxied from the provider and cached. Query 1–120 code points. | `200` with `{ "locations": [ { "id", "name", "kind", "x", "y" } ] }` |
| `GET /api/alternatives?from=&to=&notBefore=[&arrivalDeadline=]` | Rescue search from an intermediate stop after a disruption. Same validation, ranking and journey shape as `/api/journeys`. | `200` with `{ "request", "alternatives", "latestFeasibleDeparture", "noFeasibleRoute", "advice" }` |
| `GET /api/model` | Provenance of the risk artifact actually loaded by the running instance. No provider call. | `200` with `{ "modelVersion", "generatedAt", "coverage", "source", "keysLoaded", "status", "corridorsLoaded", "baselineLoaded", "byLineHourCells", "corridorKeysSample" }` |

### Additional journey fields

| Field | Type | Meaning |
| --- | --- | --- |
| `feasible` | Boolean | Present when `arrivalDeadline` was supplied. True when scheduled arrival is at or before the deadline. |
| `reasons` | Array of strings, at most 3 | Short explanations derived from real data only: `earliest arrival`, `fewest transfers`, `arrives N min before your deadline`, `corridor risk N.N%`. |
| `riskSource` | `corridor` \| `baseline` \| null | Which artifact table produced the risk values. |
| `riskBasis` | `corridor_duration` \| `baseline_duration` \| `corridor_delay` \| null | What the numbers describe. `*_duration` means the estimate is the typical length of a disruption on that corridor, not a delay a traveller will experience. |
| `riskConfidence` | `low` \| `medium` \| `high` \| `very_low` \| null | Sample-size band of the matched artifact row. |
| `disruptionDurationMin` | Integer or null | Typical disruption length in minutes for the matched corridor or hour baseline. Not a predicted delay. |

`predictedDelayMinutes` remains the field for a validated delay estimate and stays null while the only available signal is disruption duration. It is populated only when an artifact row carries `delayEstimateMin`.

### Additional response fields

`latestFeasibleDeparture` (offset timestamp or null) is the latest departure among journeys that still meet `arrivalDeadline`; `noFeasibleRoute` (Boolean) is true when a deadline was supplied and nothing qualifies; `advice` is one sentence, at most 240 characters, built only from data present in the response, and states explicitly when risk is unknown.

Risk data comes from an offline artifact built from public SBB liveticker incidents (2024-2026, 142 corridors, ~91% incident coverage). Coverage, model version and confidence limits are reported by `GET /api/model` and must be shown or linked wherever a modelled number is displayed.
