# Analytics

Role C owns `analytics/` on `feature/analytics`. Python supports offline data validation, exploratory statistics and, if the observations support it, predictive modeling. The browser and Ktor MVP can start independently.

**Current state.** The `sbb_duration` package trains a model of the remaining duration of SBB disruptions on the public liveticker export (2024–2026), evaluates it chronologically, and exports it as JSON for the Kotlin backend. It also replays past incidents for the demo. The data's usage rights are not yet reviewed: the CSVs, the trained model and every generated file stay local and out of Git.

## Work order

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
python -m pytest tests -p no:cacheprovider  # JSON evaluator == sklearn, advice thresholds, pessimistic >= p50
python -m sbb_duration.replay [--detour 60] # 3 past incidents, terminal table + output/replay/replay-0N.json (MOCK)
python -m sbb_duration.sources [--detour 60] # 3-source timelines -> output/timelines/timeline-<id>.json + index.json (MOCK)
```

| Module | Role |
| --- | --- |
| `data.py` | Loads all incident versions (audit + current) ordered by `last_modified_date`; SHA-256 provenance. |
| `features.py` | `build_features`, shared by training, prediction and export; the Kotlin port mirrors it. |
| `train.py` | Snapshots of ongoing versions, chronological 80/20 split by incident start, P50 and pessimistic (0.85) quantile models. Metrics are measured on the split; the exported model is refitted on all snapshots. |
| `predict.py`, `advice.py` | sklearn prediction; advice codes `TAKE_DETOUR`, `DETOUR_IF_DEADLINE`, `WAIT`, `UNKNOWN`. |
| `artifact.py`, `export.py` | JSON artifact (trees, categories, regexes, provenance, metrics, limitations) and its dependency-free reference evaluator. |
| `replay.py` | Version-by-version replay of three test-period incidents, chosen by rule, using the split models that never saw them. |
| `sources.py` | Joins the public liveticker, VIA (`Daten/Daten/Ereignismeldungen/`) and the internal liveticker (`Daten/Daten/intern-liveticker/`). Builds one timeline per incident present in all three sources, with stations (UIC + sloid), lines, outlook, advice and `notify`. It downloads the opentransportdata.swiss service-points export to the ignored `analytics/data/service_points/` when missing; `--no-fetch` disables the download. |
| `messages.py` | Sanitizes internal messages (HTML, phone numbers, e-mails, names, user ids) and extracts replacement bus, stops, resumption forecast and resumed service with keyword rules. |

Current results on the test period (104 incidents, 639 snapshots after 2026-04-06): the P50 error is 97.9 min against 112.7 for the official announcement and 127.0 for the median per cause. The pessimistic estimate covers 83.1% of cases after a split-conformal calibration on the most recent training incidents (73.7% before; target 85%, `metrics.evaluation.calibration`). The official announcement is too optimistic in 69% of snapshots. Robustness test (`metrics.evaluation.robustness`): 80 incidents close 0–2 min after their last announced end, which looks like an automatic close at the announced time. With them removed and the split redone, the P50 error is 103.6 min against 120.1 for the announcement (88 test incidents). The API contract is in [API_CONTRACT.md](../docs/API_CONTRACT.md#disruption-outlook-proposal). The timeline format is in [API_CONTRACT.md](../docs/API_CONTRACT.md#multi-source-incident-timeline-proposal).

## Evaluation and reproducibility

- For descriptive analytics, report input and retained counts, coverage periods, missingness, and denominators where known. Share filters, grouping definitions, and sample-size limitations.
- For any supported model, separate train, validation, and test windows chronologically. Prevent a service or incident spanning a split from leaking information across windows. Fit preprocessing on training data and use only features known at the prediction instant. Hold out the final period until the evaluation protocol is stable.
- Compare with a simple baseline. Use mean absolute error in minutes for delay regression where labels support it. For a late-arrival classifier, report event prevalence, Brier score, calibration by bin with counts, and precision/recall at any threshold presented. Record uncertainty and holdout coverage before interpreting a result.
- Record the script revision, data checksum or version, pinned environment, configuration, random seed where applicable, and one command sequence that reproduces the derived output. Test invalid dates, empty and malformed files, missing required fields once known, grouping across the time split, and stable export from synthetic fixtures.

See [analytics interfaces](INTERFACES.md), [architecture](../docs/ARCHITECTURE.md), and [testing](../docs/TESTING.md).


## Sponsor deliverables

Prepare synthetic appointment and disruption cases while awaiting approved CSV access. Measure extraction calls, latency and observable usage on a fixed sample. Test disruption matching with B, including uncertain and unrelated events. [Challenge plan](../docs/CHALLENGES.md)
