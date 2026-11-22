# ALT — Kotlin Compose Multiplatform frontend

A second, native client for the ALT Swiss journey planner, written once in Kotlin and compiled to
**two targets**: Desktop JVM (windowed app) and **WebAssembly** (browser).

It talks to the same public Ktor API as the web app and focuses on the disruption story: known
delays, statistical estimates, projected arrival times, fares, disruption risk, deadline feasibility
and a one-click **simulated incident** for demos.

## Targets and run commands

Prerequisites: JDK 21 (`$JAVA_HOME`), Node 22 on `PATH` for the Wasm/Webpack step.

```bash
cd frontend/kmp

# Desktop app (calls the deployed API directly; no CORS involved)
./gradlew :composeApp:run

# Web/Wasm distribution -> composeApp/build/dist/wasmJs/productionExecutable
./gradlew :composeApp:wasmJsBrowserDistribution

# Static server + API proxy for the Wasm build (http://127.0.0.1:8788/)
node serve.mjs

# Headless API smoke test (prints a real search, delays, fares and a simulated incident)
./gradlew -q :composeApp:smokeSearch
```

`ALT_API_BASE` overrides the API origin:

- Desktop: environment variable, defaults to `https://lauzhauk-winners-2026-production.up.railway.app`.
- Wasm: `globalThis.ALT_API_BASE` (set in `index.html`), defaults to **same-origin relative paths**.
  `serve.mjs` proxies `/api/*` and `/health` to the deployed backend, so the browser build needs no CORS change.

A prebuilt Wasm bundle is committed in [`dist/`](dist/) (built on 2026-11-22) so the page can be served
without a Gradle/Node toolchain:

```bash
cd frontend/kmp/dist && python3 -m http.server 8788
```

(Serving `dist/` alone gives the UI but no API: use `node serve.mjs` for a working demo.)

## Features

- From/to autocomplete via `GET /api/locations` (3-character debounce, explicit selection, swap button).
- Optional departure date/time and arrival deadline, interpreted in `Europe/Zurich` and sent as RFC 3339.
- Preference (fastest / cheapest / simplest), half-fare and 1st-class selection, mapped to
  `preference`, `discount=HALF_FARE` and `fareClass`.
- Recommended journey pinned first with badge, planned route shown separately when the backend
  recommends a different itinerary, then the alternatives (expandable).
- Disruption banner with localized reason sentences (`PRIMARY_LEG_DELAYED`, `EARLIER_ARRIVAL`,
  `EXPECTED_FASTER`, `DEADLINE_MET/MISSED`, …), action (`STAY` / `REROUTE`) and the disruption object.
- Per-leg rows: service label, platform, occupancy, **live delay** badge, **statistical estimate**
  badge (`≈+1.4`), projected times, incident cancellation / stop-skip / transfer-miss risk chips
  (rendered only when the API sends them).
- Fares (`selectedChf` + product), highlights (fastest / simplest / cheapest), modelled corridor risk
  with `typicalIncidentDurationMinutes`.
- **Simulate disruption**: re-issues the current search with `demoPerturbation=12345`, shows the
  `SIMULATION` chip with seed and the backend's `applied[]` labels.
- Deadline summary: feasible / not feasible, latest feasible departure, explicit
  “no route meets your deadline” state.
- English / French toggle (~55 keys), light / dark theme, loading, empty and error states with the
  API error envelope (`code — message`) and retry.
- Honest provenance: unknown values render `—`, never fabricated zeros; `MOCK` and “not verified”
  chips come straight from the payload.

## Structure

```
frontend/kmp/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradlew, gradle/wrapper/           # Gradle 8.11 wrapper (same version as backend/)
├── serve.mjs                          # static server + /api proxy for the Wasm build
├── dist/                              # committed Wasm distribution
└── composeApp/
    ├── build.gradle.kts               # kotlin multiplatform + compose + ktor + kotlinx
    └── src/
        ├── commonMain/kotlin/alt/     # Model.kt, Api.kt, State.kt, Copy.kt, Format.kt, App.kt, Results.kt
        ├── desktopMain/kotlin/alt/    # Main.kt (window), Api.desktop.kt, Smoke.kt
        └── wasmJsMain/                # main.kt (ComposeViewport), Api.wasm.kt, resources/index.html
```

## Versions

Kotlin 2.2.21 · Compose Multiplatform 1.9.3 · Ktor 3.3.3 · kotlinx-serialization 1.9.0 ·
kotlinx-coroutines 1.10.2 · kotlinx-datetime 0.7.1 · Gradle 8.11 · JDK 21.

## Limits

No map, no geolocation, no live push notifications, no seat/occupancy prediction of our own. The
disruption-detection status (`DISRUPTION_DETECTED`) is whatever the backend returns; the simulated
incident is a labelled demo path, never a claimed real disruption.
