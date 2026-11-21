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


@pytest.mark.skipif(not os.environ.get("OJP_API_KEY"), reason="no OJP_API_KEY")
def test_live_lausanne():
    xml = rs.fetch("Lausanne", "ch:1:sloid:1120", os.environ["OJP_API_KEY"])
    assert rs.place_name(xml, "ch:1:sloid:1120") == "Lausanne" and rs.parse_events(xml, "Lausanne", "ch:1:sloid:1120", "t")
