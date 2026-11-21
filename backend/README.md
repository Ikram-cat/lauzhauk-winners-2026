# CFF Plan B – Backend

Ktor backend for the CFF Plan B travel assistant. Serves `GET /health` and `GET /api/journeys` with journey search and recommendation ranking, and `POST /api/disruptions/outlook` (proposal) with a disruption duration estimate and wait-or-detour advice.

## Prerequisites

- JDK 21 (Temurin recommended). Set `JAVA_HOME` if not on PATH.
- No other system dependencies required (Gradle wrapper is bundled).

## Quick start

```bash
cd backend
export JAVA_HOME=/path/to/jdk-21   # if not already on PATH
./gradlew --no-daemon run
```

Then:
```bash
curl http://localhost:8080/health
curl "http://localhost:8080/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve"
```

## Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `PORT` | No | `8080` | Listen port |
| `CORS_ALLOWED_ORIGINS` | No | (any) | Comma-separated allowed origins, e.g. `https://app.vercel.app` |
| `TRANSPORT_PROVIDER` | No | `mock` | `mock` or `ojp` |
| `OJP_API_KEY` | When `ojp` | — | Bearer token for opentransportdata.swiss. Never hardcoded. |
| `DURATION_ARTIFACT_PATH` | No | (unset) | Path to `duration_model.v1.json` produced by `analytics/` (`python -m sbb_duration.train` then `python -m sbb_duration.export`). Unset, missing, invalid or unsupported: one warning at startup and `/api/disruptions/outlook` answers `status: UNAVAILABLE`. |
| `DISRUPTION_TIMELINES_DIR` | No | (unset) | Directory of analytics `timeline-*.json` (`python -m sbb_duration.sources`), replayed as active disruptions (`MOCK`) by `POST /api/journeys/impact`. Unset: no journey is affected. |

## Running tests

```bash
./gradlew --no-daemon test
```

`DurationGoldenVectorsTest` checks the Kotlin evaluator against the 100 cases in `../analytics/output/golden_vectors.json`, within 1e-6 of Python. It is skipped when the analytics output has not been generated locally. Override the paths with `DURATION_ARTIFACT_PATH` and `DURATION_GOLDEN_PATH`.

## Disruption outlook with the model

```bash
cd analytics && python -m sbb_duration.train && python -m sbb_duration.export && cd ..
cd backend
DURATION_ARTIFACT_PATH=../analytics/output/duration_model.v1.json ./gradlew --no-daemon run
curl -X POST http://localhost:8080/api/disruptions/outlook -H "Content-Type: application/json"   -d '{"disruption":{"reasonCode":"EG_008","durationType":"UNTIL","startedAt":"2026-11-21T07:40:00+01:00","announcedEndAt":"2026-11-21T09:00:00+01:00","updatesSoFar":2,"summaryEn":"Interruption between Lausanne and Genève"},"observedAt":"2026-11-21T08:00:00+01:00","detourExtraMinutes":35}'
```

The artifact is not committed and the Docker image does not include it. Until the data-use review is done, a deployed backend answers `UNAVAILABLE`.

## Building a JAR / distribution

```bash
./gradlew --no-daemon installDist
./build/install/cff-plan-b-backend/bin/cff-plan-b-backend
```

## Docker

```bash
docker build -t cff-plan-b-backend .
docker run -p 8080:8080 -e TRANSPORT_PROVIDER=mock cff-plan-b-backend
```

For OJP live data:
```bash
docker run -p 8080:8080 \
  -e TRANSPORT_PROVIDER=ojp \
  -e OJP_API_KEY=<your-key> \
  -e CORS_ALLOWED_ORIGINS=https://your-frontend.vercel.app \
  cff-plan-b-backend
```

## API Contract

See [`docs/API_CONTRACT.md`](../docs/API_CONTRACT.md) for the full HTTP contract.

| Endpoint | Method | Description |
|---|---|---|
| `/health` | GET | Returns `{"status":"ok"}`. Makes no provider calls. |
| `/api/journeys` | GET | Journey search. Params: `origin` (required), `destination` (required), `departure` (optional RFC 3339 with offset). |
| `/api/disruptions/outlook` | POST | Proposal. Remaining-duration estimate (P50 and pessimistic) and `advice.code` for a disruption. See the contract. |
| `/api/journeys/impact` | POST | Proposal. Checks the best journey against active disruptions, computes `detourExtraMinutes` from the best unaffected alternative, returns the outlook. |

### Error codes

| HTTP | Code | Trigger |
|---|---|---|
| 400 | `INVALID_REQUEST` | Missing/invalid/duplicate/unknown parameter |
| 500 | `INTERNAL_ERROR` | Unexpected server error |
| 502 | `UPSTREAM_ERROR` | Provider failure |
| 503 | `UPSTREAM_RATE_LIMITED` | Provider throttling |
| 504 | `UPSTREAM_TIMEOUT` | Provider timeout (8s) |

## Package structure

```
com.cffplanb.backend
├── Application.kt          entry point
├── api/
│   ├── Dtos.kt             wire DTOs (@Serializable)
│   ├── DisruptionDtos.kt   outlook request/response DTOs
│   ├── DisruptionRoutes.kt POST /api/disruptions/outlook
│   └── Routes.kt           Ktor routing + validation
├── config/
│   ├── AppConfig.kt        env var reader
│   └── Plugins.kt          Ktor plugin wiring
├── domain/
│   ├── Disruption.kt       DisruptionContext, DurationEstimate, AdviceCode
│   └── Models.kt           Journey, JourneyLeg, SearchRequest, enums
├── provider/
│   ├── TransportProvider.kt  interface + ProviderResult sealed class
│   ├── MockTransportProvider.kt  deterministic mock (MOCK dataStatus)
│   └── OjpTransportProvider.kt   OJP 2.0 adapter
└── service/
    ├── AdviceService.kt          wait-or-detour rule (port of analytics advice.py)
    ├── JourneySearchService.kt   orchestrates provider + ranking
    ├── RecommendationService.kt  pure ranking function
    ├── duration/                 artifact DTOs, loader, features, tree evaluator
    └── impact/                   disruption source (timeline replay), journey matcher, impact service
```

## OJP provider notes

The OJP adapter posts OJP 2.0 XML to `https://api.opentransportdata.swiss/ojp2020`. It:
- Sends a `TripRequest` with origin/destination as free-text stop names
- Has an 8-second bounded timeout
- Maps HTTP 429 → `UPSTREAM_RATE_LIMITED`, timeout → `UPSTREAM_TIMEOUT`, other errors → `UPSTREAM_ERROR`
- Sets `dataStatus: LIVE_SCHEDULE` on all parsed journeys
- Keeps all XML parsing and OJP DTOs private to the adapter

The `OJP_API_KEY` is passed as a bearer token in the `Authorization` header. It is never logged, committed, or included in error responses.
