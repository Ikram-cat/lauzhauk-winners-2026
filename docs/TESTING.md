# Testing and acceptance

Use the [API contract](API_CONTRACT.md) as the reference for expected HTTP behavior. The repository currently contains documentation without runnable application modules or automated tests. Add each suite alongside the feature it verifies, and publish the exact module command once it runs on the checked-in toolchain.

## Unit and integration tests

| Owner and level | Scenarios | Expected evidence |
| --- | --- | --- |
| B · ranking unit | Earliest departure eligibility, earliest scheduled arrival versus shortest duration, ties, shuffled input, midnight rollover and mixed offsets | Compare instants. For eligible journeys, order by arrival instant, transfers, departure instant, then ID. Empty input stays empty. |
| B · adapter unit | Approved or synthetic provider fixture, multiple legs, omitted platforms, canceled service, and invalid upstream response | Preserve valid times and leg order, keep unavailable platforms unknown, exclude known canceled journeys and handle invalid upstream payloads according to the contract. Use no external network. |
| B · Ktor integration | Inject a fixed clock and fake provider. Check health, valid search, omitted departure, empty results, invalid inputs, upstream rate limit, timeout, and bad payload. | Verify the status codes, JSON and provenance against the contract. For invalid inputs, cover blank and over-length values, duplicate or unknown parameters and departure timestamps without an offset. Health must pass even when the provider fails. |
| A · UI and API client | Exercise search with an injected client. Cover loading, populated result, empty result, network or HTTP error, and clearly marked mock data. | Render the relevant state, retry after failure, encode query values, format times in `Europe/Zurich`, and omit values the API leaves unknown. Validate date-input conversion at DST transitions if a local time picker is present. |
| C · offline analytics | Validate an approved sanitized sample or a labeled synthetic fixture once the schema is known. Cover malformed rows, timestamp parsing, missing values, chronological splits and deterministic export. | Report data coverage and invalid-row counts without exposing raw restricted data. Validate artifact schema and compatibility before B consumes an export. If modeling is viable, confirm feature availability at prediction time and evaluate on later observations. |
| D with B · container/config | Start the production image with an explicit local port. Check health and a request from an allowed browser origin, plus a disallowed origin. | Container responds on the configured port. The allowed origin receives the matching CORS response header. The disallowed origin receives no matching allow header. |

Use the [backend README](../backend/README.md), [frontend README](../frontend/README.md), and [analytics README](../analytics/README.md) for each implemented suite's command. Store fixture provenance alongside the fixture and keep restricted historical data out of Git and CI. Use synthetic timestamps, fixed clocks and fake providers to keep normal CI repeatable. A separate public smoke test checks the real provider.

## Deployed smoke test

Run after both services deploy and after any change to production domains, API configuration, or CORS. Use the [deployment runbook](DEPLOYMENT.md) to find the expected source revisions and obtain the HTTPS URLs.

1. Check `GET /health` on Railway and confirm HTTP 200 with `{"status":"ok"}`. Send a search missing `destination` and confirm HTTP 400 with the contract's error envelope.
2. Open the Vercel URL in a clean browser session. Search Lausanne to Genève for a departure during operating hours. If the app accepts a departure time, use a current service-time value with the correct Zurich UTC offset.
3. Inspect the Network panel. Confirm search uses the deployed Ktor origin over HTTPS, the response is HTTP 200 with at least one `LIVE_SCHEDULE` journey, and the browser reports no CORS or asset load errors.
4. Confirm the UI displays departure, arrival and available leg details. Check that ranking matches the API ordering and missing platforms, fares or risk values remain unknown. At T+11 also check alternatives when the provider returns them.
5. Use a mobile viewport and a second clean browser or device. Test empty and error states with controlled fixtures or browser network controls. Confirm a valid search succeeds after restoring connectivity.
6. Record time, tested source revision or deployment IDs for both services, URLs, browser or device, observed outcomes, and any limitations. Use the [demo checklist](DEMO_SCRIPT.md) and [session handoff](SESSION_HANDOFF.md) for evidence. Report a mock run as mock and an unavailable live-provider check as pending.

## Milestone acceptance

| Relative target | Required evidence | Owner |
| --- | --- | --- |
| T+2 · integration checkpoint | Health reachable, browser mock search renders a labeled fixture, first configured build checks pass | A, B, D |
| T+4 · initial MVP | Public Web and Ktor URLs, browser renders one real itinerary, basic validation and loading/error/empty states, documented reproducible `main` revision | A, B, D |
| T+11 · stable MVP | All T+4 checks pass on the released version, responsive results and alternatives when available, ranking and parser tests pass, demo fallback is documented, verified checkpoint tagged `mvp-t11` | A, B, D |
| T+22 · submission | Repeat the deployed smoke test on the submission revision. Verify at least one distinctive Plan B feature and its provenance. Capture the submission artifacts and tag. If differentiation remains pending, preserve the stable demo and record its evidence and the unmet feature target. | D with relevant owners |

Tags and a successful health check give release context. Acceptance requires the feature checks listed for the milestone. Evaluate timelines relative to the actual kickoff recorded by the team. [Master plan](MASTER_PLAN.md) · [Roadmap](ROADMAP.md)


## Sponsor acceptance checks

Follow the [criterion mapping](CHALLENGES.md). Run the same core demo on both KMP targets. Verify both themes and French and English without clipped copy. Exercise correct, ambiguous and failed image extraction, user corrections, and the zero-model-call manual path. Report measured calls, latency and observable token or cost totals on the agreed sample. Test matched, unrelated and uncertain incidents, feasible alternatives and no feasible route. Record mock provenance and actual Air evidence separately.
