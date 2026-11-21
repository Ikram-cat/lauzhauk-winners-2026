# Shared contract agent rules

Follow the [root agent rules](../AGENTS.md). Role D coordinates shared changes. Obtain review from each affected producer and consumer, including Role C when a Kotlin analytics artifact is involved.

## Rules

- Start from [API_CONTRACT.md](../docs/API_CONTRACT.md). Keep HTTP field names, optionality, enums, error codes, provenance, and time semantics aligned with application DTOs and synthetic examples.
- For Kotlin boundary DTOs, use names such as `JourneyResponseDto` and lower camel case for JSON fields. Keep Ktor internals, browser APIs, vendor DTOs, feature state, and scoring logic in their owning modules. Use explicit serialization mappings if a Kotlin name differs from its wire field name.
- Review additive fields for existing-consumer compatibility. A new enum member can break a strict decoder. Changing meaning, type, names or required fields needs an agreed migration and compatibility strategy before release.
- Preserve offset-aware timestamps and explicit unknown values. Coordinate any artifact format change with its Python producer and Kotlin consumer independently of changes to the browser HTTP contract.
- Use documented JSON and passing producer and consumer tests to verify changes. Attach migration and deployment ordering to shared-interface PRs. Record significant decisions in the architecture decision log and report unresolved compatibility issues in the handoff through Role D.

## Acceptance checks

The reviewed contract, producer output and consumer decoding agree on success, empty, and error cases, including null and omitted optional values. Release ordering preserves active clients. Adding a Kotlin module also requires build evidence for each adopting target and deployment context.
