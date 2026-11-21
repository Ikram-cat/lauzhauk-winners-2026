# Backend

Role B owns `backend/` on `feature/backend-ktor`. Ktor provides a stateless HTTP API, obtains valid itineraries from a Swiss transport provider, maps them to app-owned data, and ranks eligible trips by earliest scheduled arrival. The MVP has no database and needs no analytics artifacts.

**Current state.** This directory contains documentation. The Ktor project, HTTP routes, adapter, Dockerfile, and Railway service deployment are planned. Provider capabilities and deployed connectivity are unverified.

## Planned architecture

| Package under `src/main/kotlin/` | Responsibility |
| --- | --- |
| `api` | Ktor routes, query validation, JSON DTOs, mapping to and from domain models, and HTTP error handling. |
| `domain` | Provider-neutral search requests, journeys, legs, and provenance. |
| `service` | `JourneySearchService` orchestration and `RecommendationService` eligibility checks and deterministic ordering. |
| `provider` | `TransportProvider` interface, Swiss API client and mapping, and explicit demo adapter if needed. |
| `config` | Startup configuration, Ktor plugins, logging, and dependency wiring. |

Wire a single injected clock for server defaults and deterministic tests. Keep request validation and public response schemas at the route boundary. Keep vendor-specific DTOs and status mapping in the provider adapter. Isolate ranking so it can run on fixtures without a server. Use Ktor JSON negotiation, centralized error handling, and CORS for the permitted browser origins.

## Implementation and local checks

1. Create the JVM Gradle wrapper and pin the toolchain. Add `GET /health` with a route test. Run the service locally before adding provider wiring. Coordinate the Dockerfile and Railway health check with Role D.
2. Implement `GET /api/journeys` with the proposed contract and a fixture-backed provider for tests. Validate the candidate upstream’s terms, limits, timestamps, and actual fields before implementing its mapping. Set bounded request timeouts and map upstream failures to the contract.
3. Test the real search end to end, including timestamp eligibility and ordering. Handle incomplete optional fields, empty results, provider cancellation signals where available, and provider failures. Retain explicit demo behavior for a rehearsed fallback.
4. At T+11, verify multiple returned itineraries are mapped and ordered correctly. Later, expand `RecommendationService` for feature-approved preferences and deadlines and enable `RiskEstimator` only for an agreed, validated artifact.

After scaffolding, install the pinned JDK and run the Gradle wrapper from `backend/`. On Windows PowerShell run `.\gradlew.bat`. The planned Gradle Application entry point and test commands are below. [Ktor server execution](https://ktor.io/docs/server-run.html), [Ktor server tests](https://ktor.io/docs/server-testing.html).

```sh
./gradlew run
./gradlew test
```

Configure `PORT` and `CORS_ALLOWED_ORIGINS` for local tests and deployment using the documented settings. Verify a local health request against the selected port. Use `testApplication` with a fake `TransportProvider` and fixed clock to test route responses. Run adapter mapping tests against fixtures and isolate live-provider verification in manual or dedicated integration smoke tests. Build the Docker image from the module after `Dockerfile` lands and verify its published health endpoint before deploying it to Railway. Record the actual commands in this README once verified.

See [backend interfaces](INTERFACES.md), [API contract](../docs/API_CONTRACT.md), [deployment](../docs/DEPLOYMENT.md), and [testing](../docs/TESTING.md).
