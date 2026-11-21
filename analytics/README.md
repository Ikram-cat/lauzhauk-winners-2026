# Analytics

Offline pipeline that turns the SBB extern-liveticker CSVs into two artifacts for the backend. Python never runs online.

## Rebuild

```bash
python -m venv ~/code/venv-analytics && ~/code/venv-analytics/bin/pip install -r analytics/requirements.txt
ln -s /path/to/Daten analytics/Daten/Daten      # analytics/Daten/ is gitignored raw data
~/code/venv-analytics/bin/python -m analytics.build_artifacts --data analytics/Daten/Daten/extern-liveticker --out analytics/artifacts
~/code/venv-analytics/bin/python -m pytest analytics -q
```

`--data` may also come from `$LIVETICKER_DATA`. `analytics/artifacts/` is gitignored (rebuild, don't commit).
Code: `duration_model.py` (snapshots, train, evaluate, `predict_remaining`, `advice`), `line_hour.py` (byLineHour), `build_artifacts.py` (entry point).

## Artifacts (`analytics/artifacts/`)

- `incident_duration.joblib` + `incident_duration.json`: quantile models (P50, P80, log target) for "how much longer will this live incident last". JSON holds features, categories, quantiles, training window, counts, metrics. Load the joblib as `{"models","features","categories"}` and call `duration_model.predict_remaining(features, bundle)`. The shipped model is refit on all snapshots after evaluation.
- `predictions.json`, the file the backend joins on:

```json
{"modelVersion": "...", "generatedAt": "UTC ISO", "coverage": {"from": "...", "to": "...", "scope": "..."},
 "notes": ["..."],
 "byLineHour": [{"key": "Bern - Thun", "hourBucket": 8, "lateProbability": 0.0-1.0,
                 "expectedDelayMin": 67.7, "sampleSize": 1, "confidence": "low|medium|high"}]}
```

Round 2 adds (all backward compatible; `byLineHour` keeps its schema):

```json
"corridors": [{"from":"Lausanne","to":"Brig","corridor":"Lausanne - Brig","via":["..."],   // via only for >2-stop text
   "disruptionDays":43,"windowDays":953,"disruptionProbabilityPerDay":0.0451,
   "expectedDelayMin":178.1,"sampleSize":40,"confidence":"high","source":"line|segment"}],
"hourBaseline": [{"hourBucket":0,"disruptionProbabilityPerHour":0.0493,"expectedDelayMin":607.8,"sampleSize":51}],  // exactly 24
"stationAliases": {"Genf":"Genève", "...":"..."}, "joinHints": {"normalisation":"...", "...":"..."},
"stats": {"corridorsKept":40, "...":"..."}
```

- `corridors`: one row per **undirected** endpoint pair (A-B and B-A are pooled, hours are pooled); `from`/`to` is the more frequent orientation. Parsed from "line A - B" (`source: line`, preferred) or, if absent, from "between A and B" (`source: segment`, short sub-sections, rarely joinable). `disruptionDays` = distinct local calendar days with at least one active disruption on the corridor; `disruptionProbabilityPerDay` = `disruptionDays / windowDays` (windowDays = calendar days from first to last incident, 953); `expectedDelayMin` = median disruption duration (upper bound on passenger delay); `sampleSize` = distinct incidents. Round 3: corridors with a single incident (n == 1) are dropped (`stats.corridorsDropped`; `corridorsDroppedSampleLt3` was renamed). n=2-7 are `low`. (Round 4: n == 1 is kept as `very_low`.) Texts with >2 stops also pool each adjacent 2-stop segment as `source: segment` (implemented and unit-tested; the data has no such text).
- `hourBaseline`: **nationwide**, all parsable incidents regardless of corridor. `disruptionProbabilityPerHour(h)` = share of the window's days (953) on which at least one disruption was active *somewhere in Switzerland* during local hour h (Europe/Zurich). It is not per journey and not a lateness rate; it is a coarse time-of-day shape and an upper bound for any single journey. `sampleSize` = distinct incidents active in that hour, `expectedDelayMin` their median duration.
- `byLineHour`: unchanged definition, but cells with sampleSize < 8 are dropped (`stats.byLineHourCellsDropped`).
- `stationAliases` / `joinHints`: matching procedure for the backend; matching is on `fold()` (accent- and case-insensitive). Aliases such as Genf/Geneva/Zurich were **not found in the CSVs** (CSV names are already display forms such as `Genève`, `Zürich HB`, `Biel/Bienne`, `Fribourg/Freiburg`); they come from a static list for OJP/user input. Observed spelling variants (whitespace/accent) are added automatically; there were none in this build.

`hourBucket` is the local Europe/Zurich hour 0-23. `confidence`: sampleSize <8 low, 8-19 medium, >=20 high.

## Realtime sampler

`realtime_sampler.py` polls the OJP stop-event API (needs `OJP_API_KEY`, never logged) for 12 corridor stations (`--stations-file` takes `name,stop_ref` lines instead). Each station's SLOID is checked against the returned `StopPlaceName`; a mismatch is logged and skipped. One CSV row per departure goes to `analytics/data/realtime_samples.csv` (gitignored): timetabled and estimated time, `delay_min` (empty without an estimate), `cancelled` (the `<Cancelled>` element, or `<NotServicedStop>` at the departure call), `unplanned`, `deviation`, quay and the Europe/Zurich hour of the timetabled time.

```bash
set -a; . ~/lauzhack-ojp.env; set +a
~/code/venv-analytics/bin/python -m analytics.realtime_sampler --once
~/code/venv-analytics/bin/python -m analytics.realtime_sampler --loop --interval 60 --duration 3600   # a failed run is logged, never fatal
~/code/venv-analytics/bin/python -m analytics.realtime_sampler --summarize   # -> artifacts/realtime_delays.json
```

The summary keeps the last observation of each departure (so repeated polls are not double counted). Departures with no delay estimate or cancelled are excluded from delay statistics. Output `artifacts/realtime_delays.json` (gitignored; the backend bundles a copy at `backend/src/main/resources/realtime/realtime_delays.json`):

- `modelVersion` (`rt-yyyymmdd-hhmm`, UTC), `generatedAt`, `window {from,to,samples}` (unique departures).
- `portions`: per station and Europe/Zurich `hourBucket` of the timetabled time: `samples`, `medianDelayMin`, `p90DelayMin`, `shareLate` (fraction with delay **> 2 minutes**) and `cancelled` (cancelled or skipped-stop departures in that station/hour).
- `lines`: the same statistics per `serviceLabel` (e.g. `IR90`), station and hour, without `cancelled`.
- `cancellations`: evidence list (station, lineRef, serviceLabel, timetabled, journeyRef), capped at 100.

Threshold: a `portions` or `lines` row is emitted only with **at least 10** departures that have a delay estimate and are not cancelled.

Refresh: collect with `--loop`, then `python -m analytics.realtime_sampler --summarize`, or `--export-backend <path>` to also write the same JSON to `<path>` (e.g. the backend resources file) and print a one-line summary.

Caveat: this measures **today's conditions** over the sampled window, not a historical average. A bucket seen on one evening says nothing about other days; collect several days before treating it as chronic lateness.

## Data and measured results (build of 2026-11-21)

Coverage 2024-03-04 to 2026-10-11. 550 incidents, 5,305 versions, 520 with a known end, 3,063 snapshots.
Chronological split (last 20% of incidents by start, cutoff 2026-04-06; train incidents must also have ended before the cutoff): 416 train / 104 test incidents.

| Metric (minutes, test) | Value |
| --- | --- |
| MAE, model P50 | 100.6 (train 61.0) |
| MAE, median-per-reason baseline | 128.6 |
| Snapshots with official announcement (n=626): model / official / baseline | 98.5 / 112.7 / 127.0 |
| Official estimate too optimistic | 69% |
| P80 coverage (target 80%) | 66% test, 80% train |

Round-2 build (same data): 507 usable incidents (ended, <24 h); 462 (91%) have a parsable corridor, 332 (65%) fall in a kept corridor.
- `corridors`: 40 kept (>= 3 incidents), 105 dropped, **3 with sampleSize >= 20** (Lausanne - Brig 40, Genève - Lausanne 23, Palézieux - Payerne 20).
- `hourBaseline`: sampleSize per hour 0-23 = 51 35 31 40 63 82 101 123 133 132 130 126 128 134 140 135 134 128 134 125 109 90 77 54; probability 0.05 (night) to 0.13 (daytime).
- `byLineHour`: 15 cells kept (>= 8), 1,213 dropped (was 1,246 keys, none >= 20).
- Parser fix: texts "on the line A - B, between ..., as well as on the line ..." now yield `A - B` (previously a garbage key).

Round-3 build (same data, OJP key present):
- `corridors`: **64 kept** (n >= 2), 78 dropped (n == 1), 3 with n >= 20, 17 with n >= 8. `hourBaseline` sampleSize 31-140 (unchanged).
- `stats.coverageShare` = **0.757** (384 of 507 ended incidents in a kept corridor); the 90% target is **not reachable** under the "drop n == 1" rule: 45 incidents (9%) are station-only texts ("Train services at Genève station ...", no corridor) and 78 are singleton corridors. Upper bound with n >= 2 is what we have. Keeping n == 1 would add 78 (to ~91%) but those rows are one observation each.
- Parser fix: "between X and Y will travel via Z" no longer yields a garbage stop `Y will travel via Z`.
- `stationValidation`: top 40 stations (endpoints of the best-sampled corridors, from `build_artifacts.top_stations`) were looked up with OJP `LocationInformationRequest` (`ojp_validate.py`, best of 5 stop results by `fold()` score, matched at >= 0.8). **40/40 matched with score 1.0 and an identical name**, so no new `stationAliases` were needed (26 static aliases remain; each unverified alias such as Genf is user-input only). Each row carries the OJP `sloid` (`ch:1:sloid:1008` = Genève).
- Offline builds: without `OJP_API_KEY` (env or `~/lauzhack-ojp.env`) or with `--no-ojp`, `stationValidation` is `[]`. The OJP free tier answers 429 under bursts, so lookups are paced and retried.
- Still unmatchable: 45 station-only incidents (no corridor), 3 stations outside the OJP rail network were not in the top 40 (only the top 40 are checked, ~half of all corridor stations, mostly Romandie because the data is), and foreign endpoints (Delle, Annemasse, Varese) match OJP names here but journeys there may use other providers' stops.
- Test: `test_ojp_validate_live_five_stations` runs 5 live lookups and skips without a key.

Round-4 build (final, same data, OJP key present):
- `corridors`: **142 kept** (every corridor with >= 1 ended incident): 3 high, 14 medium, 47 low, **78 very_low** (n == 1). 0 dropped. `confidence` is `very_low|low|medium|high`; the backend tells n == 1 rows apart by `confidence == "very_low"` (and `sampleSize == 1`). `source` is now `full` (whole corridor text, was `line`) or `segment` (adjacent pair of a >2-stop text, or a "between A and B" sub-section). 107 full, 35 segment. Texts with >= 3 stops emit the full A-Z (with `via`) plus each adjacent pair only, never non-adjacent pairs (unit-tested); the data contains no such text, so all 35 segment rows come from "between A and B" texts.
- `stats.coverageShare` = **0.9112** (462 of 507 ended incidents <24 h in a kept corridor). Target 0.95 **not reachable**: the 45 unmatched incidents (8.9%) are all single-station texts ("Train services at Nyon station are interrupted", "Limited train service at Olten"): one stop, no A-B pair, and no pair may be invented. Zero are unparsable text or foreign-only pairs. `stats.incidentsUnmatched` = 45, `stats.corridorsByConfidence` has the split.
- `unmatchedSamples`: up to 20 `{text, reason}` (first line of the incident text, distinct); this build returns 20, all reason "single station / no A-B pair".
- `hourBaseline` sampleSize range 31-140 (unchanged); `stationValidation` 40/40 matched, 26 aliases.

**Residual error, honestly.** A corridor's probability is "share of days since March 2024 with an active disruption on that A-B pair". For 78 corridors that rests on one incident, for 47 more on 2-7: such a number is mostly a fact about that one event, not a stable rate, and corridors never seen in the data have no row at all (absence is not safety). Disruption minutes are an upper bound on a passenger's delay. A UI must label `very_low` as "based on 1 past incident" and `low` as "few past incidents", never show them as a bare percentage or rank them against high-confidence corridors, and prefer wording such as "rarely disrupted / disrupted before" over a precise probability; only `medium` and `high` may be shown as a figure, still with the sample size.

## Demo CLI

```bash
~/code/venv-analytics/bin/python -m analytics.predict_duration --elapsed-min 95 --announced-remaining-min 60 --hour 14 --weekday 5 [--reason-id 3|EG_008]
# {"medianRemainingMin": 88, "pessimisticRemainingMin": 163, "advice": "consider detour", "modelVersion": "2026-11-duration-v1"}
~/code/venv-analytics/bin/python -m analytics.predict_duration --from-snapshot-file incident.json   # keys: elapsed_min, announced_remaining_min, hour, weekday, reason_id
```

Reads `analytics/artifacts/incident_duration.joblib` only (no pipeline code, no pandas code of our own; scikit-learn pulls pandas in when unpickling). Output equals `duration_model.predict_remaining` (checked). `--reason-id` takes `3` (= `EG_003`) or `EG_003`; an id the model never saw is encoded as unknown. Omitted `--hour`/`--weekday` default to now.

**Advice rule.** `consider detour` if pessimistic remaining (P80) >= `DETOUR_EXTRA_MIN` = **60 min**, else `wait`. Reasoning: a detour is worth it when the disruption plausibly outlasts what the detour costs, and we use the pessimistic quantile because the official estimate is too optimistic in 69% of test snapshots. **The 60 is not measured from the data**: the CSVs contain no alternative journeys, so the "median alternative travel time" cannot be derived offline. It is a round default, overridable with `--detour-extra-min`; replace it by the median extra travel time of the OJP alternatives (backend `/api/alternatives`) once measured. For reference, from the data: the median incident lasts 162 min (IQR 98-251, 507 ended incidents <24 h), so with 60 the rule says "wait" only when the model expects the incident to end within about an hour.

## Reproducibility

One command, deterministic for a fixed seed (`--seed`, default 42, used as the `random_state` of both quantile models and stored as `seed` in `incident_duration.json`):

```bash
~/code/venv-analytics/bin/python -m analytics.build_artifacts --data analytics/Daten/Daten/extern-liveticker --out analytics/artifacts --seed 42
```

`test_build_is_deterministic_for_fixed_seed` builds twice (`--no-ojp`) and asserts identical metrics, corridor table, hourBaseline and byLineHour (only `generatedAt` differs). Changing the default seed from 0 to 42 left every metric below unchanged. `incident_duration.json` carries `inputFiles` (sha256 + rows) for any build.

Inputs of the current artifacts (`extern-liveticker/`, rows = pandas-parsed records excluding header):

| File | Rows | sha256 |
| --- | --- | --- |
| `incident_202610121914.csv` | 523 | `bc7fbd0c27ee09a1ca5f0bf4112d051d6851b1f57a0d0cdcc73a3bb356f6b7c8` |
| `incident_audit_202610121914.csv` | 4782 | `091e59b4605b47f5804d8a206f14a8b3ae32c0be996ed95dee048b6cfc5bf82a` |
| `message_202610121835.csv` | 3234 | `e9f970ec153c236b1b7c3ebddbc2301533e56835d131d7d9fbdbdbee1d0bbaaa` |
| `message_audit_202610121835.csv` | 4722 | `9fa4e42ece6d311e7dfbc4bbfb539e33c0d857000134207988f5e4bd31f2ae3f` |
| `reason_202610121034.csv` | 93 | `4b8335a13c142a8be5f77df2b8def2423d26c929910c8067e8259a2505e558d3` |

The model reads `incident` and `incident_audit` only; the other files are hashed for completeness. 523 + 4782 rows = 5,305 versions after dedup, 550 incidents, 520 with an end. Confirm with `sha256sum analytics/Daten/Daten/extern-liveticker/*.csv`.

## What this model can and cannot claim

| Metric (test = last 104 incidents, 639 snapshots) | Value |
| --- | --- |
| MAE of median forecast (P50) | 100.6 min (baseline median-per-reason 128.6) |
| MAE on snapshots with an official estimate (n=626) | model 98.5 / official 112.7 / baseline 127.0 min |
| Official estimate too optimistic | 69% of those snapshots |
| P80 coverage (target 80%) | 66% test, 80% train |

- **Can claim:** once an incident is in the liveticker, the median forecast beats the per-reason baseline by ~22% and the official estimate by ~13% in MAE on held-out recent incidents. It is still off by about 1.7 h on average: say "typically", never a precise end time.
- **P80 is under-covered:** the pessimistic value is exceeded in ~34% of test snapshots, not 20%. Present it as "could be much longer", not as an 80% guarantee. Small sample (520 incidents), one chronological split, no confidence interval.
- **`lateProbability` is a corridor-day frequency**, the share of days since March 2024 with an active disruption on that corridor (and hour), not the chance that a given train is late, and not a rate over all trips (the data has no undisrupted trips). Rows with `very_low`/`low` confidence rest on 1-7 incidents.
- **Cannot claim:** whether a disruption will happen, per-train delay, platforms or fares, anything for disruptions over 24 h or never-ended ones (excluded), corridors absent from the data (absence is not safety).
- **Provenance string for the UI** (fill counts from the artifact): `risk: SBB liveticker 2024-2026, 142 corridors, model 2026-11-duration-v1`.

## Limitations

- The model predicts remaining minutes of a disruption already in the liveticker. It cannot predict whether a disruption will occur, nor per-train delay, platforms or fares.
- The P80 interval is under-covered on recent data (66% vs 80%): it is optimistic. Errors of 1-2 h are normal; the sample is small (520 incidents).
- Disruptions over 24 h and incidents without an ENDED version are dropped.
- **Key gap versus OJP:** the CSVs hold no OJP lineRef (`ojp:91008:E`) or train number. `key` is the corridor text from the description ("Lausanne - Brig"), parsed for ~80% of incidents (102 of 507 unparsable, excluded). The backend needs its own corridor-to-line mapping; no OJP refs are fabricated.
- `lateProbability` is the share of days a corridor had an active disruption in that hour, not a per-train lateness rate (no non-disrupted trips in the data). `expectedDelayMin` is the median disruption duration, an upper bound on passenger delay.
- Remaining uncertainty for the backend: only 3 corridors reach n >= 20 and 40 corridors exist, so most journeys will fall back to the nationwide `hourBaseline`. Endpoint matching is exact; a journey sharing track with a corridor but not its endpoints does not match. No multi-stop corridor text occurs in this data (`via` is implemented and unit-tested, never emitted). `windowDays` assumes the liveticker covered every day between first and last incident, which is unverified; if it had gaps the probabilities are underestimates. Disruptions over 24 h or without a known end are excluded, which biases probabilities low. Pooling both directions assumes an interruption hits both.
- Data are public-liveticker incidents only (not intern-liveticker or Ereignismeldungen).

## Disruption-duration package `sbb_duration` (role C)

The `sbb_duration` package trains a model of the remaining duration of SBB disruptions on the public liveticker export (2024–2026), evaluates it chronologically, calibrates its pessimistic estimate, and exports it as JSON for the Kotlin backend (`POST /api/disruptions/outlook`, `POST /api/journeys/impact`). It also joins the three sources (public liveticker, VIA, internal liveticker) into replay timelines for the demo. Its commands, modules and results are in [Planned layout and setup](#planned-layout-and-setup) below, its demo in [DEMO.md](../docs/DEMO.md).

The data's usage rights are not yet reviewed: the CSVs, the trained model and every generated file stay local in `analytics/output/`, out of Git. The package is separate from `duration_model.py` / `build_artifacts.py` above; both are trained on the same liveticker export.

## Original work order (kept from the scaffold)



1. Start with the transport interface while awaiting data. Review the candidate provider’s documented fields and terms, sample a permitted response, record only observed field behavior, and propose sanitized fixtures covering legs, optional platforms, and empty results. Agree on fixture placement with Role B. Use the examples in the [API contract](../docs/API_CONTRACT.md) as synthetic input for consumer checks.
2. When the CSV arrives, record source, dataset version or checksum, permitted use, and sharing constraints. Inspect encoding, delimiter, headers, row count, timestamps, coverage, duplicates, missingness, and potential personal fields locally. Determine the unit of observation, time zone, and whether non-incident services and usable labels are represented. Ask the data owner about unresolved semantics before freezing ingestion decisions.
3. Implement an ingestion report. Keep raw input read-only, parse with explicit encoding and date rules, and validate only confirmed semantics. Count or quarantine invalid records with reproducible reasons. Record filtering and deduplication rules and check that accepted and rejected counts reconcile.
4. Select the analysis based on what the audit establishes. Incident-only data may support observed counts and summaries by confirmed time or location fields. A model for late-arrival probability needs labeled outcomes and a defensible population denominator plus a verified mapping to live journeys. If those prerequisites are absent, record the limitation and scope the analysis accordingly.

## Planned layout and setup

Use `scripts/` for repeatable inspection, validation and export entry points, `tests/` for synthetic test cases, and optional `notebooks/` for exploration. Keep raw CSV under the private, ignored `analytics/data/` directory and stage generated outputs in ignored `analytics/output/`. Promote a derived file to a reviewed tracked location after its data-use and version review.

Python 3.14, with dependencies pinned in `requirements.txt`. The liveticker CSVs go in `analytics/Daten/Daten/extern-liveticker/` (ignored), or in a directory set by `SBB_DATA_DIR`. Outputs go to `analytics/output/` (ignored), or `SBB_OUTPUT_DIR`. From `analytics/`:

```sh
python -m pip install -r requirements.txt   # or inside python -m venv .venv
python -m sbb_duration.train                # metrics.json + duration_model.joblib
python -m sbb_duration.export               # duration_model.v1.json + golden_vectors.json
cd .. && python -m pytest analytics -q       # from the repo root (as CI): our tests and main's pipeline tests
python -m sbb_duration.replay [--detour 60] # 3 past incidents, terminal table + output/replay/replay-0N.json (MOCK)
python -m sbb_duration.sources [--detour 60] # 3-source timelines -> output/timelines/timeline-<id>.json + index.json (MOCK)
python scripts/run_demo.py [--offline]       # builds missing outputs, then starts the backend with the model (docs/DEMO.md)
python -m sbb_duration.pitch                  # output/pitch_numbers.md: key figures with one-sentence definitions
```

| Module | Role |
| --- | --- |
| `data.py` | Loads all incident versions (audit + current) ordered by `last_modified_date`; SHA-256 provenance. |
| `features.py` | `build_features`, shared by training, prediction and export; the Kotlin port mirrors it. |
| `train.py` | Snapshots of ongoing versions, chronological 80/20 split by incident start, P50 and pessimistic (0.85) quantile models. Metrics are measured on the split; the exported model is refitted on all snapshots. |
| `predict.py`, `advice.py` | sklearn prediction; advice codes `TAKE_DETOUR`, `DETOUR_IF_DEADLINE`, `WAIT`, `UNKNOWN`. |
| `artifact.py`, `export.py` | JSON artifact (trees, categories, regexes, provenance, metrics, explanations, limitations) and its dependency-free reference evaluator. `export` also writes `duration_model.v1.eval.json`, the split models that never saw the test period, used for replays of past incidents. |
| `explain.py` | Per-cause values from the training data: past incidents, median duration, median gap between real and announced end. |
| `replay.py` | Version-by-version replay of three test-period incidents, chosen by rule, using the split models that never saw them. |
| `sources.py` | Joins the public liveticker, VIA (`Daten/Daten/Ereignismeldungen/`) and the internal liveticker (`Daten/Daten/intern-liveticker/`). Builds one timeline per incident present in all three sources, with stations (UIC + sloid), lines, outlook, advice and `notify`. It downloads the opentransportdata.swiss service-points export to the ignored `analytics/data/service_points/` when missing; `--no-fetch` disables the download. |
| `messages.py` | Sanitizes internal messages (HTML, phone numbers, e-mails, names, user ids) and extracts replacement bus, stops, resumption forecast and resumed service with keyword rules. |

Current results on the test period (104 incidents, 639 snapshots after 2026-04-06): the P50 error is 97.9 min against 112.7 for the official announcement and 127.0 for the median per cause. The pessimistic estimate covers 83.1% of cases after a split-conformal calibration on the most recent training incidents (73.7% before; target 85%, `metrics.evaluation.calibration`). The official announcement is too optimistic in 69% of snapshots. Robustness test (`metrics.evaluation.robustness`): 80 incidents close 0–2 min after their last announced end, which looks like an automatic close at the announced time. With them removed and the split redone, the P50 error is 103.6 min against 120.1 for the announcement (88 test incidents). The API contract is in [API_CONTRACT.md](../docs/API_CONTRACT.md#disruption-outlook-proposal). The timeline format is in [API_CONTRACT.md](../docs/API_CONTRACT.md#multi-source-incident-timeline-proposal).

## Live mode: not implemented

There is no live mode; the Renens replay remains the main demo. Checked on 2026-11-21, after merging `main`:

1. **No usable key in this checkout.** `.env` exists but is empty (0 bytes), so `GTFS_RT_API_KEY` (the variable used by the team's `backend/.../gtfs/GtfsRealtimeSource.kt`) is not set. Without a key nothing could be inspected:
   - `https://api.opentransportdata.swiss/la/gtfs-rt` (TripUpdates, used by the team) answers `401` without a key;
   - `https://api.opentransportdata.swiss/la/gtfs-sa` (Service Alerts) also answers `401`, so the endpoint exists. Whether the same key opens it is unverified.
2. **The team's GTFS code reads TripUpdates only.** `GtfsDecoder` decodes `FeedEntity.trip_update` (field 3), not `alert` (field 5). Our model needs a cause, an announced end and the affected stations. TripUpdates carry none of the first two: delays, cancellations and skipped stops are effects, not causes or announced ends. A live mode needs the Service Alerts feed.
3. **The mapping of a Service Alert to our features is unproven**, even with a key:

   | Feature input | Liveticker (training data) | GTFS-RT Service Alert (to verify on real messages) |
   | --- | --- | --- |
   | `reasonCode` | SBB cause `EG_xxx` | `cause` is a 12-value enum (`ACCIDENT`, `TECHNICAL_PROBLEM`, …) with no published mapping to the 31 `EG_xxx` codes. An unmapped cause becomes a missing category. |
   | `announcedEndAt` | `duration_to` | `active_period.end` is the validity window of the alert. That it equals the announced end of the disruption is unverified. |
   | `startedAt`, `updatesSoFar` | `created_at_source`, number of published versions | `active_period.start`; the version history must be rebuilt by polling (5 requests per minute at most). |
   | affected stations | VIA UIC → sloid | `informed_entity.stop_id`: GTFS-RT stop ids are platform sloids (`ch:1:sloid:1026:2:4` in the team's captured fixture), which the team's `GtfsMatch.sameStop` already matches to UIC/sloid. This part is reliable. |
   | identifier | `alea_fall_id` | No evidence that the alert id carries the liveticker / VIA id, so live predictions cannot be checked against training labels. |
4. **No GTFS journey search exists.** Journeys come from OJP or the mock. The team's GTFS-RT matching itself links a leg to a GTFS trip through OJP stop events (`lineRef`, timetabled origin departure). `detourExtraMinutes` therefore cannot be computed "with GTFS". The demo keeps `TRANSPORT_PROVIDER=demo`.

What is implemented for GTFS: `backend/.../service/impact/GtfsDisruptionAdapter.kt`. It does not change the team's code. It finds, in a GTFS-RT TripUpdates snapshot, the trips whose stop updates (intermediate stops included) touch a VIA disruption, with the same rule as journey legs, and reports cancelled trips, skipped stops and delays at those stations. It is tested on the team's captured fixture (`GtfsDisruptionAdapterTest`).

To enable a live mode:
- put the key in `.env` (`GTFS_RT_API_KEY=…`, ignored by Git);
- record a day of `la/gtfs-sa` alerts for SBB;
- extend the decoder with `alert` (field 5: `active_period`, `informed_entity`, `cause`, `header_text`);
- verify the rows above on alerts that also appear in the liveticker;
- add a `DisruptionSource` that reads alerts, with responses labelled `LIVE`.

## Evaluation and reproducibility

- For descriptive analytics, report input and retained counts, coverage periods, missingness, and denominators where known. Share filters, grouping definitions, and sample-size limitations.
- For any supported model, separate train, validation, and test windows chronologically. Prevent a service or incident spanning a split from leaking information across windows. Fit preprocessing on training data and use only features known at the prediction instant. Hold out the final period until the evaluation protocol is stable.
- Compare with a simple baseline. Use mean absolute error in minutes for delay regression where labels support it. For a late-arrival classifier, report event prevalence, Brier score, calibration by bin with counts, and precision/recall at any threshold presented. Record uncertainty and holdout coverage before interpreting a result.
- Record the script revision, data checksum or version, pinned environment, configuration, random seed where applicable, and one command sequence that reproduces the derived output. Test invalid dates, empty and malformed files, missing required fields once known, grouping across the time split, and stable export from synthetic fixtures.

See [analytics interfaces](INTERFACES.md), [architecture](../docs/ARCHITECTURE.md), and [testing](../docs/TESTING.md).


## Sponsor deliverables

Prepare synthetic appointment and disruption cases while awaiting approved CSV access. Measure extraction calls, latency and observable usage on a fixed sample. Test disruption matching with B, including uncertain and unrelated events. [Challenge plan](../docs/CHALLENGES.md)
