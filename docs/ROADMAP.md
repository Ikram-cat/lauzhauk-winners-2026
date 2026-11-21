# Implementation roadmap

Milestones are relative to the actual development kickoff. D records the kickoff and official submission time when confirmed. Owners are roles from the [master plan](MASTER_PLAN.md). Keep task IDs stable in PRs and [handoff entries](SESSION_HANDOFF.md).

Use `Ready` for work that can start, `Planned` for sequenced work, `In progress` once an owner starts, `Blocked` when a named dependency is unavailable, and `Verified` only with evidence. Application milestones are open.

| ID | Owner | Status | Definition of done and evidence |
| --- | --- | --- | --- |
| I0 | D · Repository documentation baseline | Verified locally | The master plan matches the supplied file byte for byte, onboarding paths resolve, API examples parse, and repository status is recorded in the handoff. The changes are awaiting review in the working tree. |

## P0 · Start in parallel

All four tasks can start from this documentation baseline. A and B review the [v1 contract](API_CONTRACT.md) with D in the first 30 minutes. C can begin the provider field audit before receiving the CSV. Review any proposed contract changes with the affected owners before merging implementations.

| ID | Target | Owner and scope | Dependencies | Status | Definition of done |
| --- | --- | --- | --- | --- | --- |
| F1 | T+1–T+2 h | A · `feature/frontend-mvp` · timebox the Wasm browser build, then build search and results against a local contract fixture | v1 examples and local toolchain | Ready | Record the pinned toolchain, browser/build commands, output path, and browser evidence. If 45 minutes pass without a working browser build, coordinate the minimal HTML/JS fallback with D. Exercise loading, success, empty, and error states with labeled fixtures. |
| B1 | T+1–T+2 h | B · `feat/backend` · scaffold Ktor with health, request validation and test/demo transport adapter | v1 contract and local JDK | **Done** | Build from the wrapper, verify `GET /health`, and prove the journey success, empty, and invalid-input cases against the v1 wire shape with tests. Fixture results carry `MOCK`. Verified at `47a3d66` on `feat/backend`. |
| A1 | T+2 h | C · `feature/analytics` · audit the candidate transport provider and prepare field-mapping cases under `analytics/` | v1 DTO field list and access to the candidate | Ready | Record source URL/date, observed fields and missing fields, schedule versus realtime semantics, and usage/rate limits where verified. Share lawful sanitized cases or field notes with B. Surface unknowns. |
| I1 | T+1 h | D · `feature/integration` · establish the build and deployment checklist, confirm time and service access, and review the v1 contract | team access and v1 draft | Ready | Record kickoff, official deadline and challenge requirements or their blockers. Confirm who can configure Vercel/Railway, production branch, service roots, and environment ownership. Prepare CI jobs once A/B supply verified commands. |

## P0 · Public vertical slice by T+4 h

| ID | Owner | Dependencies | Status | Definition of done |
| --- | --- | --- | --- | --- |
| B2 | B · Live adapter and scheduled-arrival ranking | B1, verified provider behavior with A1 input as available | **Done** | OJP 2.0 adapter implemented with 8s timeout, Bearer auth, namespace-aware XML parser, ranking by arrival→transfers→departure→id. Requires `OJP_API_KEY` env var for live calls. Live OJP not yet validated (no key). Verified at `47a3d66`. |
| F2 | A · Browser API integration | F1, B1 and B2 for live verification | Planned | Point the Web build at Ktor, render normalized legs and provenance, and exercise success, loading, empty, and error states through the API. Use the exact browser origin configured by D. |
| I2 | D · Integrate, enable CI and deploy the public slice | I1, F2, B2, service access | Planned | Run verified backend and frontend CI jobs. Deploy reviewed `main` to Railway and Vercel, confirm public HTTPS, health, CORS, and a real browser journey from a clean session. Record URLs, revision, smoke evidence, and checkpoint `mvp-t4`. |

## P0 · Stable MVP by T+11 h

| ID | Owner | Dependencies | Status | Definition of done |
| --- | --- | --- | --- | --- |
| B3 | B · Harden normalization and ranking | B2 | Planned | Verify scheduled eligibility, stable ordering, missing platforms, midnight rollover, and provider failures. Return alternatives where supplied and record the build/test commands. |
| F3 | A · Polish responsive results | F2, B3 contract | Planned | Show recommended and alternative journeys when supplied, with departure, arrival, duration, transfers, supplied platforms, and provenance. Verify keyboard use and narrow/wide layouts. Coordinate any deadline control through the API proposal first. |
| I3 | D · Verify a deployable fallback checkpoint | I2, B3, F3 | Planned | Automated checks pass on reviewed `main`. Run the deployed smoke matrix, rehearse the [demo](DEMO_SCRIPT.md), and record `mvp-t11`, URLs, and rollback information. |

## P1 · Differentiation after the stable checkpoint

| ID | Target | Owner | Dependencies | Status | Definition of done |
| --- | --- | --- | --- | --- | --- |
| B4 | T+11–T+17 h | B with A · Deadline-aware and simplicity-aware recommendations | I3, agreed API extension | Planned | Test cutoff behavior, eligible alternatives, and no feasible arrival. Show reasons derived from supported data and handle unknown risk explicitly. |
| A2 | T+11–T+17 h | C · Audit the historical incident CSV | access and permission to inspect the supplied file | Blocked | Record actual fields and data dictionary, coverage, timestamp semantics, quality issues, permitted downstream use, and presence of non-incident observations. Keep raw data private. Decide what statistics or modeling the data supports. |
| A3 | T+11–T+17 h | C with B · Export an evaluated historical artifact if supported | A2, stable join keys and agreed artifact schema | Planned | Confirm chronological validation and data provenance. Export a reproducible versioned artifact only when justified. Report uncertainty and handle missing or incompatible lookups explicitly in Kotlin. |
| I4 | T+17–T+20 h | D with A and B · Add optional recovery guidance | I3, reliable place data and time budget | Planned | If delivered, show sourced feasible recovery options and disclose walking and buffer assumptions. Stage behind the stable demo until verified. |
| I5 | T+20–T+22 h | D · Freeze and submit | I3, official submission requirements, at least one verified differentiated capability from B4, A3 if integrated into the UI, or I4 | Planned | Re-run public smoke tests and demonstrate the capability with honest provenance. Record screenshots, credits, data limitations, fallback and tested commit, and preserve the known-good `submission` checkpoint. If the capability misses the gate, preserve and submit the stable MVP and record the full target as unmet. |

Stretch options include verified fares, contextual occupancy proxies, multimodal input, notifications, and native builds. Review them only after the stable demo and confirmed data or platform availability.


## Challenge submission work

See the [official criteria mapping](CHALLENGES.md). These tasks extend the baseline milestones above. A second KMP target is required for the JetBrains submission even though it is outside the Web MVP.

| ID | Owner | Target and dependencies | Status | Definition of done |
| --- | --- | --- | --- | --- |
| F4 | A | T+11 · F1 and B1 | Ready | Run the shared journey core on Web/Wasm and desktop JVM, or a verified Android alternative. Record both builds and the shared boundary (JB1, JB2). |
| B5 | B with C and A | T+11 replay, T+22 integrated · B2 and verified disruption identifiers or synthetic fixtures | Planned | Match events to legs, explain the impact, and return a feasible next step or an explicit no-option state (CFF1, CFF2). |
| F5 | A with B and C | T+22 · F4, reviewed extraction interface and test samples | Planned | Run screenshot plus typed context and manual fallback on both targets, show both themes and locales, and capture measured frugality (JB3, JB4, AX1, AX2). |
| I6 | D with all roles | Throughout development and freeze · I1, F4, B5, F5 | Planned | Record actual Air work and reviews, team credits, two-platform rehearsal, every criterion's evidence, and remaining gaps (JB5, AX3). |
