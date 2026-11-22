# ALT → Alternative

Disruption-aware Swiss public-transport journey planner — built at LauzHack 2026 by a four-person team.

ALT doesn't just find your route: it **predicts what will go wrong with it** — chronic delays per line, station and hour; incident-amplified delay and cancellation risk; and the probability you lose a tight connection — and recommends the best alternative *before* you're stranded. When everything is on time, it tells you that too, with data and provenance.

**Live:** https://lauzhauk-winners-2026-production.up.railway.app (web app + API, same origin)

## What it does

- **Journey search** (from/to with station autocomplete, GPS "use my location", depart-now or arrive-by deadline, fastest / easiest preference) against live Swiss open data (OJP journey search + stop-event realtime, GTFS-RT cancellations and delays, live fares, occupancy).
- **Predicted arrival delays**, not just timetables: every trip carries per-leg statistical estimates learned from **274 days (Jan–Oct 2026) of national SBB actuals** — 652M rows parsed, 5.7M measured delay samples, 60,484 line buckets + 6,568 station buckets (median + p90 per line/station/weekday-class/hour). Blue badge = statistically estimated; red badge = live announced delay; projected times compose both.
- **Incident-conditioned predictions**: when a disruption is active on your route, estimates switch to buckets measured *during* 140 real incidents — extra delay, cancellation risk, suppressed-stop risk per line/station.
- **Connection-miss math**: the probability that a predicted delay eats your transfer slack. Above 50% the recommendation switches to the journey that avoids the tight connection (reason `TRANSFER_MISS_RISK`) — the win is "you don't lose your train", not five minutes.
- **Honesty as architecture**: unknown is `null`, never zero; every number carries its provenance (`HISTORICAL_ACTUALS` / live / simulation); the demo simulation (`demoPerturbation` seed) is labelled in the API response itself. We even measured whether rail incidents cascade onto buses (9 months, 104M rows) and report the honest negative result instead of inventing a feature — see [docs/PROPAGATION_2B.md](docs/PROPAGATION_2B.md).
- **Demo trigger**: a `Simulate disruption` button injects a reproducible synthetic incident into the *real* pipeline — status goes red, the reroute reason is shown, everything labelled.

## Architecture

- **Backend** — Kotlin 2.2 / Ktor 3, deployed on Railway (auto-deploy from `main`, Docker build from the repo root). Strict structured JSON API, additive-only contract, error envelopes, rate-limit-safe upstream clients, XXE-hardened XML. **245 tests green, CI on every push.** See [backend/README.md](backend/README.md) and the [API contract](docs/API_CONTRACT.md).
- **Frontend** — Kotlin Multiplatform + Compose Multiplatform, one shared UI codebase: **Web (WebAssembly)** served same-origin at `/`, **Desktop (JVM)** via `:composeApp:run`, **iOS** target in the repo (`frontend/kmp/iosApp`), Android in flight on member branches. The committed wasm dist in `frontend/kmp/dist` is what the backend serves — no Node at image build time. Swiss editorial design (see [frontend/DESIGN.md](frontend/DESIGN.md)): EN/FR/IT/DE, dark/light, the original ALT→Alternative intro, Swiss railway clock, and the Switzerland route-loading map.
- **Analytics** — offline Python pipeline (`analytics/`) building the delay/risk artifacts the backend loads (`delay_estimates_v2.json`, corridor risk, propagation study). Ist-Daten actuals never leave the machine; artifacts are versioned and committed.
- **Coordination** — team runs on GitHub Discussion #4 (protocol: TO/FROM/TYPE/ID/RE/TIME headers).

## Running it

- Production: the URL above. Health: `GET /health`.
- Locally (backend): `backend/` with `TRANSPORT_PROVIDER=ojp|mock|demo`, `OJP_API_KEY`, `GTFS_RT_API_KEY` — see [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).
- Locally (frontend): `cd frontend/kmp && ./gradlew :composeApp:run` (desktop) or `node serve.mjs` (serves the committed wasm dist with an /api proxy).
- Quick demo seeds: `&demoPerturbation=12345` on any search URL.

## Documentation

- [API contract](docs/API_CONTRACT.md) — the wire, strict and additive.
- [Challenge plan](docs/CHALLENGES.md) — JetBrains / AXA / SBB-CFF criteria mapping.
- [Architecture](docs/ARCHITECTURE.md), [deployment](docs/DEPLOYMENT.md), [analytics README](analytics/README.md), [backend README](backend/README.md), [propagation study](docs/PROPAGATION_2B.md).
- Agent rules for the workspace: [AGENTS.md](AGENTS.md).

## Status (submission)

Main is deployable and deployed. All backend features live in production; the Compose Multiplatform web app is the shipped frontend; iOS target present, Android on a member branch. Numbers: 274 training days · 5.7M delay samples · 140 conditioned incidents · 142 risk-scored corridors · 245 tests · 4 languages · 3 platforms in-repo · 0 fabricated values.
