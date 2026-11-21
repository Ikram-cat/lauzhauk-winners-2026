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

- `corridors`: one row per **undirected** endpoint pair (A-B and B-A are pooled, hours are pooled); `from`/`to` is the more frequent orientation. Parsed from "line A - B" (`source: line`, preferred) or, if absent, from "between A and B" (`source: segment`, short sub-sections, rarely joinable). `disruptionDays` = distinct local calendar days with at least one active disruption on the corridor; `disruptionProbabilityPerDay` = `disruptionDays / windowDays` (windowDays = calendar days from first to last incident, 953); `expectedDelayMin` = median disruption duration (upper bound on passenger delay); `sampleSize` = distinct incidents. Round 3: corridors with a single incident (n == 1) are dropped (`stats.corridorsDropped`; `corridorsDroppedSampleLt3` was renamed). n=2-7 are kept with `confidence: low`. Texts with >2 stops also pool each adjacent 2-stop segment as `source: segment` (implemented and unit-tested; the data has no such text).
- `hourBaseline`: **nationwide**, all parsable incidents regardless of corridor. `disruptionProbabilityPerHour(h)` = share of the window's days (953) on which at least one disruption was active *somewhere in Switzerland* during local hour h (Europe/Zurich). It is not per journey and not a lateness rate; it is a coarse time-of-day shape and an upper bound for any single journey. `sampleSize` = distinct incidents active in that hour, `expectedDelayMin` their median duration.
- `byLineHour`: unchanged definition, but cells with sampleSize < 8 are dropped (`stats.byLineHourCellsDropped`).
- `stationAliases` / `joinHints`: matching procedure for the backend; matching is on `fold()` (accent- and case-insensitive). Aliases such as Genf/Geneva/Zurich were **not found in the CSVs** (CSV names are already display forms such as `Genève`, `Zürich HB`, `Biel/Bienne`, `Fribourg/Freiburg`); they come from a static list for OJP/user input. Observed spelling variants (whitespace/accent) are added automatically; there were none in this build.

`hourBucket` is the local Europe/Zurich hour 0-23. `confidence`: sampleSize <8 low, 8-19 medium, >=20 high.

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

## Limitations

- The model predicts remaining minutes of a disruption already in the liveticker. It cannot predict whether a disruption will occur, nor per-train delay, platforms or fares.
- The P80 interval is under-covered on recent data (66% vs 80%): it is optimistic. Errors of 1-2 h are normal; the sample is small (520 incidents).
- Disruptions over 24 h and incidents without an ENDED version are dropped.
- **Key gap versus OJP:** the CSVs hold no OJP lineRef (`ojp:91008:E`) or train number. `key` is the corridor text from the description ("Lausanne - Brig"), parsed for ~80% of incidents (102 of 507 unparsable, excluded). The backend needs its own corridor-to-line mapping; no OJP refs are fabricated.
- `lateProbability` is the share of days a corridor had an active disruption in that hour, not a per-train lateness rate (no non-disrupted trips in the data). `expectedDelayMin` is the median disruption duration, an upper bound on passenger delay.
- Remaining uncertainty for the backend: only 3 corridors reach n >= 20 and 40 corridors exist, so most journeys will fall back to the nationwide `hourBaseline`. Endpoint matching is exact; a journey sharing track with a corridor but not its endpoints does not match. No multi-stop corridor text occurs in this data (`via` is implemented and unit-tested, never emitted). `windowDays` assumes the liveticker covered every day between first and last incident, which is unverified; if it had gaps the probabilities are underestimates. Disruptions over 24 h or without a known end are excluded, which biases probabilities low. Pooling both directions assumes an interruption hits both.
- Data are public-liveticker incidents only (not intern-liveticker or Ereignismeldungen).

## Original work order (kept from the scaffold)



1. Start with the transport interface while awaiting data. Review the candidate provider’s documented fields and terms, sample a permitted response, record only observed field behavior, and propose sanitized fixtures covering legs, optional platforms, and empty results. Agree on fixture placement with Role B. Use the examples in the [API contract](../docs/API_CONTRACT.md) as synthetic input for consumer checks.
2. When the CSV arrives, record source, dataset version or checksum, permitted use, and sharing constraints. Inspect encoding, delimiter, headers, row count, timestamps, coverage, duplicates, missingness, and potential personal fields locally. Determine the unit of observation, time zone, and whether non-incident services and usable labels are represented. Ask the data owner about unresolved semantics before freezing ingestion decisions.
3. Implement an ingestion report. Keep raw input read-only, parse with explicit encoding and date rules, and validate only confirmed semantics. Count or quarantine invalid records with reproducible reasons. Record filtering and deduplication rules and check that accepted and rejected counts reconcile.
4. Select the analysis based on what the audit establishes. Incident-only data may support observed counts and summaries by confirmed time or location fields. A model for late-arrival probability needs labeled outcomes and a defensible population denominator plus a verified mapping to live journeys. If those prerequisites are absent, record the limitation and scope the analysis accordingly.

## Planned layout and setup

Use `scripts/` for repeatable inspection, validation and export entry points, `tests/` for synthetic test cases, and optional `notebooks/` for exploration. Keep raw CSV under the private, ignored `analytics/data/` directory and stage generated outputs in ignored `analytics/output/`. Promote a derived file to a reviewed tracked location after its data-use and version review.

Choose and record the Python version with the first scaffold. From `analytics/`, create an isolated environment with `python -m venv .venv`. Invoke the environment’s Python directly via `.venv/bin/python` on POSIX or `.\.venv\Scripts\python.exe` in PowerShell. Pin dependencies once required and record the exact install, run, and test commands then. There are currently no scripts or test entry points here.

## Evaluation and reproducibility

- For descriptive analytics, report input and retained counts, coverage periods, missingness, and denominators where known. Share filters, grouping definitions, and sample-size limitations.
- For any supported model, separate train, validation, and test windows chronologically. Prevent a service or incident spanning a split from leaking information across windows. Fit preprocessing on training data and use only features known at the prediction instant. Hold out the final period until the evaluation protocol is stable.
- Compare with a simple baseline. Use mean absolute error in minutes for delay regression where labels support it. For a late-arrival classifier, report event prevalence, Brier score, calibration by bin with counts, and precision/recall at any threshold presented. Record uncertainty and holdout coverage before interpreting a result.
- Record the script revision, data checksum or version, pinned environment, configuration, random seed where applicable, and one command sequence that reproduces the derived output. Test invalid dates, empty and malformed files, missing required fields once known, grouping across the time split, and stable export from synthetic fixtures.

See [analytics interfaces](INTERFACES.md), [architecture](../docs/ARCHITECTURE.md), and [testing](../docs/TESTING.md).


## Sponsor deliverables

Prepare synthetic appointment and disruption cases while awaiting approved CSV access. Measure extraction calls, latency and observable usage on a fixed sample. Test disruption matching with B, including uncertain and unrelated events. [Challenge plan](../docs/CHALLENGES.md)
