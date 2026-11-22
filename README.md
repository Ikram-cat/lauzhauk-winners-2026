# CFF Plan B

CFF Plan B is a four-person LauzHack 2026 project for Swiss public-transport journeys. When a traveler needs to reach a commitment on time, the app aims to show the best feasible itinerary, explain what is known about arrival reliability, and offer a practical alternative when one exists. The first release focuses on finding a real itinerary from a public browser.

## Delivery and status

| Milestone | Exit condition | Status |
| --- | --- | --- |
| Repository baseline | A new agent can find its assignment, the JSON contract, and the current state | Documentation present. See the handoff for verification. |
| T+4 h | Public Vercel Web app calls public Railway Ktor API and displays at least one actual transport itinerary | Planned |
| T+11 h | Deployable `main`, reliable search, alternatives where supplied, ranking and parsing tests, public smoke test, and a recorded known-good checkpoint | Planned |
| T+22 h | Final submission with a preserved working demo and at least one verified, distinctive Plan B capability | Planned |

Times are relative to actual kickoff. Integration records the kickoff and checks the official submission deadline. The MVP takes origin, destination, and an optional earliest departure and sorts valid itineraries by scheduled arrival. It includes loading, error, and empty results, and shows platforms when supplied. Deadline-aware recommendations, validated historical insights, workspace suggestions, iOS, and Android are future work. Accounts and a database are outside the MVP.

The backend is deployed and public (see below); the Vercel Web app, executable CI, and Compose Web/Wasm viability are still pending or unverified. Samples in the API contract are illustrative. Runtime mock data must be visibly identified in the UI.

**Live backend** ([runbook](docs/DEPLOYMENT.md#backend-on-railway)):
`https://lauzhauk-backend-production.up.railway.app` — `GET /health`, `GET /api/locations`, `GET /api/journeys`, `GET /api/model`, backed by OJP and GTFS-RT. The backend README documents the request rules and the additive predictive-delay fields.

## Architecture and ownership

| Owner | Area | Responsibility |
| --- | --- | --- |
| A | [Frontend](frontend/README.md) | Compose Multiplatform Web on Vercel if the Wasm build gate passes, browser UI state, and a client for the Ktor JSON API |
| B | [Backend](backend/README.md) | Ktor on Railway, request validation, Swiss transport adapter, normalized journey DTOs, and scheduled-arrival ranking |
| C | [Analytics](analytics/README.md) | Provider field audit and fixtures, then offline Python analysis after the historical CSV is validated |
| D | [Integration](docs/DEVELOPMENT_WORKFLOW.md) | Shared contracts, CI, deployment coordination, public smoke tests, and cross-module handoff |
| Coordinated | [Shared](shared/README.md) | Documented JSON contracts and compatibility rules. A Kotlin shared module is optional and has no build of its own yet. |

The browser calls Ktor over HTTPS. Ktor owns transport-provider calls and mapping, so frontend models can follow the project-owned contract. Later, Ktor can load versioned analytics artifacts generated offline. See [Architecture](docs/ARCHITECTURE.md) and the [API contract](docs/API_CONTRACT.md).

## Local setup and verification

From a clone, check the base revision and read the onboarding files before editing.

```text
git status --short --branch
git log -5 --oneline
git diff --check
```

Copy [.env.example](.env.example) to a local ignored `.env` if useful. Pass the variables to the module's documented launcher or hosting environment. The template itself does not load them. The frontend needs a public API base URL and the backend needs the exact browser origin in its CORS allowlist.

As each toolchain lands, use its module README for the pinned prerequisites and runnable commands. The planned sequence is to run the Ktor tests and start the API, confirm `GET /health` locally, start the Web development build pointed at the API, then exercise search from the browser. Analytics runs separately with its documented Python environment and approved input. See [Testing](docs/TESTING.md) for the smoke matrix and [Deployment](docs/DEPLOYMENT.md) for build outputs, configuration, verification, and rollback.

## GitHub workflow

Keep `main` deployable once the runnable baseline lands. Start the assigned feature branch from `main` and integrate small reviewed PRs using the [PR template](.github/PULL_REQUEST_TEMPLATE.md). Coordinate contract changes with their consumers and leave remote settings to authorized maintainers. Use the [development workflow](docs/DEVELOPMENT_WORKFLOW.md) for branch names, conflict resolution, and integration responsibilities.

## Project documentation

- [Agent rules](AGENTS.md) and [master plan](docs/MASTER_PLAN.md) for session setup, scope, and responsibilities.
- [Architecture](docs/ARCHITECTURE.md), [API contract](docs/API_CONTRACT.md), and [decision log](docs/DECISIONS.md) for stable designs and interfaces.
- [Workflow](docs/DEVELOPMENT_WORKFLOW.md), [deployment](docs/DEPLOYMENT.md), [testing](docs/TESTING.md), [demo script](docs/DEMO_SCRIPT.md), and [CI plan](.github/workflows/README.md) for delivery procedures.
- [Roadmap](docs/ROADMAP.md) and [session handoff](docs/SESSION_HANDOFF.md) for the active work queue and verified state.
- Module guidance for [frontend rules](frontend/AGENTS.md) and [interfaces](frontend/INTERFACES.md), [backend rules](backend/AGENTS.md) and [interfaces](backend/INTERFACES.md), [analytics rules](analytics/AGENTS.md) and [interfaces](analytics/INTERFACES.md), and [shared rules](shared/AGENTS.md) and [interfaces](shared/INTERFACES.md).


## Sponsor challenge submission

The [challenge plan](docs/CHALLENGES.md) maps the official JetBrains, AXA and SBB/CFF briefs to owners and demo evidence. Beyond the Web MVP, the submission targets Web and desktop KMP, shared Compose, French and English, light and dark themes, bounded screenshot-plus-text extraction, and personalized disruption recovery. These features are planned.
