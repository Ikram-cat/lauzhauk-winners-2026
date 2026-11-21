# Shared interfaces

| Boundary | Source of truth | Owners |
| --- | --- | --- |
| Frontend ↔ backend HTTPS JSON | [API contract](../docs/API_CONTRACT.md) | A and B implement. D coordinates contract reviews and rollout. |
| Analytics → backend offline artifacts, later | [Analytics interfaces](../analytics/INTERFACES.md), then a reviewed artifact schema | C produces, B consumes, and D coordinates the change. |

## Serialization and compatibility

Use JSON DTOs independent of UI presentation, backend domain types, and the external provider’s payload. Preserve `lowerCamelCase` wire fields, offset-aware time strings, and version-specific enum values. Keep the contract’s definition of required, nullable and optional fields explicit. Producer and consumer must agree on supported versions and how they handle unsupported values.

For the initial JSON contract, keep documented examples usable as synthetic fixtures by either module. An additive optional field is usually deployable after verifying the old consumer tolerates it. An incompatible change requires an agreed transition that serves both versions or coordinates the consumers, producers and deployment order. Version analytics artifacts independently so the Kotlin loader can reject unknown versions while continuing scheduled journey search.

For an approved shared Kotlin module, keep reusable DTOs and their serialization tests platform-neutral and preserve the same HTTP JSON wire representation. Link back to the contract for field defaults and date/time semantics. See the [shared README](README.md) for its adoption criteria.
