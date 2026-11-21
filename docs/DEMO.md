# Disruption demo: Renens VD – Morges, 17 September 2026

This is the run sheet for the disruption-duration demo (SBB challenge). The journey-search demo is in [DEMO_SCRIPT.md](DEMO_SCRIPT.md). Everything shown here is a **replay of a past incident, labelled `MOCK`**: no live disruption feed is used (see [analytics README, Live mode](../analytics/README.md#live-mode-not-implemented)). Predictions come from the **evaluation models**, trained only on incidents before 2026-04-06, which never saw this incident.

## Start: one command

Run from the repository root:

```sh
python analytics/scripts/run_demo.py            # port 8080, evaluation model, replayed incidents
python analytics/scripts/run_demo.py --offline  # mock provider, no network at all
python analytics/scripts/run_demo.py --rebuild  # retrain and regenerate every output first
```

The script does the following:
- **Builds the analytics outputs if any is missing:** `train`, `export`, `sources` (timelines) and `replay`, all in `analytics/output/`, which is never committed. This needs the local SBB data in `analytics/Daten/Daten/`, about 1 minute.
- **Builds and starts the backend** with `DURATION_ARTIFACT_PATH=analytics/output/duration_model.v1.eval.json` and `DISRUPTION_TIMELINES_DIR=analytics/output/timelines`. The provider is `ojp` when `OJP_API_KEY` is set in the environment or in `.env`, and `mock` otherwise.

It finds JDK 21 through `JAVA_HOME`, or `%LOCALAPPDATA%\Programs\Temurin\jdk-21*` on Windows. Ready when it prints `Backend on http://localhost:8080`. Stop it with Ctrl+C.

Python 3.14 with `analytics/requirements.txt` installed. `--model final` loads the model trained on all data instead; do not use it to replay past incidents, because it has seen them.

## Screen by screen (Renens VD – Morges, incident `2195005188970`)

The timeline is `analytics/output/timelines/timeline-2195005188970.json`. `python -m sbb_duration.sources` (from `analytics/`) prints its notified moments. The cause is a power failure (`EG_011`). With only 4 past incidents of that cause, the explanation uses all causes (`scope: ALL_REASONS`): 416 incidents, median duration 160 min, real end a median 8 min after the announcement.

All times are local. The detour cost in the timelines is a hypothetical +60 min.

| # | Time | What happens (source) | SBB announcement | Our prediction (P50 / pessimistic) | Advice (+60 min detour) | Reality (hindsight) |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 17:35 | First detection: VIA reports Renens VD – Allaman / Morges, lines IC1, IR15, IR57, IR90, IR95, RE33, R8, R9 | – | – (no public announcement yet) | – | 209 min left |
| 2 | 17:44 | SBB announces "at least until 18:30" | 46 min | 72 / 121 min | Take the detour | 200 min left |
| 3 | 18:30 | SBB pushes the end to 20:30 (notification) | 120 min | 136 / 174 min | Take the detour | 154 min left |
| 4 | 18:46 | Internal liveticker: 14 replacement buses ordered (notification) | 104 min | 115 / 167 min | Take the detour | 138 min left |
| 5 | 19:08 | Internal: buses on site at Renens VD and Morges at 19:30 | 82 min | 83 / 141 min | Take the detour | 116 min left |
| 6 | 20:02 | Advice changes (notification); VIA publishes the replacement bus | 27 min | 28 / 79 min | Detour if you cannot be late | 61 min left |
| 7 | 21:04 | Internal: "Premiers trains Lausanne – Genève": **real resumption** | 21:00 announced | prediction stops (`SERVICE_RESUMED`) | – | 0 |
| 8 | 21:05 | SBB still announces an end at 23:59, and the public feed closes automatically at 00:00 | 174 min | – | – | – |

The message: against the real resumption at 21:04, the announcement is off by **95.8 min** on average and our P50 by **83.3 min** (34 moments). The internal liveticker knew about the buses and the resumption hours before the public feed.

### Journey check

Lausanne → Genève at 18:31 is affected: it passes Lausanne (line end) and Genève (line end), and Morges (disruption point) when the provider returns intermediate stops.
- With the mock provider, every Lausanne → Genève journey is affected. No unaffected alternative exists, so `detourExtraMinutes` is null and `advice.code` is `UNKNOWN`. In that case, explain the advice with the timeline's hypothetical +60 min, or with the outlook request below.
- The backend test `JourneyImpactServiceTest` replays the same instant with a lake alternative via Évian: detour +95 min, advice `DETOUR_IF_DEADLINE`.

## Fallback requests (curl)

Use ASCII station names (`Geneve`) or `--data-binary @file.json`: Windows consoles can send `è` in a legacy encoding, and the name then no longer matches. With PowerShell, call `curl.exe` explicitly.

```sh
curl http://localhost:8080/health

# Journey impact on the Renens replay
curl -X POST http://localhost:8080/api/journeys/impact -H "Content-Type: application/json" \
  -d '{"origin":"Lausanne","destination":"Geneve","observedAt":"2026-09-17T18:31:00+02:00"}'

# Outlook for the Renens situation at 18:31, with a 60 min detour
curl -X POST http://localhost:8080/api/disruptions/outlook -H "Content-Type: application/json" \
  -d '{"disruption":{"reasonCode":"EG_011","durationType":"UNTIL_APPROX_DATE_TIME","startedAt":"2026-09-17T17:22:29+02:00","announcedEndAt":"2026-09-17T20:30:00+02:00","updatesSoFar":1,"summaryEn":"Interrupted service: Renens VD - Morges"},"observedAt":"2026-09-17T18:31:00+02:00","detourExtraMinutes":60}'
```

Without the backend, show the files directly:

```sh
cd analytics
python -m sbb_duration.sources --no-fetch   # prints the notified moments of the 10 incidents
python -m sbb_duration.replay               # 3 other replays (Granges-Marnand, Lausanne, Cossonay)
```

## If something breaks

| Symptom | Cause | Action |
| --- | --- | --- |
| `status: "UNAVAILABLE"` in every outlook | Artifact not loaded (warning at startup) | Run with `--rebuild`, or check `DURATION_ARTIFACT_PATH`. |
| `affected: false` for Lausanne → Genève | Timelines not loaded, wrong `observedAt`, or station names sent in a legacy encoding | Check the startup log for `Loaded 10 replay disruption timelines`; use `observedAt` between 17:35 and 21:04 on 2026-09-17; use `Geneve`. |
| Backend does not start | No JDK 21 | Set `JAVA_HOME` to a JDK 21. |
| OJP errors (`502`/`504`) | Network or key | Restart with `--offline` (mock provider). Replay and predictions do not need the network. |
| Renens story does not land | – | Switch to **Zürich Stadelhofen – Stettbach** (`2195005195846`, 2026-09-25, accident, cause `EG_047` with 52 past incidents). Its public end (20:30) matches the end of operations, so no "real resumption" caveat is needed. At 15:52 SBB announces 187 min, we predict 212 / 267, reality 278. At 18:54 SBB says 155, we say 138, reality 96. Mean error 76.2 min (SBB) against 63.2 (model). Journey: `{"origin":"Zürich Stadelhofen","destination":"Stettbach","observedAt":"2026-09-25T18:40:00+02:00"}`. |
| No data on the demo machine | `analytics/Daten/` is not in Git | Copy `analytics/output/` from a machine that has the data. The backend only needs `duration_model.v1.eval.json` and `timelines/`. |

Key figures for the pitch are generated in `analytics/output/pitch_numbers.md` (`python -m sbb_duration.pitch`).
