# ALT / ALTERNATIVE — Frontend Feature Contract

> LauzHack 2026 · Implementation specification · Status: **[to do]** unless otherwise noted.
>
> This document consolidates the supplied `FEATURES.md`, `FRONTEND_FEATURES.md`, and the new requirements. Visual rules live in `DESIGN.md`.

## 1. Product mission and scope

ALT (expanded to **ALTERNATIVE** in the welcome animation) is a disruption-aware Swiss public-transport journey planner. It must explain what happened, how it affects the traveller, and which viable journey to take. **All schedules, prices, disruptions, feasibility decisions, and recommendations must originate from the backend**, not fabricated client data.

### Implementation decisions and existing contracts

- **Preferred stack:** Kotlin Multiplatform (KMP), Compose Multiplatform shared UI, Kotlin backend integration, shared models/state in `commonMain`. Platform-specific adapters handle location, notifications, etc.
- **Existing backend is the source of truth.** API reference: `../backend/README.md`; detailed response contract: `../docs/API_CONTRACT.md`; backend feature status: `../backend/FEATURES.md`. Confirm these files before coding; they were referenced but not attached here.
- Existing frontend contract specifies **one `GET /api/journeys` call per new search** and a single response envelope. Do not silently assume a pagination endpoint exists.
- The supplied requirements also mention **Expo/React Native** location integration. That is an **alternative stack**, not a dependency of the selected KMP/Compose implementation. For KMP, use platform-native geolocation behind a shared interface; for a separately chosen React Native implementation, use the appropriate Expo/React Native API.
- **[done]** None verified; this is a blank-slate rebuild. **[to do]** Demo-critical features. **[later]** Future capabilities.

## 2. Entry experience and welcome animation [to do]

1. On launch, show a centered **square containing `ALT`**.
2. Animate the mark expanding/revealing the full word **`ALTERNATIVE`**; preserve readable typography and respect reduced-motion preferences.
3. Transition to the **Journey Starting Location** screen without flashing or unnecessary loading.
4. Use exclusively **red, gray, white, and black** across UI, illustration, charts, icons, and motion (see `DESIGN.md`).
5. Include subtle, optional train movement and/or a Swiss railway clock-inspired animation, without making these necessary to use the app.

**Acceptance:** intro does not block navigation; replay only if appropriate; reduced-motion mode has an immediate static `ALTERNATIVE` reveal.

## 3. First screen — Journey Starting Location [to do]

Show a welcoming, clear starting-point selector. The user chooses one of three methods:

### A. Enter an address
- Editable search field, debounced suggestions, address validation, loading/no-results/error states.
- Query `GET /api/locations?query=<text>&limit=<n>`; select `ADDRESS` results with coordinates if supplied.
- Display selected address and an edit/clear action; do not accept unresolved free text as a validated location.

### B. Select a railway station
- Search stations by name using the same locations endpoint; prioritize/filter `STOP` results.
- Show station name and available locality/coordinates; offer nearby stations when backend supports proximity search.
- Clearly confirm selected station and allow replacement.

### C. Use my current location
- Prominent **Use my current location** button; permission request **only after explicit tap**.
- Retrieve one-time coordinates with platform geolocation; show permission-request/loading states.
- Query `GET /api/locations?near=<lat>,<lon>&radiusKm=<radius>&limit=<n>` and suggest nearest supported stop(s).
- Ask the user to confirm/change the proposed origin. Do not continuously track location by default.
- Handle denied permission, GPS unavailable, timeout, no nearby stop, and service errors; provide manual alternatives.
- **Web:** Browser Geolocation API. **Android/iOS KMP:** native platform APIs behind an expect/actual or interface adapter. **Only if a React Native stack is selected:** Expo/React Native location integration.

**Flow:** open app → welcome → select address/station/location → confirm origin → journey planning. Keep the origin when navigating back.

## 4. Journey Search [to do]

- From/To location autocomplete, with validated `STOP` or `ADDRESS` entities.
- Origin prefilled from first screen; destination required before searching.
- Optional departure date/time (empty = depart now); optional **arrive-by** deadline.
- Three mode tabs: **Fastest**, **Simplest**, **Cheapest** → backend `preference`.
- 1st/2nd class and Half-Fare settings → `fareClass` and `discount`.
- **Stuck at X** rescue shortcut: set origin to X, depart now, preserve editable destination.
- Keyboard/touch accessible controls, clear validation, loading and submit states.
- On submit, call **`GET /api/journeys` once per new search** with the supported parameters. Do not make three separate calls for three modes unless backend capabilities require it and the contract is explicitly updated.

## 5. Optimization and ranking [to do]

| Mode | Primary sorting rule | Tie-breaker / constraint |
|---|---|---|
| **Fastest** | Earliest **arrival time**, not shortest duration | Deterministic tie-break (e.g. fewer transfers), if supported |
| **Simplest** | Fewest transfers | Earlier arrival for equal transfer counts |
| **Cheapest** | Lowest available ticket price | Deterministic tie-break (e.g. earlier arrival); unavailable fares must not be treated as zero |

- Backend should provide the **global ranking** for the selected mode, including across pagination batches; the client must not misrepresent a locally sorted partial page as globally ranked.
- Keep a separate **chronological browsing** option sorted by departure time (earliest first). Do **not** silently mix chronological ordering with optimization ranking.
- When fare data is unavailable, label the price as unavailable; do not claim a journey is the cheapest without comparable pricing.

## 6. Journey results and Most Recommended [to do]

- Display a **Most Recommended** card **at the top in every mode**, sourced from backend `recommendedJourney` and considering user preference, delays, disruptions, feasibility, and deadline.
- Keep `primaryJourney` distinct from `recommendedJourney`; if they differ, explain why using structured backend `reasons[]` codes, localized in the UI:
  - `EARLIER_ARRIVAL {savedMinutes}`
  - `EXPECTED_FASTER {savedMinutes}` (historical expectation, **not** proof of a live incident)
  - `FEWER_TRANSFERS {savedTransfers}`
  - `CHEAPER {savedChf}`
  - `DEADLINE_MET`, `DEADLINE_MISSED {marginMinutes}`
  - `STAY_FASTEST`, `REALTIME_UNAVAILABLE`
- Below the featured card show **View Other Options** to expand/collapse alternatives.
- Render other journeys from `alternatives`; use `highlights.fastestId`, `simplestId`, `cheapestId` when present.
- Do not duplicate the recommended journey in the alternatives list.
- Provide useful empty states and explicit indicators of whether results are live, modelled, partial, or mock.

### Disruption banner

When `disruption` exists and impacts the displayed route, show a prominent red/gray warning with a triangle icon:

> ⚠️ **Travel disruption detected**
>
> Some services on your route may be affected. We've adjusted our recommendations to help you find the most suitable journey.

- Show affected service, from → to, `+X min` or cancelled, `cause`, `severity`, and low-confidence wording where appropriate.
- Use `status`: `NO_DISRUPTION` / `DISRUPTION_DETECTED` / `UNKNOWN` and `action`: `STAY` / `REROUTE` / `NONE`.
- **`UNKNOWN` means “Live status unavailable,” never “Everything is fine.”**
- Never create a disruption warning without a supported backend indication; avoid claiming recommendations were adjusted if they were not.

## 7. Journey card data and details [to do]

Each card/detail view should expose available backend fields clearly:

- `schedule`: departure, arrival, duration, transfer count, legs, platform numbers, per-leg departure/arrival delay minutes.
- `realtime`: predicted times/delays and source `GTFS_RT`, `OJP`, `DELAY_MODEL` (including `sampleCount`), or `NONE`; show last-updated time if available.
- `verified: false`: explicit **“Estimated; not a verified live status”** note. Backend may verify only the top four candidates.
- `risk`: `corridorIncidentRate` %, `level` (`LOW`/`MEDIUM`/`HIGH`), `typicalIncidentDurationMinutes`, `confidence`, `basis`.
- `fares.selectedChf` and four-entry `catalogChf` for fare class/discount combinations; `UNAVAILABLE`/`DISABLED` as text, **never CHF 0**.
- `maxOccupancy` and per-leg occupancy where provided.
- `feasible: false`: clearly mark a missed deadline, including minutes if supplied.
- `deadlineSummary.feasible` and `latestFeasibleDeparture` (e.g. “Leave by …”).
- Leg-by-leg detail timeline, transfers, platform information, incident markers, and concise explanation of recommended action.
- Do not invent missing station platforms, transfer guarantees, prices, or arrival times.

## 8. Dynamic loading — batches of 7 [to do; backend contract extension may be required]

**Requested behavior:**

1. On initial result display, request/display **7 journeys** in the active ordering (recommended card remains pinned separately).
2. On downward scrolling, fetch the **next 7**.
3. On upward scrolling, fetch the **previous 7** if not already cached.
4. Deduplicate by stable journey ID; preserve scroll anchor and visual position when prepending results.
5. Keep query, mode, fare options, date/time, and disruption snapshot consistent within one pagination session.
6. Avoid concurrent duplicate fetches; show skeleton/loading, end-of-results, retry, and stale-session states.
7. For chronological browsing, globally sort by departure time; for mode browsing, preserve global Fastest/Simplest/Cheapest ordering across batches.

**Integration constraint:** the attached backend contract documents `GET /api/journeys` as a single search envelope but **does not establish** `limit`, `cursor`, `offset`, or bidirectional pagination support. Therefore:

- **Preferred:** backend adds a documented paginated journey-search endpoint or cursor fields (`nextCursor`, `previousCursor`, stable `sort`, snapshot/version), returning 7 results per page.
- **Fallback for demo:** call `GET /api/journeys` once, cache the returned alternatives, and **reveal them locally in groups of 7**; this is not true backend fetching and must not be described as such.
- Do not invent endpoint names or pagination query parameters until backend team confirms them.
- A changed mode/search should reset the pagination session and prevent mixing results.

## 9. Disruption impact and next actions [to do]

- Describe **what changed**, **which leg is affected**, **consequence** (late arrival, missed transfer, cancellation), and **what to do next**.
- Support `STAY` vs `REROUTE` decisions from backend; allow the traveller to inspect alternatives before choosing.
- Show uncertainties, confidence, and provenance; never auto-purchase tickets or alter bookings.
- Provide simple actionable controls: inspect journey, compare options, retry search, update departure point.

## 10. Theme, localization, accessibility [to do]

- Light, dark, and system-default themes; persist choice, switch without restart. **Only red, gray, white, black** in both themes.
- Four languages per existing rebuild contract: **FR / EN / DE / IT**. (Earlier general specification mentioned EN/FR/DE; the newer four-language contract is adopted.)
- Externalize every string, including disruption text, errors, notification content, and accessibility labels. Localize date/time/number formats; preserve proper station names.
- Maintain journey state when changing language/theme; handle long translated strings.
- Accessible labels, adequate contrast, 44–48 dp touch targets, screen-reader announcements for result changes, keyboard focus/operation, reduced motion; never use color alone for severity.
- Responsive mobile/desktop layouts; loading, partial-data, empty, offline, and failure states.

## 11. Kotlin integration architecture [to do]

```text
Compose Multiplatform Screens
    ↓
Shared ViewModels / UI State (commonMain)
    ↓
JourneyRepository + LocationRepository (commonMain)
    ↓
Typed Ktor / Kotlin HTTP client and serializers
    ↓
Existing Kotlin backend API
    ↓
Journeys / disruption / recommendation data

Platform location adapter (Web / Android / iOS) → shared LocationRepository
```

Suggested directories:

```text
composeApp/src/commonMain/kotlin/
  ui/                  # screens and reusable components
  theme/               # red/gray/white/black tokens
  navigation/          # welcome → origin → search → results → details
  presentation/        # viewmodels/state
  domain/              # typed models
  data/                # repositories and API contracts
  recommendations/     # formatting/explanations from backend reason codes
  ai/                  # optional shared AI interfaces
composeApp/src/commonMain/composeResources/
  values/ values-fr/ values-de/ values-it/
composeApp/src/androidMain/   # native location / permissions
composeApp/src/iosMain/       # native location / permissions
composeApp/src/desktopMain/   # optional desktop support
backend/src/main/kotlin/      # existing backend; coordinate changes
```

- Shared AI/agent logic (e.g. Koog) is **optional** and must be supported by chosen targets. Use AI for explanation, not invention of operational facts.
- Explicit `Loading`, `Success`, `Partial`, `Error`, `Offline` UI states.
- Keep API secrets out of client source; cancel stale requests and preserve consistent UI state.

## 12. API error handling and data honesty [to do]

| Backend condition | UI response |
|---|---|
| `LOCATION_NOT_FOUND` (404) | Identify the invalid From/To field; suggest another search |
| `INVALID_REQUEST` (400) | Identify invalid argument; allow correction |
| `UPSTREAM_TIMEOUT` (504) | Explain timeout; allow retry |
| `UPSTREAM_RATE_LIMITED` (503) | Rate-limit message; spaced/backoff retries; no aggressive polling |
| `UPSTREAM_ERROR` (502) | Service unavailable; retry action |
| `status: UNKNOWN` | “Live status unavailable” |
| `dataStatus: MOCK` | Visible demo-data label |
| `meta.modelVersion` | Show model provenance; optional `/api/model` detail |

The upstream OJP free tier is shared by the team. Do not poll or retry excessively.

## 13. Deployment [to do]

- Build a static/hosted web frontend **if web is an active KMP target**; verify Compose web target feasibility with the team.
- Existing backend CORS allowlist reportedly includes `http://localhost:8080` and `http://127.0.0.1:8773`; send production origin to backend team for configuration.
- Test API connectivity, cross-origin requests, localization, themes, permission states, and real device sizes.

## 14. Later / architecture-ready

- **[later]** Pitch replay: `POST /api/journeys/impact` with demo provider and offline replay (`analytics/scripts/run_demo.py --offline`).
- **[later]** Journey watcher / polling, favorites, history, opt-in notifications.
- **[later]** Geographic map of journey legs.
- **[later]** Additional Android/iOS targets where not in MVP.
- **[later]** CO₂ and accessibility displays.
- **[later]** Occupancy-aware journey selection.
- **[later]** Optional AI conversational interface and station-service surfaces.

## 15. Demo-critical acceptance checklist

- [ ] Square `ALT` welcome animates into `ALTERNATIVE`; reduced-motion alternative exists.
- [ ] Only red/gray/white/black appear in the UI.
- [ ] Origin can be selected via address, station, or permission-based location.
- [ ] Origin confirmation leads to destination search and optional date/deadline.
- [ ] One supported backend journey request returns a displayed recommendation and alternatives.
- [ ] Fastest = earliest arrival; Simplest = fewest transfers then earliest arrival; Cheapest = lowest real fare.
- [ ] Most Recommended is pinned above a collapsible View Other Options list.
- [ ] Disruption banner and impact explanation use backend fields.
- [ ] All journey cards use backend schedules, real-time source, risk, fares, occupancy, and feasibility when available.
- [ ] 7-at-a-time results are implemented; genuine server-side pagination is used **only if backend supports it**.
- [ ] Scroll preservation, deduplication, and ordering are tested.
- [ ] Four locales, dark/light/system theme, responsive and accessible UI.
- [ ] Error states, unavailable live data, and mock labels are honest.
- [ ] `DESIGN.md` visual specifications are followed.

**North star:** Help travellers understand what changed, how it affects their trip, and what they should do next.
