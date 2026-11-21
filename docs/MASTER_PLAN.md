# CFF Plan B — LauzHack 2026
## Master project brief, architecture, milestones and AI handoff

**Status:** planning baseline, 21 November 2026.  
**Team:** 4 developers. **Deadline:** Sunday 22 November 2026, around noon (verify official submission time).  
**Time budget:** approximately 22 hours from initial planning. Milestones are relative to actual kickoff, not fixed clock times.  
**Language:** French for collaboration; technical names/code in English.  
**Primary delivery:** publicly accessible **Web app**. **iOS** is a later objective (no Mac/Xcode available); **Android** optional, emulator only.  
**Deployment choice:** **Vercel** frontend, **Railway** Kotlin backend, **GitHub** monorepo.  
**JetBrains angle:** Kotlin end-to-end wherever practical, Kotlin Multiplatform/Compose for UI if Web toolchain validates, Ktor backend. Challenge criteria must be checked rather than assumed.

---

## 1. Product vision

**CFF Plan B** is a personalized Swiss public-transport journey planner that helps people reach important commitments despite disruptions. Instead of only showing the scheduled fastest route, it eventually ranks viable alternatives by travel time, reliability, simplicity, and (where reliable fare data exists) price, and proposes recovery actions if the user's schedule becomes impossible.

**Core user story:** “I need to travel from Lausanne to Geneva and arrive by 14:00. Show the best route, explain the risk of being late, and offer a practical Plan B.”

**Differentiator:** reliable-arrival recommendations, not merely duplicating a train timetable.

### Product principles

- Ship a **real end-to-end vertical slice** before predictive features.
- Keep `main` deployable at all times.
- Show clearly whether data is **live**, **historical**, **estimated**, or **demo/mock**.
- Prefer a working, explainable heuristic to a sophisticated model that cannot be validated.
- Avoid feature creep: do not block the MVP on iOS, ML, price feeds, voice, maps, or databases.

---

## 2. Confirmed constraints and open questions

### Confirmed / stated by team

- 4 team members.
- About 22 hours total, with a deliverable on `main` by approximately **50% of remaining time** (~T+11 h).
- First significant milestone at **T+4 h**.
- CFF-provided data is a **CSV covering roughly 30 days of historical incidents**. **Column names/schema not yet known.**
- No Mac, so no local iOS build/simulator.
- No physical Android device; emulator is possible.
- Vercel already set up by a team member; Railway preferred for backend.
- Codex, Gemini, and JetBrains AI available as development aids; avoid overlapping agents modifying the same files.

### Must verify

1. Actual CFF CSV schema, permitted uses, timestamps, coverage, and whether non-incident journeys are included.
2. Live transport API endpoint, rate limits, uptime, fields, licenses, and availability of real-time delays/platforms. Candidate: https://transport.opendata.ch/docs.html — **validate behavior before promising features**.
3. Railway account credits/limits, service availability and deployment setup; do not assume a specific free credit balance.
4. JetBrains challenge criteria (which Kotlin technologies/platforms count).
5. Whether Compose Multiplatform **Web/Wasm** builds and deploys on the team's Windows/Linux machines within a short timebox.
6. Actual submission deadline and required deliverables.
7. Availability and accuracy of fare data, occupancy, live geolocation and workspace data.

---

## 3. Delivery priorities

### P0 — MVP at T+4 h

An end user opens a **public URL**, enters **origin and destination** (e.g. Lausanne → Genève), optionally a departure time, clicks Search, and sees **at least one actual transport itinerary** returned via the **Ktor backend** and displayed in the Web frontend.

Required:
- Web search form and result state.
- Ktor `GET /health` and `GET /api/journeys` (or `POST /api/journeys/search`, choose one and document it).
- Backend adapter calling a Swiss transport routing API.
- Parse and normalize the response into an app-owned model.
- Basic fastest-by-scheduled-arrival selection for a fixed earliest departure, with clear handling of no results.
- Loading, error, and empty states.
- Vercel frontend and Railway backend reachable by public HTTPS URLs.
- Working CORS or same-origin proxy.
- `main` contains reproducible, runnable code.
- A clearly marked mock journey may be used **temporarily** at T+2 h, but the T+4 target is a **real API-backed result**.

Explicitly out of scope at T+4:
- Delay prediction, historical CSV processing, full graph algorithms, dynamic prices, accounts, persistence, chat, voice, notifications, iOS/Android.

### P0 — Stable demo-ready MVP at T+11 h

- All T+4 functionality reliably works from a public URL.
- At least one or two route options when API provides them; show departure, arrival, duration, transfers, platforms **if actually supplied**.
- Basic ranking by fastest arrival, with a coherent rule for departure-time search.
- Optional user arrival deadline field (can be present even if advanced reliability logic is not yet available).
- Responsive, clean UI; sensible validation and timeouts.
- Unit tests for ranking and data parsing; one smoke test from deployed Web to API.
- Deployment from `main`, known-good commit/tag `mvp-t11`, documented setup and fallback demo.
- Desktop/Android not required for the Web-first milestone; do not promise them.

### P1 — Second half (T+11 to T+22 h)

Order by value and feasibility:
1. **Multiple alternatives** with ranking and explanations.
2. **Deadline-aware** filtering and latest reasonable departure recommendation.
3. **Historical incident insights / predictive risk** only after CSV schema validation.
4. **Simple vs fast** preference and reliability-weighted route ranking.
5. **Plan B rescue**: curated nearby cafes/quiet workspaces if meeting will be missed, using transparent assumptions.
6. Optional notifications or lightweight incident indicator if API supports it.

### P2 — Stretch only

- Cost optimization with reliable fares, Half-Fare/GA/GA Night and zones; avoid pretending estimated fares are official.
- Contextual hub/peak-hour “pressure index”; **not actual occupancy** unless a trustworthy source exists.
- Smart opportunities during waiting periods, with outbound + return walking time + safety buffer.
- Proactive alerts and live location/platform walking time.
- Voice chatbot or screenshot extraction of meeting details for AXA multimodal angle.
- iOS (requires Mac/Xcode for native compilation/testing), Android emulator build, polished animations.
- Photo-based accessibility navigation: substantial safety/data/validation scope, not an MVP requirement.

---

## 4. Architecture: modular, replaceable boundaries

```text
Browser (Vercel)
  |
  | HTTPS JSON
  v
Web UI (prefer Kotlin Compose Multiplatform Web if validated)
  - Search form / Results / Plan B screen
  - UI state + ViewModel/presenter
  - API client interface (no direct CFF calls)
  |
  v
Ktor API (Railway)
  - Routes / request validation / error handling
  - JourneySearchService
  - RecommendationService (deterministic scoring)
  - TransportProvider interface
  |      -> SwissTransportAdapter -> live transport API
  |      -> MockTransportAdapter (demo/tests only)
  - RiskEstimator interface
  |      -> NoPredictionEstimator (MVP)
  |      -> HistoricalStatsEstimator (later)
  - PlacesProvider interface (later)
  |      -> CuratedPlacesProvider
  v
Optional static artifacts (CSV-derived JSON) / optional DB later
```

**Important:** Python is an **offline analysis/training pipeline**, not a mandatory online service. It reads the incident CSV, validates its content, computes historical features or a model, exports versioned JSON/model artifacts. Ktor loads these artifacts and applies them. No Python inference server required for MVP.

**Transport routing:** initially use the provider API's valid journeys and rank them. Do **not** implement Dijkstra/RAPTOR from scratch during the first 4 hours. A real time-dependent transport graph is harder than static shortest-path search.

### Interfaces and boundaries

- `TransportProvider.search(request): List<Journey>`: interchangeable API/mock.
- `RiskEstimator.estimate(journey, context): RiskEstimate?`: nullable/unknown when unsupported.
- `RecommendationService.rank(journeys, preferences, deadline): List<RankedJourney>`.
- `PlacesProvider.near(station, constraints): List<Place>` (later).
- `Clock` / time source injected for deterministic tests.
- Keep external API field names and serialization in adapters; never leak vendor response shapes to UI.
- Shared contract module is optional; if Web/Wasm dependencies become problematic, use **documented JSON contracts** and separately defined client DTOs instead of forcing a shared Kotlin module.
- Keep all timestamps timezone-aware ISO 8601; display in `Europe/Zurich`. Avoid comparing bare `"14:00"` strings.
- Model journeys as **legs** (not just a single train), including transfer buffers and optional platform info.
- Nullable/unknown values are better than fabricated delay, fare or confidence.

### Suggested minimal data contract (illustrative)

```json
{
  "request": {
    "origin": "Lausanne",
    "destination": "Genève",
    "earliestDeparture": "2026-11-21T16:00:00+01:00",
    "arrivalDeadline": null,
    "preference": "FASTEST"
  },
  "journeys": [
    {
      "id": "provider-derived-stable-id",
      "departure": "2026-11-21T16:10:00+01:00",
      "arrival": "2026-11-21T17:00:00+01:00",
      "durationMinutes": 50,
      "transfers": 0,
      "legs": [],
      "predictedDelayMinutes": null,
      "lateArrivalProbability": null,
      "estimatedFareChf": null,
      "dataStatus": "LIVE_SCHEDULE"
    }
  ]
}
```

This is **a contract example, not actual API data**. Define request/response DTOs in code and freeze a version before independent implementation.

### Backend API

- `GET /health` -> `{"status":"ok"}`.
- `GET /api/journeys?origin=Lausanne&destination=Genève&departure=...` -> normalized JSON.
- Later `POST /api/recommendations` with deadline and preferences.
- Later `GET /api/places?stationId=...`.
- Use consistent error format: `{ "error": { "code": "...", "message": "..." } }`.
- Validate input lengths, empty strings, dates; handle upstream 429/5xx/timeouts; do not expose secrets.
- Avoid claiming a live disruption feed exists until the selected provider is verified.

---

## 5. Repository layout

```text
cff-plan-b/
├── README.md
├── docs/
│   ├── MASTER_PLAN.md             # this document
│   ├── API_CONTRACT.md
│   ├── ARCHITECTURE.md
│   ├── DEMO_SCRIPT.md
│   └── DECISIONS.md
├── frontend/                       # Web UI (Kotlin/Compose Web if viable)
│   ├── src/
│   └── README.md
├── backend/                        # Kotlin Ktor JVM
│   ├── Dockerfile
│   ├── src/main/kotlin/
│   │   ├── api/
│   │   ├── domain/
│   │   ├── service/
│   │   ├── provider/
│   │   └── config/
│   ├── src/test/kotlin/
│   └── README.md
├── analytics/
│   ├── README.md
│   ├── notebooks/                  # optional exploratory work
│   ├── scripts/
│   ├── tests/
│   └── output/                     # sanitized/versioned derived artifacts
├── shared/                         # OPTIONAL Kotlin DTOs; only if toolchain works
├── .github/
│   ├── workflows/
│   │   └── ci.yml
│   └── pull_request_template.md
├── .gitignore
└── .env.example                    # names only, no secrets
```

Avoid overengineering: the tree is a target layout, not a requirement to create every directory before the first working journey.

### Git workflow

- `main` is protected by team agreement (or GitHub branch protection if quick to configure).
- Feature branches: `feature/web-ui`, `feature/ktor-api`, `feature/analytics`, `feature/integration-planb`.
- Small, frequent commits; short PRs; one reviewer/integration owner; merge only when builds/tests pass.
- Avoid four long-lived branches that merge only at the end; merge vertical slices regularly.
- Tag working checkpoints (`mvp-t4`, `mvp-t11`, `submission`).
- Never commit CSV data if licensing, size or privacy is uncertain; use `.gitignore` and document access.
- Never commit API tokens, Railway/Vercel secrets, `.env`, service credentials.
- AI agents get **scoped directories** and must not silently change shared contracts.

### CI / CD

- GitHub Actions: backend Gradle build + tests, frontend build if toolchain works, basic lint if cheap.
- Vercel: connect same GitHub repo, root `frontend/`, configure static build output and production branch `main`.
- Railway: connect same repo, root `backend/`, Dockerfile deployment, production branch `main`.
- Backend binds to `0.0.0.0` and runtime `PORT` (Railway service config).
- Set frontend public `API_BASE_URL` to Railway HTTPS URL **at build time if required by Web bundle**; redeploy frontend after changes.
- Configure Ktor CORS for actual Vercel production origin and optional preview origins; avoid permissive wildcard with credentials.
- Deploy a health endpoint first; then connect the Web UI.
- Free tiers, resource quotas, sleep and billing behavior **must be verified in the team's actual accounts**.
- Keep local fallback run instructions and a cached/demo dataset clearly labeled for a network failure.

### No database at MVP

Do not add Postgres just to store third-party journey search results. Static curated places and CSV-derived stats can be JSON files in the backend. Add a database only for persisted user profiles, favorites, notification subscriptions or durable analytics. If required later, PostgreSQL via Railway/Supabase is an option; add migrations then.

---

## 6. Four-person task allocation

| Role | Owns | T+4 h deliverable | T+11 h deliverable | Later |
|---|---|---|---|---|
| **A — Frontend** | `frontend/` | Search + loading/result/error UI; calls backend | Responsive results and alternatives | Deadline controls, Plan B screens |
| **B — Backend / Transport** | `backend/` | `/health`, search endpoint, transport adapter, real itinerary | Robust parsing, validation, ranking | Reliability and preferences |
| **C — Analytics / Data** | `analytics/` | Inspect available transport fields, write fixture/contract tests; await CSV | Prepare CSV validation and baseline when available | Historical stats, predictive risk |
| **D — Integration / Deployment** | `.github/`, `docs/`, deployment settings | GitHub, CI, Railway, Vercel, deployed smoke test | Stable `main`, tagged demo, error monitoring | Plan B places, demo polish |

**Dependency rule:** A and B agree on a JSON example and API contract in the first 30 minutes. A uses a mock response until B's endpoint is ready. C must not block A/B on unavailable CSV. D merges frequently and owns end-to-end smoke tests.

---

## 7. Timeline and exit criteria

### T+0 to T+1 h — Skeleton

- Create monorepo and branches.
- Decide frontend viability: compile minimal Kotlin/Wasm Compose Web.
- Create minimal Ktor server `/health`.
- Agree on DTO and one example JSON response.
- Railway and Vercel initial service connections.
- **Decision gate:** if Web Compose setup consumes >45 minutes without a working browser build, use a minimal HTML/JS frontend (possibly served by Ktor or hosted on Vercel). Keep the backend Kotlin and UI/API separation. This is a deliberate fallback, not a failure.

### T+1 to T+2 h — Fake vertical slice

- Search form -> API client -> mock itinerary -> rendered result.
- Mock data is explicitly marked as such.
- Health endpoint live.
- First CI build green.

### T+2 to T+3 h — Real integration

- Transport provider adapter fetches actual journey(s).
- Map provider JSON to domain DTOs.
- Test Lausanne → Genève, plus one invalid/no-results case.
- Handle loading, upstream timeout and errors.

### T+3 to T+4 h — Public demo

- Deploy Railway API and Vercel Web.
- Test from a different browser/device.
- Confirm real itinerary, HTTPS, CORS, logs and `main` commit.
- Tag `mvp-t4`; record a short backup demo if time.

### T+4 to T+11 h — Stabilize and enrich

- More robust journey cards and transfers.
- Alternative itineraries and earliest-arrival ranking.
- Optional deadline input.
- Automated tests and production smoke checks.
- Documentation, screenshot, demo script, fallback plan.
- Tag `mvp-t11`. **A working, submittable version must exist here.**

### T+11 to T+17 h — Product differentiator

- Add preference ranking and deadline awareness.
- Inspect incident CSV. Choose either:
  - historical incident severity/duration patterns if only incident records; or
  - a delay/risk model **only if non-incident observations and valid labels exist**.
- Integrate versioned risk artifact into Kotlin `RiskEstimator`.
- Show transparent explanations and unknown/uncertain states.

### T+17 to T+20 h — Optional Plan B

- Curated quiet workspaces/cafes near relevant stations, with sourced/verified attributes.
- If arrival is impossible, show alternatives such as a quiet call location or suggested rescheduling.
- Optional voice/screenshot input only if core works and AXA challenge benefits.

### T+20 to T+22 h — Freeze

- Stop risky feature work.
- Final deployed smoke test; screenshots, pitch, repository README, credits/licenses, submission.
- Tag `submission`, preserve working demo.
- Do not deploy untested changes right before judging.

---

## 8. Journey selection and predictive analytics

### MVP journey choice

For a fixed origin, destination and **earliest departure**, ask transport API for valid itineraries, then choose the one with the **earliest scheduled arrival** among eligible departures. If optimizing pure in-vehicle/travel duration instead, label the metric correctly; the shortest duration and earliest arrival are not always the same.

Avoid making a route with impossible transfers: trust the API's connection validation at first.

### Future scoring

Normalize metrics before weighting:

`score = w_time * expected_arrival_penalty + w_risk * late_risk + w_transfers * transfer_penalty + w_cost * normalized_fare`

- `FASTEST`: emphasize arrival time.
- `SIMPLEST`: emphasize fewer transfers.
- `CHEAPEST`: only offer when actual comparable fare estimates exist.
- `RELIABLE`: prioritize probability of arriving before the deadline **if calibrated risk is available**.
- Do not use uncalibrated arbitrary risk scores as actual percentages.
- Consider a hard arrival deadline constraint and show “no reliable route” when appropriate.
- For connection misses, account for transfer time and estimated delay of incoming legs, not just total journey delay.
- Show rationale: “Recommended because it avoids a tight connection,” etc.

### CSV and ML decision tree

**First inspect columns**:
- Incident-only dataset? No population denominator; cannot directly infer probability that an arbitrary train is delayed. Consider counts, affected stations/lines, time-of-day severity, typical incident duration, and a **qualitative risk indicator** with caveats.
- Contains all services with scheduled/actual times and outcomes? Then train simple baseline per line/station/hour, compare to small gradient boosting or random forest.
- Chronological train/validation split; avoid leakage of incident end times or post-departure information.
- Test calibration for probability estimates; report baseline and uncertainty.
- If historical incidents lack a match to live journeys, keep insights separate rather than invent linkage.
- Do not infer rolling-stock dependencies or real crowding solely from adjacent trains or same station.

### Python integration

`CSV -> validate/clean -> exploratory statistics or train -> chronological evaluation -> export JSON -> backend resource -> RiskEstimator`.

Ktor loads the JSON into memory on startup. No online Python process is required.

---

## 9. UX scope

**Screen 1 — Search:** origin, destination, optional time/deadline, Search CTA.  
**Screen 2 — Results:** recommended itinerary, alternatives, departure/arrival, transfers, data provenance, optional reliability note.  
**Screen 3 — Plan B (later):** if disruption threatens commitment, alternative travel or nearby verified workspace; show walking time and buffer.

Design: uncluttered, readable, high contrast, restrained Swiss rail-inspired red accents; avoid claiming official CFF affiliation or using trademarks without permission. Loading and failure states matter as much as the happy path.

---

## 10. Quality, security and demo reliability

- Health check, request timeouts, safe upstream error handling, structured logs.
- No secrets in frontend; no personal user data stored by default.
- Rate limit/caching where needed and consistent with provider terms.
- Validate input, avoid injection in HTML and logs.
- Test timezone and midnight rollover, missing platform, canceled service, zero results, upstream unavailable.
- Unit test ranking: earliest arrival, fewer transfers, deadline, missing prices/risk.
- Integration test provider JSON mapping with fixture files.
- Manual smoke test deployed URL from a clean browser.
- Maintain fallback local/demo mode and label simulated data honestly.
- Document data provenance, licensing and limitations.

---

## 11. Suggested first commits / actionable checklist

1. Create repo `cff-plan-b`; add this brief as `docs/MASTER_PLAN.md`.
2. Commit a short `README.md` with run/deploy instructions and owners.
3. Create `frontend/`, `backend/`, `analytics/`; don't overbuild empty modules.
4. Write `docs/API_CONTRACT.md` with the agreed journey DTO and mock JSON.
5. Start Ktor `/health` and deploy to Railway.
6. Start Web frontend; test browser build; deploy to Vercel.
7. Wire UI to mock backend journey.
8. Replace mock with live transport adapter.
9. Test Lausanne → Genève publicly; tag `mvp-t4`.
10. Stabilize and tag `mvp-t11`; only then add ML/Plan B.

---

## 12. AI-agent handoff instructions

When sharing this file with Codex, Gemini, JetBrains AI or another agent:

> You are helping build **CFF Plan B**, a four-person LauzHack 2026 project. Treat `docs/MASTER_PLAN.md` as product and architecture context, but inspect the **actual repository and toolchain** before editing. Preserve a working `main` and follow the T+4/T+11 milestone scope. Work only within your assigned module. First state the files you will change, expected behavior, and tests. Do not invent CSV fields, live API response fields, fares, probabilities, or challenge criteria. Keep Kotlin Ktor backend and Web-first Vercel frontend; use Python offline for analytics. Make small commits and provide reproducible commands. If the Compose Web/Wasm build blocks for over 45 minutes, propose the documented minimal Web fallback instead of silently expanding scope. Do not add a database, auth, or ML service unless a specific feature requires it. Keep mock data clearly labeled.

### Agent assignments

- **Frontend agent:** `frontend/`; search/results UI, API client, browser build.
- **Backend agent:** `backend/`; Ktor endpoints, transport adapter, mapping/tests.
- **Analytics agent:** `analytics/`; CSV schema audit, honest statistical baseline.
- **Integration agent:** `.github/`, `docs/`, Docker/deploy config, smoke tests. Coordinate shared contracts through PR review.

### Definition of done

A task is done only if it builds, has a reproducible test/manual check, respects the API contract, includes error states where relevant, and does not break the deployed mainline.

---

## 13. Decision log

| Decision | Rationale |
|---|---|
| Web-first | No Mac for iOS; public URL is judge-friendly |
| Vercel + Railway | Existing Vercel setup; Ktor server hosting on Railway |
| Kotlin Ktor backend | JetBrains/Kotlin ecosystem; clean REST architecture |
| Compose Web as preferred, with fallback | Preserve Kotlin ambition without risking first demo |
| No database initially | Stateless journey queries and static stats need none |
| No ML in first 4 hours | CSV schema unknown; end-to-end demo has higher value |
| Provider-based route search | Real timetable routing is time-dependent and complex |
| Python offline training only | Fast iteration without extra runtime service |
| Modular adapters/interfaces | Allow upgrades without rewriting frontend/backend |
| `main` always deployable | Hackathon demo reliability |

**Final success criterion:** by T+4, a public Web app returns one real itinerary. By T+11, a stable, submittable demo is live. By submission, add at least one honest, distinctive Plan B capability without sacrificing reliability.
