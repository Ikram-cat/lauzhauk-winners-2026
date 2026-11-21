# Agent instructions

## Start every session

1. Read this file and the root [README](README.md).
2. Read the [master plan](docs/MASTER_PLAN.md). It governs the product scope, architecture, milestones, and role assignments. Edit it only with explicit authorization.
3. Read the assigned module's `README.md`, `AGENTS.md`, and `INTERFACES.md`. Integration reads the [workflow](docs/DEVELOPMENT_WORKFLOW.md), [deployment](docs/DEPLOYMENT.md), and [testing](docs/TESTING.md) guides and the interface files of affected modules.
4. Read the [handoff](docs/SESSION_HANDOFF.md) and [roadmap](docs/ROADMAP.md). Read the [API contract](docs/API_CONTRACT.md), [architecture](docs/ARCHITECTURE.md), and [decisions](docs/DECISIONS.md) for the work you will touch.
5. Inspect `git status --short --branch`, `git log -5 --oneline`, and the affected files and diffs. Use the relevant revision and runnable checks to resolve differences between the notes and the actual checkout.
6. Pick the next unfinished task within your assignment, confirm ownership with the integration owner when it is unclear, and resume from the latest verified implementation. State the scope, expected behavior, and verification plan before editing. Prior chat context may be unavailable.

## Work within the module boundaries

- Frontend owns browser state, presentation, and the API client under `frontend/`. Backend owns routes, DTOs, ranking, and upstream calls under `backend/`. Analytics owns offline data processing and artifacts under `analytics/`. Integration owns coordination, project docs, GitHub files, and cross-module verification.
- Use small, testable components and established interfaces. Keep UI, transport adapters, and analytics decoupled. Add dependencies only when they are needed for the current milestone. Leave persistence, accounts, and online Python outside the initial MVP.
- Route shared changes through the integration owner and affected producers and consumers. Update the API contract, fixtures, implementation, tests, and migration notes together as applicable. State compatibility changes in the PR and agree on rollout before merging a breaking change. Record significant architectural decisions and consequences in the decision log.
- Use verified provider fields and approved data. Leave unavailable delays, platforms, fares, and predictions unknown as the contract specifies. Identify runtime mocks and distinguish scheduled, historical, and estimated data. Keep secrets and restricted raw data out of Git, fixtures, logs, and browser bundles.
- Keep work on an assigned feature branch, preserve other agents' changes, and keep `main` functional and deployable as the MVP lands. Avoid direct pushes to `main` and remote settings changes without authorization.

## Finish significant sessions

- Run the available checks for the changes and report modified files, commands and results, verification gaps, and unresolved issues. Mark a task complete only when its definition of done is verified.
- Update [SESSION_HANDOFF.md](docs/SESSION_HANDOFF.md) using its entry format. Include the date, owner, branch, verified revision, completed work, files, checks, status, blockers, decisions, dependencies, and prioritized next steps. Update [ROADMAP.md](docs/ROADMAP.md) when a task changes status.
- Include the updates in the related PR when possible. When concurrent agents need to edit progress records, send each entry and task status change to the integration owner for consolidation. Preserve other agents' unmerged entries and verify conflicting claims against the checkout and history before reconciling them.
- Keep ongoing progress in the handoff and roadmap. Change permanent rules, architecture, and interfaces only when the corresponding decision or agreement changes.


## Challenge work

Read the [challenge plan](docs/CHALLENGES.md) before taking a sponsor-related task. Reference its criterion IDs in the PR and attach reproducible evidence. Record actual JetBrains Air work and review decisions. Keep personal screenshots, restricted data and secrets out of evidence.
