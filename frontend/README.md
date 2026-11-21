# Frontend

Role A owns the browser application on `feature/frontend-mvp`. The frontend takes station names and an optional earliest departure, calls the Ktor API, and displays itinerary options in the order returned by the API. The release target is a static site on Vercel.

**Current state.** The Swiss Station browser preview runs as static HTML, CSS, and JavaScript. The Gradle scaffold, Kotlin/Wasm gate and public deployment are pending.

## Run the Swiss Station preview

From the repository root, run `python -m http.server 8765 --bind 127.0.0.1 --directory frontend` and open `http://127.0.0.1:8765/`. There are no package or build dependencies.

- `index.html` and `styles.css` define the search form, Zurich clock, departure board, route rail, and responsive cards. Click the clock to switch between analog and digital. Train and loading animations honor the system reduced-motion setting.
- `app.js` connects the controls, clock, countdown and result states. `journeys.js` owns Swiss time conversion and the separate demo and Ktor adapters.
- The default view uses synthetic departures and supports demo searches between Lausanne and Genève, Bern, or Zürich HB. The board remains a labeled demo because v1 has no departures endpoint.
- To use Ktor, set the `api-base-url` meta tag in `index.html` to the public backend origin and allow the preview origin in its CORS configuration. The preview uses the v1 search API and displays API errors without substituting demo results.

## Implementation order

1. Timebox the first Compose Multiplatform Web/Wasm spike to 45 minutes. Verify that the pinned wrapper builds a page, the page opens in a supported browser, and a production distribution includes the required assets. Record the commands, versions, output directory, and evidence for the integration owner. Apply the master plan’s minimal HTML/JavaScript fallback if the gate fails.
2. Build a station search form, results list and journey card. Connect them through a presenter or state holder and a `JourneyApiClient`. Use the documented synthetic response until the backend is reachable.
3. Point the client at Ktor and test the real search, an empty result, and API/network failures. Verify the Vercel preview before the public smoke test.
4. By T+11, show multiple options when returned, with leg and platform details when present. Keep deadline controls behind a capability supported by the API. Later Plan B screens can reuse the journey result and add recovery actions through reviewed contracts.

## Planned layout

Keep the browser entry point, public build configuration, and browser-specific networking in `wasmJsMain`. Where practical, use `commonMain` for platform-neutral presentation and state. Organize the form, search state, screen, and reusable result components together by feature. Keep theme and API client code separate from rendering. A failed Wasm spike can replace the rendering stack while preserving the same HTTP client boundary and screen states.

## Local build and verification

Once the scaffold lands, install the JDK pinned by the frontend build, run commands from `frontend/`, and set public `API_BASE_URL` to the local Ktor HTTP URL or an approved preview HTTPS URL. Use the frontend dev server’s reported port and add its browser origin to the backend CORS allowlist when connecting across origins. Expected Wasm tasks are below. Use `.\gradlew.bat` on Windows PowerShell. If the generator creates a nested Gradle project, use its qualified task path and directory. [Kotlin/Wasm setup](https://kotlinlang.org/docs/wasm-get-started.html), [Kotlin Multiplatform run configurations](https://kotlinlang.org/docs/multiplatform/multiplatform-setup.html).

```sh
./gradlew wasmJsBrowserDevelopmentRun
./gradlew wasmJsBrowserDistribution
```

The distribution should be in `build/dist/wasmJs/productionExecutable` relative to the Gradle project owning the target. Verify the actual path and serve the produced site over HTTP during the spike. Execute state and response-mapping tests through the scaffold’s browser test task once configured. In a clean browser, test keyboard interaction, narrow and wide layouts, loading, successful results, no results, transport failure, and rapid successive submissions. Record the verified commands for the chosen toolchain.

See [frontend interfaces](INTERFACES.md), [API contract](../docs/API_CONTRACT.md), [testing](../docs/TESTING.md), and [deployment](../docs/DEPLOYMENT.md).


## Sponsor deliverables

Use Web/Wasm and desktop JVM for the same core Compose workflow, with Android emulator as the alternate target. Share presentation and extraction orchestration in `commonMain`, add French and English and both themes, and isolate image pickers per platform. Verify manual recovery when extraction fails and an actionable next step after a disruption. [Challenge plan](../docs/CHALLENGES.md)
