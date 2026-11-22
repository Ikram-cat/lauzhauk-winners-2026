# Frontend — feature status (blank-slate rebuild)

The sources were cleared; the frontend is rebuilt from scratch. This file is the feature
contract for everyone touching `frontend/`: what must exist to use the **full potential of
the backend**. The stack choice is the lead's call; nothing here assumes a framework.

Legend: **[done]** shipped. **[to do]** build these (demo-critical first). **[later]** known
future features — keep the architecture ready for them.

API usage reference: [../backend/README.md](../backend/README.md) (URL, methods, arguments)
and [../docs/API_CONTRACT.md](../docs/API_CONTRACT.md) (full response shapes).
Backend feature status: [../backend/FEATURES.md](../backend/FEATURES.md).

## Done

- **[done]** Nothing yet — blank slate.

## To do — the rebuild

### 1. Search form

- **[to do]** From/To autocomplete against `GET /api/locations?query=&limit=` (debounced; results are `STOP` or `ADDRESS` with optional coordinates)
- **[to do]** "Use my location" button: browser geolocation → `GET /api/locations?near=<lat>,<lon>&radiusKm=&limit=` → nearest stop fills the From field (graceful denial/timeout states)
- **[to do]** Optional date + time (blank = depart now); optional **arrive-by deadline**
- **[to do]** Mode tabs: fastest / cheapest / simplest → `preference`
- **[to do]** Class 1st/2nd + Half-Fare toggle → `fareClass` + `discount`
- **[to do]** "Stuck at X" rescue entry: same search with `origin=X`, `departure=now`

### 2. One search, one call

- **[to do]** Call `GET /api/journeys` exactly once per search with all params; render from the single envelope
- **[to do]** Top status line from `status` (`NO_DISRUPTION` | `DISRUPTION_DETECTED` | `UNKNOWN`) and `action` (`STAY` | `REROUTE` | `NONE`)
- **[to do]** Disruption banner when `disruption` exists: service-label chip, from → to, "+X min" or "cancelled", `cause` + `severity` chips (structured tokens — the UI owns all wording), low-confidence note when `confidence` is low
- **[to do]** Recommended card = `recommendedJourney`; when it differs from `primaryJourney`, explain it using the client's own words built from `reasons[]` codes:
  `EARLIER_ARRIVAL{savedMinutes}`, `EXPECTED_FASTER{savedMinutes}` ("usually faster by X min" — not a disruption), `FEWER_TRANSFERS{savedTransfers}`, `CHEAPER{savedChf}`, `DEADLINE_MET`/`DEADLINE_MISSED{marginMinutes}`, `STAY_FASTEST`, `REALTIME_UNAVAILABLE`
- **[to do]** Alternatives list from `alternatives`; badge the `highlights` ids (fastestId / simplestId / cheapestId)

### 3. Journey cards (use everything the backend sends)

- **[to do]** `schedule` departure/arrival, duration, transfers; legs with platforms and per-leg `departureDelayMinutes`/`arrivalDelayMinutes`
- **[to do]** `realtime`: expected times + delays + source label — `GTFS_RT` / `OJP` / `DELAY_MODEL` (with `sampleCount`) / `NONE`
- **[to do]** `verified: false` → "expectations, not a live verdict" hint (backend could only check the top 4 candidates)
- **[to do]** `risk` chip: `corridorIncidentRate` %, `level` LOW/MEDIUM/HIGH, `typicalIncidentDurationMinutes`, `confidence`, `basis`
- **[to do]** `fares`: `selectedChf` for the requested combo; the four-number `catalogChf` for class/discount switching; render UNAVAILABLE/DISABLED as words, never 0
- **[to do]** `maxOccupancy` + per-leg occupancy; `feasible: false` → "misses your deadline by X min"
- **[to do]** Deadline panel: `deadlineSummary.feasible` + `latestFeasibleDeparture` ("leave by …")

### 4. Errors and honesty

- **[to do]** `LOCATION_NOT_FOUND` (404, names the field), `INVALID_REQUEST` (400, names the offending argument), `UPSTREAM_TIMEOUT`/`UPSTREAM_RATE_LIMITED`/`UPSTREAM_ERROR` (504/503/502) with a retry affordance; rate-limit retries must be spaced (the upstream OJP free tier is shared by the whole team)
- **[to do]** `UNKNOWN` must render as "live status unavailable" — never as "all good"

### 5. Presentation

- **[to do]** Four languages (FR/EN/DE/IT), light + dark, responsive desktop/mobile, basic a11y (labels, live region for results)
- **[to do]** Model provenance line (`meta.modelVersion`, `/api/model`) and demo-mode labelling (`dataStatus: MOCK`)

### 6. Deploy

- **[to do]** Static/hosted web build; the backend's `CORS_ALLOWED_ORIGINS` already whitelists `http://localhost:8080` and `http://127.0.0.1:8773` — post the production origin to the team channel so it gets appended

## Later (architecture should tolerate)

- **[later]** Pitch replay: `POST /api/journeys/impact` against the demo provider + replay timelines (`analytics/scripts/run_demo.py --offline`) for a controlled disruption story
- **[later]** Journey watcher (poll a departure), favourites/history
- **[later]** Map view of legs (geo coordinates are available upstream)
- **[later]** Android/iOS targets of the same app (multi-device)
- **[later]** CO2 and accessibility displays (backend has the data)
- **[later]** Occupancy-aware departure picking
