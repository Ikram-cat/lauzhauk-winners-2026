# Plan B frontend

The Swiss Station journey planner uses Kotlin Multiplatform and Compose for Web/Wasm and desktop JVM. The shared UI, clock, date conversion, search state, demo data and Ktor client live in `src/commonMain`. Desktop and browser entry points provide platform configuration.

## JetBrains challenge criteria

Keep these requirements in scope for future frontend changes. Verify them on both targets before calling a release ready.

| Criterion | Implementation and verification |
| --- | --- |
| Dark and light mode | Use the shared Compose palette for the UI, including custom clock and track visuals. Switch modes and check readability on Web and desktop. |
| Localization | Keep French and English UI copy in a shared catalog. Verify search feedback, accessibility labels, journey details, and boarding information in both languages. Preserve station names as received. |
| Compose Multiplatform UI | Keep screens and presentation in `src/commonMain`. Put window and browser setup in their respective source sets. |
| Kotlin backend integration | Use the shared Ktor client and v1 journey search contract. Verify a configured origin on both targets, and keep demo data visibly labeled. |
| Shared AI or agent logic, when useful | Current journey search and clock need no model. If an AI feature is added, share its orchestration, confirmation, and fallback in `commonMain`. Evaluate Koog if it fits. Keep provider secrets on the backend. |
| At least two working platforms | Run the same journey workflow on Web/Wasm and desktop JVM. Record both smoke outcomes and the tested revision for the submission. |

## Run locally

From `frontend/`, use the included Gradle wrapper with JDK 17 or 21. Use `bash ./gradlew` on Linux or macOS.

```powershell
.\gradlew.bat desktopTest
.\gradlew.bat run
.\gradlew.bat wasmJsBrowserDevelopmentRun
```

The desktop command opens a native window. Use the browser URL printed by Gradle for the Web version. Build static Web assets with `wasmJsBrowserDistribution` and serve `build/dist/wasmJs/productionExecutable` over HTTP. The browser entry document only loads the compiled Kotlin application.

Use **EN** or **FR** in the header to change language. Use **Mode sombre / Dark mode** or **Mode clair / Light mode** beside the navigation to override the system appearance. Both choices apply to the current session, including journeys already displayed.

## Data and settings

- With no API origin, the app uses labeled demo data for trips between Lausanne and Geneva, Bern or Zurich HB. The departure board has its own demo label because the v1 API has no departure board endpoint.
- For Ktor search, set `API_BASE_URL` for the desktop process. For the browser, set the public `api-base-url` meta tag in `src/wasmJsMain/resources/index.html` before building, and add the Web origin to Ktor's CORS allowlist. The client uses the [v1 search contract](../docs/API_CONTRACT.md) and reports failures without switching to demo.
- Date and time are interpreted in Zurich. Leave both blank for immediate departure. Times skipped or repeated during a daylight-saving transition are rejected explicitly.
- The clock toggles between analog and digital. The route train and progress rail respect the in-app motion control. The Web version initializes that control from the system preference.

See the [frontend interfaces](INTERFACES.md), [testing guide](../docs/TESTING.md), and [challenge plan](../docs/CHALLENGES.md) for integration and submission work.
