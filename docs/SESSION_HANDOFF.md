# Session handoff

Check the branch, revision, and affected files before relying on an entry. Use the [roadmap](ROADMAP.md) for ownership and task status. Keep decisions with lasting architectural impact in [DECISIONS.md](DECISIONS.md).

## Entry format

Add dated entries with the newest first. Include the responsible role, branch and latest relevant commit, what was completed, paths changed, commands and results, current implementation status, known bugs and blockers, pending decisions, dependencies affecting other owners, and prioritized next actions. Link to evidence when available. Identify uncommitted changes explicitly. For concurrent sessions, send the entry and task IDs to D for consolidation in the relevant PR and preserve other entries during conflict resolution.

## 2026-11-21 · B · Complete Ktor backend implementation

**Branch and commit**
`feat/backend` at `47a3d66732f97bfcba940cfcbb6701c3515640cf`

**Completed and files changed**

- Created complete Gradle Kotlin DSL project (Kotlin 2.0.21, Ktor 3.0.1, JDK 21 toolchain).
- Gradle wrapper generated and checked in (`gradlew`, `gradlew.bat`, `gradle/wrapper/`).
- Implemented all packages: `domain`, `provider`, `service`, `api`, `config`.
- `MockTransportProvider`: 3 deterministic journeys relative to `earliestDeparture`, `dataStatus=MOCK`.
- `OjpTransportProvider`: OJP 2.0 XML POST with 8s timeout, Bearer auth, namespace-aware XML parser. Maps HTTP 429→RateLimited, timeout→Timeout, errors→Failure. API key never logged.
- `RecommendationService.rank()`: filters by `earliestDeparture`, sorts arrival→transfers→departure→id.
- `JourneySearchService`: delegates to provider, maps `ProviderResult` to `SearchResult`.
- Route validation: missing/blank/>120cp origin/destination, duplicate params (raw query parse), unknown params, invalid departure (no offset), RFC 3339 required.
- Error envelope: `INVALID_REQUEST`(400), `UPSTREAM_ERROR`(502), `UPSTREAM_RATE_LIMITED`(503), `UPSTREAM_TIMEOUT`(504), `INTERNAL_ERROR`(500).
- Tests: `RecommendationServiceTest` (6 cases), `OjpProviderTest` (8 cases incl. fixture), `RouteTest` (16 Ktor integration cases). All pass offline.
- OJP fixture: `src/test/resources/ojp_fixture.xml` with direct IC and transfer+walk trip.
- Dockerfile: multi-stage eclipse-temurin:21 build + JRE runtime.
- Updated `backend/README.md` with commands, env vars, package structure, OJP notes.

**Files changed**
`backend/build.gradle.kts`, `backend/settings.gradle.kts`, `backend/gradle.properties`, `backend/gradlew`, `backend/gradlew.bat`, `backend/gradle/wrapper/*`, `backend/Dockerfile`, `backend/README.md`, `backend/src/main/kotlin/com/cffplanb/backend/**`, `backend/src/test/kotlin/com/cffplanb/backend/**`, `backend/src/test/resources/ojp_fixture.xml`, `backend/src/main/resources/logback.xml`

**Verification**
```
./gradlew --no-daemon test   → BUILD SUCCESSFUL (all tests pass)
./gradlew --no-daemon build  → BUILD SUCCESSFUL
curl http://localhost:18080/health → {"status":"ok"}
curl "/api/journeys?origin=Lausanne&destination=Genève&departure=2026-11-21T16:00:00+01:00"
  → 200 with 3 ranked MOCK journeys, mock-direct-1 first (arrival 17:00)
curl "/api/journeys?origin=Lausanne&destination=Bern&unknown=x" → 400 INVALID_REQUEST
curl "/api/journeys?origin=&destination=Bern" → 400 INVALID_REQUEST
curl "/api/journeys?origin=Lausanne&origin=Bern&destination=Geneve" → 400 INVALID_REQUEST
```

**Implementation status**
B1 (health + mock + validation) and B2 (OJP adapter) tasks are complete. The OJP live endpoint requires `OJP_API_KEY` env var from opentransportdata.swiss. Live OJP calls were not tested (no key available offline). XML parsing uses namespace-aware DOM; production correctness depends on actual OJP 2.0 endpoint field names.

**Blockers**
None for backend. Live OJP validation requires the API key (set `TRANSPORT_PROVIDER=ojp OJP_API_KEY=<key>`).

**Dependencies affecting other owners**
- Frontend (A): API is live at `http://localhost:8080` with mock. Set `CORS_ALLOWED_ORIGINS` to the Vercel origin.
- Integration (D): Set `CORS_ALLOWED_ORIGINS=https://<vercel-url>` in Railway config. `PORT` defaults to 8080. Push `feat/backend` branch and open PR.

**Next actions (priority order)**
1. D: Deploy `feat/backend` to Railway, set `CORS_ALLOWED_ORIGINS`, confirm `/health` publicly.
2. A: Point frontend at the Railway URL, test CORS and journey display.
3. B: Obtain OJP API key, switch `TRANSPORT_PROVIDER=ojp`, validate live parsing.
4. B: B3 — edge cases: midnight rollover, empty OJP response, provider failure flows.

---

## 2026-11-21 - D - Sponsor criteria alignment

Prepared on `feature/integration` from merged baseline `0d1e8a6`. Reviewed the official AXA, JetBrains and SBB briefs and added `CHALLENGES.md`. Updated root and module onboarding, architecture and interface planning, deployment, testing, demo and workflow guides, and roadmap. The master plan remains the original baseline. No application source, builds, deployments or challenge evidence exist in this checkout. Next, agree the second KMP target and extraction/recovery contracts, then execute F4, B5, F5 and I6.

## 2026-11-21 · D · Integration and documentation

**Branch and commit**  
`main` at `ab843e95c35f94d5a35953e582fd382a89123551` (`Initial commit`), tracking `origin/main` in the local checkout. The documentation changes are in the working tree and have no commit yet. The initial checkout was clean.

**Completed and files changed**

- Updated root `README.md` and `.gitignore`, and added `AGENTS.md` and `.env.example`.
- Copied the supplied master plan verbatim to `docs/MASTER_PLAN.md`. Added architecture, API contract, workflow, deployment, testing, decisions, roadmap, demo script, and this handoff under `docs/`.
- Added `README.md`, `AGENTS.md`, and `INTERFACES.md` under `frontend/`, `backend/`, `analytics/`, and `shared/`. Added `.github/PULL_REQUEST_TEMPLATE.md` and `.github/workflows/README.md`.

**Verification**  
- `git diff --check`, working tree inventory, and tracked diff inspection passed. `LICENSE` is unchanged. The copy of the master plan matches the supplied file by SHA-256.
- Parsed all 26 Markdown files, validated local link targets and fragments, and checked authored Markdown fences and whitespace. Parsed the four API JSON examples and checked mock provenance, null estimates, effective departure, leg times, and duration.
- `git check-ignore` confirmed local environment, raw and staged analytics data, and build files are ignored. The environment template and approved fixture paths are eligible for tracking.
- Mermaid diagrams were reviewed in source. Browser rendering, application builds and tests, CI, provider calls, and deployment were not verified in this session.

**Implementation status and blockers**  
The repository holds the documentation baseline. Application sources, wrappers, deployable artifacts, and an executable CI workflow are pending. No application behavior has been tested. The candidate transport provider has no recorded live validation in the repository. Historical data access, schema, coverage, and usage terms are unverified. No application bugs are recorded at this stage.

**Pending decisions and checks**

- Confirm the v1 contract with A, B, and D before independent implementations merge.
- Choose the frontend toolchain at the 45-minute browser build gate. Record the implemented build and output paths before wiring CI or Vercel.
- Verify provider semantics and permitted use, Railway/Vercel project access and quotas, official kickoff and submission timing, and challenge requirements.
- Defer historical artifact and model decisions until C verifies the supplied data and available journey join keys.

**Cross-module dependencies and next actions**

1. D coordinates review of I0 so fresh checkouts can start from a shared revision of the documentation. D starts I1 and convenes the contract review.
2. A starts F1 against the documented fixture shape. B starts B1 with health, route validation, and a test/demo adapter. Both send verified build commands to D.
3. C starts A1 and shares the audited provider fields and sanitized mapping cases with B. CSV work is independent of the public vertical slice.
4. D coordinates B2, F2, and I2 once the local slice works and service access is confirmed.
