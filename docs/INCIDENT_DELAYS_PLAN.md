# Implementation plan — Phase 2: incident-conditioned delays + cancellation risk

Branch: `feat/incident-delays` (from main @ e617071). Worktree: `~/code/wt/incident`.
Scope: `backend/`, `analytics/`, `docs/API_CONTRACT.md`. FORBIDDEN: `frontend/`, `shared/`.
Additive contract only — every merged field keeps its exact name, type and meaning.

## 0. Product outcome

While an incident is LIVE on the traveller's line/corridor, the API switches the
estimate from the usual chronic delay to the INCIDENT-CONDITIONED one:

    "18:05  +3 known  +9.2 estimated (incident on your line; normally +1.4)"

New additive per-leg/journey fields (all nullable, only populated when an
active incident touches the leg):

    incidentExtraMinutes   — conditioned median minus the unconditional median
                            (decimal, signed) — "the incident typically costs
                            you this much ON TOP"
    incidentCancellationRisk — 0..1, share of matching trains that were
                            cancelled during similar past incidents
    incidentSamples / incidentCount — measurements / distinct past incidents
    estimatedBasis         — STATION | LINE | STATION_INCIDENT | LINE_INCIDENT | NONE

Ranking consumes the conditioned estimate via the existing projectedArrival
path, so the recommendation becomes incident-aware with no ranking change.
Proactive reroute story: a high `incidentCancellationRisk` on the primary leg
is surfaced BEFORE the operator cancels the trip.

## 1. Feasibility verdict (grounded in the data we hold)

- Incident volume: 523 extern incidents over ~2.7 years ≈ 16/month → ~32 inside
  the current 2-month Ist-Daten window. TOO THIN for fine conditioning;
  healthy when Ist-Daten is extended to 12 months (~190 incidents).
- WHAT IS LEARNABLE: same-line / same-corridor conditioning at COARSE
  granularity — (line x 4h-block) and (station x 4h-block) pools across all
  incidents. Also cancellation share (FAELLT_AUS_TF during windows) — the
  most actionable number.
- WHAT IS NOT LEARNABLE (documented, out of scope): per-(line,station,hour)
  conditioned buckets (sample starvation), and cross-line spatial
  propagation ("incident NEXT TO my route on another line") — would need
  years of incident history; the physical signal is mostly same-line anyway.
- DATA TRAP (verified by probe 2026-11-22): the incident CSV's
  `duration_from` is EMPTY for 519/523 rows — real windows must be
  reconstructed from `incident_audit_*.csv` (79k events) and
  `message_*.csv`, exactly like the existing sbb_duration analytics already
  does. Reuse its parsing; do not re-invent. Also: the CSV has embedded
  newlines — python csv module only, never awk.

## 2. Data extension

- Ist-Daten months: add 2026-01..2026-08 (monthly ZIPs already listed in the
  archive; ~1.4 GB each, ~10-30 min total download). Skip December/June
  halves containing timetable changes when picking extra months; Jan-May +
  Jul-Oct 2026 = 10 months ≈ 160 incidents. Rebuild cost with --workers 8:
  ~67 s per 2 months → ~6 min total.
- Incident windows: incident -> {stations on corridor, start, end} via the
  existing sbb_duration reconstruction (audit trail + "service resumed"
  messages). Corridor station lists reuse the same station-forms folding.

## 3. Conditioning (the join)

For every Ist-Daten train trip (grouped by BETRIEBSTAG + FAHRT_BEZEICHNER —
the trip's full stop list is reconstructible from its rows):

    route = set of stop names (folded) of the trip
    INCIDENT_ON_LINE := any incident whose corridor shares >= 2 folded
                        stations with the route AND the measurement's
                        scheduled time lies inside [start, end]
    incident of that measurement := the matching incident id

Conditioned buckets (parallel ladder, ONE MORE DIMENSION on the same
artifact — not a new model):

    linesIncident:   (serviceLabel, weekdayClass ANY-only, 4h block)
    stationsIncident:(station, weekdayClass ANY-only, 4h block)

Per conditioned bucket emit: median/p90/shareLate (seconds-resolution, 1
decimal, same as Phase 1), samples, incidentCount (distinct incidents),
cancellationShare (FAELLT_AUS_TF=true trips at matching stops during
windows / all matching trips). MIN_SAMPLES 10 pooled over all incidents.

Survivor-bias honesty (contract wording): conditioned delays are measured
only on trips that ran and reported; cancelled trips are excluded from the
delay numbers but counted in cancellationShare.

## 4. Backend changes (additive)

- DelayEstimateModel: new `incident` parameter on estimate() — populated only
  when the existing live detection (GTFS-RT/OJP/SIRI disruption on the leg's
  line/corridor) found an ACTIVE incident. Lookup order when incident is
  set: line-conditioned -> station-conditioned -> unconditional ladder (the
  Phase-1 path, unchanged when no incident). estimatedBasis exposes which
  ladder answered.
- JourneyPlanService: pass the detected disruption's corridor/label into
  estimateFor; compose projected* from the CONDITIONED estimate; emit the new
  fields. Known layer and all Phase-1 fields untouched.
- Wire: legs[].realtime and journeys[].realtime gain
  `incidentExtraMinutes`, `incidentCancellationRisk`, `incidentSamples`,
  `incidentCount`, `estimatedBasis`. Null + basis STATION/LINE as today when
  no incident. /health estimates feed gains `incidentBuckets`.
- Contract: new subsection under the Phase-1 estimates section.

## 5. Tests

- Aggregator: fixture with synthetic incident windows -> conditioned bucket
  emission, cancellationShare math, ANY-only weekday pooling.
- ModelTest: ladder switch (incident set -> conditioned bucket; incident set
  but bucket missing -> unconditional fallback), basis reporting.
- Route/integration: DemoScenario replay of a disruption (existing
  ReplayTimelineDisruptionSource machinery) drives an end-to-end journey
  where conditioned fields appear and the unconditional-only case still
  behaves exactly as Phase 1 (byte-identical old fields).
- Full fresh suite green; count from JUnit XML.

## 6. Order of work (suggest: subagent again, plan-as-brief)

1. Probe the audit-based window reconstruction against Sep-Oct (how many
   incidents, window sanity) — abort/adjust if < 20 incidents in 10 months.
2. Download 2026-01..2026-08 monthly ZIPs; extend aggregator (conditioned
   tables + cancellationShare) — one pass, --workers 8.
3. Backend model + service + wire fields + contract + tests.
4. Fresh suite, installDist smoke incl. a DemoScenario incident replay,
   scope diff, commit, push. NO merge without Sacha's word.

## 7. Traps

- incident CSV: duration_from empty (use audit reconstruction); multiline
  quoting (python csv only).
- Grouping rows per trip to get routes: cap memory by streaming per-day
  (route sets are per-day only).
- Never let conditioned stats leak into the unconditional ladder (separate
  tables, separate emission threshold checks).
- Cancellation share denominator: use ALL trips matching the stop+window
  (including cancelled), not just reporting ones.
- Do not modify Phase-1 bucket semantics; the artifact grows a new top-level
  array, existing consumers ignore it.
