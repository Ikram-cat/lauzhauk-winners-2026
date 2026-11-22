#!/usr/bin/env python3
"""Build the per-bucket historical delay artifact (delay_estimates_v2.json) from Ist-Daten v2.

Streams the monthly ZIPs (no extraction, no pandas for the base pass) and aggregates, per the frozen
parameter table in docs/PREDICTIVE_DELAYS_PLAN.md §2b:

  lines[]            (serviceLabel, station, weekdayClass, hourBucket, dep{}, arr{})
  stations[]         (station, weekdayClass, hourBucket, dep{}, arr{})
  linesIncident[]    (serviceLabel, ANY, 4h block, dep{}, arr{}, risk shares)  [Phase 2]
  stationsIncident[] (station, ANY, 4h block, dep{}, arr{}, risk shares)       [Phase 2]

Emitted weekdayClass values: MON_FRI, SAT, SUN and the pooled ANY.
Emitted bucketType values: HOUR (hourBucket 0-23) and BLOCK4 (hourBucket = block start:
0, 4, 8, 12, 16, 20). Only buckets with >= MIN_SAMPLES real measurements are emitted;
thin weekday rows still reach the ladder through their pooled ANY / ANY+BLOCK4 rows.

Delay = prognosis - scheduled, signed, computed in SECONDS (the REAL prognosis carries
seconds) and emitted as MINUTES rounded to 1 decimal place.

Phase 2 (docs/INCIDENT_DELAYS_PLAN.md): every train row whose trip route shares >= 2 folded
stations with a reconstructed incident corridor AND whose scheduled time falls inside that
incident's [start, end] feeds the parallel *Incident tables (ANY weekday, 4h block only) and
the cancellation/suppression counters. Incident windows are reconstructed from the liveticker
audit trail by the existing analytics.duration_model / analytics.corridors parsers.

Usage:
  ~/code/venv-analytics/bin/python analytics/scripts/build_delay_estimates.py \
      [--zip PATH ...] [--out PATH] [--limit-days N] [--workers N] [--incidents-dir PATH]

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
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
from analytics.corridors import fold  # noqa: E402  (single source of truth for station folding)

MIN_SAMPLES = 10
MAX_ABS_DELAY = 180
WEEKDAY_CLASSES = ("MON_FRI", "SAT", "SUN")
NONRAIL_PRODUCTS = frozenset({"BUS", "TRAM", "METRO"})
BLOCK_STARTS = (0, 4, 8, 12, 16, 20)
HOUR = "HOUR"
BLOCK4 = "BLOCK4"

DEFAULT_ZIPS = [
    os.path.expanduser("~/Downloads/ist-daten/ist-daten-v2-2026-09.zip"),
    os.path.expanduser("~/Downloads/ist-daten/ist-daten-v2-2026-10.zip"),
]
DEFAULT_INCIDENTS_DIR = os.path.expanduser("~/Downloads/Daten/extern-liveticker")
DEFAULT_OUT = os.path.join(os.path.dirname(__file__), "..", "..",
                           "backend", "src", "main", "resources", "realtime", "delay_estimates_v2.json")
# Phase 2b ships as a SEPARATE artifact on purpose: the backend loader for delay_estimates_v2.json
# is not touched, so the frozen wire format cannot regress.
DEFAULT_PROP_OUT = os.path.join(os.path.dirname(__file__), "..", "output", "propagation_services.json")

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

# ---------------------------------------------------------------- Phase 2b: service-name towns
#
# Ist-Daten gives rail rows a clean station name ("Renens VD") but non-rail rows a
# "Town, stop" name ("Morges, Gare"). Propagation joins the two on the TOWN prefix only —
# never on coordinates (the brief forbids it and Ist-Daten carries no usable coordinates here).


def _fold_no_sep(s: str) -> str:
    """Fold to letters+digits, uppercase, keeping word separators for prefix splitting."""
    return " ".join(s.upper().replace("-", " ").replace("/", " ").split())


def town_key(station_name: str) -> str:
    """Rail station / bus stop name -> the town key both sides share ('Renens VD'->'RENENS')."""
    s = station_name.split(",")[0].strip()
    words = _fold_no_sep(s).split()
    if not words:
        return ""
    if len(words) > 1 and words[0] == "ST" and words[1] == "GALLEN":
        return "STGALLEN"
    return words[0]


WHITELIST_TOWNS = frozenset(town_key(s) for s in STATIONS) - {""}


def incident_town(folded_station: str) -> str | None:
    """Incident corridor stations arrive already folded ('zürichhb', 'renensvd'); longest
    whitelisted town prefix wins. Unknown towns return None (not propagated)."""
    s = folded_station.upper()
    best = None
    for t in WHITELIST_TOWNS:
        if s.startswith(t) and (best is None or len(t) > len(best)):
            best = t
    return best

FLAG_TRUE = "true"
ZURICH = ZoneInfo("Europe/Zurich")
_EPOCH_DAY0 = datetime(2020, 1, 1)


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


def _epoch_utc(days: int, secs: int) -> float:
    """Ist-Daten wall-clock (Europe/Zurich) -> UTC epoch seconds, so it compares with incident windows."""
    dt = _EPOCH_DAY0 + timedelta(days=days, seconds=secs)
    return dt.replace(tzinfo=ZURICH).timestamp()


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
    """Sparse (dep, arr) minute-histograms per bucket key + the Phase-2 conditioned tables."""

    def __init__(self):
        self.dep = {}
        self.arr = {}
        self.cdep = {}
        self.carr = {}
        # key -> {"rows": int, "cancelled": int, "suppressed": int, "incidents": set}
        self.cmeta = {}
        # Phase 2b: (line, town, product, block) histograms; conditioned vs unconditional
        self.pdep = {}
        self.updep = {}
        self.pmeta = {}

    def add(self, table, key, delay):
        d = (self.dep if table == 0 else self.arr).setdefault(key, Counter())
        d[delay] += 1

    def add_cond(self, table, key, delay, incident_id):
        d = (self.cdep if table == 0 else self.carr).setdefault(key, Counter())
        d[delay] += 1
        self.cmeta.setdefault(key, {"rows": 0, "cancelled": 0, "suppressed": 0, "incidents": set()})["incidents"].add(incident_id)

    def risk(self, key, cancelled: bool, suppressed: bool):
        m = self.cmeta.setdefault(key, {"rows": 0, "cancelled": 0, "suppressed": 0, "incidents": set()})
        m["rows"] += 1
        if cancelled:
            m["cancelled"] += 1
        if suppressed:
            m["suppressed"] += 1

    def add_prop(self, key, delay, incident_id=None):
        if delay is not None:
            self.pdep.setdefault(key, Counter())[delay] += 1
        if incident_id is not None:
            self.pmeta.setdefault(key, {"rows": 0, "cancelled": 0, "suppressed": 0,
                                        "incidents": set()})["incidents"].add(incident_id)

    def add_uncond(self, key, delay):
        if delay is not None:
            self.updep.setdefault(key, Counter())[delay] += 1

    def prop_risk(self, key, cancelled: bool, suppressed: bool):
        m = self.pmeta.setdefault(key, {"rows": 0, "cancelled": 0, "suppressed": 0, "incidents": set()})
        m["rows"] += 1
        if cancelled:
            m["cancelled"] += 1
        if suppressed:
            m["suppressed"] += 1


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


# ---------------------------------------------------------------- Phase 2: incident conditioning

def load_incident_windows(data_dir: str) -> list[dict]:
    """Reconstruct incident windows from the liveticker audit trail (existing analytics parser).

    duration_from is empty for 519/523 rows, so the window is start = first created_at_source and
    end = first ENDED updated_at_source, exactly as analytics.duration_model does for the duration
    model; the corridor station list comes from analytics.corridors.parse_corridor.
    """
    from analytics.corridors import add_corridors, build_station_table
    from analytics.duration_model import add_start_end, load_versions

    v = add_start_end(load_versions(data_dir))
    inc = v.groupby("id").first().reset_index()
    canon, _ = build_station_table(list(inc.description_en.fillna("")) + list(inc.description_de.fillna("")))
    inc = inc.rename(columns={"description_en": "desc_en", "description_de": "desc_de"})
    inc = add_corridors(inc, canon)
    out = []
    for r in inc.itertuples():
        stops = r.stops
        if not stops:
            continue
        out.append({
            "id": r.id,
            "start": r.start.timestamp(),
            "end": r.end.timestamp(),
            "stations": frozenset(fold(s) for s in stops if s),
        })
    return out


def collect_routes(reader_factory) -> dict:
    """Pass 1: (BETRIEBSTAG, FAHRT_BEZEICHNER) -> set of folded stop names (all ZUG rows of the day)."""
    routes: dict = {}
    reader = reader_factory()
    for row in reader:
        if row["PRODUKT_ID"].upper() != "ZUG":
            continue
        station = row["HALTESTELLEN_NAME"].strip()
        if not station:
            continue
        key = (row["BETRIEBSTAG"], row["FAHRT_BEZEICHNER"])
        route = routes.get(key)
        if route is None:
            route = routes[key] = set()
        route.add(fold(station))
    return routes


def trip_candidates(routes: dict, incidents: list[dict]) -> dict:
    """Trip -> incidents whose corridor shares >= 2 stations with the trip route, most-shared first."""
    index: dict = {}
    for i, inc in enumerate(incidents):
        for st in inc["stations"]:
            index.setdefault(st, []).append(i)
    out = {}
    for key, route in routes.items():
        hits = Counter()
        for st in route:
            for i in index.get(st, ()):
                hits[i] += 1
        cands = [(incidents[i]["id"], incidents[i]["start"], incidents[i]["end"], n)
                 for i, n in hits.items() if n >= 2]
        if cands:
            cands.sort(key=lambda c: (-c[3], c[1]))
            out[key] = cands
    return out


def _matching_incident(cands, dep_epoch: float | None, arr_epoch: float | None) -> str | None:
    if not cands:
        return None
    for iid, start, end, _ in cands:
        if dep_epoch is not None and start <= dep_epoch <= end:
            return iid
        if arr_epoch is not None and start <= arr_epoch <= end:
            return iid
    return None


def reader_for(zf: zipfile.ZipFile, name: str):
    """Fresh DictReader over one daily CSV (called once per pass)."""
    raw = zf.open(name)
    return csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline=""), delimiter=";")


# ---------------------------------------------------------------- Phase 2b: propagation

def build_prop_index(incidents: list[dict]):
    """(town, ISO day) -> [(incident id, start, end)], the towns involved, and each incident's days.

    A non-rail row propagates when it serves a TOWN on an incident corridor inside the
    incident window. Windows are indexed over their calendar day +/-1 so a trip that
    departs before midnight and is caught mid-incident still matches.
    """
    windows: dict = {}
    towns: set = set()
    inc_days: dict = {}
    for inc in incidents:
        inc_towns = {t for t in (incident_town(st) for st in inc["stations"]) if t}
        if not inc_towns:
            continue
        towns |= inc_towns
        d0 = datetime.fromtimestamp(inc["start"], ZURICH).date() - timedelta(days=1)
        d1 = datetime.fromtimestamp(inc["end"], ZURICH).date() + timedelta(days=1)
        days = []
        day = d0
        while day <= d1:
            days.append(day.isoformat())
            day += timedelta(days=1)
        inc_days[inc["id"]] = days
        for d in days:
            for t in inc_towns:
                windows.setdefault((t, d), []).append((inc["id"], inc["start"], inc["end"]))
    return windows, towns, inc_days


def augment_prop_index(window_idx, towns: set, inc_days: dict, routes: dict, cands_of: dict) -> set:
    """Widen the corridor to the towns actually traversed by the affected rail trips.

    Incident descriptions only yield the two endpoint stations (e.g. "Yverdon - Lausanne"),
    while the affected trains run through Morges, Renens, ... in between. A town therefore
    counts as "on the corridor" when a train whose route intersects the corridor at >= 2
    stations calls there during the window. This is the join that makes the 701-at-Morges
    question answerable at all; it is measured, not assumed.
    """
    extra: set = set()
    for trip, cands in cands_of.items():
        route = routes.get(trip)
        if not route:
            continue
        route_towns = {t for t in (incident_town(s) for s in route) if t}
        if not route_towns:
            continue
        for iid, start, end, _ in cands:
            for day in inc_days.get(iid, ()):
                for t in route_towns:
                    lst = window_idx.setdefault((t, day), [])
                    if not any(c[0] == iid for c in lst):
                        lst.append((iid, start, end))
                    extra.add(t)
    return extra


def _prop_row(row, agg: Agg, prop_ctx, state: dict):
    """One non-rail row (Bus/Tram/Metro): unconditional baseline + incident-window conditioning."""
    window_idx, prop_towns = prop_ctx
    product = row["PRODUKT_ID"].strip().upper()
    if product not in NONRAIL_PRODUCTS:
        return
    station = row["HALTESTELLEN_NAME"].strip()
    if not station:
        return
    town = town_key(station)
    if town not in prop_towns:
        return
    try:
        op_day = datetime.strptime(row["BETRIEBSTAG"].strip(), "%d.%m.%Y").date()
    except ValueError:
        return
    label = _norm_label(row["LINIEN_TEXT"])
    if not label:
        return
    dep_dt = _parse_dt(row["ABFAHRTSZEIT"])
    arr_dt = _parse_dt(row["ANKUNFTSZEIT"])
    hour = (dep_dt[1] // 3600) % 24 if dep_dt else ((arr_dt[1] // 3600) % 24 if arr_dt else None)
    if hour is None:
        return
    block = (hour // 4) * 4
    key = (label, town, product, block)
    zusatz = row["ZUSATZFAHRT_TF"].lower() == FLAG_TRUE
    faellt = row["FAELLT_AUS_TF"].lower() == FLAG_TRUE
    durchfahrt = row["DURCHFAHRT_TF"].lower() == FLAG_TRUE

    delay = None
    if not zusatz and row["AB_PROGNOSE_STATUS"].strip().upper() == "REAL":
        prog = _parse_dt(row["AB_PROGNOSE"])
        if dep_dt and prog:
            dm = (prog[0] - dep_dt[0]) * 86400 + (prog[1] - dep_dt[1])
            if abs(dm) <= MAX_ABS_DELAY * 60:
                delay = dm
    state["prop_rows"] += 1
    if delay is not None:
        state["prop_samples"] += 1
        agg.add_uncond(key, delay)

    incs = window_idx.get((town, op_day.isoformat()))
    if not incs:
        return
    dep_ep = _epoch_utc(*dep_dt) if dep_dt else None
    arr_ep = _epoch_utc(*arr_dt) if arr_dt else None
    iid = None
    for cid, start, end in incs:
        if (dep_ep is not None and start <= dep_ep <= end) or (arr_ep is not None and start <= arr_ep <= end):
            iid = cid
            break
    if iid is None:
        return
    state["prop_cond_rows"] += 1
    agg.prop_risk(key, faellt, durchfahrt)
    if faellt or durchfahrt or zusatz:
        agg.add_prop(key, None, iid)
    else:
        agg.add_prop(key, delay, iid)


def process_day(reader_factory, agg: Agg, state: dict, incidents: list[dict] | None = None):
    """One daily CSV: base Phase-1 histograms plus the Phase-2 and 2b conditioned tables.

    reader_factory() returns a fresh DictReader; it is called twice (routes, then measurements).
    """
    prop_ctx = None
    cands_of = {}
    if incidents:
        window_idx, prop_towns, inc_days = build_prop_index(incidents)
        routes = collect_routes(reader_factory)
        cands_of = trip_candidates(routes, incidents)
        prop_towns = prop_towns | augment_prop_index(window_idx, prop_towns, inc_days, routes, cands_of)
        prop_ctx = (window_idx, prop_towns)
    _consume(reader_factory(), agg, state, cands_of, prop_ctx)


def rows_from_day(zf: zipfile.ZipFile, name: str, agg: Agg, state: dict, incidents=None):
    process_day(lambda: reader_for(zf, name), agg, state, incidents)


def _consume(reader, agg: Agg, state: dict, cands_of: dict, prop_ctx=None):
    for row in reader:
        state["rows_read"] += 1
        if row["PRODUKT_ID"].strip().upper() != "ZUG":
            if prop_ctx is not None:
                _prop_row(row, agg, prop_ctx, state)
            continue
        zusatz = row["ZUSATZFAHRT_TF"].lower() == FLAG_TRUE
        faellt = row["FAELLT_AUS_TF"].lower() == FLAG_TRUE
        durchfahrt = row["DURCHFAHRT_TF"].lower() == FLAG_TRUE
        dropped = zusatz or faellt or durchfahrt
        station = row["HALTESTELLEN_NAME"].strip()
        if station not in WHITELIST:
            continue
        if not dropped:
            state["whitelist_rows"] += 1
        try:
            op_day = datetime.strptime(row["BETRIEBSTAG"].strip(), "%d.%m.%Y").date()
        except ValueError:
            continue

        wd = _weekday_class(op_day)
        label = _norm_label(row["LINIEN_TEXT"])
        trip = (row["BETRIEBSTAG"], row["FAHRT_BEZEICHNER"])

        dep_dt = _parse_dt(row["ABFAHRTSZEIT"])
        arr_dt = _parse_dt(row["ANKUNFTSZEIT"])
        iid = None
        if cands_of:
            cands = cands_of.get(trip)
            if cands:
                iid = _matching_incident(
                    cands,
                    _epoch_utc(*dep_dt) if dep_dt else None,
                    _epoch_utc(*arr_dt) if arr_dt else None)
                if iid is not None:
                    hour = (dep_dt[1] // 3600) % 24 if dep_dt else (arr_dt[1] // 3600) % 24
                    block = (hour // 4) * 4
                    for k in [("SI", station, block)] + ([("LI", label, block)] if label else []):
                        agg.risk(k, faellt, durchfahrt and bool(dep_dt or arr_dt))

        if dropped:
            continue
        state["trip_keys"].add(trip)
        if label:
            state["labels"].add(label)

        # departure measurement
        if row["AB_PROGNOSE_STATUS"].strip().upper() == "REAL":
            prog = _parse_dt(row["AB_PROGNOSE"])
            if dep_dt and prog:
                dm = (prog[0] - dep_dt[0]) * 86400 + (prog[1] - dep_dt[1])
                if abs(dm) <= MAX_ABS_DELAY * 60:
                    hour = (dep_dt[1] // 3600) % 24
                    _add(agg, 0, label, station, wd, hour, dm)
                    state["dep_samples"] += 1
                    if iid is not None:
                        block = (hour // 4) * 4
                        if label:
                            agg.add_cond(0, ("LI", label, block), dm, iid)
                        agg.add_cond(0, ("SI", station, block), dm, iid)

        # arrival measurement
        if row["AN_PROGNOSE_STATUS"].strip().upper() == "REAL":
            prog = _parse_dt(row["AN_PROGNOSE"])
            if arr_dt and prog:
                dm = (prog[0] - arr_dt[0]) * 86400 + (prog[1] - arr_dt[1])
                if abs(dm) <= MAX_ABS_DELAY * 60:
                    hour = (arr_dt[1] // 3600) % 24
                    _add(agg, 1, label, station, wd, hour, dm)
                    state["arr_samples"] += 1
                    if iid is not None:
                        block = (hour // 4) * 4
                        if label:
                            agg.add_cond(1, ("LI", label, block), dm, iid)
                        agg.add_cond(1, ("SI", station, block), dm, iid)

# ---------------------------------------------------------------- aggregation / emission

def _day_task(task):
    """Worker entry point: one daily CSV -> (aggregates, counters)."""
    zip_path, name, incidents = task
    agg = Agg()
    state = {"rows_read": 0, "whitelist_rows": 0, "dep_samples": 0, "arr_samples": 0,
             "trip_keys": set(), "labels": set(),
             "prop_rows": 0, "prop_samples": 0, "prop_cond_rows": 0}
    with zipfile.ZipFile(zip_path) as zf:
        rows_from_day(zf, name, agg, state, incidents)
    return name, agg, state


def _merge(agg: Agg, name: str, day: Agg, state: dict, total: dict):
    for key, counter in day.dep.items():
        agg.dep.setdefault(key, Counter()).update(counter)
    for key, counter in day.arr.items():
        agg.arr.setdefault(key, Counter()).update(counter)
    for key, counter in day.cdep.items():
        agg.cdep.setdefault(key, Counter()).update(counter)
    for key, counter in day.carr.items():
        agg.carr.setdefault(key, Counter()).update(counter)
    for key, meta in day.cmeta.items():
        m = agg.cmeta.setdefault(key, {"rows": 0, "cancelled": 0, "suppressed": 0, "incidents": set()})
        m["rows"] += meta["rows"]
        m["cancelled"] += meta["cancelled"]
        m["suppressed"] += meta["suppressed"]
        m["incidents"] |= meta["incidents"]
    for k in ("rows_read", "whitelist_rows", "dep_samples", "arr_samples"):
        total[k] += state[k]
    total["trip_keys"] |= state["trip_keys"]
    total["labels"] |= state["labels"]
    for key, counter in day.pdep.items():
        agg.pdep.setdefault(key, Counter()).update(counter)
    for key, counter in day.updep.items():
        agg.updep.setdefault(key, Counter()).update(counter)
    for key, meta in day.pmeta.items():
        m = agg.pmeta.setdefault(key, {"rows": 0, "cancelled": 0, "suppressed": 0, "incidents": set()})
        m["rows"] += meta["rows"]
        m["cancelled"] += meta["cancelled"]
        m["suppressed"] += meta["suppressed"]
        m["incidents"] |= meta["incidents"]
    for k in ("prop_rows", "prop_samples", "prop_cond_rows"):
        total[k] = total.get(k, 0) + state.get(k, 0)


def _EMPTY():
    return {"median": None, "p90": None, "shareLate": None, "samples": 0}


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


def emit_conditioned(agg: Agg):
    """Phase-2 tables: ANY weekday, 4h blocks, plus the risk shares over ALL matching planned stops."""
    rows = {}
    for table in (0, 1):
        hist = agg.cdep if table == 0 else agg.carr
        side = "dep" if table == 0 else "arr"
        for key, counter in hist.items():
            kind, name, block = key
            ident = (kind, name, block)
            row = rows.setdefault(ident, {"kind": kind, "name": name, "hourBucket": block,
                                          "dep": _EMPTY(), "arr": _EMPTY()})
            row[side] = _stats(counter)
    for key, meta in agg.cmeta.items():
        kind, name, block = key
        row = rows.setdefault((kind, name, block), {"kind": kind, "name": name, "hourBucket": block,
                                                    "dep": _EMPTY(), "arr": _EMPTY()})
    out_lines, out_stations = [], []
    for (kind, name, block), row in rows.items():
        for side in ("dep", "arr"):
            if row[side]["samples"] < MIN_SAMPLES:
                row[side] = _EMPTY()
        if not (row["dep"]["samples"] or row["arr"]["samples"]):
            continue
        meta = agg.cmeta.get((kind, name, block), {"rows": 0, "cancelled": 0, "suppressed": 0, "incidents": set()})
        n = meta["rows"]
        out = {"weekdayClass": "ANY", "hourBucket": block, "bucketType": BLOCK4,
               "dep": row["dep"], "arr": row["arr"],
               "incidentCount": len(meta["incidents"]),
               "cancellationShare": round(meta["cancelled"] / n, 3) if n else 0.0,
               "suppressionShare": round(meta["suppressed"] / n, 3) if n else 0.0,
               "plannedStops": n}
        if kind == "LI":
            out["serviceLabel"] = name
            out["station"] = None
            out_lines.append(out)
        else:
            out["station"] = name
            out_stations.append(out)
    out_lines.sort(key=lambda r: (r["serviceLabel"], r["hourBucket"]))
    out_stations.sort(key=lambda r: (r["station"], r["hourBucket"]))
    return out_lines, out_stations


def emit_propagation(agg: Agg):
    """Phase 2b table: non-rail services (Bus/Tram/Metro) per (line, town, product, 4h block)
    conditioned on an incident touching that town, plus the unconditional median for the delta."""
    rows = {}
    for key, counter in agg.pdep.items():
        if sum(counter.values()) < MIN_SAMPLES:
            continue
        label, town, product, block = key
        meta = agg.pmeta.get(key, {"rows": 0, "cancelled": 0, "suppressed": 0, "incidents": set()})
        dep = _stats(counter)
        base = agg.updep.get(key)
        uncond = _stats(base) if base else _EMPTY()
        n = meta["rows"]
        rows[key] = {
            "serviceLine": label,
            "town": town,
            "product": product,
            "hourBucket": block,
            "bucketType": BLOCK4,
            "weekdayClass": "ANY",
            "dep": dep,
            "unconditionalMedian": uncond["median"],
            "unconditionalSamples": uncond["samples"],
            "deltaMedian": (round(dep["median"] - uncond["median"], 1)
                            if dep["median"] is not None and uncond["median"] is not None else None),
            "incidentCount": len(meta["incidents"]),
            "cancellationShare": round(meta["cancelled"] / n, 3) if n else 0.0,
            "suppressionShare": round(meta["suppressed"] / n, 3) if n else 0.0,
            "plannedStops": n,
        }
    return sorted(rows.values(), key=lambda r: (r["product"], r["town"], r["serviceLine"], r["hourBucket"]))


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", action="append", default=None, help="Ist-Daten v2 monthly ZIP (repeatable)")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--limit-days", type=int, default=0, help="debug: only the first N daily files (per ZIP)")
    ap.add_argument("--workers", type=int, default=1, help="parallel day-file processes (default 1)")
    ap.add_argument("--incidents-dir", default=DEFAULT_INCIDENTS_DIR,
                    help="extern-liveticker CSV dir for the Phase-2 incident windows (empty string disables)")
    ap.add_argument("--prop-out", default=DEFAULT_PROP_OUT,
                    help="Phase-2b non-rail propagation artifact (separate file; no artifact shape change)")
    args = ap.parse_args(argv)
    zips = args.zip or DEFAULT_ZIPS

    incidents = []
    if args.incidents_dir:
        incidents = load_incident_windows(args.incidents_dir)
        print(f"incidents reconstructed: {len(incidents)}")

    state = {"rows_read": 0, "whitelist_rows": 0, "dep_samples": 0, "arr_samples": 0,
             "trip_keys": set(), "labels": set(),
             "prop_rows": 0, "prop_samples": 0, "prop_cond_rows": 0}
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
            tasks += [(path, name, incidents) for name in names]
    days = []
    t0 = time.time()
    workers = max(1, args.workers)
    if workers == 1:
        for task in tasks:
            day_start = time.time()
            name, day_agg, st = _day_task(task)
            _merge(agg, name, day_agg, st, state)
            days.append(name)
            print(f"{name}: {st['rows_read']:>10,} rows read, {time.time() - day_start:.1f}s", flush=True)
    else:
        with mp.Pool(processes=workers) as pool:
            for name, day_agg, st in pool.imap_unordered(_day_task, tasks):
                _merge(agg, name, day_agg, st, state)
                days.append(name)
                print(f"{name}: {st['rows_read']:>10,} rows read, total {state['rows_read']:,} "
                      f"in {time.time() - t0:.0f}s", flush=True)
    days.sort()

    lines, stations = emit(agg)
    if not lines and not stations:
        print("no buckets emitted", file=sys.stderr)
        return 3
    lines_inc, stations_inc = emit_conditioned(agg)
    prop_rows = emit_propagation(agg)

    distinct_labels = len(state["labels"])
    line_station_pairs = {(r.get("serviceLabel"), r.get("station")) for r in lines if r.get("serviceLabel")}
    matched_pairs = {(l, s) for (l, s) in line_station_pairs if l and s}
    incident_ids = {i for meta in agg.cmeta.values() for i in meta["incidents"]}
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
        "linesIncident": lines_inc,
        "stationsIncident": stations_inc,
        "incidentConditioning": {
            "incidentsTotal": len(incidents),
            "incidentsUsed": len(incident_ids),
            "minSharedStations": 2,
            "lineBuckets": len(lines_inc),
            "stationBuckets": len(stations_inc),
        },
    }
    out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(artifact, fh, ensure_ascii=False, separators=(",", ":"))

    prop_artifact = {
        "modelVersion": artifact["modelVersion"],
        "generatedAt": artifact["generatedAt"],
        "source": "ist-daten-v2 (opentransportdata.swiss), status REAL only, PRODUKT_ID Bus/Tram/Metro",
        "join": "service HALTESTELLEN_NAME town prefix x incident corridor town; no coordinates",
        "unit": "minutes",
        "window": {"from": days[0][:10], "to": days[-1][:10], "days": len(days)},
        "minSamples": MIN_SAMPLES,
        "coverage": {
            "nonRailRowsSeen": state["prop_rows"],
            "nonRailMeasurements": state["prop_samples"],
            "rowsInsideIncidentWindows": state["prop_cond_rows"],
            "buckets": len(prop_rows),
            "incidentsTotal": len(incidents),
            "incidentsUsed": len({i for m in agg.pmeta.values() for i in m["incidents"]}),
        },
        "propagationServices": prop_rows,
    }
    prop_out = os.path.abspath(args.prop_out)
    os.makedirs(os.path.dirname(prop_out), exist_ok=True)
    with open(prop_out, "w", encoding="utf-8") as fh:
        json.dump(prop_artifact, fh, ensure_ascii=False, separators=(",", ":"))

    size = os.path.getsize(out)
    print("\n--- build report ---")
    print(f"days: {len(days)}  ({days[0][:10]} .. {days[-1][:10]})")
    print(f"rows read: {state['rows_read']:,}   whitelist train rows: {state['whitelist_rows']:,}")
    print(f"measurements: dep {state['dep_samples']:,}  arr {state['arr_samples']:,}  trips {len(state['trip_keys']):,}")
    print(f"buckets emitted: lines {len(lines):,}  stations {len(stations):,}")
    print(f"incident buckets: lines {len(lines_inc):,}  stations {len(stations_inc):,}"
          f"  incidents used {len(incident_ids)}/{len(incidents)}")
    print(f"artifact: {out}  {size / 1e6:.2f} MB  in {time.time() - t0:.0f}s")
    print(f"propagation: {len(prop_rows):,} buckets, {state['prop_cond_rows']:,} non-rail rows inside "
          f"incident windows / {state['prop_rows']:,} seen -> {prop_out}")
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
    worst = sorted(((r.get("serviceLabel") or r["station"], r["hourBucket"], r["dep"]["median"], r["dep"]["samples"],
                     r["incidentCount"]) for r in lines_inc + stations_inc if r["dep"]["median"] is not None),
                   key=lambda x: -(x[2] or 0))[:5]
    print("worst conditioned medians:")
    for name, h, med, n, ic in worst:
        print(f"  {name:<10} h{h:02d}  median +{med} min  n={n}  incidents={ic}")
    print(f"joinReport: {artifact['joinReport']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
