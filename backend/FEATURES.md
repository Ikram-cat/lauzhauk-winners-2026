# Backend — feature status

Legend: **[done]** shipped on `main`, covered by tests. **[to do]** next up (demo-critical or close to it).
**[later]** planned — design current code so it can accommodate these without rework.

## Journey planning (`GET /api/journeys`)

- **[done]** One call: search + realtime disruption detection + reroute recommendation, structured JSON only (no prose)
- **[done]** `origin`/`destination` accept a stop id, `"lat,lon"`, or free text (resolved via OJP)
- **[done]** `departure` (blank = now), `preference` FASTEST/CHEAPEST/SIMPLEST, `fareClass` 1|2, `discount` NONE/HALF_FARE
- **[done]** `arrivalDeadline` mode: per-journey `feasible`, `deadlineSummary.latestFeasibleDeparture`, DEADLINE_MET/MISSED reasons with margin
- **[done]** `alternatives` sorted by preference (≤ `limit`), plus `highlights` (fastestId / simplestId / cheapestId)
- **[done]** Rescue flow: a "stuck at station X" search is the same endpoint with `origin=X`, `departure=now`

## Disruption detection (the core value)

- **[done]** GTFS-RT primary: cancelled trips (matched by route + start time), suppressed stops, delays ≥ 3 min
- **[done]** OJP fallback: timetable vs live board diff; "planned but not live" = cancelled
- **[done]** `cancelledEarlier`: notices the train the traveller wanted being silently dropped by the planner
- **[done]** Detection depth: top 4 candidates by preference; deeper journeys carry `verified: false`
- **[done]** Reroute only when ≥ 5 min saved (`EARLIER_ARRIVAL` savedMinutes); `FEWER_TRANSFERS` / `CHEAPER` bonus reasons
- **[done]** Calm case: `EXPECTED_FASTER` (only under `preference=FASTEST`) — expected 5+ min earlier via live estimate or measured chronic delay
- **[done]** `UNKNOWN` + `STAY` honesty when live state cannot be established (`REALTIME_UNAVAILABLE`); never a fabricated `NO_DISRUPTION`
- **[done]** SIRI PtSituation `cause` + `severity` tokens attached to matched disruptions (no prose)

## Data models

- **[done]** Delay artifact: measured station/hour + line/hour lateness (live-sampled), fills `realtime` when nothing live is known (`source: DELAY_MODEL`, `sampleCount`)
- **[done]** Risk artifact: 953-day corridor incident rate + typical disruption duration per journey
- **[done]** Occupancy per leg per class + journey `maxOccupancy`
- **[done]** NOVA fares: `selectedChf` for the requested class/discount + full four-number catalog; PRICED / UNAVAILABLE / DISABLED, never a fake 0
- **[done]** `GET /health` per-feed status with GTFS-RT counters; `GET /api/model` static, cacheable metadata

## Duration prediction (role C, merged in PR #10)

- **[done]** `POST /api/disruptions/outlook` + `POST /api/journeys/impact` (proposal routes): remaining-disruption estimate (P50 + calibrated pessimistic), advice code, training-data explanation
- **[done]** Offline-trained model with golden-vector parity; demo scenario provider + replay timelines (`analytics/scripts/run_demo.py --offline`)
- **[later]** Fold the outlook into `GET /api/journeys` responses so the main client gets duration advice without the separate POST

## Operations

- **[done]** 60 s search cache, circuit breaker, keys never logged or echoed
- **[done]** `./scripts/smoke.sh <base>` — 22 deploy checks; 187 tests; CI green
- **[to do]** Refresh the delay artifact from the sampler right before the demo (one `--export-backend` run + commit)
- **[to do]** Frontend-origin appends to `CORS_ALLOWED_ORIGINS` when the new frontend posts its production origin

## Later (design for it)

- **[later]** GTFS-SA live Service Alerts (`GTFS_SA_API_KEY` needs a separate subscription; wiring exists)
- **[later]** Connection risk: leg-1 slip vs leg-2 margin under the modeled p90 (missed-connection warning)
- **[later]** Risk-aware calm-case ranking (corridor incident rate as a tiebreak/penalty)
- **[later]** CO2 estimates and accessibility notes surfaced end-to-end (data already parsed upstream)
- **[later]** Map-ready geo payloads for legs (OJP carries the coordinates)
