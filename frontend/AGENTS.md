# Frontend agent rules

Follow the [root agent rules](../AGENTS.md). Role A owns `frontend/`. Coordinate API changes with the backend and integration owners before editing consumers or shared documentation.

Use the [JetBrains challenge criteria](README.md#jetbrains-challenge-criteria) for frontend changes. Implement frontend behavior in Kotlin and keep the shared Compose UI, theme, localization, and Ktor behavior usable on Web/Wasm and desktop. Share AI orchestration only when a feature needs it.

## Implementation rules

- Name Kotlin types and composables with `UpperCamelCase`, and functions and state properties with `lowerCamelCase`. Use feature names such as `JourneySearchScreen`, `JourneySearchState`, `JourneyCard`, and `JourneyApiClient` to make ownership visible.
- Let the presenter or state holder own immutable screen state and in-flight work. Hoist input values and user actions to the closest common owner. Keep composables side-effect free during rendering and launch requests from explicit events or deliberately scoped effects. Cancel superseded work or ignore stale responses.
- Keep network DTOs and browser networking outside screen components. Map DTOs to display models and use the backend’s itinerary order. Convert time instants for display in `Europe/Zurich`, preserving the date on midnight crossings.
- Use the backend base URL as public configuration. Keep credentials out of browser bundles. Route every journey request through Ktor.
- Use explicit `Idle`, `Loading`, `Results`, `Empty`, and `Error` states. Keep entered values available after an error, announce state changes accessibly, and use keyboard-operable controls with clear labels and visible focus.
- Display source status on results. Show unavailable optional fields as blank or with appropriate UI copy. Label mock itineraries as demo data. Ensure nullable risk, fare and platform fields render safely.

## Acceptance checks

- T+4 requires a browser build and a public Web search returning a real itinerary through Ktor, with successful, loading, empty, and failure states exercised. A synthetic result is suitable for the T+2 integration check.
- T+11 requires a responsive results view with the returned alternatives, schedule details, and optional fields handled safely. Verify narrow and wide layouts, keyboard search, one no-results response, and one external failure.
- Record the toolchain decision, build and browser checks, and open API issues in the PR. Send the integration owner the evidence and handoff update.
