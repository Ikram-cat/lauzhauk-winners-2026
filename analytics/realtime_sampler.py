"""Realtime delay sampler: polls OJP stop events, appends rows to a CSV, summarizes late portions (network; needs OJP_API_KEY, never logged)."""
from __future__ import annotations

import argparse
import csv
import json
import logging
import os
import statistics
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from .ojp_validate import URL, MIN_SCORE, score

HERE = Path(__file__).parent
CSV_PATH = HERE / "data" / "realtime_samples.csv"
SUMMARY_PATH = HERE / "artifacts" / "realtime_delays.json"
COLUMNS = ["sampled_at_utc", "station_name", "stop_ref", "line_ref", "service_label", "journey_ref", "operating_day",
           "timetabled", "estimated", "delay_min", "cancelled", "unplanned", "deviation", "quay", "bucket_hour_local"]
MIN_SAMPLES = 10   # portions with fewer departures are not emitted
LATE_MIN = 2       # shareLate = fraction of departures with delay > this
MAX_CANCELLATIONS = 100
ZRH = ZoneInfo("Europe/Zurich")
# SLOIDs are the UIC suffix; each is verified against the returned StopPlaceName before sampling.
STATIONS = [("Lausanne", "ch:1:sloid:1120"), ("Genève", "ch:1:sloid:1008"), ("Bern", "ch:1:sloid:7000"),
            ("Zürich HB", "ch:1:sloid:3000"), ("Olten", "ch:1:sloid:218"), ("Biel/Bienne", "ch:1:sloid:4300"),
            ("Fribourg", "ch:1:sloid:4100"), ("Vevey", "ch:1:sloid:1200"), ("Montreux", "ch:1:sloid:1300"),
            ("Yverdon-les-Bains", "ch:1:sloid:4200"), ("Neuchâtel", "ch:1:sloid:4221"), ("Renens VD", "ch:1:sloid:1118")]
log = logging.getLogger("realtime_sampler")


def _request(name: str, ref: str, n: int = 20) -> bytes:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0"><OJPRequest><siri:ServiceRequest>
<siri:RequestTimestamp>{ts}</siri:RequestTimestamp><siri:RequestorRef>PlanB-Sampler</siri:RequestorRef>
<OJPStopEventRequest><siri:RequestTimestamp>{ts}</siri:RequestTimestamp><siri:MessageIdentifier>{uuid.uuid4()}</siri:MessageIdentifier>
<Location><PlaceRef><siri:StopPointRef>{ref}</siri:StopPointRef><Name><Text>{name.replace("&", "&amp;")}</Text></Name></PlaceRef></Location>
<Params><NumberOfResults>{n}</NumberOfResults><StopEventType>departure</StopEventType><IncludePreviousCalls>false</IncludePreviousCalls>
<IncludeOnwardCalls>false</IncludeOnwardCalls><UseRealtimeData>full</UseRealtimeData></Params>
</OJPStopEventRequest></siri:ServiceRequest></OJPRequest></OJP>""".encode()


def _t(el, path):
    x = el.find(path) if el is not None else None
    return x.text.strip() if x is not None and x.text else None


def _ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")) if s else None


def delay_min(timetabled: str, estimated: str | None) -> float | None:
    if not estimated:
        return None
    return round((_ts(estimated) - _ts(timetabled)).total_seconds() / 60, 1)


def place_name(xml: bytes, ref: str) -> str | None:
    """Name the provider returned for the requested stop place (None if absent)."""
    for sp in ET.fromstring(xml).findall(".//{*}StopPlace"):
        if _t(sp, "{*}StopPlaceRef") == ref:
            return _t(sp, "{*}StopPlaceName/{*}Text")
    return None


def parse_events(xml: bytes, station: str, ref: str, sampled_at: str) -> list[dict]:
    rows = []
    for ev in ET.fromstring(xml).findall(".//{*}StopEvent"):
        call, svc = ev.find("{*}ThisCall/{*}CallAtStop"), ev.find("{*}Service")
        tt = _t(call, "{*}ServiceDeparture/{*}TimetabledTime")
        if call is None or svc is None or not tt:
            continue
        est = _t(call, "{*}ServiceDeparture/{*}EstimatedTime")
        flag = lambda e, tag: int(_t(e, f"{{*}}{tag}") == "true")
        rows.append({
            "sampled_at_utc": sampled_at, "station_name": station, "stop_ref": ref,
            "line_ref": _t(svc, "{*}LineRef"), "service_label": _t(svc, "{*}PublishedServiceName/{*}Text"),
            "journey_ref": _t(svc, "{*}JourneyRef"), "operating_day": _t(svc, "{*}OperatingDayRef"),
            "timetabled": tt, "estimated": est, "delay_min": delay_min(tt, est),
            # inferred: a skipped stop at the departure call also means this train does not leave here
            "cancelled": int(flag(svc, "Cancelled") or flag(call, "NotServicedStop")),
            "unplanned": flag(svc, "Unplanned"), "deviation": flag(svc, "Deviation"),
            "quay": _t(call, "{*}PlannedQuay/{*}Text"), "bucket_hour_local": _ts(tt).astimezone(ZRH).hour,
        })
    return rows


def fetch(name: str, ref: str, key: str) -> bytes:
    req = urllib.request.Request(URL, _request(name, ref), {"Authorization": f"Bearer {key}", "Content-Type": "application/xml; charset=utf-8"})
    for wait in (2, 5, 10, 0):  # the free tier answers 429 under bursts
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 429 or not wait:
                raise
            time.sleep(wait)


def load_stations(path: str | None) -> list[tuple[str, str]]:
    if not path:
        return STATIONS
    rows = [l.split(",", 1) for l in Path(path).read_text().splitlines() if l.strip() and not l.startswith("#")]
    return [(n.strip(), r.strip()) for n, r in rows]  # lines: name,stop_ref


def sample_once(stations, key: str, out: Path = CSV_PATH) -> list[dict]:
    rows = []
    for name, ref in stations:
        try:
            xml = fetch(name, ref, key)
            got = place_name(xml, ref)
            if got is None or score(got, name) < MIN_SCORE:
                log.warning("skip %s %s: provider returned %r", name, ref, got)
                continue
            rows += parse_events(xml, name, ref, datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
        except Exception as e:  # one bad station must not stop the rest
            log.warning("skip %s: %s", name, type(e).__name__)
    out.parent.mkdir(parents=True, exist_ok=True)
    new = not out.exists()
    with out.open("a", newline="") as f:
        w = csv.DictWriter(f, COLUMNS)
        if new:
            w.writeheader()
        w.writerows(rows)
    return rows


def run_loop(stations, key, interval: int, duration: int, out: Path = CSV_PATH) -> None:
    end = time.monotonic() + duration
    while time.monotonic() < end:
        t0 = time.monotonic()
        try:
            log.info("sampled %d departures", len(sample_once(stations, key, out)))
        except Exception as e:
            log.warning("run failed: %s", type(e).__name__)
        time.sleep(max(0, min(interval - (time.monotonic() - t0), end - time.monotonic())))


def _pct(xs: list[float], q: float) -> float:
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(q * len(xs)))]


def summarize(src: Path = CSV_PATH, dst: Path = SUMMARY_PATH) -> dict:
    with src.open(newline="") as f:
        rows = list(csv.DictReader(f))
    latest = {}  # the same departure is sampled every run: keep its last (most up to date) observation
    for r in rows:
        latest[(r["stop_ref"], r["journey_ref"], r["operating_day"], r["timetabled"])] = r
    groups = defaultdict(list)
    for r in latest.values():
        groups[(r["station_name"], int(r["bucket_hour_local"]))].append(r)
    portions = []
    for (st, h), rs in sorted(groups.items()):
        ds = [float(r["delay_min"]) for r in rs if r["delay_min"] not in ("", None) and r["cancelled"] != "1"]
        if len(ds) >= MIN_SAMPLES:  # cancelled or estimate-less departures are not in the delay stats
            portions.append({"station": st, "hourBucket": h, "samples": len(ds), "medianDelayMin": round(statistics.median(ds), 1),
                             "p90DelayMin": round(_pct(ds, 0.9), 1), "shareLate": round(sum(d > LATE_MIN for d in ds) / len(ds), 2),
                             "cancelled": sum(r["cancelled"] == "1" for r in rs)})
    canc = [{"station": r["station_name"], "lineRef": r["line_ref"], "timetabled": r["timetabled"], "journeyRef": r["journey_ref"]}
            for r in latest.values() if r["cancelled"] == "1"][:MAX_CANCELLATIONS]
    times = sorted(r["sampled_at_utc"] for r in rows)
    out = {"generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
           "window": {"from": times[0] if times else None, "to": times[-1] if times else None, "samples": len(latest)},
           "portions": portions, "cancellations": canc}
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(out, ensure_ascii=False, indent=1))
    return out


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--loop", action="store_true")
    ap.add_argument("--summarize", action="store_true")
    ap.add_argument("--interval", type=int, default=60)
    ap.add_argument("--duration", type=int, default=3600)
    ap.add_argument("--stations-file")
    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    if a.once or a.loop:
        key = os.environ["OJP_API_KEY"]
        st = load_stations(a.stations_file)
        if a.once:
            rows = sample_once(st, key)
            late = [r for r in rows if r["delay_min"] is not None and r["delay_min"] > LATE_MIN]
            print(f"departures={len(rows)} late>{LATE_MIN}min={len(late)} cancelled={sum(r['cancelled'] for r in rows)} "
                  f"deviation={sum(r['deviation'] for r in rows)} unplanned={sum(r['unplanned'] for r in rows)}")
        else:
            run_loop(st, key, a.interval, a.duration)
    if a.summarize:
        s = summarize()
        print(f"unique departures={s['window']['samples']} portions={len(s['portions'])} cancellations={len(s['cancellations'])}")


if __name__ == "__main__":
    main()
