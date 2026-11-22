#!/usr/bin/env python3
"""Build the per-bucket historical delay artifact (delay_estimates_v2.json) from Ist-Daten v2.

Streams the monthly ZIPs (no extraction, no pandas) and aggregates, per the frozen
parameter table in docs/PREDICTIVE_DELAYS_PLAN.md §2b:

  lines[]    (serviceLabel, station, weekdayClass, hourBucket, dep{}, arr{})
  stations[] (station, weekdayClass, hourBucket, dep{}, arr{})

Emitted weekdayClass values: MON_FRI, SAT, SUN and the pooled ANY.
Emitted bucketType values: HOUR (hourBucket 0-23) and BLOCK4 (hourBucket = block start:
0, 4, 8, 12, 16, 20). Only buckets with >= MIN_SAMPLES real measurements are emitted;
thin weekday rows still reach the ladder through their pooled ANY / ANY+BLOCK4 rows.

Delay = prognosis - scheduled, signed, computed in SECONDS (the REAL prognosis carries
seconds) and emitted as MINUTES rounded to 1 decimal place.

Usage:
  ~/code/venv-analytics/bin/python analytics/scripts/build_delay_estimates.py \
      [--zip PATH ...] [--out PATH] [--limit-days N] [--workers N]

--workers N shards the daily CSVs over N processes (they are independent; the parent merges
the per-day histograms). Default 1 = single process (~7.5 s per 2.6M-row day on this PC).
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import multiprocessing as mp
import os
import sys
import time
import zipfile
from collections import Counter
from datetime import date, datetime, timezone

MIN_SAMPLES = 10
MAX_ABS_DELAY = 180
WEEKDAY_CLASSES = ("MON_FRI", "SAT", "SUN")
BLOCK_STARTS = (0, 4, 8, 12, 16, 20)
HOUR = "HOUR"
BLOCK4 = "BLOCK4"

DEFAULT_ZIPS = [
    os.path.expanduser("~/Downloads/ist-daten/ist-daten-v2-2026-09.zip"),
    os.path.expanduser("~/Downloads/ist-daten/ist-daten-v2-2026-10.zip"),
]
DEFAULT_OUT = os.path.join(os.path.dirname(__file__), "..", "..",
                           "backend", "src", "main", "resources", "realtime", "delay_estimates_v2.json")

# Major Swiss hubs + the demo corridor (Lausanne, Genève, Renens VD, Morges, Allaman,
# Zürich Stadelhofen, Stettbach). Ist-Daten HALTESTELLEN_NAME is a clean proper name,
# so exact-name matching is safe; the backend folds accents/suffixes via stationForms.
STATIONS = [
    "Zürich HB", "Zürich Hardbrücke", "Zürich Oerlikon", "Zürich Stadelhofen", "Zürich Altstetten",
    "Zürich Enge", "Zürich Flughafen", "Zürich Wiedikon", "Zürich Wollishofen", "Stettbach",
    "Bern", "Bern Wankdorf", "Biel/Bienne", "Fribourg/Freiburg", "Solothurn",
    "Basel SBB", "Basel Bad Bf", "Olten", "Aarau", "Baden", "Zofingen", "Liestal",
    "Luzern", "Zug", "Arth-Goldau", "Schwyz",
    "Winterthur", "Frauenfeld", "Wil SG", "St. Gallen", "St. Gallen St. Fiden",
    "Rorschach", "Romanshorn", "Kreuzlingen", "Schaffhausen", "Uster", "Wetzikon ZH", "Rapperswil SG", "Glarus",
    "Chur", "Brig", "Visp", "Spiez", "Thun", "Interlaken Ost", "Interlaken West",
    "Bellinzona", "Lugano", "Locarno", "Faido", "Sion", "Martigny", "Brig Bahnhofplatz",
    "Lausanne", "Renens VD", "Morges", "Allaman", "Vevey", "Montreux", "Nyon",
    "Genève", "Genève-Aéroport", "Genève-Eaux-Vives", "Yverdon-les-Bains", "Neuchâtel", "Delémont",
    "Bulle", "Montbovon",
]
WHITELIST = frozenset(STATIONS)

FLAG_TRUE = "true"


def _norm_label(s: str) -> str:
    """LINIEN_TEXT / OJP serviceLabel -> one comparison key (matches backend normalizeRiskKey)."""
    return "".join(ch for ch in s.upper() if ch.isalnum())


def _parse_dt(s: str):
    """'01.09.2026 06:30[:51]' -> (days since 2020-01-01, seconds of day); hours >=24 stay valid."""
    s = s.strip()
    if len(s) < 16:
        return None
    try:
        d = date(int(s[6:10]), int(s[3:5]), int(s[0:2]))
        h = int(s[11:13])
        m = int(s[14:16])
        sec = int(s[17:19]) if len(s) >= 19 and s[16] == ":" and s[17:19].isdigit() else 0
    except ValueError:
        return None
    return ((d - date(2020, 1, 1)).days, h * 3600 + m * 60 + sec)


def _weekday_class(op_day: date) -> str:
    wd = op_day.weekday()  # Mon=0
    return "SAT" if wd == 5 else "SUN" if wd == 6 else "MON_FRI"


def _percentile(sorted_items, q: float):
    """Nearest-rank percentile from [(delay, count)] sorted by delay."""
    total = sum(c for _, c in sorted_items)
    if total == 0:
        return None
    target = max(1, min(total, int(round(q * total + 0.5))))
    upto = 0
    for delay, count in sorted_items:
        upto += count
        if upto >= target:
            return float(delay)
    return float(sorted_items[-1][0])


def _stats(counter: Counter):
    n = sum(counter.values())
    if n == 0:
        return {"median": None, "p90": None, "shareLate": None, "samples": 0}
    items = sorted(counter.items())
    late = sum(c for d, c in items if d >= 180)  # seconds: 3 minutes
    return {
        "median": round(_percentile(items, 0.5) / 60.0, 1),
        "p90": round(_percentile(items, 0.9) / 60.0, 1),
        "shareLate": round(late / n, 3),
        "samples": n,
    }


class Agg:
    """Sparse (dep, arr) minute-histograms per bucket key."""

    def __init__(self):
        self.dep = {}
        self.arr = {}

    def add(self, table, key, delay):
        d = (self.dep if table == 0 else self.arr).setdefault(key, Counter())
        d[delay] += 1


def rows_from_day(zf: zipfile.ZipFile, name: str, agg: Agg, state: dict):
    with zf.open(name) as raw:
        reader = csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline=""), delimiter=";")
        for row in reader:
            state["rows_read"] += 1
            if row["PRODUKT_ID"].upper() != "ZUG":
                continue
            if row["ZUSATZFAHRT_TF"].lower() == FLAG_TRUE or row["FAELLT_AUS_TF"].lower() == FLAG_TRUE \
                    or row["DURCHFAHRT_TF"].lower() == FLAG_TRUE:
                continue
            station = row["HALTESTELLEN_NAME"].strip()
            if station not in WHITELIST:
                continue
            state["whitelist_rows"] += 1
            try:
                op_day = datetime.strptime(row["BETRIEBSTAG"].strip(), "%d.%m.%Y").date()
            except ValueError:
                continue
            state["trip_keys"].add((row["BETRIEBSTAG"], row["FAHRT_BEZEICHNER"]))
            wd = _weekday_class(op_day)
            label = _norm_label(row["LINIEN_TEXT"])
            if label:
                state["labels"].add(label)

            # departure measurement
            if row["AB_PROGNOSE_STATUS"].strip().upper() == "REAL":
                sch = _parse_dt(row["ABFAHRTSZEIT"])
                prog = _parse_dt(row["AB_PROGNOSE"])
                if sch and prog:
                    dm = (prog[0] - sch[0]) * 86400 + (prog[1] - sch[1])
                    if abs(dm) <= MAX_ABS_DELAY * 60:
                        hour = (sch[1] // 3600) % 24
                        _add(agg, 0, label, station, wd, hour, dm)
                        state["dep_samples"] += 1

            # arrival measurement
            if row["AN_PROGNOSE_STATUS"].strip().upper() == "REAL":
                sch = _parse_dt(row["ANKUNFTSZEIT"])
                prog = _parse_dt(row["AN_PROGNOSE"])
                if sch and prog:
                    dm = (prog[0] - sch[0]) * 86400 + (prog[1] - sch[1])
                    if abs(dm) <= MAX_ABS_DELAY * 60:
                        hour = (sch[1] // 3600) % 24
                        _add(agg, 1, label, station, wd, hour, dm)
                        state["arr_samples"] += 1


def _day_task(task):
    """Worker entry point: one daily CSV -> (dep histograms, arr histograms, counters)."""
    zip_path, name = task
    agg = Agg()
    state = {"rows_read": 0, "whitelist_rows": 0, "dep_samples": 0, "arr_samples": 0,
             "trip_keys": set(), "labels": set()}
    with zipfile.ZipFile(zip_path) as zf:
        rows_from_day(zf, name, agg, state)
    return name, agg.dep, agg.arr, state


def _merge(agg: Agg, name: str, dep: dict, arr: dict, state: dict, total: dict):
    for key, counter in dep.items():
        agg.dep.setdefault(key, Counter()).update(counter)
    for key, counter in arr.items():
        agg.arr.setdefault(key, Counter()).update(counter)
    for k in ("rows_read", "whitelist_rows", "dep_samples", "arr_samples"):
        total[k] += state[k]
    total["trip_keys"] |= state["trip_keys"]
    total["labels"] |= state["labels"]


def _add(agg: Agg, table: int, label: str, station: str, wd: str, hour: int, delay: int):
    """One measurement feeds the base families plus the ANY-day / 4h-block roll-ups."""
    block = (hour // 4) * 4
    # line + station (ladder 1-3)
    if label:
        agg.add(table, ("LH", label, station, wd, hour), delay)
        agg.add(table, ("LH", label, station, "ANY", hour), delay)
        agg.add(table, ("LB", label, station, "ANY", block), delay)
        # line pooled over stations (ladder 4-5)
        agg.add(table, ("LPH", label, wd, hour), delay)
        agg.add(table, ("LPB", label, "ANY", block), delay)
    # station only (ladder 6-8)
    agg.add(table, ("SH", station, wd, hour), delay)
    agg.add(table, ("SH", station, "ANY", hour), delay)
    agg.add(table, ("SB", station, "ANY", block), delay)


def emit(agg: Agg):
    """Turn histograms into artifact rows; keep only buckets with enough samples."""
    lines, stations = {}, {}
    for table in (0, 1):
        hist = agg.dep if table == 0 else agg.arr
        side = "dep" if table == 0 else "arr"
        for key, counter in hist.items():
            kind = key[0]
            bucket_type = BLOCK4 if kind.endswith("B") else HOUR
            if kind.startswith("L"):
                if len(key) == 5:  # line + station
                    _, label, station, wd, slot = key
                    row = lines.setdefault((label, station, wd, slot, bucket_type),
                                           {"serviceLabel": label, "station": station, "weekdayClass": wd,
                                            "hourBucket": slot, "bucketType": bucket_type,
                                            "dep": _EMPTY(), "arr": _EMPTY()})
                else:  # line pooled over stations
                    _, label, wd, slot = key
                    row = lines.setdefault((label, None, wd, slot, bucket_type),
                                           {"serviceLabel": label, "station": None, "weekdayClass": wd,
                                            "hourBucket": slot, "bucketType": bucket_type,
                                            "dep": _EMPTY(), "arr": _EMPTY()})
            else:
                _, station, wd, slot = key
                row = stations.setdefault((station, wd, slot, bucket_type),
                                          {"station": station, "weekdayClass": wd,
                                           "hourBucket": slot, "bucketType": bucket_type,
                                           "dep": _EMPTY(), "arr": _EMPTY()})
            row[side] = _stats(counter)
    # threshold applies per side; drop sides that are too thin, drop rows with no side left
    out_lines, out_stations = [], []
    for row in lines.values():
        for side in ("dep", "arr"):
            if row[side]["samples"] < MIN_SAMPLES:
                row[side] = _EMPTY()
        if row["dep"]["samples"] or row["arr"]["samples"]:
            out_lines.append(row)
    for row in stations.values():
        for side in ("dep", "arr"):
            if row[side]["samples"] < MIN_SAMPLES:
                row[side] = _EMPTY()
        if row["dep"]["samples"] or row["arr"]["samples"]:
            out_stations.append(row)
    order = lambda r: (r.get("serviceLabel") or "", r.get("station") or "", r["weekdayClass"], r["bucketType"], r["hourBucket"])
    out_lines.sort(key=order)
    out_stations.sort(key=lambda r: (r["station"], r["weekdayClass"], r["bucketType"], r["hourBucket"]))
    return out_lines, out_stations


def _EMPTY():
    return {"median": None, "p90": None, "shareLate": None, "samples": 0}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", action="append", default=None, help="Ist-Daten v2 monthly ZIP (repeatable)")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--limit-days", type=int, default=0, help="debug: only the first N daily files (per ZIP)")
    ap.add_argument("--workers", type=int, default=1, help="parallel day-file processes (default 1)")
    args = ap.parse_args(argv)
    zips = args.zip or DEFAULT_ZIPS

    state = {"rows_read": 0, "whitelist_rows": 0, "dep_samples": 0, "arr_samples": 0,
             "trip_keys": set(), "labels": set()}
    agg = Agg()
    tasks = []
    for path in zips:
        if not os.path.exists(path):
            print(f"missing zip: {path}", file=sys.stderr)
            return 2
        with zipfile.ZipFile(path) as zf:
            names = sorted(n for n in zf.namelist() if n.lower().endswith(".csv"))
            if args.limit_days:
                names = names[: args.limit_days]
            tasks += [(path, name) for name in names]
    days = []
    t0 = time.time()
    workers = max(1, args.workers)
    if workers == 1:
        for path, name in tasks:
            day_start = time.time()
            _, dep, arr, st = _day_task((path, name))
            _merge(agg, name, dep, arr, st, state)
            days.append(name)
            print(f"{name}: {st['rows_read']:>10,} rows read, {time.time() - day_start:.1f}s", flush=True)
    else:
        with mp.Pool(processes=workers) as pool:
            for name, dep, arr, st in pool.imap_unordered(_day_task, tasks):
                _merge(agg, name, dep, arr, st, state)
                days.append(name)
                print(f"{name}: {st['rows_read']:>10,} rows read, total {state['rows_read']:,} "
                      f"in {time.time() - t0:.0f}s", flush=True)
    days.sort()

    lines, stations = emit(agg)
    if not lines and not stations:
        print("no buckets emitted", file=sys.stderr)
        return 3

    distinct_labels = len(state["labels"])
    line_station_pairs = {(r.get("serviceLabel"), r.get("station")) for r in lines if r.get("serviceLabel")}
    matched_pairs = {(l, s) for (l, s) in line_station_pairs if l and s}
    artifact = {
        "modelVersion": "ist-v2-" + days[0][:10].replace("-", "") + "-" + days[-1][:10].replace("-", ""),
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "window": {"from": days[0][:10], "to": days[-1][:10], "days": len(days),
                   "trips": len(state["trip_keys"]),
                   "rowsRead": state["rows_read"], "whitelistRows": state["whitelist_rows"],
                   "depSamples": state["dep_samples"], "arrSamples": state["arr_samples"]},
        "joinReport": {"linesMatched": distinct_labels,
                       "lineMatchRate": round(len(matched_pairs) / len(line_station_pairs), 3) if line_station_pairs else 0.0,
                       "stationsMatched": len({r["station"] for r in lines if r.get("station")} | {r["station"] for r in stations})},
        "source": "ist-daten-v2 (opentransportdata.swiss), status REAL only, PRODUKT_ID Zug only",
        "minSamples": MIN_SAMPLES,
        "lines": lines,
        "stations": stations,
    }
    out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(artifact, fh, ensure_ascii=False, separators=(",", ":"))

    size = os.path.getsize(out)
    print("\n--- build report ---")
    print(f"days: {len(days)}  ({days[0][:10]} .. {days[-1][:10]})")
    print(f"rows read: {state['rows_read']:,}   whitelist train rows: {state['whitelist_rows']:,}")
    print(f"measurements: dep {state['dep_samples']:,}  arr {state['arr_samples']:,}  trips {len(state['trip_keys']):,}")
    print(f"buckets emitted: lines {len(lines):,}  stations {len(stations):,}")
    print(f"artifact: {out}  {size / 1e6:.2f} MB  in {time.time() - t0:.0f}s")
    top_lines = sorted(((r.get("serviceLabel") or "", r.get("station") or "", r["weekdayClass"], r["hourBucket"],
                         r["dep"]["samples"] or r["arr"]["samples"]) for r in lines),
                       key=lambda x: -(x[4] or 0))[:8]
    print("top line coverage:")
    for l, s, wd, h, n in top_lines:
        print(f"  {l:<6} {s:<22} {wd:<7} h{h:02d}  {n} samples")
    top_st = sorted(((r["station"], r["weekdayClass"], r["hourBucket"], r["dep"]["samples"] or r["arr"]["samples"])
                     for r in stations), key=lambda x: -(x[3] or 0))[:8]
    print("top station coverage:")
    for s, wd, h, n in top_st:
        print(f"  {s:<22} {wd:<7} h{h:02d}  {n} samples")
    print(f"joinReport: {artifact['joinReport']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
