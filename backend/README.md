# CFF Plan B – Backend

Ktor backend for the CFF Plan B travel assistant. Serves `GET /health` and `GET /api/journeys` with journey search and recommendation ranking.

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
| `RISK_SOURCE` | No | `artifact` | `artifact` or `none` (all risk values null) |
| `RISK_ARTIFACT_PATH` | No | `/app/risk/predictions.json` | Falls back to `analytics/artifacts/predictions.json`. Reloaded when mtime changes. |

## Running tests

```bash
./gradlew --no-daemon test
```

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
| `/api/journeys` | GET | Journey search. Params: `origin`, `destination` (required), `departure`, `arrivalDeadline` (optional RFC 3339 with offset). With a deadline, each journey gets `feasible` and the response a nullable `latestFeasibleDeparture`; infeasible journeys stay listed. Risk fields are filled only for an exact line+hour artifact match with non-low confidence. |
| `/api/locations` | GET | `?query=` autocomplete (stop/address, max 10, cached). 400 on empty query. |
| `/api/model` | GET | Risk artifact metadata: `modelVersion`, `generatedAt`, `coverage`, `source`, `keysLoaded`, `status` (`ok`/`missing`/`stale`). |

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
│   └── Routes.kt           Ktor routing + validation
├── config/
│   ├── AppConfig.kt        env var reader
│   └── Plugins.kt          Ktor plugin wiring
├── domain/
│   └── Models.kt           Journey, JourneyLeg, SearchRequest, enums
├── provider/
│   ├── TransportProvider.kt  interface + ProviderResult sealed class
│   ├── MockTransportProvider.kt  deterministic mock (MOCK dataStatus)
│   └── OjpTransportProvider.kt   OJP 2.0 adapter
└── service/
    ├── JourneySearchService.kt   orchestrates provider + ranking
    └── RecommendationService.kt  pure ranking function
```

## OJP provider notes

The OJP adapter posts OJP 2.0 XML to `https://api.opentransportdata.swiss/ojp2020`. It:
- Sends a `TripRequest` with origin/destination as free-text stop names
- Has an 8-second bounded timeout
- Maps HTTP 429 → `UPSTREAM_RATE_LIMITED`, timeout → `UPSTREAM_TIMEOUT`, other errors → `UPSTREAM_ERROR`
- Sets `dataStatus: LIVE_SCHEDULE` on all parsed journeys
- Keeps all XML parsing and OJP DTOs private to the adapter

The `OJP_API_KEY` is passed as a bearer token in the `Authorization` header. It is never logged, committed, or included in error responses.
