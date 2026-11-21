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

byLineHour: 1,246 (line, hour) keys over 153 line corridors, **0 with sampleSize >= 20** (max 10; 15 with >= 8). Treat all as low/medium confidence.

## Limitations

- The model predicts remaining minutes of a disruption already in the liveticker. It cannot predict whether a disruption will occur, nor per-train delay, platforms or fares.
- The P80 interval is under-covered on recent data (66% vs 80%): it is optimistic. Errors of 1-2 h are normal; the sample is small (520 incidents).
- Disruptions over 24 h and incidents without an ENDED version are dropped.
- **Key gap versus OJP:** the CSVs hold no OJP lineRef (`ojp:91008:E`) or train number. `key` is the corridor text from the description ("Lausanne - Brig"), parsed for ~80% of incidents (102 of 507 unparsable, excluded). The backend needs its own corridor-to-line mapping; no OJP refs are fabricated.
- `lateProbability` is the share of days a corridor had an active disruption in that hour, not a per-train lateness rate (no non-disrupted trips in the data). `expectedDelayMin` is the median disruption duration, an upper bound on passenger delay.
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
