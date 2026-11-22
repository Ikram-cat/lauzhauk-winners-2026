# Disruption demo: Renens VD – Morges, 17 September 2026

This is the run sheet for the disruption-duration demo (SBB challenge). The journey-search demo is in [DEMO_SCRIPT.md](DEMO_SCRIPT.md). The demo is a **replay of a past incident, labelled `MOCK`**. The only live element is the optional `liveAlerts` step, labelled `LIVE`, which lists current alerts without any prediction (see [analytics README, Live mode](../analytics/README.md#live-mode-alerts-only-no-prediction)). Predictions come from the **evaluation models**, trained only on incidents before 2026-04-06, which never saw this incident.

## Start: one command

Run from the repository root:

```sh
python analytics/scripts/run_demo.py            # port 8080, demo journeys, evaluation model, replayed incidents
python analytics/scripts/run_demo.py --offline  # same, without any download (no network needed)
python analytics/scripts/run_demo.py --rebuild  # retrain and regenerate every output first
python analytics/scripts/run_demo.py --provider mock   # the team mock instead of the demo journeys
```

The script does the following:
- **Builds the analytics outputs if any is missing:** `train`, `export`, `sources` (timelines) and `replay`, all in `analytics/output/`, which is never committed. This needs the local SBB data in `analytics/Daten/Daten/`, about 1 minute.
- **Builds and starts the backend** with `DURATION_ARTIFACT_PATH=analytics/output/duration_model.v1.eval.json`, `DISRUPTION_TIMELINES_DIR=analytics/output/timelines` and `TRANSPORT_PROVIDER=demo`.

The `demo` provider (`backend/.../provider/DemoScenarioTransportProvider.kt`) is separate from the team mock, and every journey it returns is `MOCK`. Its times are plausible approximations relative to the requested departure, not a timetable:
- **Lausanne → Genève:**
  - IC 1, 35 min, via Morges;
  - IR 90, 51 min, via Renens VD, Morges and Allaman;
  - a lake alternative that avoids the section: m2 to Ouchy, CGN boat to Évian, Léman Express L1 to Genève, arriving 100 min after the IC 1.
- **Zürich Stadelhofen → Stettbach:** S11, 6 min, and a tram alternative (Tram 11 + Tram 7) arriving 23 min later.
- **Lausanne → Fribourg:** a tight connection (IC 1 to Fribourg, 3 min to the IR 15 to Bern) and a safe one (IR 90 to Fribourg, 30 min to the IC 5). Use it with `demoPerturbation=9`: the injected 13-minute delay on the IC 1 makes the tight connection a likely miss, so the response marks it `transferMissRisk: 1.0` (`KNOWN`), returns `TRANSFER_MISS_RISK`, and recommends the safe journey while the tight one stays in `alternatives`.
- **Any other request:** the team mock, unchanged.

`--provider ojp` uses the live timetable once a key is available. When `.env` holds `GTFS_RT_API_KEY`, the script also enables the team's GTFS-RT TripUpdates feed (startup line `gtfs-rt=on`); the replay does not depend on it, and `--offline` keeps it off. With both keys in `.env` (`GTFS_RT_API_KEY` for TripUpdates, `GTFS_SA_API_KEY` for Service Alerts), the startup line shows `gtfs-rt=on  service-alerts=on`. `POST /api/journeys/impact` then also returns `liveAlerts`: current Service Alerts that touch the journey, labelled `LIVE`, with SBB text, validity periods and stations, and `prediction: null`. No duration is predicted live. The replay does not depend on these feeds, and `--offline` turns them off.

It finds JDK 21 through `JAVA_HOME`, or `%LOCALAPPDATA%\Programs\Temurin\jdk-21*` on Windows. Ready when it prints `Backend on http://localhost:8080`. Stop it with Ctrl+C.

Python 3.14 with `analytics/requirements.txt` installed. `--model final` loads the model trained on all data instead; do not use it to replay past incidents, because it has seen them.

## Screen by screen (Renens VD – Morges, incident `2195005188970`)

The timeline is `analytics/output/timelines/timeline-2195005188970.json`. `python -m sbb_duration.sources` (from `analytics/`) prints its notified moments. The cause is a power failure (`EG_011`). With only 4 past incidents of that cause, the explanation uses all causes (`scope: ALL_REASONS`): 416 incidents, median duration 160 min, real end a median 8 min after the announcement.

All times are local. The last columns come from `POST /api/journeys/impact` for Lausanne → Genève: the planned IC 1 is affected, and the computed detour is +100 min via the lake. The notifications in the timeline file use a hypothetical +60 min detour instead.

| # | Time | What happens (source) | SBB announcement | Our prediction (P50 / pessimistic) | Advice, detour +100 min | Reality (hindsight) |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 17:35 | First detection: VIA reports Renens VD – Allaman / Morges, lines IC1, IR15, IR57, IR90, IR95, RE33, R8, R9 | – | – (no public announcement yet: show detection only) | – | 209 min left |
| 2 | 17:44 | SBB announces "at least until 18:30" | 46 min | 72 / 121 min | `DETOUR_IF_DEADLINE`: detour if you cannot be late | 200 min left |
| 3 | 18:31 | SBB pushes the end to 20:30 (notification) | 119 min | 139 / 174 min | `TAKE_DETOUR` | 153 min left |
| 4 | 18:47 | Internal liveticker: 14 replacement buses ordered (notification) | 103 min | 118 / 167 min | `TAKE_DETOUR` | 137 min left |
| 5 | 19:09 | Internal: buses on site at Renens VD and Morges at 19:30 | 81 min | 84 / 141 min | `DETOUR_IF_DEADLINE` | 115 min left |
| 6 | 20:03 | VIA publishes the replacement bus | 27 min | 28 / 79 min | `WAIT`: should be over before the detour pays off | 61 min left |
| 6b | 20:30 | SBB moves the end to 21:00 | 30 min | 25 / 70 min | `WAIT` | 34 min left |
| 7 | 21:04 | Internal: "Premiers trains Lausanne – Genève": **real resumption** | 21:00 announced | prediction stops (`SERVICE_RESUMED`) | – | 0 |
| 8 | 21:05 | SBB still announces an end at 23:59, and the public feed closes automatically at 00:00 | 174 min | – | – | – |

The message: against the real resumption at 21:04, the announcement is off by **95.8 min** on average and our P50 by **83.3 min** (34 moments). The internal liveticker knew about the buses and the resumption hours before the public feed.

### Journey check

Between 17:43 and 21:04, Lausanne → Genève is affected:
- the IC 1 and the IR 90 pass Lausanne, Morges and Genève (and Renens VD and Allaman for the IR), matched by sloid;
- the lake journey touches only Genève, so it is the unaffected alternative: `detourExtraMinutes` = 100;
- the advice is never `UNKNOWN` in this window. The backend test `DemoScenarioTransportProviderTest` checks the demo instants of both incidents.

Before 17:43 there is no public announcement, so there is no prediction and the advice is `UNKNOWN`; show the VIA detection only. From 21:04 the journey is no longer affected, because the real resumption has happened.

### Optional live step

After the replay, show that the same check works on today's network: send the journey-impact request **without** `observedAt`. `liveAlerts` lists the alerts active now on Lausanne → Genève. On 2026-11-22 01:05 UTC the backend merged 9 feed alerts into 6 entries, led by "Interrupted service: Renens VD - Allaman" (planned works, both directions, matched at Morges). The content changes over time. Say clearly that these are `LIVE` and that no duration is predicted for them (`prediction: null`, with the reason).

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
| `advice.code: "UNKNOWN"` in the journey check | Team mock instead of the demo provider (no unaffected alternative), or `observedAt` before 17:43 | Start with the default `--provider demo`; use the times in the table. |
| `affected: false` for Lausanne → Genève | Timelines not loaded, wrong `observedAt`, or station names sent in a legacy encoding | Check the startup log for `Loaded 10 replay disruption timelines`; use `observedAt` between 17:35 and 21:04 on 2026-09-17; use `Geneve`. |
| Backend does not start | No JDK 21 | Set `JAVA_HOME` to a JDK 21. |
| OJP errors (`502`/`504`) | Network or key | Restart with `--offline` (mock provider). Replay and predictions do not need the network. |
| Renens story does not land | – | Switch to **Zürich Stadelhofen – Stettbach** (`2195005195846`, 2026-09-25, accident, cause `EG_047` with 52 past incidents). Its public end (20:30) matches the end of operations, so no "real resumption" caveat is needed. At 15:53 SBB announces 187 min, we predict 212 / 267, reality 278. At 18:54 SBB says 155, we say 138, reality 96. Mean error 76.2 min (SBB) against 63.2 (model). Journey check `{"origin":"Zurich Stadelhofen","destination":"Stettbach","observedAt":"2026-09-25T18:40:00+02:00"}`: S11 affected, tram alternative +23 min, `TAKE_DETOUR` (also at 15:53 and 20:00). |
| No data on the demo machine | `analytics/Daten/` is not in Git | Copy `analytics/output/` from a machine that has the data. The backend only needs `duration_model.v1.eval.json` and `timelines/`. |

Key figures for the pitch are generated in `analytics/output/pitch_numbers.md` (`python -m sbb_duration.pitch`).
