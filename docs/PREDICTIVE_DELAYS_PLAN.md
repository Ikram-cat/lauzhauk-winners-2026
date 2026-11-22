# Implementation plan: predictive per-leg delay estimates (+ incident amplification)

Branch: `feat/predictive-delays` (from main @ 1d447cc). Worktree: `~/code/wt/predict`.
Scope: `backend/`, `analytics/`, `docs/API_CONTRACT.md`. FORBIDDEN: `frontend/`, `shared/`.
Additive contract only — the frontend is wire-building against the current
envelope, so every existing field keeps its exact name, type and semantics.

## 0. Product outcome (Sacha's spec)

Per leg the API exposes, separated and combined:

    10:42  +3 (known delay)  +2 (additional estimated delay, can be -2)

    departure/arrival                 = scheduled times (unchanged)
    departureDelayMinutes             = KNOWN live delay (unchanged: OJP/GTFS-RT)
    estimatedDepartureDelayMinutes    = NEW chronic/statistical delay (Ist-Daten)
    projectedDeparture                = NEW scheduled + known + estimated
    (same four for arrivals, per leg and per journey)

Known and estimated never mix in one field; the UI renders the badges directly
from them. Unknown stays null — never a fabricated 0.

## 1. Data foundation — Ist-Daten (real, public, no faking)

Source: opentransportdata.swiss "Actual data v2" (ist-daten-v2), monthly ZIP
archives at https://archive.opentransportdata.swiss (2016 → last month).
Free, same platform as our OJP/GTFS-RT keys. Per journey per stop:

    BETRIEBSTAG, FAHRT_BEZEICHNER, LINIEN_ID, LINIEN_TEXT (e.g. "IC5"),
    BPUIC + SLOID + HALTESTELLEN_NAME, UMLAUF_ID (vehicle rotation!),
    ANKUNFTSZEIT vs AN_PROGNOSE + AN_PROGNOSE_STATUS,
    ABFAHRTSZEIT vs AB_PROGNOSE + AB_PROGNOSE_STATUS,
    FAELLT_AUS_TF, DURCHFAHRT_TF, ZUSATZFAHRT_TF

Window: 3 months (fallback 1 month if download/time tight; minimum 3 weeks —
fewer weeks cannot support weekday classes).

Delay computation per (journey, stop):
    departure delay = AB_PROGNOSE - ABFAHRTSZEIT
    arrival delay   = AN_PROGNOSE - ANKUNFTSZEIT
Keep: status REAL or ESTIMATED only (FORECAST/UNKNOWN rows are not actuals),
PRODUKT_ID = Train (rail first; buses optional later), DURCHFAHRT_TF=false.
Drop: FAELLT_AUS_TF=true, ZUSATZFAHRT_TF=true (extra trips skew patterns).
Keep negative delays (early arrivals are real in CH: "even -2").

Known caveat (document, do not hide): Ist-Daten is often the LAST FORECAST,
not true actual — good enough for delay distributions, stated in the contract.

## 2. Artifact v2 — analytics/estimates

New file `backend/src/main/resources/realtime/delay_estimates_v2.json`
(the sampler artifact stays untouched; this is a separate file).

    { modelVersion, generatedAt, window{from,to,days,trips},
      joinReport{linesMatched, lineMatchRate, stationsMatched},
      lines:   [ { serviceLabel, station, weekdayClass, hourBucket,
                    dep{median,p90,shareLate,samples}, arr{median,p90,shareLate,samples} } ],
      stations:[ { station, weekdayClass, hourBucket, dep{...}, arr{...} } ] }

weekend classes: MON_FRI | SAT | SUN, with an implicit ANY fallback row
emitted by the aggregator (pooled) for thin buckets.

Aggregation rules:
- hourBucket 0-23 local Europe/Zurich, from the SCHEDULED time.
- median can be negative; p90 caps the bad tail; samples = distinct trips.
- Emission threshold: >= 10 samples per bucket, else pool weekdayClass -> ANY
  (>= 8), else pool hour into 4-hour blocks (>= 8), else drop.
- Fallback chain at lookup time (backend): line@station -> line (pooled over
  stations in the same hour+weekday) -> station -> null.

Matching (the two known traps, handled by name not id):
- stations: Ist-Daten HALTESTELLEN_NAME -> backend `stationForms` (accents,
  hub suffixes, aliases) — BPUIC/sloid never joined to OJP ids.
- lines: LINIEN_TEXT -> OJP serviceLabel through `normalizeRiskKey`
  ("ojp:IC 5" -> "IC5"). Aggregator prints a joinReport; if match rate < 60%
  on a spot-check, backend keeps station-only estimates (honest nulls).

## 3. Backend changes (additive only)

New `service/estimate/DelayEstimateModel.kt`: loads artifact v2 (mtime reload
like RealtimeDelayModel), lookup `estimate(serviceLabel, station, weekday,
hour)` -> `LegEstimate(depMedian, arrMedian, depP90, arrP90, samples, source)`
where source = HISTORICAL_ACTUALS | LIVE_SAMPLE (if we fold the sampler medians
in as a second table with fewer samples it keeps its own source) | NONE.

Wiring in `JourneyPlanService.realtime` + `journeyDto` (Routes.kt):
- Known layer exactly as today (detector/OJP), untouched.
- estimatedDelayMinutes per leg (dep and arr) from the model.
- projected = scheduled + knownDelay + estimated (Sacha's additive spec),
  estimated component clamped to [-10, +30] before applying.
- Overlap policy: default ADDITIVE (transparent UI). Env
  `ESTIMATE_OVERLAP=known_wins` zeroes the estimated component on legs that
  already carry a known delay (avoids double-counting live forecasts) —
  documented, off by default.
- Journey level mirrors today's rule: first transit leg for departure,
  last transit leg for arrival.

New additive wire fields (legs[].realtime + journey.realtime):
    estimatedDepartureDelayMinutes, estimatedArrivalDelayMinutes,
    estimatedSource, estimatedSamples,
    projectedDeparture, projectedArrival
All nullable; absent model -> all null, source NONE.

Ranking: `arrivalOf()` becomes projectedArrival ?: expectedArrival ?: scheduled
— the re-rank / EXPECTED_FASTER / reroute-on-5-min logic all inherit better
inputs with NO contract change (still gated to FASTEST where required).

/health: add additive `feeds.estimates { status, lineBuckets, stationBuckets }`.

## 4. Phase 2 — delay estimation WHEN incident (chain reaction)

Opinion (grounded in the data we already hold):
- Incident-conditional delay IS measurable: join liveticker incidents (we
  have 2.7y of incident timelines) x Ist-Daten by corridor+time overlap ->
  per corridor: unconditional vs during-incident delay distributions ->
  amplification (e.g. "normally +2, during an incident on your line +12,
  p90 +25"). Real, explainable, judges-friendly.
- Backend: when a disruption is already detected on the leg (existing
  detector), swap that leg's estimate to the conditional bucket; the new
  field `estimatedBasis: CORRIDOR | CORRIDOR_INCIDENT | STATION | NONE` +
  the existing disruption block let the UI say "+2 +10 because of the
  incident on your line".
- TRUE chain reaction (your train inherits the delay of the incoming
  vehicle on its previous UMLAUF_ID circuit) is in the data too — Phase 3
  stretch only; needs rotation sequencing and is not needed for the demo.

## 5. Tests

Analytics (pytest, existing venv): aggregation unit tests on a synthetic
fixture (bucketing, pooling order, negative medians, thresholds), plus a
golden joinReport test.

Backend: EstimationModelTest (fallback chain, clamps, weekday classes,
mtime reload), RouteTest additions asserting (a) the six new fields exist and
are null-safe, (b) EVERY pre-existing field keeps its exact name/set (the
"journey object carries every documented field" test extended), (c) projected
= scheduled+known+estimated on a fixture bucket, (d) ranking follows
projected arrival. Full fresh `./gradlew test --rerun-tasks`, XML counts.

## 6. Verification protocol (same as accounts round)

1. Fresh suite green (count from JUnit XML, not the banner).
2. `installDist` + live binary, curl: one search with a FUTURE departure
   (known null, estimated populated, projected = sched+est), one with a
   near-term departure (known+estimated both present), one on a corridor with
   no data (all new fields null, source NONE — honest).
3. Regression: old realtime fields byte-identical semantics; /health still OK.
4. `git diff --stat` scope check; commit; no merge without Sacha's word.

## 7. Traps

- Ist-Daten monthly ZIP is large (stream, chunked pandas; 117G free, fine).
- Never join Ist-Daten ids to OJP sloids — name matching only.
- Weekday classes need >= 3 weeks of data — do not pool below that.
- Double-count caveat (live forecast already contains some chronic effect) —
  default additive per spec, `ESTIMATE_OVERLAP` documented.
- The sampler artifact and this artifact stay SEPARATE files/consumers until
  a deliberate merge — two loaders, two sources, zero regression risk.
- Human step only if the archive requires an acceptance click: Sacha may
  need to confirm the dataset license once in the browser.

## 8. Effort / order

Phase 1 (core, ~3-4h): downloader+aggregator -> artifact v2 -> backend model
+ additive fields + ranking -> tests -> smoke.
Phase 2 (incident-conditional, +2-3h): join, conditional buckets, basis field.
Phase 3 (rotation chain, stretch): only if time remains.

## 9. After implementation (Teddy)

Re-verify independently, post additive-contract note to GitHub Discussion #4
before the frontend builds against the new fields, ask Sacha for
`merge feat/predictive-delays`.
