# Session handoff

Check the branch, revision, and affected files before relying on an entry. Use the [roadmap](ROADMAP.md) for ownership and task status. Keep decisions with lasting architectural impact in [DECISIONS.md](DECISIONS.md).

## Entry format

Add dated entries with the newest first. Include the responsible role, branch and latest relevant commit, what was completed, paths changed, commands and results, current implementation status, known bugs and blockers, pending decisions, dependencies affecting other owners, and prioritized next actions. Link to evidence when available. Identify uncommitted changes explicitly. For concurrent sessions, send the entry and task IDs to D for consolidation in the relevant PR and preserve other entries during conflict resolution.

## 2026-11-21 · C · Notify hysteresis, real resumption, robustness metric

**Branch and commit**
`prediction`, in the commit that contains this entry (after `ee4c54e`).

**Completed and files changed**

- `analytics/sbb_duration/sources.py`:
  - Notify hysteresis. An advice change notifies only when it is seen at two successive moments, or when the detour cost is more than 10 min from the prediction that was crossed.
  - Rate limit: one notification per 15 min at most, except a replacement-bus announcement. Withheld codes are kept in `notify.withheld` and fire later if they still hold.
  - Real end. `incident.publicEndAt` is the first public `ENDED` version, or the VIA global end when the public feed has none. `actualResumptionAt` is the internal resumption when it comes first, and then becomes `actualEndAt`. After it, the outlook is `UNAVAILABLE` with reason `SERVICE_RESUMED`.
  - New `comparison` block: SBB announcement vs model P50, measured against the real end.
  - `index.json` explains the demo choice in `demo.why`.
- `analytics/sbb_duration/messages.py`: proposals, partial reopenings (one track, "avec mesures") and "en train de vérifier" no longer count as resumed service.
- `analytics/sbb_duration/train.py`: `metrics.evaluation.robustness`, metrics without the incidents closed automatically (first `ENDED` 0–2 min after the last announced end).
- `analytics/tests/test_sources.py`: tests for the hysteresis, the rate limit and the partial-resumption exclusions. `API_CONTRACT.md` and `analytics/README.md` are updated.

**Verified results**

- Robustness: 80 incidents look auto-closed, 7 of them in the original test period. Removing them and redoing the split gives a P50 error of 103.6 min against 120.1 for the announcement (88 test incidents). With the same split and only the test filtered: 98.6 against 114.4.
- Primary demo incident (Renens VD – Morges):
  - the four advice changes between 17:46 and 18:27 now give one notification, at 17:55 (detour 12 min from the P50; the advice then holds 35 min). 8 notifications in all, against 10 before;
  - real resumption 21:04 (internal "Premiers trains …"), against a public end at 00:00. Against 21:04, the model's mean error is 83.3 min and the announcement's 95.8.
- Other incidents: only Chavornay also gets an internal resumption, at 06:57 against 07:05. VIA's global end equals the public end in the 8 incidents where VIA has one; Renens and Stadelhofen have none.
- Demo choice: Renens stays primary. Its story is still clear with the 21:04 resumption, it uses all three sources (VIA travel recommendation, 14 buses with stops at Renens and Morges, internal resumption), and the model beats the announcement. Stadelhofen stays the backup: its public end matches the end of operations, and the model's mean error is 63.2 min against 76.2.
- `python -m pytest tests`: 15 passed.

## 2026-11-21 · C · Multi-source incident timelines

**Branch and commit**
`prediction`, in the commit that contains this entry (after `1657b48`).

**Completed and files changed**

- `analytics/sbb_duration/sources.py` joins the three sources:
  - public liveticker ↔ VIA: VIA `alea_fall_id` = public incident id;
  - VIA ↔ internal (KULT) liveticker: same cause code and creation time within 30 min, matched one-to-one, nearest first.
- It writes one timeline per incident present in all three sources to `analytics/output/timelines/`, labelled MOCK. Each moment carries the public announcement, the outlook from the evaluation models, the advice, the stations (UIC + sloid) and lines, the internal facts and a `notify` flag.
- `analytics/sbb_duration/messages.py` sanitizes internal messages and extracts facts with keyword rules: replacement bus, stops, resumption forecast and resumed service.
- `analytics/tests/test_sources.py`: synthetic checks of sanitizing, extraction, notify rules and the sloid rule.
- `openpyxl==3.1.5` added to the requirements, to read the VIA `.xlsx`.
- Proposed timeline format documented in `API_CONTRACT.md`. Commands added to `analytics/README.md`.
- Correction to the previous entry: the sources contain no U+FFFD. The "lost accents" were a Windows console display artefact. The no-op replay filter was removed, and the replay selection is unchanged.

**Verified facts**

- Linkage: 168 VIA events, 14 of them in the public liveticker; 69 of 137 internal cases linked to VIA; 10 incidents in all three sources.
- VIA and KULT timestamps are naive Swiss local time; VIA creation times match the public `created_at_source` exactly.
- The sloid rule `ch:1:sloid:<UIC − 8500000>` holds for all 32,911 Swiss service points (opentransportdata.swiss `service-points-actual-date`, 2026-06-30). 224 of the 257 VIA UIC codes are in that export; the others are foreign.
- Personal data: the `name`, `vorname` and `telefonnummer` columns are empty and are never read. No phone, e-mail, honorific or user id appears in the 882 messages. Staff-account messages are skipped.
- `python -m pytest tests` gives 14 passed. On the primary demo incident (2195005188970, Renens VD – Morges), the timeline has 45 moments and 10 notifications:
  - replacement bus first mentioned internally at 18:46 (14 buses), stops at Renens VD and Morges at 19:30;
  - VIA publishes the replacement bus at 20:02;
  - the public end announcement moves 18:30 → 20:30 → 23:59.

**Limits and next actions**

1. The advice can switch back and forth when the detour cost is close to the P50: four changes between 17:46 and 18:27 on the demo incident. Add a minimum interval or a margin before pushing notifications.
2. Journey matching needs leg stop sloids: `JourneyLegDto` exposes names only, and sections give only their two ends. B decides how to expose OJP stop refs and intermediate stops.
3. The first public `ENDED` version can come much later than the internal resumption: 00:00 against 21:04 on the demo incident. `actualEndAt` follows the public definition.
4. A reviews the timeline format for the UI. D includes it in the contract review.

## 2026-11-21 · C · Disruption duration model, Kotlin estimator and outlook route

**Branch and commit**
`prediction` at `700f113` (replay), plus the documentation commit containing this entry. Based on `7f6734f`, which merged `origin/main` and B1.

**Completed and files changed**

- `analytics/sbb_duration/`: data / features / train / predict / advice / artifact / export / replay package, with `analytics/tests/` (3 files) and `analytics/requirements.txt`. Replaces `analytics/sbb_duration_model.py`, which was deleted along with the obsolete `model/` ignore entry.
- Model: HistGradientBoosting quantile models for the P50 and a pessimistic estimate (q 0.85) of the minutes until the first `ENDED` version. Metrics come from an 80/20 chronological split by incident start (cutoff 2026-04-06). The exported model is refitted on all 3,063 snapshots.
- Export: `duration_model.v1.json` (trees, categories, regexes, provenance, metrics, limitations) and `golden_vectors.json` (100 cases), written to the ignored `analytics/output/`. Infinite sklearn thresholds are clipped to ±max double so the file is valid JSON.
- Backend: `POST /api/disruptions/outlook`.
  - The artifact is loaded at startup from `DURATION_ARTIFACT_PATH`. If it is missing or invalid, `NoDurationEstimator` is used and the route returns `UNAVAILABLE`.
  - New code: Kotlin feature port and tree evaluator under `service/duration/`, plus `AdviceService`.
  - `Plugins.kt` and `Routes.kt` changed: CORS now allows POST, and `configureRoutes` takes an optional estimator. The user notifies B.
- Docs: proposed contract section in `API_CONTRACT.md`, commands in `analytics/README.md` and `backend/README.md`, roadmap rows A2 and A3.

**Commands and results**

- From `analytics/`: `python -m sbb_duration.train`, then `export`, then `python -m pytest tests -p no:cacheprovider` gives 10 passed. The maximum gap between the JSON evaluator and sklearn is 1.07e-14.
- Test metrics (104 incidents, 639 snapshots): P50 MAE 97.9 min, against 112.7 for the official announcement and 127.0 for the median per cause. Pessimistic coverage 73.7%.
- From `backend/`, with JDK 21: `./gradlew --no-daemon test` gives 46 passed. `DurationGoldenVectorsTest` ran and matched within 1e-6. `DisruptionRouteTest` covers `UNAVAILABLE` and the 400 response.
- Manual check: backend started with the real artifact, then curl on the contract example. Response `AVAILABLE`, p50 228 and pessimistic 507, the same as sklearn (228.26 and 506.52). The 400 response and the CORS preflight were also checked.
- `python -m sbb_duration.replay`: three test-period incidents chosen by rule. Two are wins (mean error 884 → 183 min and 103 → 38 min). One is a counterexample where the SBB announcement was better (47 against 190 min). Files `output/replay/replay-0N.json` are labelled `MOCK`.

**Status, blockers and decisions**

- The data's usage rights are not reviewed. The CSVs, the model, the golden vectors and the replay JSON stay local, so deployed backends answer `UNAVAILABLE`.
- The pessimistic estimate is not a calibrated probability: it covers 73.7% of the test period, below the 85% target. Accuracy drifts with time: recent incidents last longer.
- Environment: JDK 21 is installed per-user under `%LOCALAPPDATA%\Programs\Temurin`, with `JAVA_HOME` set at user level, because the winget MSI needed UAC.

**Next actions**

1. A, B and D review the outlook contract proposal.
2. Data-use review, then decide whether to package the artifact (Docker or Railway).
3. A renders `replay-0N.json` in the UI. B connects the outlook route to verified disruptions and legs (CFF1, B5).
4. Optional: calibrate the pessimistic estimate on a validation window.

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
