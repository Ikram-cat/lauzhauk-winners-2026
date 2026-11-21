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

- The station fields suggest known stops as you type. The local catalog also matches station-address aliases such as Place de la Gare and Place de Cornavin. Choose a suggestion with the mouse or arrow keys and Enter. Free-text stop names still go to Ktor. General street-address geocoding requires a separate location service.
- Each result shows its scheduled departure, arrival and date in Zurich time. Expand the details for individual legs, then choose the departure you intend to take. Selecting a journey opens the Smart Waiting Time preview when a transit leg is available.
- With no API origin, the app uses labeled local demo data for trips between Lausanne and Geneva, Bern or Zurich HB. In API mode, the departure board derives the first transit departure from each displayed itinerary and preserves the journey's provenance. It represents the current search and has no station-wide feed.
- Start the [Ktor backend](../backend/README.md) for API mode. Set `API_BASE_URL=http://127.0.0.1:8080` for the desktop process, or set that public origin in the Web entry document's `api-base-url` meta tag before building. Add the Web origin to Ktor's CORS allowlist. The frontend checks `GET /health` and uses the [v1 search contract](../docs/API_CONTRACT.md). The server defaults to mock data. OJP credentials belong only on the server.
- The **Journey options** panel filters returned itineraries by direct service and an inclusive arrival deadline. The filters stay in shared Kotlin and preserve Ktor's ranking. If no result matches, the earliest returned option appears separately with an option to revise the search.
- Expanded journeys show transit or walking segments and supplied departure and arrival platforms. The app localizes provider timeout, rate-limit, and other error states without substituting demo results. Live disruptions, station departures, fares, and AI extraction depend on future backend support.
- Date and time are interpreted in Zurich. Leave both blank for immediate departure. Times skipped or repeated during a daylight-saving transition are rejected explicitly.
- The clock toggles between analog and digital. The route train and progress rail respect the in-app motion control. The Web version initializes that control from the system preference.

## Planned features

### Smart Waiting Time / Smart Opportunity · P1

Give travelers practical ways to use the wait before their next train. For example, a traveler with 20 minutes at Lausanne station could see which activities would leave enough time to return to the departure platform.

- **Detect the waiting window.** Use the next unboarded transit leg on the selected journey. Compare its departure instant with the current time and display the station's local time. Prefer a verified live departure when available. Label timetable-only estimates and demo data explicitly. Hide recommendations when the departure is unknown or has passed.
- **Suggest nearby activities.** Use the traveler's selected preferences and nearby, verifiable opportunities such as coffee or a quick snack, a shop visit, a short walk, an attraction, or a café or public space to relax in. Use the traveler's current location with permission, or clearly identify the station as the assumed starting point. Mark fit as unavailable when the walking route or activity duration is unknown.
- **Check whether each activity fits.** Add the outbound walk, the expected activity duration, the return walk to the departure platform, and a configurable safety margin. For example, 4 minutes out + 7 minutes at the activity + 4 minutes back + a 5-minute margin needs 20 minutes. Use a confirmed station rendezvous point with a disclosed allowance if the provider omits the platform and a safe route can still be estimated.
- **Recalculate when departure changes.** On a verified delay, earlier departure, platform change, or cancellation, refresh the countdown and reassess the recommendations immediately. If the data becomes stale or the selected activity no longer fits, update its status and give a clear return-to-station prompt.
- **Show the decision in the frontend.** Build a localized **You have 20 minutes!** panel with a live departure countdown, activity cards showing durations and walking distances, a fit indicator with a text equivalent, and a clear **Return to station** reminder timed from the return walk and safety margin. Add a map of nearby opportunities once verified coordinates and walking routes are available. Keep the card list usable without the map.
- **Reuse the shared frontend design.** Keep the countdown and fit calculation in `src/commonMain` for Web/Wasm and desktop JVM. Use the existing Swiss Station typography, red accents, light and dark themes, French and English strings, and motion preference. Verify the exact-fit boundary, an earlier departure, an unknown platform, and missing or stale data on both targets.

**Available now.** The schedule-based preview uses the selected itinerary's first transit leg. Confirm that you are at the boarding station to see estimated station activities, adjust the safety margin, and set an in-app return-by reminder. A link opens the station area on OpenStreetMap.

**Still planned.** Live departure updates, traveler location, verified places and walking routes, an embedded map with activity locations, personalized suggestions, and background reminders depend on future data and platform support.

See the [frontend interfaces](INTERFACES.md), [testing guide](../docs/TESTING.md), and [challenge plan](../docs/CHALLENGES.md) for integration and submission work.
