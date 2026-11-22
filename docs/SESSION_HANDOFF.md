# Session handoff

Check the branch, revision, and affected files before relying on an entry. Use the [roadmap](ROADMAP.md) for ownership and task status. Keep decisions with lasting architectural impact in [DECISIONS.md](DECISIONS.md).

## Entry format

Add dated entries with the newest first. Include the responsible role, branch and latest relevant commit, what was completed, paths changed, commands and results, current implementation status, known bugs and blockers, pending decisions, dependencies affecting other owners, and prioritized next actions. Link to evidence when available. Identify uncommitted changes explicitly. For concurrent sessions, send the entry and task IDs to D for consolidation in the relevant PR and preserve other entries during conflict resolution.

## 2026-11-22 · A · Opening animation

- On `feat/frontened-mvp` at `1d447cc`, refined the ALT welcome sequence in `frontend/web/index.html`, `style.css`, and `app.mjs`. The frame expands with the red rule and suffix, the full wordmark settles, and the opening layer lifts to reveal the planner. Updated `frontend/DESIGN.md`.
- Chrome checked the animation at 320, 390 and 1200 px, keyboard dismissal without replay, and reduced motion. JavaScript syntax and `git diff --check` passed. The preview is `http://127.0.0.1:8790/`. Backend and roadmap status are unchanged.

## 2026-11-22 · A · Suggestion and result polish

- Updated `frontend/web/app.mjs`, `frontend/web/style.css`, and `frontend/DESIGN.md` on `feat/frontened-mvp` at `1d447cc`. Nested parentheses use regular weight, zero-minute detail annotations are omitted, and alternatives use one surface per theme. Enlarged the recommendation label and added more prominent status gradients.
- Chrome verified the presentation and narrow layout using live responses with test overrides for the nested name, delay values and status states. Node syntax and `git diff --check` passed. Backend integration and F1–F3 review status are unchanged.

## 2026-11-22 · A · Welcome rotation and result formatting

- Branch `feat/frontened-mvp` at `1d447cc` with uncommitted changes in `frontend/web/`, `frontend/DESIGN.md`, and `frontend/FEATURES.md`. Added nineteen localized rotating headings, scrollable suggestions with regular-weight parenthetical text, rectangular status panels, hour-and-minute journey durations, and a jump back to the preceding batch. Removed the visible realtime source label while retaining backend data and estimates.
- Chrome verified successive reloads, live suggestions and results, all disruption states, cached expansion from seven to nine results and navigation back to the original row using test responses, and a narrow viewport. Node syntax and `git diff --check` passed. The preview is `http://127.0.0.1:8790/`. The API returns a bounded set of journeys, with no server pagination. F1–F3 remain in local review.

## 2026-11-22 · A · Page gradient and route loading

- Branch `feat/frontened-mvp` at `1d447cc` with uncommitted browser work in `frontend/web/`. Extended the red and gray gradient, grouped the active mode styling, spaced result times, and added the red recommendation label. Clear status remains above the recommendation and requires backend confirmation.
- Loading keeps Searching and its progress line in the submit button and draws a schematic Switzerland outline with a red train connecting the selected endpoints. It reuses the bundled swisstopo boundary and is static with reduced motion.
- Chrome verified a live Lausanne–Genève search through loading and results, mode styling and help, the map and moving train, clear status, mobile width and reduced motion. Node syntax and `git diff --check` passed. The local preview is `http://127.0.0.1:8790/`. Backend contracts and F1–F3 review status are unchanged.

## 2026-11-22 · A · Journey search and results refinements

- Branch `feat/frontened-mvp` at `1d447cc`. Updated the uncommitted browser frontend’s departure controls, shared ranking-help panel, localized disruption messages and sunset-inspired masthead treatment. Updated `frontend/DESIGN.md` and `frontend/FEATURES.md`.
- Chrome verified a live NOW and scheduled search, API timestamp conversion and retention across ranking changes, desktop keyboard and mobile touch tooltip dismissal, and each disruption state in all four locales using in-browser test variants. Confirmed that the warning mentions alternatives only when supplied. Node syntax and `git diff --check` passed. The static module defines no separate build or lint command.
- The preview is available at `http://127.0.0.1:8790/`. F1–F3 remain in local review. Backend integration, providers and response contracts are unchanged.

## 2026-11-22 · A · Unified browser planner

- Branch `feat/frontened-mvp` at `1d447cc`, with the expanded browser changes uncommitted in `frontend/web/`. Updated frontend design, feature and preview documentation. Reused the existing outlined ALT brand mark, web components and Kotlin API integration.
- Delivered unified FROM/TO autocomplete, explicit geolocation on both fields, Swiss boundary verification with ©swisstopo, a real Swiss local clock, NOW, Fastest/Simplest tooltips, automatically visible alternatives and an editable mobile summary. Results retain backend recommendations, fares, disruption information and provenance. Observed historical corridor and baseline statistics replace risk-level-only labels.
- Verified Node syntax and assertions for Swiss and foreign coordinates, DST, sorting, deduplication and all visible copy keys checked by the script. Chrome connected to the live Kotlin API for both ranking modes and validated mobile and desktop layouts, address and station suggestions, simulated positions, locale persistence, dark mode, reduced motion, and recovery from a blocked journey request. `git diff --check` passed. The static web module defines no separate build or lint script.
- F1–F3 remain in local review. The preview runs at `http://127.0.0.1:8790/`. Next with D and B: review any future verified country metadata or pagination extensions. Current location filtering needs verified coordinates, alternatives use cached batches, and average historical delay is not exposed by the API. No backend contract or deployment setting changed.

## 2026-11-22 · A · ALT browser preview

- Branch `feat/frontened-mvp` at `1d447cc` with the browser implementation uncommitted. Added `frontend/web/` and `frontend/README.md`. Used a dependency-free browser implementation because this checkout has generated Wasm output and platform fragments but lacks the frontend build configuration and application entry point.
- Implemented validated origin and destination selection, the three backend modes, the pinned recommendation, editorial alternatives, disruption details, and browser preferences. The preview proxies the documented Kotlin API. It requests the supported result limit once per search and reveals cached alternatives in batches of seven.
- Verification used `node --check`, Node assertions for Swiss time and DST conversion and result ordering, and Chrome via DevTools. Live Lausanne–Genève requests succeeded in all three modes. Browser checks covered station and address selection, simulated geolocation with confirmation, Simplest keyboard help, route details, French, dark theme, and a 390 px viewport. A synthetic response exercised an impacted leg and 7 then 9 deduplicated options from one request. `git diff --check` for the existing working tree reports a pre-existing Markdown hard break in `frontend/FEATURES.md`.
- F1–F3 browser behavior is available for local review at `http://127.0.0.1:8790/`. Physical-device and production frontend verification remain open. Backend cursors and journey notifications remain unavailable in this browser implementation. No native or Wasm delivery is claimed.
- Next with D: review the browser fallback and configure same-origin forwarding for deployment. Confirm behavior on a physical mobile device and check a provider-confirmed disruption when available.

## 2026-11-22 · C · Final series: live grouping, security check, team quick start, merge, PR

- **Live alerts grouped.** `ServiceAlertGrouper` merges feed alerts with the same title kind, the same two end stations in either order and the same matched stations. Each `liveAlerts` entry carries `ids`, `operators` and `directions`. Measured: 9 alerts on Lausanne → Genève became 6 entries (`212b028`).
- **Security.** Whole history checked: 85 commits, 1,413 git objects. Neither key appears, in full or as a fragment, and there is no JWT/Bearer/`KEY=` token pattern, no private key, and no `.env`, `Daten`, `analytics/output`, `analytics/data` or `.joblib`. The only binary is main's 2 KB GTFS-RT test fixture.
- **Quick start.** "Démarrage rapide pour l'équipe" opens `analytics/README.md`: get or regenerate the model, the `.env` keys, the demo command, and one curl per route (`dec62f9`).
- **Second merge of `main`** (`3636751`, 11 commits: four-endpoint API, strict contract).
  - Both sides kept.
  - `/api/journeys/impact` now has its own `ImpactJourneyDto`.
  - Main's strict `LegDto` test means the sloids are not added to `/api/journeys`. They stay in the domain and in the impact response; B decides whether to add them.
- **Checks after the merge.** Kotlin 187 passed. Python 39 passed, 3 skipped. `run_demo.py` with both live feeds: every request in `DEMO.md` gives its documented value, and the key is not in the log.
- **PR.** `gh` is not installed. Title and description are in `analytics/output/PR.md` (ignored); open it at https://github.com/Ikram-cat/lauzhauk-winners-2026/compare/main...prediction?expand=1. The team messages (B, duration-model colleague, frontend, keys) are in `analytics/output/TEAM_NOTES.md`, unsent.

## 2026-11-22 · C · Live alerts in the journey check (LIVE, no prediction)

- **Keys.** `.env` has two keys. `GTFS_RT_API_KEY` gives `la/gtfs-rt` `200` (1.8 MB); `GTFS_SA_API_KEY` gives `la/gtfs-sa` `200` (12.6 MB). Each key gets `403` on the other feed. The second line of `.env` was saved as `GTFS_RT_API_KEY`; only its name was changed to `GTFS_SA_API_KEY`, and its value was not read out. `.env` stays ignored, and `.env.example` has `GTFS_SA_API_KEY=`.
- **Backend.** New `service/impact/ServiceAlerts.kt`: a decoder separate from the team's TripUpdates decoder, a cached `ServiceAlertsSource` (one fetch per minute) and `ServiceAlertMatcher` (alert stop places against leg stops, active at `observedAt`). `POST /api/journeys/impact` returns `liveAlerts` (`dataStatus: LIVE`, `status` `OK`/`DISABLED`/`UNAVAILABLE`, SBB texts, periods, stations), always with `prediction: null` and `predictionReason`. New settings `GTFS_SA_API_KEY` and `GTFS_SA_URL`. `run_demo.py` passes both keys and prints `gtfs-rt=on service-alerts=on`.
- **Checks.** Kotlin: 182 passed (new `ServiceAlertsTest` with a hand-encoded feed and the IC 1 via Morges; the route test asserts `DISABLED` without a key). Python: `python -m pytest analytics` 39 passed, 3 skipped. Live run on 2026-11-22 00:41 UTC: both feeds refreshed (2,588 trip updates, 1,470 alerts). Lausanne → Genève now has 9 live alerts ("Interrupted service: Renens VD - Allaman", planned works, matched at Morges). The Renens replay is unchanged (+100 min, `TAKE_DETOUR`, no live alert at that past time). No key appears in the log.
- **Not done, by decision.** No live duration prediction (reasons in the analytics README). The live alerts are not deduplicated (the same text can appear once per operator or direction).

## 2026-11-22 · C · Service Alerts decoded; live mode not reliable yet

- **New key** (`.env`, never committed). `la/gtfs-sa`: `200` (12.6 MB). `la/gtfs-rt`: `403` "Access to this API has been disallowed", confirmed after a minute. The team's TripUpdates feature is therefore off with this key; the previous key had the opposite subscriptions. A key with both is needed.
- **Decoder.** `analytics/sbb_duration/gtfs_alerts.py` is separate from the team's TripUpdates decoder. It is tested on a hand-encoded message (`test_gtfs_alerts.py`) and run on the real feed.
- **Real feed.** 1,470 alerts (679 SBB). Causes: 67% CONSTRUCTION, 30% OTHER_CAUSE. `effect` is always UNKNOWN_EFFECT. 418 alerts have recurring periods. Stops: 99.6% sloids. Ids are UUIDs. Of the 414 alerts active at 23:00 UTC, none is an unplanned SBB rail incident.
- **Verdict: no live mode.** Stations are reliable. The cause has no mapping to `EG_xxx`. `active_period.end` is a validity window, unverified as an announced end. There is no shared id and no overlap with the training export, so validation is impossible. The population is out of distribution. Details and next steps are in the analytics README, Live mode.
- **Checks.** `python -m pytest analytics`: 39 passed, 3 skipped. Kotlin: 180 passed. `run_demo.py` works; GTFS-RT logs a failed refresh (403) and the replay is unaffected.

## 2026-11-22 · C · Live mode checked with the key: Service Alerts refused (403)

- **Key.** `.env` holds `GTFS_RT_API_KEY` (120 characters). It is never printed, logged or committed; `.env` is ignored.
- **Access.** `la/gtfs-rt` (TripUpdates) answers `200` (2.55 MB protobuf). `la/gtfs-sa` (Service Alerts) answers `403 Forbidden` with `{"error": "Access to this API has been disallowed"}`: the key is not subscribed to that API.
- **Consequence.** No real alert can be read, so neither the cause, nor the announced end (`active_period`), nor the affected stops (`informed_entity`) could be verified. As agreed, no alert decoder and no live mode were written. The Renens replay stays the main demo.
- **Verified on the real TripUpdates feed.** All 53,268 stop ids are sloids (27,284 platform-level, 25,984 stop-place, 10,013 stop places), including Renens VD, Morges, Lausanne and Genève. `GtfsDisruptionAdapter` (stop-place comparison) therefore holds on real data.
- **To unblock (team).** Subscribe the key to GTFS-RT Service Alerts on the opentransportdata.swiss API manager, then: decode `alert` separately, check the mapping on real alerts, and add a `LIVE` `DisruptionSource`.
- **Demo.** With the key in `.env`, `run_demo.py` now starts with `gtfs-rt=on` (the team's TripUpdates feed); `--offline` keeps it off. The replay does not depend on it.

## 2026-11-22 · C · Option A applied, live mode ready but not implemented

- **Option A (chosen by the user).** `analytics/duration_model.py` keeps its public interface (`load_versions`, `build_snapshots`, `chrono_split`, `train_models`, `evaluate`, `predict_remaining`, `advice`, …) and now imports from `sbb_duration` the feature lists, the text-flag rules, the `P50 ≤ P80` guarantee (`finalize`) and the advice thresholds (same three messages). The colleague's snapshot code, `announced_end`, `build_artifacts.py`, `predict_duration.py` and their tests are untouched; `python -m pytest analytics` gives 38 passed, 3 skipped.
- **Live mode: ready to be done, not implemented.** The Service Alerts endpoint is `https://api.opentransportdata.swiss/la/gtfs-sa` (`401` without a key) and the key variable is `GTFS_RT_API_KEY`, but `.env` is still empty (0 bytes) and the variable is not set, so access could not be tested. Needed next: save the key, check `la/gtfs-sa`, write a separate decoder for `alert` (`active_period`, `informed_entity`, `cause`), and verify the mapping to our features (analytics README, Live mode).

## 2026-11-21 · C · Merge of main, comparison of the two duration models, GTFS

**Branch and commits**
`prediction`:
- `c06fc4c`: merge of `origin/main` at `45912ec`;
- the GTFS / documentation commit that contains this entry.

Pushed to `origin/prediction`. `.env`, `Daten/`, `analytics/output/` and `analytics/data/` are not committed.

**Merge**
Every conflict kept both sides:
- `configureRoutes`: main's positional parameters first, ours appended with defaults; main's `toDto(risk, …)` plus a no-argument overload for the impact route.
- Configuration and wiring: union of both sides.
- `.gitignore`: lines of both sides.
- `requirements.txt`: union, one version per package (numpy 2.5.5 from main; pytest>=8).
- READMEs: main's structure kept, with our sections added.

`analytics/tests/conftest.py` makes `sbb_duration` importable when CI runs `python -m pytest analytics` from the root. That command is now the only Python test command: main's tests cannot run from `analytics/`.

**Checks**
- Kotlin: `./gradlew --no-daemon test` → 180 passed: main's tests, ours, and 2 new GTFS adapter tests.
- Python: `python -m pytest analytics -q` → 38 passed, 3 skipped. The skipped ones are main's tests that need `OJP_API_KEY` or built artifacts.
- `python analytics/scripts/run_demo.py`: backend up with both teams' artifacts. `/api/journeys/impact` (detour +100, `TAKE_DETOUR`), `/api/disruptions/outlook`, `/api/redirect` and `/api/model` answer.

**Two remaining-duration models: comparison (nothing removed)**
Both models predict how much longer a disruption already in the liveticker will last. They use the same data, snapshots and features, and the same gradient-boosted quantile models.

| Measured on the same 104 test incidents / 639 moments (cutoff 2026-04-06) | main `analytics/duration_model.py` (seed 42) | `analytics/sbb_duration` |
| --- | --- | --- |
| P50 MAE, all moments | 100.15 min | 100.15 min |
| P50 MAE, 626 moments with an announcement (official SBB: 112.7) | 97.9 min | 97.9 min |
| Upper estimate covers the real end | 67.0% (P80, uncalibrated) | 83.1% (q 0.85 + split-conformal offset; 73.7% before calibration) |
| Served to the backend | no; `predict_duration` CLI only | yes: JSON artifact evaluated in Kotlin (`/api/disruptions/outlook`, `/api/journeys/impact`) |
| Extras | — | per-cause explanation, eval artifact for replays, three-source timelines, golden vectors |

Notes:
- Main's stricter rule (train incidents must also end before the cutoff) excludes no incident here, so both splits are identical.
- Main's README reports 100.6 / 98.5; re-running its code today gives 100.15 / 97.9.
- Main's other modules have different roles and do not overlap ours: `corridors.py`, `line_hour.py` and `build_artifacts.py` give the risk of disruption per corridor and hour (backend `RiskEstimator`, `predictions.json`); `realtime_sampler.py` gives measured OJP delays (`realtime_delays.json`); `ojp_validate.py` validates station names.

Proposal, to be confirmed by the user before anything is applied:
- **Option A, recommended.** Give the models separate roles: main's corridor risk answers "is my route often disrupted?" before the trip, and `sbb_duration` answers "how long will the current disruption last?" during it. Main's duration model and its CLI would become thin wrappers over `sbb_duration`, or be retired, with the colleague's agreement.
- Option B: merge the calibration and the export into `duration_model.py`, and drop the package.
- Option C: keep both as they are, at the cost of two models with diverging upper estimates and advice rules (main: "consider detour if P80 ≥ 60 min"; ours: detour compared with the P50 and the pessimistic estimate).

**GTFS**
- **Key:** `.env` is empty (0 bytes), so `GTFS_RT_API_KEY` is not available, and neither the real feed nor Service Alerts could be queried. `GTFS_RT_API_KEY=` is added to `.env.example`, without a value. `run_demo.py` passes the key on when `.env` has one.
- **Stop ids:** GTFS-RT `stop_id` values are platform sloids (`ch:1:sloid:1026:2:4`, from the team's captured fixture). VIA UIC codes map to them through `ch:1:sloid:<UIC − 8500000>`, compared on the stop place (the team's `GtfsMatch.sameStop`). `GtfsDisruptionAdapter` uses this, without changing the team's code, to list the realtime trips that touch a disruption, intermediate stops included.
- **Journey search:** none exists for GTFS. Journeys come from OJP or the mock, and the team's GTFS-RT matching itself uses OJP stop events. `detourExtraMinutes` cannot be computed "with GTFS"; the demo provider stays the default.
- **Live mode:** not implemented. `la/gtfs-sa` exists (`401` without a key), but the team's decoder reads TripUpdates only. The mapping of cause, announced end and identifier is unverified (analytics README, Live mode).

**Next actions**
1. User: choose option A, B or C.
2. User: save the key in `.env`, then a live check of `la/gtfs-sa` can be run.
3. Team: decide whether a GTFS journey search is wanted. It would be new work (static GTFS plus routing) and a change of approach.

## 2026-11-21 · C · Explanation, calibration, journey impact, demo, pitch, final review

**Branch and commits**
`prediction`. The commits are:
- `7187a0e`: explanation;
- `be929b7`: calibration;
- `8639b11`: journey impact;
- `bb6f32b`: live mode not implemented;
- `96f6757`: demo;
- `ec128c9`: pitch numbers;
- the review commit that contains this entry.

All are pushed to `origin/prediction`. `Daten/`, `analytics/output/`, `analytics/data/` and `.env` are never committed.

**Completed**

1. **Explanation.**
   - `analytics/sbb_duration/explain.py` computes per-cause values from the training data: past incidents, median duration, and the median of (real end − announced end). A cause with fewer than 5 incidents falls back on all causes (`scope: ALL_REASONS`).
   - The values are in the artifact, in the `explanation` field of the outlook response (Kotlin), and in the timelines (computed on the split's training data).
2. **Calibration.** A split-conformal offset (log1p scale) is fitted on the most recent 20% of training incidents. Test coverage of the pessimistic estimate: 73.7% → 83.1%, target 85%; P50 unchanged at 97.9 min. The offset is in the artifact (`calibrationOffset`), the Kotlin evaluator and the golden vectors.
3. **Journey ↔ disruption.**
   - `JourneyLegDto.originSloid` and `destinationSloid` (optional); the OJP adapter reads board, alight and intermediate stops.
   - New `service/impact/`: `ReplayTimelineDisruptionSource`, `JourneyDisruptionMatcher`, `JourneyImpactService`, exposed as `POST /api/journeys/impact`.
   - Renens test: at 18:30, Lausanne → Genève on an IC 1 via Morges is affected; a lake alternative gives +95 min; advice `DETOUR_IF_DEADLINE`.
4. **Live mode: not implemented.** There is no `.env` and no API key. The reasons and the missing checks are in `analytics/README.md#live-mode-alerts-only-no-prediction`.
5. **Demo.**
   - `python analytics/scripts/run_demo.py [--offline]`.
   - `export` also writes `duration_model.v1.eval.json` (split models) so replays are never predicted by a model that saw the incident.
   - `docs/DEMO.md` is the run sheet: Renens screen by screen, verified curl calls, troubleshooting, Stadelhofen backup.
6. **Pitch numbers.** `python -m sbb_duration.pitch` writes `analytics/output/pitch_numbers.md`, every figure generated, with a one-sentence definition.
7. **Review fixes.**
   - Nothing is notified after the real end. Renens now has 6 notifications; before, one fired at 21:05, after trains resumed at 21:04.
   - New parity test for the evaluation artifact.
   - `analytics/INTERFACES.md` rewritten for the real outputs; new seams listed in `backend/INTERFACES.md`; contract figure updated; ROADMAP A3 and B5.

**Checks**
- From `analytics/`: `python -m pytest tests -p no:cacheprovider` → 18 passed.
- From `backend/`: `./gradlew --no-daemon test` (JDK 21) → 50 passed, golden vectors run (not skipped).
- `run_demo.py --offline` starts the backend, and every curl in `docs/DEMO.md` returns its documented result.

**Needs more data, another role, or a decision**

| # | Item | Who |
| --- | --- | --- |
| 1 | Data-use review of the liveticker, VIA and internal exports. It must happen before any artifact, timeline or golden file is committed, packaged in Docker or deployed. Until then, a deployed backend answers `UNAVAILABLE` and has no disruptions. | D with the data owner |
| 2 | Review of the three proposals in `API_CONTRACT.md` (outlook with `explanation`, multi-source timeline, journey impact) and of the additive `originSloid` / `destinationSloid` leg fields. | A, B, D |
| 3 | UI: render outlook, explanation (values only, localised by the UI), advice codes, timeline replay and notifications. No frontend file was changed. | A |
| 4 | OJP: verify with a key that `StopPointRef` uses stop-place sloids and whether `IncludeIntermediateStops` is needed. Without intermediate stops, a leg crossing the middle of a section is detected only by the two-station rule. | B |
| 5 | Alternatives: the planned timetable does not know the disruption, so the same search rarely contains an unaffected journey (none with the mock). This leaves `detourExtraMinutes` null and the advice `UNKNOWN`. Decide whether replacement buses or a second search via other corridors should count as alternatives. | B, product |
| 6 | Live mode: SIRI-SX or GTFS-RT key, then verification of cause, announced end, affected stops and identifiers (analytics README). | B, C, D |
| 7 | Advice rule: it compares the detour's extra minutes with the remaining disruption time. It ignores the traveller's deadline and the trip time after resumption. A product decision is needed before using deadlines. | Product, A |
| 8 | Model limits that need more data: drift (recent incidents last longer; coverage 83.1% below the 85% target); rare causes (EG_011 has 4 incidents); unmeasured recall and precision of the keyword extraction and the station gazetteer (needs labelled internal messages); retraining cadence. | C, data owner |
| 9 | Decision log: record the JSON tree artifact evaluated in Kotlin (in line with ADR-007), the replay disruption source and the conformal calibration. Add `DURATION_ARTIFACT_PATH` and `DISRUPTION_TIMELINES_DIR` to `.env.example`, and link `docs/DEMO.md` from `DEMO_SCRIPT.md`. | D |
| 10 | Merge: branch `prediction` (convention `feature/analytics`) needs a PR to `main` with the challenge criterion IDs (CFF1, CFF2) and this evidence. | D |

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
`backend/build.gradle.kts`, `backend/settings.gradle.kts`, `backend/gradle.properties`, `backend/gradlew`, `backend/gradlew.bat`, `backend/gradle/wrapper/*`, `backend/Dockerfile`, `backend/README.md`, `backend/src/main/kotlin/com/alt/backend/**`, `backend/src/test/kotlin/com/alt/backend/**`, `backend/src/test/resources/ojp_fixture.xml`, `backend/src/main/resources/logback.xml`

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
