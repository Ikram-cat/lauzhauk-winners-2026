# Development workflow

The integration owner (D) coordinates the four workstreams and merges small, working slices into `main`. Base each feature branch on current `main` and send changes back through a pull request. Use the repository's branch names below when following the master plan's workflow.

| Branch | Owner | Default scope |
| --- | --- | --- |
| `main` | D — Integration / Deployment | Reviewed, runnable checkpoints |
| `feature/frontend-mvp` | A — Frontend | `frontend/` |
| `feature/backend-ktor` | B — Backend / Transport | `backend/` |
| `feature/analytics` | C — Analytics / Data | `analytics/` |
| `feature/integration` | D — Integration / Deployment | `.github/`, `docs/`, root documentation and deployment coordination |

Start with the [session initialization rules](../AGENTS.md) and claim one item in the [roadmap](ROADMAP.md). Share the expected behavior, file paths, and checks with D before editing. Use separate checkouts or worktrees when concurrent agents need different branches. Agree on disjoint paths before editing in a shared checkout. A and B coordinate request/response changes through D, and C brings in B when designing a fixture consumed by backend tests or an artifact consumed at runtime. D coordinates ownership for changes under `shared/` and edits to the backend Dockerfile or frontend build settings.

Use French for team collaboration and English for code and technical names.

## Start and sync a branch

In your own clean checkout, run the following. Use the corresponding feature branch from the table for your assignment.

```sh
git status --short --branch
git fetch origin
git switch -c feature/frontend-mvp origin/main
```

If the feature branch already exists locally, switch to it. If it only exists on the remote, check it out with `git switch --track origin/feature/frontend-mvp`. Never recreate a branch with unfinished work. When upstream changes affect your feature, fetch and merge current `origin/main` into your feature branch. Resolve conflicts within your owned paths after reading the competing changes. If a conflict affects another workstream, request that owner's review before choosing the resolution. Rerun affected checks before updating the PR. Coordinate any history rewrite on an unpublished personal branch and keep shared branch history intact.

## Commits and pull requests

1. Commit one coherent change at a time with an action-oriented subject, such as `Add Ktor health endpoint`. Review the staged list and diff before committing. Exclude credentials, local environment files, build outputs and any data whose redistribution has yet to be approved.
2. Open a focused PR into `main` using the [PR template](../.github/PULL_REQUEST_TEMPLATE.md). Include purpose, changed files, affected interfaces, exact tests and outcomes, and risks or limitations. Link the relevant roadmap item and mention dependent PRs. Describe an unrun check or a known failure explicitly.
3. Get at least one reviewer. For changes to request/response shapes, environment variables, or analytics artifacts, have affected owners review the migration and merge order with D. Update the contract and module documentation in the same PR where practical. For a breaking change, agree on compatibility, sequencing, and rollback before merging.
4. D merges after applicable builds and tests pass and a reviewer approves. Until CI exists, attach reproducible local check results to the PR. Merge a working vertical slice early, then keep integrating small increments. Save incomplete experiments on the feature branch.
5. D verifies the merged state and public endpoints when available. Record the exact source revision and deployment evidence for each milestone, then tag verified checkpoints `mvp-t4`, `mvp-t11`, and `submission` as applicable. Follow the [testing criteria](TESTING.md) and [deployment runbook](DEPLOYMENT.md).

Use PRs for `main`. Obtain explicit authorization before modifying GitHub branch rules, repository settings, or platform settings. The team enforces review and merge expectations by agreement until any remote checks are configured.

## Preserve session state

At the end of a significant session, update the [handoff](SESSION_HANDOFF.md) with the branch, relevant commit, completed work, changed paths, checks, blockers, pending decisions, dependencies, and ordered next actions. Update the [roadmap](ROADMAP.md) when any task status changes and the [decision log](DECISIONS.md) when the team makes a significant architectural decision. Keep the [master plan](MASTER_PLAN.md) unchanged unless explicitly authorized to edit it.

Send D the module's handoff entry when concurrent PRs touch the same file. D reconciles entries against Git history and verified checks while preserving each owner's evidence. When no application changes merge, record what actually changed and which runtime checks remain pending.


## Challenge evidence in PRs

Include [criterion IDs](CHALLENGES.md), the command and observed result for each tested target, and relevant contribution credits. When using JetBrains Air, include a summary of the task assignment, human steering and reviewed changes. Keep private session data out of the PR.
