"""Phase-2b propagation layer: non-rail services conditioned on a town-corridor incident."""
import io
import csv
from datetime import datetime
from zoneinfo import ZoneInfo

from analytics.scripts import build_delay_estimates as bde

ZURICH = ZoneInfo("Europe/Zurich")
HEADER = ["BETRIEBSTAG", "FAHRT_BEZEICHNER", "PRODUKT_ID", "LINIEN_TEXT", "ZUSATZFAHRT_TF", "FAELLT_AUS_TF",
          "HALTESTELLEN_NAME", "ANKUNFTSZEIT", "AN_PROGNOSE", "AN_PROGNOSE_STATUS",
          "ABFAHRTSZEIT", "AB_PROGNOSE", "AB_PROGNOSE_STATUS", "DURCHFAHRT_TF"]
DAY = "15.09.2026"


def ts(hhmm, day=DAY):
    return f"{day} {hhmm}:00"


def row(trip="BUS1", product="Bus", label="701", station="Morges, Gare", dep="10:00", prog_dep="10:04",
        arr=None, prog_arr=None, zusatz="false", faellt="false", durchfahrt="false",
        dep_status="REAL", arr_status="REAL", day=DAY):
    arr = arr or dep
    prog_arr = prog_arr or prog_dep
    return dict(zip(HEADER, [day, trip, product, label, zusatz, faellt, station,
                             ts(arr, day), ts(prog_arr, day), arr_status,
                             ts(dep, day), ts(prog_dep, day), dep_status, durchfahrt]))


def incident(start_h=10, end_h=12, stations=("Lausanne", "Renens VD", "Morges"), iid="INC1"):
    return {"id": iid,
            "start": datetime(2026, 9, 15, start_h, tzinfo=ZURICH).timestamp(),
            "end": datetime(2026, 9, 15, end_h, tzinfo=ZURICH).timestamp(),
            "stations": frozenset(bde.fold(s) for s in stations)}


def run(rows, incidents):
    text = "\n".join([";".join(HEADER)] + [";".join(str(r[h]) for h in HEADER) for r in rows])
    agg = bde.Agg()
    state = {"rows_read": 0, "whitelist_rows": 0, "dep_samples": 0, "arr_samples": 0,
             "trip_keys": set(), "labels": set(),
             "prop_rows": 0, "prop_samples": 0, "prop_cond_rows": 0}
    bde.process_day(lambda: csv.DictReader(io.StringIO(text), delimiter=";"), agg, state, incidents)
    return agg, state


def bus_rows(n=12, dep_prefix="10", delay_min=4, **kw):
    """n departures at 10:00..10:11, each `delay_min` minutes late."""
    return [row(trip=f"T{k}", dep=f"{dep_prefix}:{k:02d}",
                prog_dep=f"{dep_prefix}:{k + delay_min:02d}", **kw) for k in range(n)]


def test_conditioned_and_unconditional_medians_with_delta():
    rows = bus_rows()                                                    # 12 inside the window, +4 min
    rows += bus_rows(dep_prefix="08", delay_min=1)                       # 12 outside, same 08-12 block, +1 min
    agg, state = run(rows, [incident()])

    prop = bde.emit_propagation(agg)
    assert len(prop) == 1
    p = prop[0]
    assert (p["serviceLine"], p["town"], p["product"], p["hourBucket"]) == ("701", "MORGES", "BUS", 8)
    assert p["weekdayClass"] == "ANY" and p["bucketType"] == "BLOCK4"
    assert p["dep"]["samples"] == 12 and p["dep"]["median"] == 4.0
    assert p["unconditionalMedian"] == 1.0 and p["unconditionalSamples"] == 24
    assert p["deltaMedian"] == 3.0
    assert p["incidentCount"] == 1
    assert state["prop_cond_rows"] == 12 and state["prop_rows"] == 24


def test_cancellation_and_suppression_shares_use_all_planned_stops():
    rows = bus_rows()
    rows.append(row(trip="CXL", dep="10:30", prog_dep="10:30", faellt="true",
                    dep_status="PROGNOSE", arr_status="PROGNOSE"))
    rows.append(row(trip="SKP", dep="10:40", prog_dep="10:40", durchfahrt="true",
                    dep_status="PROGNOSE", arr_status="PROGNOSE"))
    agg, _ = run(rows, [incident()])
    p = bde.emit_propagation(agg)[0]
    assert p["plannedStops"] == 14
    assert p["cancellationShare"] == round(1 / 14, 3)
    assert p["suppressionShare"] == round(1 / 14, 3)
    assert p["dep"]["samples"] == 12                     # cancelled/suppressed rows are not delays


def test_town_outside_the_incident_corridor_is_never_counted():
    rows = bus_rows()
    rows += [row(trip=f"Z{k}", station="Zürich, Bellevue", label="31",
                 dep=f"10:{k:02d}", prog_dep=f"10:{k + 4:02d}") for k in range(12)]
    agg, state = run(rows, [incident()])
    prop = bde.emit_propagation(agg)
    assert {p["town"] for p in prop} == {"MORGES"}
    assert state["prop_rows"] == 12                      # Zürich rows pruned before counting


def test_thin_bucket_is_dropped():
    agg, _ = run(bus_rows(n=9), [incident()])
    assert bde.emit_propagation(agg) == []


def test_metro_and_tram_are_separate_products():
    rows = bus_rows(n=10)
    rows += [row(trip=f"M{k}", product="Metro", label="m1", station="Lausanne, Ouchy",
                 dep=f"10:{k:02d}", prog_dep=f"10:{k + 2:02d}") for k in range(10)]
    agg, _ = run(rows, [incident()])
    prop = {(p["product"], p["town"], p["serviceLine"]): p for p in bde.emit_propagation(agg)}
    assert ("BUS", "MORGES", "701") in prop
    assert ("METRO", "LAUSANNE", "M1") in prop
    assert prop[("METRO", "LAUSANNE", "M1")]["dep"]["median"] == 2.0


def test_no_incident_no_propagation_table():
    agg, _ = run(bus_rows(), [])
    assert bde.emit_propagation(agg) == []


def test_rail_tables_do_not_receive_non_rail_rows():
    agg, _ = run(bus_rows(), [incident()])
    assert bde.emit(agg) == ([], [])
    assert bde.emit_conditioned(agg) == ([], [])
