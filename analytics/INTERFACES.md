# Analytics interfaces

## Inputs

| Input | Contract and status |
| --- | --- |
| CFF historical CSV | Approximately 30 days of incidents per the [master plan](../docs/MASTER_PLAN.md). Obtain the file through the team’s authorized channel and verify coverage, schema and usage rights. The ingestion contract will follow the audit. |
| Provider documentation and permitted samples | Used to verify transport fields and propose mapping fixtures. Record endpoint, observation date, source permissions and relevant field limitations. |
| Synthetic fixtures | Repeatable, shareable inputs for validation tests. Use the [public API example](../docs/API_CONTRACT.md) to exercise app JSON and targeted synthetic CSVs to exercise ingestion once the schema is confirmed. |

## Outputs

All outputs are generated locally in `analytics/output/`, which is ignored by Git, from data that is not committed. The data-use review is still pending, so none of these files is tracked or packaged.

| Output | Producer | Consumer | Contract |
| --- | --- | --- | --- |
| `duration_model.v1.json` | `python -m sbb_duration.export` | Kotlin `DurationArtifactLoader` (`DURATION_ARTIFACT_PATH`) | `schemaVersion` 1. Contents: tree ensembles for `p50` and `pessimistic` (with `calibrationOffset`), feature specs and categories, derivation rules (time zone, text-flag regexes), provenance (SHA-256 of the inputs), metrics, per-cause `explanations`, limitations. Unknown keys are ignored by Kotlin; an unsupported version leaves estimates unknown. |
| `duration_model.v1.eval.json` | same | same, for replays of past incidents | Same schema. It holds the split models, fitted before the test cutoff, with their own calibration and explanations. |
| `golden_vectors.json` | same | Python and Kotlin parity tests | 100 cases: raw input, features, expected outputs. Tolerance 1e-6. |
| `metrics.json` | `python -m sbb_duration.train` | team, pitch | Chronological split, MAE against the baselines, calibration before and after, robustness without auto-closed incidents. |
| `timelines/timeline-<id>.json`, `timelines/index.json` | `python -m sbb_duration.sources` | backend `ReplayTimelineDisruptionSource` (`DISRUPTION_TIMELINES_DIR`), frontend (proposal) | See [API contract, multi-source timeline](../docs/API_CONTRACT.md#multi-source-incident-timeline-proposal). |
| `replay/replay-0N.json` | `python -m sbb_duration.replay` | frontend (proposal) | `steps[].outlook` subset of the outlook response, `MOCK`. |
| `pitch_numbers.md` | `python -m sbb_duration.pitch` | pitch | Generated key figures with one-sentence definitions. |

The backend loads the artifact at startup, validates it, and keeps it in memory behind `DisruptionDurationEstimator`. An absent, unsupported or invalid artifact leaves estimates unknown (`status: UNAVAILABLE`) and logs the reason, without affecting journey search. See [backend interfaces](../backend/INTERFACES.md).
