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
| `originSloid`, `destinationSloid` | Strings or null | Additive, optional. Stop-place sloid of the boarding and alighting stop (`ch:1:sloid:<n>`, platform suffix removed) when the provider supplies one. The mock provider leaves them null. Clients that do not know the fields ignore them. |

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

## Disruption outlook (proposal)

**Status: proposal.** This endpoint is implemented on the `prediction` branch, and the frontend, backend and integration owners have not yet reviewed it. Its fields may change until that review. The `/health` and `/api/journeys` contracts are unchanged.

`POST /api/disruptions/outlook` estimates how long an ongoing disruption will last and whether a detour is worth taking. The estimate comes from an offline model trained on the SBB public liveticker (2024–2026). The backend loads it at startup as a JSON artifact (see [analytics README](../analytics/README.md) and [backend README](../backend/README.md)). It is an estimate, not an official SBB forecast.

### Request

`Content-Type: application/json`.

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `disruption.startedAt` | RFC 3339 timestamp with offset | Yes | When the disruption was first recorded. |
| `disruption.reasonCode` | String or null | No | SBB cause code, such as `EG_008` for an overhead-line problem. Unknown or missing codes are allowed. |
| `disruption.durationType` | String or null | No | SBB duration type, such as `UNTIL` or `UNTIL_APPROX_DATE_TIME`. |
| `disruption.announcedEndAt` | RFC 3339 timestamp or null | No | End currently announced by SBB. Null means no announcement. |
| `disruption.updatesSoFar` | Integer ≥ 0, default `0` | No | Number of earlier published versions of this disruption. |
| `disruption.summaryEn` | String or null | No | English summary, such as `Interrupted service: Lausanne`. Used for the interruption and restriction keywords. |
| `observedAt` | RFC 3339 timestamp with offset | No | Instant of the estimate. Defaults to the server clock. Pass it explicitly when replaying. |
| `detourExtraMinutes` | Integer ≥ 0 or null | No | Extra travel time of the best alternative, in minutes. Without it, `advice.code` is `UNKNOWN`. |

```json
{
  "disruption": {
    "reasonCode": "EG_008",
    "durationType": "UNTIL",
    "startedAt": "2026-11-21T07:40:00+01:00",
    "announcedEndAt": "2026-11-21T09:00:00+01:00",
    "updatesSoFar": 2,
    "summaryEn": "Interruption between Lausanne and Genève"
  },
  "observedAt": "2026-11-21T08:00:00+01:00",
  "detourExtraMinutes": 35
}
```

### Response

`200` for every valid request, with or without a model.

| Field | Type | Meaning |
| --- | --- | --- |
| `status` | `AVAILABLE` or `UNAVAILABLE` | `UNAVAILABLE` when the backend has no model loaded: `DURATION_ARTIFACT_PATH` unset, file missing, invalid, or of an unsupported version. |
| `remainingMinutes.p50` | Integer or null | Median remaining duration, rounded to the nearest minute. |
| `remainingMinutes.pessimistic` | Integer or null | Pessimistic remaining duration, rounded up: the 0.85 quantile model plus a split-conformal offset fitted on the most recent training incidents. On the test period it covers 83.1% of cases (73.7% before calibration). It is not a guaranteed probability, since durations drift over time. It is always ≥ `p50`. |
| `expectedEndAt.p50`, `expectedEndAt.pessimistic` | RFC 3339 timestamp or null | `observedAt` plus the rounded minutes, using the offset of `observedAt`. |
| `officialRemainingMinutes` | Integer or null | Minutes from `observedAt` to `announcedEndAt`, floored at 0. Null without an announcement. Provided even when `status` is `UNAVAILABLE`. |
| `advice.code` | `TAKE_DETOUR`, `DETOUR_IF_DEADLINE`, `WAIT` or `UNKNOWN` | `TAKE_DETOUR` if detour < p50; `DETOUR_IF_DEADLINE` if detour < pessimistic; otherwise `WAIT`. `UNKNOWN` without a model or without `detourExtraMinutes`. Thresholds use unrounded minutes. The UI localizes the code. |
| `advice.detourExtraMinutes` | Integer or null | Echo of the request field. |
| `provenance` | Object or null | `kind` (`ESTIMATED`), `modelVersion`, `pessimisticQuantile`, and `pessimisticCoverage` (measured on the test split, after calibration). Null when `UNAVAILABLE`. |
| `explanation` | Object or null | Values for the cause, computed on the model's training data only, for the UI to format in each language. `reasonCode`; `scope`: `REASON`, or `ALL_REASONS` when the cause has fewer than 5 past incidents or is unknown; `similarIncidents`; `medianDurationMinutes`, the median total duration; `announcementsCompared`; `medianEndMinusAnnouncedMinutes`, the median of actual end minus announced end over every announcement (positive means later than announced); `source`: `TRAINING_DATA`. Null when `UNAVAILABLE`. |

```json
{
  "status": "AVAILABLE",
  "remainingMinutes": {
    "p50": 228,
    "pessimistic": 640
  },
  "expectedEndAt": {
    "p50": "2026-11-21T11:48:00+01:00",
    "pessimistic": "2026-11-21T18:40:00+01:00"
  },
  "officialRemainingMinutes": 60,
  "advice": {
    "code": "TAKE_DETOUR",
    "detourExtraMinutes": 35
  },
  "provenance": {
    "kind": "ESTIMATED",
    "modelVersion": "duration-v1",
    "pessimisticQuantile": 0.85,
    "pessimisticCoverage": 0.8309859154929577
  },
  "explanation": {
    "reasonCode": "EG_008",
    "scope": "REASON",
    "similarIncidents": 52,
    "medianDurationMinutes": 328,
    "announcementsCompared": 331,
    "medianEndMinusAnnouncedMinutes": 46,
    "source": "TRAINING_DATA"
  }
}
```

```json
{
  "status": "UNAVAILABLE",
  "remainingMinutes": null,
  "expectedEndAt": null,
  "officialRemainingMinutes": 60,
  "advice": { "code": "UNKNOWN", "detourExtraMinutes": 35 },
  "provenance": null,
  "explanation": null
}
```

### Errors

`400 INVALID_REQUEST`, using the error envelope above, when:
- the body is not valid JSON or lacks `disruption.startedAt`;
- a timestamp is not RFC 3339 with an offset;
- `startedAt` is after `observedAt`;
- `updatesSoFar` or `detourExtraMinutes` is negative.

A missing model is never an error. The browser must allow `POST`, which the backend's CORS configuration now does.

### Limits and provenance

- The model was trained on past liveticker incidents whose end is known. Its P50 error on the test period was 97.9 minutes, against 112.7 for the official announcement. Long or unusual incidents remain hard to predict.
- The backend has no verified live disruption feed. Until one exists, clients send disruption facts themselves, and demo data must be labelled.
- Historical replays for the demo come from `python -m sbb_duration.replay`. That command writes `analytics/output/replay/replay-0N.json` locally; the files are not committed because they contain source rows. Each file has `dataStatus: "MOCK"` and `kind: "HISTORICAL_REPLAY"`. Each `steps[].outlook` object holds `status`, `remainingMinutes`, `expectedEndAt` and `advice` from the response shape above. `officialRemainingMinutes`, `actualRemainingMinutes` and the model provenance sit at step or file level.

## Multi-source incident timeline (proposal)

**Status: proposal.** This is a file format, not an HTTP route. `python -m sbb_duration.sources` produces it in `analytics/output/timelines/`, where the files stay local and uncommitted. The frontend can render it, and the backend can later serve it or rebuild it from live feeds. Frontend, backend and integration owners have not yet reviewed it.

Each file joins one past incident across three sources:
- **Public liveticker:** announcements and lifecycle.
- **VIA event messages:** affected stations and lines, and when a travel recommendation and a replacement bus were first published.
- **Internal liveticker:** operational messages, with the useful facts extracted by keyword rules.

The sources are linked as follows:
- VIA `alea_fall_id` equals the public incident id.
- An internal case matches a VIA event when they share the same cause code and were created within 30 minutes of each other. Matching is one-to-one, nearest first.

On the September 2026 exports, 69 of 137 internal cases link to VIA, and 10 incidents are present in all three sources. `index.json` lists them and names the demo incidents and explains why (`demo.why`): primary `2195005188970` (Renens VD – Morges, 2026-09-17; real resumption 21:04 per the internal liveticker, public end auto-closed at 00:00) and backup `2195005195846` (Zürich Stadelhofen – Stettbach, 2026-09-25; public end at 20:30 matches the end of operations).

Every file is `dataStatus: "MOCK"`, `kind: "HISTORICAL_MULTI_SOURCE_TIMELINE"`, `schemaVersion: 1`. It is a replay, not live data.

### File structure

| Field | Meaning |
| --- | --- |
| `incident` | `id`, `summaryEn` (last public summary), `reasonCode`, `reasonLabel`, `startedAt`, and the end fields below. |
| `incident.publicEndAt` | First public `ENDED` version, or VIA `zeitpunkt_globale_endmeldung` when the public feed has none. Where VIA has a global end (8 of the 10 incidents), it equals the public end. |
| `incident.actualResumptionAt` | Real resumption reported by the internal liveticker (for example "Premiers trains …", "concept d'exploitation terminé") when it precedes `publicEndAt`. Otherwise null. Partial reopenings (one track, trains "with measures") and proposals do not count. |
| `incident.actualEndAt`, `actualEndSource`, `actualDurationMinutes` | Real end: `actualResumptionAt` when set (`INTERNAL_RESUMPTION`), else `publicEndAt` (`PUBLIC_END`). Show it as the "real resumption" when the source is internal. |
| `comparison` | Mean absolute error, against `actualEndAt`, of the SBB announcement and of the model P50, over the moments where both exist. |
| `explanation` | Same object as in the outlook response. It is computed on the training split of the evaluation models, so it never includes the replayed incident. |
| `linkage` | How the VIA and internal records were joined, the creation-time delta, and the number of internal messages used and skipped. |
| `detourExtraMinutes` | Hypothetical detour cost used for every `advice` (default 60). |
| `model` | Provenance of the predictions: the evaluation models, trained before 2026-04-06, never saw these incidents. |
| `affected` | Union over the incident of `stations`, `sections` and `lines`. This is what a client checks a journey against. |
| `notifications` | Number of moments with `notify.value = true`. |
| `moments[]` | One entry per event from any source, in time order, up to the actual end. Each carries the state known at that instant. |

Fields of each `moments[]` entry:

| Field | Meaning |
| --- | --- |
| `at` | Instant, RFC 3339 with the Swiss offset. |
| `trigger` | `source` (`PUBLIC_LIVETICKER`, `VIA` or `INTERNAL_LIVETICKER`) and `event` (`PUBLIC_VERSION`, `PUBLIC_ENDED`, `VIA_UPDATE` or `INTERNAL_MESSAGE`). |
| `public` | Latest public version, or null before the first one: `lifecycle`, `summaryEn`, `durationType`, `announcedEndAt`, `versionAt`, `officialRemainingMinutes`. |
| `outlook` | The same fields as the [disruption outlook](#disruption-outlook-proposal) response: `status`, `remainingMinutes`, `expectedEndAt` and `advice`, computed at `at`. `status: "UNAVAILABLE"` comes with a `reason`: `NO_PUBLIC_ANNOUNCEMENT_YET`, `SERVICE_RESUMED` (after `actualResumptionAt`) or `ENDED`. |
| `affected` | Latest VIA state: `stations`, `sections`, `lines`. Null before the first VIA record. |
| `via` | `travelRecommendationPublishedAt` and `replacementBusPublishedAt`, the first VIA publication times, or null. |
| `internal` | Cumulative facts from the internal liveticker: `replacementBus` (`firstAt`, `busCount` = the most buses mentioned in one message), `stops[]` (station, `firstMentionedAt`, `times[]`), `resumptionForecast` (`known`, `at`, `messageAt`), `serviceResumedAt`. |
| `message` | Only for `INTERNAL_MESSAGE`: `senderRole` (`TRAFFIC_CONTROL` or `STATION`), sanitized `text`, and the `facts` extracted from that message alone. |
| `actualRemainingMinutes` | Hindsight, for the demo only, measured to `incident.actualEndAt`. Never show it as a prediction. |
| `notify` | `value` and `reasons[]` of `{code, detail}`. When the rate limit holds a notification back, `withheld` lists its codes. |

A station object has these fields:
- `uic`: integer UIC code, as in VIA;
- `sloid`: `ch:1:sloid:<UIC − 8500000>`, or null outside Switzerland;
- `name` and `abbreviation`, from the service-points dataset;
- `inServicePoints`;
- `roles`: `DISRUPTION_POINT` for the VIA disruption location (`stoerungs_ort1/2`), `LINE_FROM` and `LINE_TO` for the ends of the affected line (`stoerungs_linie_von/bis`).

A section is `{from, to}` in UIC codes. `lines` are VIA line numbers such as `IC1` or `R9`.

### Notify rules

`notify.value` is true when at least one of these applies:

| Code | Rule |
| --- | --- |
| `FIRST_DETECTION` | First moment of the incident, in any source. |
| `ADVICE_CHANGED` | `outlook.advice.code` differs from the last notified (or first known) code, **and** either the new code is seen at two successive moments, or the detour cost is more than 10 minutes from the prediction that was crossed (P50 between `TAKE_DETOUR` and `DETOUR_IF_DEADLINE`, pessimistic between `DETOUR_IF_DEADLINE` and `WAIT`). `UNKNOWN` is ignored. |
| `ANNOUNCED_END_SHIFTED` | The public `announcedEndAt` moved by more than 15 minutes since the last value that triggered a notification. |
| `REPLACEMENT_BUS_ANNOUNCED` | First moment with a replacement bus, from VIA or from an internal message. |

**Rate limit:** at most one notification every 15 minutes, except when it includes `REPLACEMENT_BUS_ANNOUNCED`. A withheld reason keeps its baseline, so it is notified at the next allowed moment if it still holds.

These rules remove the flip-flops when the detour cost is close to the P50. On the primary demo incident, the four advice changes between 17:46 and 18:27 now produce one notification: at 17:55, where the detour is 12 minutes from the P50 and the advice then holds for 35 minutes. Nothing is notified at or after `incident.actualEndAt` (`notify.afterRealEnd: true`). The timeline has 6 notifications in all.

### Checking a journey against an incident

The backend implements this check in `POST /api/journeys/impact` (see below).

A journey is affected when one of its legs boards, alights or passes through a station in `affected.stations`, or runs on a line listed in `affected.lines`.
- **Match on `sloid`.** OJP returns `StopPointRef` / `StopPlaceRef` values such as `ch:1:sloid:7000` or, for a platform, `ch:1:sloid:7000:2:3`. Compare the first four `:`-separated parts. `JourneyLegDto` now carries `originSloid` and `destinationSloid`, and the backend also reads the OJP intermediate stops when they are present. Matching on names is a weaker fallback, used for the mock provider.
- **Intermediate stations.** A section gives only its two ends. A leg passing through the middle of the section matches only if the client also checks the leg's intermediate stops, which OJP can return.
- **Accuracy of the sloid rule.** It was checked against all 32,911 Swiss service points of the opentransportdata.swiss `service-points-actual-date` export (2026-06-30): 100% match. Of the 257 UIC codes in VIA, 224 are in that export; the 33 others are foreign stations without a Swiss sloid.

### Privacy and extraction limits

- The internal export's `name`, `vorname` and `telefonnummer` columns are never read; they are empty in this export anyway.
- Message text is sanitized: HTML is removed, and phone numbers, e-mail addresses, honorific + name and user ids are replaced by placeholders. A scan of the 882 messages found none of these.
- Messages from individual staff accounts are skipped. Only `TCC` (traffic control) and `KOO Bhf` (station) messages are used.
- The extraction uses German, French and Italian keyword rules, without a language model, and can miss or misread facts. `facts` must be shown as "according to internal operations", never as an official SBB announcement.

## Journey impact (proposal)

**Status: proposal.** `POST /api/journeys/impact` checks whether the best journey of a search is affected by an active disruption. When it is, the route computes `detourExtraMinutes` and returns the outlook, so the traveller no longer types a detour cost.

Request, `Content-Type: application/json` (UTF-8):

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `origin`, `destination` | Strings, 1–120 characters | Yes | As for `/api/journeys`. |
| `departure` | RFC 3339 timestamp | No | Earliest departure; defaults to `observedAt`. |
| `observedAt` | RFC 3339 timestamp | No | Instant at which disruptions are looked up and predicted; defaults to the server clock. Set it to replay a past incident. |
| `journeyId` | String | No | Check this journey of the search instead of the first-ranked one. |

How the backend proceeds:
1. It runs the usual journey search and takes the first-ranked journey, or `journeyId`.
2. It looks up the disruptions active at `observedAt`. Today the only source is replayed timelines: `DISRUPTION_TIMELINES_DIR` points to `analytics/output/timelines/`, labelled `MOCK`. Without it, no journey is affected.
3. A transit leg is affected when one of its stops (boarding, intermediate or alighting) is a `DISRUPTION_POINT` of the disruption, or when at least two of its stops are affected stations of any role, meaning the leg runs along the affected line. Stops match on sloid, else on accent-insensitive name.
4. The alternative is the earliest-arriving journey of the same search that no active disruption touches. `detourExtraMinutes` = alternative arrival − planned arrival, floored at 0.
5. The outlook is computed with that detour. If no unaffected alternative exists, `detourExtraMinutes` is null, `alternative.status` is `NONE_UNAFFECTED`, and `advice.code` is `UNKNOWN`.

The planned timetable (OJP, mock) does not know the disruption, so its alternatives usually go through the same section. With the mock provider, every Lausanne → Genève journey is affected.

Response `200`:

```json
{
  "dataStatus": "MOCK",
  "observedAt": "2026-09-17T18:31:00+02:00",
  "journey": { "id": "ic1", "departure": "…", "arrival": "…", "legs": [ { "origin": "Lausanne", "destination": "Genève", "originSloid": "ch:1:sloid:1120", "destinationSloid": "ch:1:sloid:1008", "…": "…" } ], "…": "…" },
  "affected": true,
  "impacts": [
    {
      "incidentId": "2195005188970",
      "summaryEn": "Interrupted service: Renens VD - Morges and Bussigny",
      "matchedStations": [
        { "legIndex": 0, "uic": 8501120, "sloid": "ch:1:sloid:1120", "name": "Lausanne", "roles": ["LINE_FROM"], "matchedBy": "SLOID" },
        { "legIndex": 0, "uic": 8501037, "sloid": "ch:1:sloid:1037", "name": "Morges", "roles": ["DISRUPTION_POINT"], "matchedBy": "SLOID" }
      ],
      "matchedLines": ["IC1"],
      "detourExtraMinutes": 95,
      "alternative": { "status": "FOUND", "journey": { "id": "lake", "…": "…" } },
      "outlook": { "status": "AVAILABLE", "remainingMinutes": { "p50": 66, "pessimistic": 296 }, "advice": { "code": "DETOUR_IF_DEADLINE", "detourExtraMinutes": 95 }, "…": "same fields as the disruption outlook response" }
    }
  ]
}
```

These values come from the backend test that replays Renens at 18:30 with a fake provider: a direct IC 1 via Morges, and a lake alternative via Évian arriving 95 minutes later.
- `dataStatus` is `MOCK` when the disruptions are replayed or the provider is the mock.
- A search failure maps to the same `502`, `503` and `504` errors as `/api/journeys`.
- An invalid body or timestamp returns `400 INVALID_REQUEST`.
- The outlook uses whichever artifact the backend loaded. For historical replays, load the evaluation artifact (see [DEMO.md](DEMO.md)), which never saw the replayed incident.

## Changes and future endpoints

Use the same baseline JSON examples in frontend decoding, backend serialization and integration smoke checks. For additive output fields, define default behavior for clients that encounter missing or unfamiliar values. Any change to required fields, types, null semantics, ranking or supported queries needs review from frontend, backend and integration owners. Use a coordinated migration or new version for breaking changes. [Shared interfaces](../shared/INTERFACES.md) · [Development workflow](DEVELOPMENT_WORKFLOW.md)

Deadline and preference requests are future contract work. `POST /api/recommendations` and `GET /api/places?stationId=…` are suggested later interfaces and have no v1 schema. Define their eligibility, provenance and failure behavior before implementation. [Roadmap](ROADMAP.md)


## Challenge extensions in design

The [challenge plan](CHALLENGES.md) requires reviewing any extraction or disruption endpoints before implementation. Agree on image size and media types, timeout and fallback, candidate fields and unknowns, and retention behavior for screenshot extraction. For disruption recovery, agree on affected leg identifiers, source/time, unknown-match behavior, deadline impact, and explicit next-action or no-feasible-option outcomes. Existing v1 examples remain the search baseline.
