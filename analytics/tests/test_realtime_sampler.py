import csv
import os

import pytest

from analytics import realtime_sampler as rs

def _ev(tt, est=None, extra="", call_extra="", jid="j1"):
    e = f"<EstimatedTime>{est}</EstimatedTime>" if est else ""
    return f"""<StopEvent><ThisCall><CallAtStop><PlannedQuay><Text>6</Text></PlannedQuay>
<ServiceDeparture><TimetabledTime>{tt}</TimetabledTime>{e}</ServiceDeparture>{call_extra}</CallAtStop></ThisCall>
<Service><OperatingDayRef>2026-11-21</OperatingDayRef><JourneyRef>{jid}</JourneyRef><siri:LineRef>L1</siri:LineRef>
<PublishedServiceName><Text>R1</Text></PublishedServiceName>{extra}</Service></StopEvent>"""

XML = ("""<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri"><StopEventResult>"""
       + _ev("2026-11-21T20:33:00Z", "2026-11-21T20:55:00Z", "<Deviation>true</Deviation>", jid="late")
       + _ev("2026-11-21T20:40:00Z", "2026-11-21T20:40:00Z", jid="ok")
       + _ev("2026-11-21T20:45:00Z", extra="<Cancelled>true</Cancelled>", jid="canc")
       + _ev("2026-11-21T20:50:00Z", jid="noest")
       + _ev("2026-11-21T20:51:00Z", call_extra="<NotServicedStop>true</NotServicedStop>", jid="skip")
       + "</StopEventResult></OJP>").encode()


def rows():
    return {r["journey_ref"]: r for r in rs.parse_events(XML, "Lausanne", "ch:1:sloid:1120", "2026-11-21T20:00:00Z")}


def test_parse_cases():
    r = rows()
    assert r["late"]["delay_min"] == 22 and r["late"]["deviation"] == 1 and r["late"]["bucket_hour_local"] == 21
    assert r["ok"]["delay_min"] == 0 and r["ok"]["cancelled"] == 0
    assert r["canc"]["cancelled"] == 1 and r["skip"]["cancelled"] == 1
    assert r["noest"]["delay_min"] is None and r["noest"]["estimated"] is None
    assert list(r["late"]) == rs.COLUMNS and r["late"]["quay"] == "6" and r["late"]["service_label"] == "R1"


def test_delay_across_midnight():
    assert rs.delay_min("2026-11-21T23:58:00Z", "2026-11-22T00:03:30Z") == 5.5


def _csv(path, n, delay, canc=0):
    new = not path.exists()
    with path.open("a", newline="") as f:
        w = csv.DictWriter(f, rs.COLUMNS)
        if new:
            w.writeheader()
        for i in range(n):
            w.writerow({"sampled_at_utc": "t", "station_name": "S", "stop_ref": "x", "line_ref": "L", "journey_ref": f"j{delay}{i}",
                        "operating_day": "d", "timetabled": f"T{delay}{i}", "delay_min": delay, "cancelled": canc,
                        "bucket_hour_local": 8 if delay != 99 else 9})


def test_summarize_threshold_cancel_and_dedup(tmp_path):
    src, dst = tmp_path / "s.csv", tmp_path / "o.json"
    _csv(src, 10, 5); _csv(src, 10, 5)      # re-sampled twice: still 10 unique departures
    _csv(src, 9, 99)                        # below threshold: no portion
    _csv(src, 1, 7, canc=1)
    out = rs.summarize(src, dst)
    assert [(p["hourBucket"], p["samples"], p["shareLate"], p["medianDelayMin"]) for p in out["portions"]] == [(8, 10, 1.0, 5.0)]
    assert out["portions"][0]["cancelled"] == 1
    assert [c["journeyRef"] for c in out["cancellations"]] == ["j70"]


def _rows(path, specs):
    with path.open("w", newline="") as f:
        w = csv.DictWriter(f, rs.COLUMNS)
        w.writeheader()
        for i, (st, label, d, h, c) in enumerate(specs):
            w.writerow({"sampled_at_utc": "2026-11-21T20:00:00Z", "station_name": st, "stop_ref": "x", "line_ref": "L", "service_label": label,
                        "journey_ref": f"j{i}", "operating_day": "d", "timetabled": f"T{i}", "delay_min": d, "cancelled": c,
                        "bucket_hour_local": h})


def test_aggregation_math_lines_and_export(tmp_path):
    src, dst, exp = tmp_path / "s.csv", tmp_path / "o.json", tmp_path / "sub" / "e.json"
    # 10 IR90 delays 0..9 -> median 4.5, p90 (index 9) 9.0, late (>2) = 3..9 = 7/10; 10 R1 on time, same station/hour
    specs = [("S", "IR90", d, 22, 0) for d in range(10)] + [("S", "R1", 0, 22, 0) for _ in range(10)]
    specs += [("S", "X", 1, 22, 0)] * 5            # label below threshold: no line row, still in the portion
    _rows(src, specs)
    out = rs.summarize(src, dst, exp)
    assert out["modelVersion"].startswith("rt-") and exp.read_text() == dst.read_text()
    by = {l["serviceLabel"]: l for l in out["lines"]}
    assert set(by) == {"IR90", "R1"} and (by["IR90"]["medianDelayMin"], by["IR90"]["p90DelayMin"], by["IR90"]["shareLate"]) == (4.5, 9.0, 0.7)
    p = out["portions"][0]
    assert (p["samples"], p["medianDelayMin"], p["shareLate"]) == (25, 1.0, 0.28)


def test_midnight_bucket_and_cancellations_capped(tmp_path):
    wrap = lambda tt: f"<OJP xmlns='http://www.vdv.de/ojp' xmlns:siri='http://www.siri.org.uk/siri'>{_ev(tt)}</OJP>".encode()
    assert rs.parse_events(wrap("2026-11-21T22:30:00Z"), "S", "r", "t")[0]["bucket_hour_local"] == 23   # 23:30 CET
    assert rs.parse_events(wrap("2026-11-21T23:30:00Z"), "S", "r", "t")[0]["bucket_hour_local"] == 0    # past local midnight
    src = tmp_path / "s.csv"
    _rows(src, [("S", "R1", 1, 8, 1) for _ in range(150)])
    assert len(rs.summarize(src, tmp_path / "o.json")["cancellations"]) == rs.MAX_CANCELLATIONS


@pytest.mark.skipif(not os.environ.get("OJP_API_KEY"), reason="no OJP_API_KEY")
def test_live_lausanne():
    xml = rs.fetch("Lausanne", "ch:1:sloid:1120", os.environ["OJP_API_KEY"])
    assert rs.place_name(xml, "ch:1:sloid:1120") == "Lausanne" and rs.parse_events(xml, "Lausanne", "ch:1:sloid:1120", "t")
