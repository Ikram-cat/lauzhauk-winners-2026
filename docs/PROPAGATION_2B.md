# Phase 2b — propagation to non-rail services (measured)

Branch: `feat/propagation-2b` (from `main` @ 0adedcc). Analytics only; the backend wire
format, `delay_estimates_v2.json` and every backend source file are untouched.

Question from the lead: when an incident hits a rail leg (e.g. a Morges → Lausanne train
is cancelled), what happens to the nearby **701 bus**, trams and the Lausanne **m1** metro?
Can we train it, and how big is the effect?

## 1. Answer in one paragraph

Non-rail services are richly measured — 104.2 M Bus/Tram/Metro rows fall in the 45 rail
corridor towns over the 9-month window, 92.4 M of them with a `REAL` departure prognosis —
and the incident join produces 2,574 conditioned service buckets from 83 incidents. But the
measured propagation effect is **small**: the median conditioned-vs-unconditional delay
delta is **0.0 min**, only 39 % of buckets are positive, and just 71 of 2,574 (2.8 %) reach
+1 min. The largest honest Morges-corridor effects are +0.3 min on the 701, +0.9 min on the
724 and +2.0 min on the 724 at Allaman. The Lausanne **m1 metro shows no measurable
propagation** (all deltas between −0.1 and +0.1 min over 20–28 incidents per block).
"the cancelled train makes the 701 much later" is **not** what the data says; "the 701 is
usually a couple of minutes late on a bad evening whether or not a train incident is live"
is. Occupancy — the actual "crowded bus" story — is **not measured anywhere** in our data
(§5).

## 2. Availability probe (the lead's data question)

Sampled days from three months (2026-03, 2026-07, 2026-10), 1–2 daily CSVs each,
`PRODUKT_ID` is German and mixed-case in the feed (`Bus`/`BUS`, `Tram`, `Metro`):

| Product | rows / day | `REAL` departure rows / day | notes |
| --- | --- | --- | --- |
| Bus | 2.08–2.14 M | 1.85–1.92 M | national; ~90 % REAL |
| Tram | 0.25 M | 0.23 M | national; Bern + Zürich only |
| Metro | 4.3–4.9 k | 3.3–4.5 k | **m1 only** (Lausanne/Renens); ~90 % REAL |
| Zug (rail) | 0.18 M | — | the Phase-1/2 tables |

Town-prefix availability per day (`HALTESTELLEN_NAME` before the comma):

- **Morges** 6.8–7.6 k rows/day, 10 bus lines: 701, 702, 703, 704, 724, 726, 730, 735, 736, 740.
- **Lausanne** 3.9–16.2 k rows/day, 14–34 bus lines plus m1.
- **Renens** ~4.4–4.8 k rows/day, 11–14 bus lines plus m1.
- **Nyon** ~6.1–6.3 k, **Vevey** ~7.8 k, **Allaman** ~560–570 (6 lines: 720, 721, 723, 724, 727, 846).

So bus coverage is **not** thin: the 701 alone contributes ~1.9 k `REAL` departure
measurements per 4-hour block in Morges. Metro coverage exists but is only one line, which
bounds what "metro availability" can ever say.

Verdict: **conditioning is feasible for bus and tram, barely meaningful for metro** (m1 is
one line, two towns, and it runs independently of the rail corridor).

## 3. Method (what was actually trained)

Script: `analytics/scripts/build_delay_estimates.py` (extended, rail paths unchanged).
Output: `analytics/output/propagation_services.json` (git-ignored, like every analytics
output), a **separate** file so the shipped `delay_estimates_v2.json` keeps its exact shape.

- **Join, never coordinates**: a non-rail row joins an incident when its
  `HALTESTELLEN_NAME` town prefix (`"Morges, Gare"` → `MORGES`) is a town on the incident
  corridor and its scheduled departure/arrival lies inside `[start, end]`.
- **Corridor towns are widened by the affected trains.** Incident descriptions only yield
  the two endpoint stations ("Yverdon – Lausanne"); the affected trains actually call at
  Morges, Allaman, Renens, Nyon, … A town therefore counts as on the corridor when a rail
  trip whose route intersects the corridor at ≥ 2 stations calls there during the window.
  Without this step the Morges question is unanswerable — the incident corpus contains no
  incident whose parsed corridor names Morges directly (17 descriptions mention it, the
  parser extracts only endpoints).
- **Buckets**: `(serviceLine, town, product, 4h block)`, ANY weekday class, `MIN_SAMPLES = 10`
  pooled over incidents — the same coarse pooling and honesty rules as Phase 2.
- **Emitted per bucket**: `dep{median, p90, shareLate, samples}`, `unconditionalMedian`
  (same line/town/block over the whole window, incident or not), `deltaMedian`,
  `incidentCount`, `cancellationShare`, `suppressionShare`, `plannedStops`.
- **Denominators**: `cancellationShare` / `suppressionShare` count every planned stop inside
  a window including cancelled/skipped rows; delay medians exclude them (survivor bias is
  the same as Phase 2 and is stated, not hidden). A measured zero is emitted as `0.0`.
- **Run**: 9 monthly ZIPs (2026-01..05,07..10; June absent), 274 days, 652.8 M rows read,
  `--workers 8`, ~12 min.

## 4. Measured results

Coverage: 474 incidents reconstructed, 102 used by the rail Phase-2 tables, **83 touch a
non-rail corridor town window**; 104,243,324 non-rail rows seen in the 45 corridor towns,
92,380,092 with a REAL departure measurement, **2,147,264 rows inside incident windows**;
2,574 emitted buckets (2,528 BUS, 34 TRAM, 12 METRO) over 1,885,769 conditioned departures.

Distribution of `deltaMedian` over the 2,574 buckets: median **0.0 min**, 39.4 % positive,
2.8 % ≥ +1 min, 1.0 % ≤ −1 min, range −4.6 … +11.2.

Morges-corridor towns (Morges, Allaman, Renens, Nyon; `n ≥ 50`, `incidents ≥ 3`):
157 buckets, median delta **+0.1 min**, 52.9 % positive.

Demo-ready rows (all measured, all from the artifact):

| service | town | block | median | normal | delta | p90 | n | incidents | cxl |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Bus 701 | Morges | 20–24 | 2.0 | 1.7 | +0.3 | 6.5 | 1,954 | 15 | 0.006 |
| Bus 724 | Morges | 16–20 | 2.7 | 1.8 | +0.9 | 7.2 | 203 | 19 | 0.000 |
| Bus 736 | Morges | 20–24 | 2.3 | 1.3 | +1.0 | 6.5 | 42 | 11 | 0.000 |
| Bus 724 | Allaman | 16–20 | 3.6 | 1.6 | +2.0 | 21.1 | 61 | 8 | 0.000 |
| Bus 18 | Lausanne | 16–20 | 4.5 | 3.3 | +1.2 | 11.3 | 1,066 | 11 | 0.029 |
| Metro m1 | Lausanne | 16–20 | 0.8 | 0.8 | 0.0 | 1.8 | 966 | 27 | 0.002 |

Reading: the 701 is the **most stable** service on the corridor (delta +0.3 min over 15
incidents — a real but tiny effect); the local feeder lines 724/736 and the Allaman branch
react more, with p90s that jump to 20 min; bus 18 (the Lausanne–Renens urban axis that
parallels the rail line) carries the clearest signal, +1.2 min and ~3 % cancellations.
Trams (Bern lines 3, 6, 7, 8, 9) sit between −0.3 and +0.2 min. `suppressionShare` is 0 for
every non-rail bucket — buses do not skip stops in this data.

## 5. Occupancy — the honest verdict

`Ist-Daten` has **no occupancy field**, and no historical occupancy exists in any dataset we
hold (liveticker, GTFS-RT service alerts, OJP responses are punctuality/alert data only).
The "cancelled train → crowded bus" narrative is *plausible* (demand shifts to the bus) and
can be **reasoned about from delay + demand**, but it **cannot be trained** on this data —
there is no label to fit. Live per-class occupancy exists only via OJP at runtime
(`occupancy` on the journey response), i.e. "at the moment you look", never historically.
We therefore emit **no occupancy numbers** anywhere: a fabricated "1.4× fuller bus" would
break the project's unknown-stays-unknown rule.

## 6. Limits

- Effect sizes are small and cell-level noise is large: 44 % of buckets use a single
  incident, and the median delta is 0.0. Treat the per-cell numbers as descriptive, not
  predictive, until the incident corpus grows (83 of 474 incidents touch a non-rail town).
- The corridor widening is a *route* join (towns served by affected trains), not a spatial
  distance model. Towns served by an affected train but with no bus interchange are still
  counted — that dilutes, it does not inflate, the deltas.
- Delay medians are survivor-biased (cancelled trips excluded) by design; shares are not.
- Town keys come from a fixed whitelist of 68 rail stations (53 towns); services in unlisted
  towns are never conditioned.
- Metro is one line (m1); "metro availability" cannot be generalised beyond Lausanne.

## 7. Rebuild and verify

```
cd <repo root>
~/code/venv-analytics/bin/python analytics/scripts/build_delay_estimates.py \
  --workers 8 \
  --zip ~/Downloads/ist-daten/ist-daten-v2-2026-01.zip ... (9 zips) \
  --out /tmp/delay_estimates_v2.json \
  --prop-out analytics/output/propagation_services.json
~/code/venv-analytics/bin/python -m pytest analytics -q   # 48 passed, 4 skipped
```

`--out` deliberately points away from `backend/src/main/resources` so a local rebuild cannot
change the shipped artifact on this branch.

## 8. Should the backend wire it before the freeze?

No. The measured median effect is 0.0 min, so a per-leg `propagationDelayMinutes` field would
mostly add `+0.0` to the wire while costing a contract edit, a model change, DTO changes and
tests in the last hour before judging. Better: keep it as a **demo artefact** (this document
plus the JSON) and, if it ever ships, do it after the freeze as an additive
`propagation{dep, p90, samples, basis}` block populated only when a live incident touches the
leg — the same ladder pattern as `incidentExtraMinutes`.
