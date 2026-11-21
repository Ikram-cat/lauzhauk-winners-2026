# Backend agent rules

Follow the [root agent rules](../AGENTS.md). Role B owns `backend/`. Coordinate DTO and error changes with Roles A and D. Coordinate the provider fixture content with Role C and Docker or Railway changes with Role D.

## Implementation rules

- Use `UpperCamelCase` for Kotlin class names and `lowerCamelCase` for functions and fields. Use a `Dto` suffix on serialized app boundary types, such as `JourneyResponseDto`. Give vendor request and response types names that identify their provider and keep them inside its adapter.
- Implement routes as validation, delegation, and response translation. Inject the provider and clock into testable services. Compare scheduled timestamps as instants and preserve offset-aware serialization. Use the documented tie-break order for equal arrivals.
- Use upstream itinerary and connection validity. Filter departures before the effective departure instant and handle cancellations only when reliable provider information is available. A provider failure must map to the documented error envelope. An empty result represents a successful search with zero eligible journeys.
- Preserve null or missing optional values. Keep estimates, fares, and predictions null during the MVP. Keep manually created journeys behind an explicit demo configuration or a test injection, and label demo responses `MOCK`.
- Bind to `0.0.0.0` on configured `PORT` and validate the exact CORS origins. Supply credentials through the host’s secret store if the verified provider requires any. Keep raw upstream errors, credentials, and personal query inputs out of public errors and routine logs.
- Keep provider timeouts bounded and handle rate-limiting according to the contract. Preserve coroutine cancellation. Keep `/health` independent of upstream availability.

## Acceptance checks

- T+4 requires reproducible local startup, a tested health route, contract-compliant validation and errors, and a public API returning at least one real provider itinerary. Provide the integration owner with the build and endpoint checks they can repeat.
- T+11 requires passing unit tests for deterministic ranking and provider payload parsing, and route integration tests for success, empty results, validation failure, and dependency failures. Follow the [testing matrix](../docs/TESTING.md) for the full cross-project check.
- Record provider verification evidence and limitations, changed interfaces, and any open deployment issue with the PR. Attach sanitized examples when appropriate.
