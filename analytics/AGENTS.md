# Analytics agent rules

Follow the [root agent rules](../AGENTS.md). Role C owns `analytics/`. Coordinate provider fixtures with Role B and changes to exported artifacts with Roles B and D.

## Implementation rules

- Use `snake_case` for Python modules, functions, and variables and `UpperCamelCase` for classes. Separate file ingestion, validation, analysis, and export so each can run on synthetic test inputs.
- Confirm dataset permission before quoting or publishing source content. Keep raw input, private paths, credentials, and row-level sensitive details out of the repository, logs, and generated artifacts.
- Base column names, types, timestamp interpretation, labels, and linkage exclusively on inspected data or verified documentation. Mark uncertain semantics and unresolved fields in the audit. Keep fixture fields explicitly synthetic where they illustrate behavior independently of the supplied CSV.
- Treat incident-only summaries as observed historical patterns. Estimate probabilities for the general journey population only with a validated denominator, labels, and linkage. Report missingness and dataset limitations alongside each relevant result.
- Split chronologically before fitting preprocessing or tuning. Enforce feature availability at prediction time. Record chronological boundaries, baseline, metric definitions, and sample sizes for any evaluated model.
- Keep dependencies pinned and analysis reproducible. Publish versioned, sanitized artifacts only after Kotlin parser and data-use review. Keep ingestion, fitting, and inference-service dependencies out of the runtime Web-to-Ktor path.

## Acceptance checks

- The first task closes when observed provider capabilities and limitations are documented and fixture coverage has been proposed to Role B with a reproducible verification method. CSV access is independent of that task.
- An ingestion task closes when the schema and permissions are evidenced, row counts reconcile, validation can be reproduced on synthetic test data, and unresolved data issues are listed.
- A future export task closes when the Kotlin reader can parse the agreed fixture, unsupported versions and missing coverage yield unknown risk, and the reviewed artifact includes provenance, scope and limitations. Record the verification in the PR and notify the integration owner.
