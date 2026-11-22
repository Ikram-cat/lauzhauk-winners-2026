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

Encode each query value, including spaces, accented characters and any literal `+` in an offset. Empty required values, invalid timestamps, and duplicate parameters return `400 INVALID_REQUEST`. Unknown parameters are ignored (asserted by `RouteTest`). The API expects stop search text rather than an app-specific station identifier. How the provider resolves that text belongs to the transport adapter.

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
- **Match on `sloid`.** OJP returns `StopPointRef` / `StopPlaceRef` values such as `ch:1:sloid:7000` or, for a platform, `ch:1:sloid:7000:2:3`. Compare the first four `:`-separated parts. The backend keeps these sloids on its internal legs (boarding, alighting and OJP intermediate stops). `POST /api/journeys/impact` returns them in its own journey shape (`legs[].originSloid`, `legs[].destinationSloid`). `GET /api/journeys` keeps the strict leg contract of `main` without them; adding them there is role B's decision. Matching on names is a weaker fallback, used for the mock provider.
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

The planned timetable (OJP, mock) does not know the disruption, so its alternatives usually go through the same section. With the mock provider, every Lausanne → Genève journey is affected. For the demo, `TRANSPORT_PROVIDER=demo` returns separate `MOCK` journeys for Lausanne → Genève and Zürich Stadelhofen → Stettbach, each with one alternative that avoids the section (+100 and +23 min); see [DEMO.md](DEMO.md).

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

### Live alerts (`liveAlerts`)

Every journey-impact response also carries `liveAlerts`. It lists the current GTFS-RT Service Alerts (`la/gtfs-sa`) that touch the planned journey, and is labelled **`dataStatus: "LIVE"`**, separately from the replayed `MOCK` impacts.
- **Matching:** an alert touches the journey when one of its `informed_entity.stop_id` stop places (sloid, platform suffix removed) is boarded, passed or alighted by a transit leg, and one of its `active_period` windows contains `observedAt`. A replayed past `observedAt` therefore normally gives no live alert.
- **`status`:** `OK`, `DISABLED` (no `GTFS_SA_API_KEY`) or `UNAVAILABLE` (feed not reachable). `fetchedAt` and `feedTimestamp` describe the snapshot, which is refreshed at most once a minute.
- **Each entry:** `ids` (UUIDs of the merged feed alerts), `operators` (GTFS agency ids, `11` = SBB), `directions` (route part of each merged title, e.g. `Renens VD - Allaman`), `headerText` and `descriptionText` (language → SBB text of the first merged alert, description truncated to 1,000 characters), `cause`, `effect`, `activePeriods` (union, `start` and `end`), `matchedStations` (`legIndex`, `sloid`, `uic`).
- **No prediction:** `prediction` is always `null`, and `predictionReason` explains why. The alert cause has no mapping to the SBB cause codes the model was trained on, `active_period` is a validity window rather than a verified announced end, and live alerts cannot yet be validated against known ends.
- **Duplicates are merged by the backend.** The feed repeats the same text with different UUIDs, once per operator set and once per direction ("Renens VD - Allaman" / "Allaman - Renens VD"). Alerts are merged when they have the same title kind (text before `: `), the same two end stations in either order, and the same matched stations. Measured on 2026-11-22 01:05 UTC: 9 alerts matched Lausanne → Genève and gave 6 entries. The 3 "Interrupted service: Renens VD - Allaman" alerts (both directions, operators 11, 29 and 74) became one entry. The live content changes over time.

```json
"liveAlerts": {
  "dataStatus": "LIVE", "status": "OK", "source": "GTFS-RT Service Alerts, opentransportdata.swiss la/gtfs-sa",
  "fetchedAt": "2026-11-22T00:41:05Z", "feedTimestamp": "2026-11-21T23:38:59Z",
  "alerts": [ { "ids": ["441a1c3a-…", "54d7890e-…", "d90682fc-…"], "operators": ["11", "29", "74"],
                "directions": ["Renens VD - Allaman", "Allaman - Renens VD"],
                "headerText": { "en": "Interrupted service: Renens VD - Allaman", "fr": "…", "de": "…", "it": "…" },
                "cause": "CONSTRUCTION", "effect": "UNKNOWN_EFFECT", "activePeriods": [ { "start": "…", "end": "…" } ],
                "matchedStations": [ { "legIndex": 0, "sloid": "ch:1:sloid:1037", "uic": 8501037 } ],
                "prediction": null, "predictionReason": "No duration is predicted for live alerts: …" } ]
}
```

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
| `GET /api/locations?query=<text>` | Stop/address autocomplete for the search box, proxied from the provider and cached. Query 1–120 code points. | `200` with `{ "locations": [ { "id", "name", "kind", "coordinates": { "lat", "lon" } \| null } ] }` |
| `GET /api/model` | Provenance of the risk artifact actually loaded by the running instance. No provider call. | `200` with `{ "riskModel": { "version", "generatedAt", "source", "status", "coverage", "counts": { "keys", "corridors", "baselineHours", "byLineHourCells" } }, "delayModel": { "version", "status", "counts": { "portions", "lines" } } }` |

### Additional journey fields

`GET /api/journeys` answers with the nested shape below (DTOs in `backend/src/main/kotlin/com/alt/backend/api/Dtos.kt`). The response has `request`, `status`, `action`, `reasons`, `disruption`, `primaryJourney`, `recommendedJourney`, `alternatives`, `highlights`, `deadlineSummary` and `meta`. A journey appears as `primaryJourney`, `recommendedJourney` (may be the same itinerary) and in `alternatives`, always with the shape of the `JourneyDto` table. Nullable fields are serialized as `null`.

| `JourneyDto` field | Type | Meaning |
| --- | --- | --- |
| `id` | String | Opaque itinerary identifier. |
| `dataStatus` | `LIVE_SCHEDULE` \| `MOCK` | Provider-backed schedule or explicit demo/test source. |
| `verified` | Boolean | True when the disruption detector checked this itinerary against live data; false means it is ranked on expectations only. |
| `feasible` | Boolean or null | Present when `arrivalDeadline` was supplied: the expected (else scheduled) arrival is at or before the deadline. Null without a deadline. |
| `maxOccupancy` | `LOW` \| `MEDIUM` \| `HIGH` \| null | Highest occupancy over transit legs for the requested `fareClass`. |
| `schedule` | `{ departure, arrival, durationMinutes, transfers }` | Scheduled times (offset timestamps) and counts. Never altered by delays. |
| `realtime` | `RealtimeDto` | Known delay and the separate estimate layer, see below and [Historical delay estimates](#historical-delay-estimates-estimated--projected). |
| `risk` | `RiskDto` | Modelled disruption risk, see below. |
| `fares` | `FaresDto` | Price, see below. |
| `legs` | Array of `LegDto` | Ordered legs. |

`risk` (`RiskDto`):

| Field | Type | Meaning |
| --- | --- | --- |
| `corridorIncidentRate` | Number in `[0, 1]` or null | Modelled disruption probability of the matched corridor (per day) or hour baseline (per hour). Not a late-arrival probability for this traveller. |
| `typicalIncidentDurationMinutes` | Integer or null | Typical disruption length of the matched row. Not a predicted delay. |
| `source` | `CORRIDOR` \| `BASELINE` \| `NONE` | Which artifact table produced the values; `NONE` means unknown. |
| `basis` | `DAY_WINDOW_<n>` \| `DAY_WINDOW` \| `HOURLY_BASELINE` \| null | Observation window the rate refers to (`n` days of coverage). |
| `confidence` | `LOW` \| `MEDIUM` \| `HIGH` \| null | Sample-size band of the matched row. |
| `level` | `LOW` \| `MEDIUM` \| `HIGH` \| null | Banding of `corridorIncidentRate` (`< 0.02`, `< 0.06`, else). |

`fares` (`FaresDto`):

| Field | Type | Meaning |
| --- | --- | --- |
| `status` | `PRICED` \| `UNAVAILABLE` \| `DISABLED` | `DISABLED` when fare lookup is off on the instance (`FARE_BASE_URL` blank); `UNAVAILABLE` when the lookup failed or has no price for the requested class and discount. |
| `selectedChf` | Number or null | Price for the requested `fareClass` and `discount` (what the traveller would pay). |
| `product` | String or null | Upstream product name, e.g. `Streckenbillett`. |
| `source` | `NOVA` \| `MOCK` \| null | Price source. |
| `catalogChf` | `CatalogFaresDto` or null | `{ secondClassFull, secondClassHalfFare, firstClassFull, firstClassHalfFare }`, each a number or null (CHF). |

`reasons` is an array of objects, never prose. Only the numbers that justify the `code` are present (absent keys are omitted, not null):

| Key | Type | Meaning |
| --- | --- | --- |
| `code` | String | `REALTIME_UNAVAILABLE`, `PRIMARY_LEG_CANCELLED`, `SUPPRESSED_STOP`, `PRIMARY_LEG_DELAYED`, `EARLIER_ARRIVAL`, `EARLIER_DEPARTURE_CANCELLED`, `EXPECTED_FASTER`, `TRANSFER_MISS_RISK`, `FEWER_TRANSFERS`, `CHEAPER`, `STAY_FASTEST`, `DEADLINE_MET`, `DEADLINE_MISSED`. |
| `serviceLabel` | String | Line the reason is about. |
| `delayMinutes` | Integer | Measured delay of that leg. |
| `savedMinutes` | Integer | Minutes the recommendation saves versus the primary journey. |
| `savedTransfers` | Integer | Fewer transfers versus the primary journey. |
| `savedChf` | Number | Cheaper by this much, CHF. |
| `marginMinutes` | Integer | Minutes of slack (`DEADLINE_MET`) or overrun (`DEADLINE_MISSED`) against `arrivalDeadline`. |

Other top-level fields: `status` (`NO_DISRUPTION` \| `DISRUPTION_DETECTED` \| `UNKNOWN`), `action` (`STAY` \| `REROUTE` \| `NONE`), `disruption` (object or null: `legIndex`, `serviceLabel`, `from`, `to`, `scheduledDeparture`, `estimatedDeparture`, `delayMinutes`, `cancelled`, `basis`, `confidence`, `cause`, `severity`), `highlights` (`{ fastestId, simplestId, cheapestId }`, each an id or null; independent of `preference`), `deadlineSummary` (`{ feasible, latestFeasibleDeparture }` or null without a deadline) and `meta` (`{ computedAt, modelVersion }`).

### `preference` query parameter (`/api/journeys`)

Optional, one of `FASTEST` (default), `CHEAPEST`, `SIMPLEST`, case-insensitive; any other value returns `400 INVALID_REQUEST`. `request.preference` echoes the canonical upper-case value and `journeys` is sorted by it:

| Preference | Order |
| --- | --- |
| `FASTEST` | arrival asc, transfers asc, departure asc, id |
| `CHEAPEST` | `fares.selectedChf` asc with nulls last, arrival asc, id |
| `SIMPLEST` | transfers asc, leg count asc, arrival asc, id |

`predictedDelayMinutes` remains the field for a validated delay estimate and stays null while the only available signal is disruption duration. It is populated only when an artifact row carries `delayEstimateMin`.

### `fareClass` and `discount` query parameters (`/api/journeys`)

| Parameter | Values | Default | Invalid |
| --- | --- | --- | --- |
| `fareClass` | `1` or `2` | `2` | `400 INVALID_REQUEST` `Parameter 'fareClass' must be 1 or 2` |
| `discount` | `NONE` or `HALF_FARE`, case-insensitive | `NONE` | `400 INVALID_REQUEST` `Parameter 'discount' must be one of NONE, HALF_FARE` |

Both are echoed in `request` (`fareClass` as an integer, `discount` upper-case). They select `fares.selectedChf` only; the raw price fields and the fare cache do not depend on them. Example for `fareClass=1&discount=HALF_FARE` (real NOVA numbers, Lausanne to Zürich HB):

```json
"request": { "origin": { "input": "Lausanne", "resolvedName": "Lausanne", "id": "ch:1:sloid:1120" }, "preference": "CHEAPEST", "fareClass": 1, "discount": "HALF_FARE", "...": "..." },
"primaryJourney": { "id": "mock-direct-1", "fares": { "status": "PRICED", "selectedChf": 65.5, "product": "Streckenbillett", "source": "NOVA",
  "catalogChf": { "secondClassFull": 77.0, "secondClassHalfFare": 38.5, "firstClassFull": 131.0, "firstClassHalfFare": 65.5 } }, "...": "..." }
```

`FARE_BASE_URL=mock` replaces NOVA with fixed demo prices (42.00 / 21.00 / 72.00 / 36.00, `fareSource` `MOCK`) for offline demos.

### Additional response fields

`highlights` (`/api/journeys` only) is `{ "fastestId", "simplestId", "cheapestId" }`, each the id of the best journey under that preference over the journeys shown, or null (`cheapestId` is null when no shown journey has a price). It does not depend on the requested `preference`.

```json
"highlights": { "fastestId": "mock-direct-1", "simplestId": "mock-direct-1", "cheapestId": "mock-direct-1" }
```

`deadlineSummary.latestFeasibleDeparture` (offset timestamp or null) is the latest scheduled departure among candidates that still meet `arrivalDeadline`; `deadlineSummary.feasible` is false when none qualifies. There is no free-text `advice` field on `/api/journeys`: the explanation is `reasons`.

Risk data comes from an offline artifact built from public SBB liveticker incidents (2024-2026, 142 corridors, ~91% incident coverage). Coverage, model version and confidence limits are reported by `GET /api/model` and must be shown or linked wherever a modelled number is displayed.

### Historical delay estimates (`estimated*` / `projected*`)

A second, statistical delay signal is exposed next to the known live delay. The known layer is untouched: `departureDelayMinutes` / `arrivalDelayMinutes` and `expectedDeparture` / `expectedArrival` keep their exact meaning and source (`GTFS_RT`, `OJP`, `DELAY_MODEL`). The new layer is separate and additive, so a v1 client can ignore it.

Applies to `journeys[].realtime` and every entry of `journeys[].legs[]`:

| Field | Type | Meaning |
| --- | --- | --- |
| `estimatedDepartureDelayMinutes` | Number (one decimal) or null | Historical statistical median delay at this leg's boarding stop, in minutes with one decimal place (measured at seconds resolution from the actuals), clamped to `-10 … +30`. Signed: negative means the train is typically early. Null when no bucket matched — never a fabricated 0. Clients may round for display. |
| `estimatedArrivalDelayMinutes` | Number (one decimal) or null | Same for the alighting stop. Looked up separately from the departure side. |
| `estimatedSource` | `HISTORICAL_ACTUALS` \| `LIVE_SAMPLE` \| `NONE` | Which table produced the estimate. `LIVE_SAMPLE` is the existing realtime sampler filling a gap the historical artifact misses; `NONE` means unknown. |
| `estimatedSamples` | Integer or null | Measurements behind the matched bucket (at least 10). |
| `projectedDeparture` | RFC 3339 string or null | `scheduled departure + known delay + estimated delay`, composed in seconds (a `+1.5` estimate lands at `:30`). Equals `expectedDeparture` when both delays are 0 or absent. |
| `projectedArrival` | RFC 3339 string or null | Same for arrival. Recommendation ranking uses this as the arrival instant, then falls back to `expectedArrival`, then the scheduled arrival. |

At journey level the fields mirror the existing rule: departure values come from the first transit leg, arrival values from the last transit leg.

Projection is additive by default: the known live delay and the chronic historical delay are both applied. Set `ESTIMATE_OVERLAP=known_wins` to drop the estimated component from `projected*` on legs that already carry a known live delay (the estimate stays visible in `estimated*`). The default is `additive`.

Source of the numbers: SBB/open data `ist-daten-v2` (Actual data v2) for January–May and July–October 2026, 274 operating days, `PRODUKT_ID = Zug` only, `*_PROGNOSE_STATUS = REAL` only, cancelled/extra/through rows and `|delay| > 180` minutes dropped. Buckets are `(line, station, weekday class, hour)` for ~70 Swiss hubs and every line serving them, with weekday classes `MON_FRI` / `SAT` / `SUN` plus a pooled `ANY`, and 4-hour blocks (`00-03 … 20-23`) for thin hours. A lookup walks line-at-station → line (pooled stations) → station, each most-specific bucket first, and the first bucket with at least 10 samples wins. Ist-Daten often stores the last forecast rather than a true actual, so treat these numbers as delay distributions, not guarantees.

`GET /health` gains one additive feed:

```json
"feeds": { "ojp": "…", "nova": "…", "gtfsRt": { "…": "…" },
           "estimates": { "status": "OK", "lineBuckets": 18234, "stationBuckets": 2411, "incidentBuckets": 412 } }
```

`status` is `OK` when the artifact loaded, `MISSING` when it did not. Bucket counts are the artifact's emitted rows, not the number of live lookups.

### Incident-conditioned estimates and stop risk (`incident*`)

While live detection reports an ACTIVE disruption on the leg's line/corridor (a GTFS-RT or OJP hit on that leg), the estimate switches from the chronic delay to the INCIDENT-CONDITIONED one. All fields are additive and nullable; without an active disruption they are `null` / `NONE` and the payload is exactly the Phase-1 one — a v1 client can ignore this layer.

Applies to `journeys[].realtime` and every entry of `journeys[].legs[]`:

| Field | Type | Meaning |
| --- | --- | --- |
| `estimatedBasis` | `LINE` \| `STATION` \| `LINE_INCIDENT` \| `STATION_INCIDENT` \| `NONE` | Which ladder answered. `LINE` / `STATION` are the unconditional Phase-1 families; `*_INCIDENT` means a conditioned bucket answered while a disruption was active. `NONE` means unknown. |
| `incidentExtraMinutes` | Number (one decimal) or null | Conditioned median minus the unconditional median of the same leg — what the incident typically costs ON TOP of the chronic delay. Null when either median is unknown. |
| `incidentCancellationRisk` | 0..1 or null | Share of matching planned trains that were cancelled during similar past incidents. The traveller should not count on that departure at all. |
| `incidentSuppressedStopRisk` | 0..1 or null | Share of matching planned stops the train actually skipped (through-running) during similar past incidents. The train runs but may not halt at the boarding stop — board elsewhere or wait. |
| `incidentSamples` | Integer or null | Measurements behind the conditioned bucket (at least 10, pooled over all incidents). |
| `incidentCount` | Integer or null | Distinct past incidents behind the conditioned bucket. |

Lookup order when a disruption is active: `linesIncident` (line × 4-hour block, pooled weekdays) → `stationsIncident` (station × 4-hour block) → the unconditional Phase-1 ladder. `projected*` compose the CONDITIONED estimate while the incident is active. At journey level `incidentExtraMinutes` / `incidentSamples` / `incidentCount` / `estimatedBasis` come from the first transit leg (else the last), and the two risk fields are the maximum over the transit legs.

Conditioned numbers are measured only on trips that ran and reported: cancelled trips are excluded from the delay medians but are counted in the `incidentCancellationRisk` and `incidentSuppressedStopRisk` denominators, which are ALL planned stops matching the station/window, including cancelled and skipped ones. This survivor bias is deliberate — the delay distribution describes trains that operated, the risk shares describe what happened to the planned ones. A zero share is a measured zero, not fabricated.

Incidents are reconstructed from the SBB public liveticker audit trail: window start is the first source record, end the first `ENDED` version (the `duration_from` column is empty for almost every incident). A train trip is in scope when its folded stop sequence shares at least two stations with the incident corridor and the measurement's scheduled time lies inside the window. The Phase-1 unconditional tables are untouched: the conditioned ones are separate top-level arrays in the same artifact.

### Transfer-miss risk (`transferMissRisk`)

A predicted arrival delay — chronic or, more sharply, incident-conditioned — can be large enough that the traveller probably misses the next connection. That risk is exposed next to the estimate and drives the recommendation. All fields are additive and nullable; the older fields keep their exact meaning.

Applies to `journeys[].realtime` (not to `legs[]`):

| Field | Type | Meaning |
| --- | --- | --- |
| `transferMissRisk` | 0..1 or null | The maximum probability over consecutive TRANSIT leg pairs that the arrival delay of the first leg exceeds the connection slack. Null when the journey has fewer than two transit legs or every transfer is unknown — never a fabricated 0. |
| `transferRiskBasis` | `KNOWN` \| `CONDITIONED_INCIDENT` \| `CHRONIC` \| `NONE` | Which evidence answered for the worst transfer: a measured live arrival, the incident-conditioned bucket, the chronic bucket, or nothing. |
| `transferRiskDetail` | Array or null | One entry per transfer, `{fromLeg, toLeg, slackMinutes, missRisk}`, where `fromLeg` / `toLeg` index the journey's `legs`. Null when there are no transfers. Kept small: the indices, the slack and the probability only. |

**Slack.** `slackMinutes = scheduled departure of the next transit leg − scheduled arrival of the previous one`, in instants, minus the duration of any WALK legs between them. A negative slack means the connection is already impossible on paper and the risk is `1.0`.

**Probability.** The arrival-delay distribution of the earlier leg answers `P(delay > slack)`, in this priority order:

1. `KNOWN`: a measured live arrival (`expectedArrival` on that leg). A known overrun past the slack is `1.0` — OJP validated the connection at search time, so a live overrun means reality broke it afterwards.
2. `CONDITIONED_INCIDENT`: while an active disruption touches the leg, the conditioned median answers (and an unconditional median is shifted by `incidentExtraMinutes`, with the p90 shifted by the same amount).
3. `CHRONIC`: the unconditional bucket's median and p90.
4. No estimate and no known delay → `null` and `NONE`.

From the (median, p90) anchors the probability is a documented approximation, **not a measured probability**: `1.0` when `slack ≤ median`, `0.1` when `slack ≥ p90`, linear in between, clamped to `[0, 1]`. A missing p90 (the live sampler only reports a median) uses a nominal `median + 10` minutes as the tail.

**Recommendation.** `transferMissRisk` is computed for every candidate before the recommendation is chosen. When the current pick is at **≥ 0.5** and another candidate is at **< 0.2** (a journey with no transfer counts as `0` — there is no connection to miss), the safer candidate becomes `recommendedJourney` with `reasons[].code = TRANSFER_MISS_RISK` and `delayMinutes` set to the predicted overrun past the slack. This switch does **not** need the 5-minute arrival gain the other reroute rules require: the benefit is not arriving earlier but arriving at all. The risky journey stays visible in `alternatives` with its own `transferMissRisk`; the ranking comparator is unchanged, and when every risk is null the payload is identical to one without this layer.

### `demoPerturbation` query parameter (DEMO ONLY, `/api/journeys` and `/api/journeys/impact`)

**This parameter exists only to demo detection and rerouting to judges. It is not part of the product contract and no client should depend on it.** It makes the backend lie about the live situation on purpose, and every response that used it says so.

| Value | Behavior |
| --- | --- |
| absent | Nothing changes. The payload is byte-identical to one from an instance without this feature. |
| `true` (case-insensitive) | A fresh random draw on **every** request. |
| integer | Reproducible seed: the same seed always produces the same delay, leg and cancellation. |
| `false`, or anything else | `false` is the same as absent; any other value is `400 INVALID_REQUEST` with `field: "demoPerturbation"`. |
| `DEMO_PERTURBATION=false` in the environment | The kill switch: the parameter is treated as absent entirely, including for validation. |

The perturbation is applied at the **provider layer**, not on the wire: the decorator wraps the real `TransportProvider`, so its search results and its stop-event boards describe the same synthetic incident. The perturbation therefore flows through the normal pipeline — `DisruptionDetector` finds the delay against the timetable board, and `JourneyPlanService` reroutes on that finding. Nothing in the response is fabricated by the route.

One draw applies:
- a **delay of 5–45 minutes** on one transit leg of the first journey of the provider result (the plan's primary), shifting that leg's expected times and the estimated time of the same service on its boarding-station live board, so detection sees at least the 3-minute threshold;
- with **~50 % probability**, the **cancellation of one alternative** journey that departs before the delayed leg: it is omitted from the search results (as OJP omits confirmed cancellations) and its trip is flagged `cancelled` on the live board, which is what the CANCELLED_FLAG / earlier-departure-cancelled rules consume.

When the parameter is honoured, the envelope gains ONE additive top-level field:

```json
"demoPerturbation": {
  "active": true,
  "seed": 12345,
  "applied": ["delay+18min Demo rail service @ Lausanne", "cancelled Demo regional @ Lausanne"]
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `active` | boolean | Always `true` when present. Its presence is the signal. |
| `seed` | integer | The seed that produced this draw, so a demo step can be replayed exactly. |
| `applied` | array of strings | Human-readable summary of what was changed; empty when the provider returned no journey to perturb. |

`seed` is drawn from a secure-free random source on `true`; send it back as the parameter to replay the same demo moment. `dataStatus` / `MOCK` conventions are untouched: the perturbation is a separate, explicit label, and `applied` may name `MOCK` journeys (the demo and mock providers) exactly as it names real ones.
