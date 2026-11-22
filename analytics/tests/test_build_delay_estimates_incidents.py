"""Phase-2 aggregator: incident-conditioned buckets, risk shares, ANY-only pooling."""
import io
import csv
from datetime import datetime
from zoneinfo import ZoneInfo

from analytics.scripts import build_delay_estimates as bde

ZURICH = ZoneInfo("Europe/Zurich")
HEADER = ["BETRIEBSTAG", "FAHRT_BEZEICHNER", "PRODUKT_ID", "LINIEN_TEXT", "ZUSATZFAHRT_TF", "FAELLT_AUS_TF",
          "HALTESTELLEN_NAME", "ANKUNFTSZEIT", "AN_PROGNOSE", "AN_PROGNOSE_STATUS",
          "ABFAHRTSZEIT", "AB_PROGNOSE", "AB_PROGNOSE_STATUS", "DURCHFAHRT_TF"]


def ts(hhmm, day="15.09.2026"):
    return f"{day} {hhmm}:00"


def row(trip="T1", label="IR 90", station="Lausanne", dep="10:05", arr="10:04", prog_dep="10:10", prog_arr="10:09",
        day="15.09.2026", zusatz="false", faellt="false", durchfahrt="false", dep_status="REAL", arr_status="REAL"):
    return dict(zip(HEADER, [day, trip, "Zug", label, zusatz, faellt, station, ts(arr, day), ts(prog_arr, day), arr_status,
                             ts(dep, day), ts(prog_dep, day), dep_status, durchfahrt]))


def incident(start_h=8, end_h=12, stations=("Lausanne", "Renens VD", "Morges"), iid="INC1"):
    return {"id": iid,
            "start": datetime(2026, 9, 15, start_h, tzinfo=ZURICH).timestamp(),
            "end": datetime(2026, 9, 15, end_h, tzinfo=ZURICH).timestamp(),
            "stations": frozenset(bde.fold(s) for s in stations)}


def run(rows, incidents):
    text = "\n".join([";".join(HEADER)] + [";".join(str(r[h]) for h in HEADER) for r in rows])
    agg = bde.Agg()
    state = {"rows_read": 0, "whitelist_rows": 0, "dep_samples": 0, "arr_samples": 0, "trip_keys": set(), "labels": set()}
    bde.process_day(lambda: csv.DictReader(io.StringIO(text), delimiter=";"), agg, state, incidents)
    return agg, state


def trip_rows(n=12, **kw):
    """Route Lausanne -> Renens VD -> Morges (inside the corridor); +5 min delay at every stop."""
    out = []
    for k in range(n):
        out.append(row(trip="T1", station="Lausanne", dep=f"10:{k:02d}", arr=f"10:{k:02d}",
                       prog_dep=f"10:{k + 5:02d}", prog_arr=f"10:{k + 5:02d}", **kw))
    out.append(row(trip="T1", station="Renens VD", dep="10:20", arr="10:20", prog_dep="10:25", prog_arr="10:25", **kw))
    out.append(row(trip="T1", station="Morges", dep="10:25", arr="10:25", prog_dep="10:30", prog_arr="10:30", **kw))
    return out


def test_conditioned_buckets_emit_medians_and_risk_shares():
    rows = trip_rows()
    rows.append(row(trip="T1", station="Lausanne", dep="10:40", arr="10:39", faellt="true",
                    dep_status="PROGNOSE", arr_status="PROGNOSE"))          # cancelled: risk only
    rows.append(row(trip="T1", station="Renens VD", dep="10:45", arr="10:44", durchfahrt="true",
                    dep_status="PROGNOSE", arr_status="PROGNOSE"))          # suppressed: risk only
    rows.append(row(trip="T2", label="IC 5", station="Bern", dep="11:00", arr="10:58",
                    prog_dep="11:04", prog_arr="11:02"))                   # different corridor
    rows.append(row(trip="T1", station="Morges", dep="14:00", arr="13:59",
                    prog_dep="14:06", prog_arr="14:05"))                    # outside the incident window
    agg, _ = run(rows, [incident()])

    lines, stations = bde.emit_conditioned(agg)
    li = {(r["serviceLabel"], r["hourBucket"]): r for r in lines}
    si = {(r["station"], r["hourBucket"]): r for r in stations}

    # ANY weekday + 4h block only: 10:xx lands in block 8
    l = li[("IR90", 8)]
    assert l["weekdayClass"] == "ANY" and l["bucketType"] == "BLOCK4"
    assert l["dep"]["samples"] == 14 and l["arr"]["samples"] == 14
    assert l["dep"]["median"] == 5.0 and l["dep"]["shareLate"] == 1.0
    assert l["incidentCount"] == 1
    # denominator: 14 measured + 1 cancelled + 1 suppressed = 16 planned stops inside the window
    assert l["plannedStops"] == 16
    assert l["cancellationShare"] == round(1 / 16, 3)
    assert l["suppressionShare"] == round(1 / 16, 3)

    s = si[("Lausanne", 8)]
    assert s["dep"]["samples"] == 12 and s["arr"]["samples"] == 12
    # Lausanne: 12 measured + cancelled + 0 suppressed = 13 planned stops
    assert s["plannedStops"] == 13 and s["cancellationShare"] == round(1 / 13, 3)

    # the IC 5 at Bern only touches no corridor station: nothing conditioned, no leak
    assert not any(k[0] == "IC5" for k in li)
    assert not any(k[0] == "Bern" for k in si)


def test_no_incidents_emits_no_conditioned_tables():
    agg, _ = run(trip_rows(), [])
    assert bde.emit_conditioned(agg) == ([], [])


def test_measurement_outside_the_window_is_not_conditioned():
    rows = trip_rows()
    rows.append(row(trip="T1", station="Morges", dep="14:00", arr="13:59", prog_dep="14:06", prog_arr="14:05"))
    agg, _ = run(rows, [incident()])
    lines, _ = bde.emit_conditioned(agg)
    l = next(r for r in lines if r["serviceLabel"] == "IR90")
    assert l["dep"]["samples"] == 14 and l["plannedStops"] == 14


def test_trip_without_two_shared_stations_never_matches():
    # corridor Lausanne + Renens VD only; the trip calls at Lausanne and Genève -> 1 shared station
    agg, _ = run([row(trip="T9", station="Lausanne")], [incident(stations=("Lausanne", "Renens VD"))])
    assert bde.emit_conditioned(agg) == ([], [])
