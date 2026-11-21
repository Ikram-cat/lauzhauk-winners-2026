# Analytics interfaces

## Inputs

| Input | Contract and status |
| --- | --- |
| CFF historical CSV | Approximately 30 days of incidents per the [master plan](../docs/MASTER_PLAN.md). Obtain the file through the team’s authorized channel and verify coverage, schema and usage rights. The ingestion contract will follow the audit. |
| Provider documentation and permitted samples | Used to verify transport fields and propose mapping fixtures. Record endpoint, observation date, source permissions and relevant field limitations. |
| Synthetic fixtures | Repeatable, shareable inputs for validation tests. Use the [public API example](../docs/API_CONTRACT.md) to exercise app JSON and targeted synthetic CSVs to exercise ingestion once the schema is confirmed. |

## Outputs

For the MVP, share capability notes, a fixture proposal, and a schema audit if the CSV is available. The browser and search service can run without these outputs. Keep actual CSV rows private unless permission and purpose for sharing are documented.

For a later Kotlin integration, prefer a small, UTF-8 JSON artifact with a documented schema version, dataset provenance identifier, generation timestamp, coverage and method metadata, and a vetted payload. Use explicit units, time zone, and unknown-value semantics. Choose the payload fields only after the source audit establishes legitimate aggregates or a validated model. Agree on key normalization, version support, size limits, and null handling with Role B before publishing any artifact. A model binary would need a separate decision on Kotlin compatibility, packaging and evaluation evidence.

The backend may package the artifact as a read-only resource, validate it at startup, and keep it in memory behind `RiskEstimator`. An absent, unsupported or unmatched artifact leaves risk unknown and logs the reason without compromising journey search or exposing raw data. Any UI-visible historical provenance or probability requires a reviewed API contract update. See [backend interfaces](../backend/INTERFACES.md).
