import json

import pandas as pd

from analytics import duration_model as dm
from analytics.build_artifacts import build_predictions
from analytics.line_hour import by_line_hour, incidents_table, line_key

T = lambda s: pd.Timestamp(s, tz="UTC")


def versions():
    """Incident 1: 3 INFO versions then ENDED then a post-END row. Incident 2: never ended."""
    r = lambda i, lc, upd, **k: dict(
        id=i, duration_type=k.get("dt", "UNKNOWN"), duration_to=k.get("to", pd.NaT),
        duration_at_least=k.get("al"), reason_id_fk="EG_008", lifecycle=lc,
        created_at_source=T("2026-01-05 07:00"), updated_at_source=T(upd),
        last_modified_date=T(upd), summary_en="Interruption: A - B",
        description_en="The train service on the Bern - Thun line between X and Y is interrupted.",
        description_de=None)
    return pd.DataFrame([
        r("1", "INFO", "2026-01-05 07:10"),
        r("1", "INFO", "2026-01-05 07:40", dt="UNTIL", to=T("2026-01-05 09:00")),
        r("1", "INFO", "2026-01-05 08:00"),
        r("1", "ENDED", "2026-01-05 09:00"),
        r("1", "INFO", "2026-01-05 09:30"),  # post-END row
        r("2", "INFO", "2026-01-05 08:00"),
    ])


def test_snapshots_exclude_post_end_and_unended():
    snap = dm.build_snapshots(dm.add_start_end(versions()))
    assert set(snap.id) == {"1"}
    assert (snap.t < snap.end).all()
    assert len(snap) == 3  # ENDED row and 09:30 row are not snapshots
    assert snap.y_remaining.tolist() == [110, 80, 60]
    assert snap.n_updates_so_far.tolist() == [0, 1, 2]


def test_announced_end_parsing():
    t = T("2026-01-05 07:40")  # 08:40 Zurich
    assert dm.announced_end(T("2026-01-05 09:00"), None, t) == T("2026-01-05 09:00")
    assert dm.announced_end(pd.NaT, "15:00:00", t) == T("2026-01-05 14:00")  # CET = UTC+1
    assert dm.announced_end(pd.NaT, "08:00:00", t) == T("2026-01-06 07:00")  # past time -> next day
    assert pd.isna(dm.announced_end(pd.NaT, None, t))


def test_chrono_split_no_label_in_test_period():
    rows = []
    for i in range(10):
        s = T("2026-01-01") + pd.Timedelta(days=i)
        rows.append(dict(id=str(i), start=s, end=s + pd.Timedelta(days=1.5 if i == 7 else 0.1)))
    train, test, cutoff = dm.chrono_split(pd.DataFrame(rows))
    assert (train.start < cutoff).all() and (train.end <= cutoff).all()
    assert (test.start >= cutoff).all()
    assert not set(train.id) & set(test.id)


def test_line_key():
    assert line_key("The train service on the Genève - Lausanne line between", None) == "Genève - Lausanne"
    assert line_key(None, "Der Bahnverkehr auf der Strecke Bern - Thun ist unterbrochen") == "Bern - Thun"
    assert line_key("Service resumed: Wettingen", None) is None


def test_predictions_schema():
    v = dm.add_start_end(versions())
    pred = build_predictions(v, dm.build_snapshots(v), "2026-01-01T00:00:00Z")
    json.dumps(pred)
    assert {"modelVersion", "generatedAt", "coverage", "notes", "byLineHour"} <= pred.keys()
    assert {"from", "to", "scope"} <= pred["coverage"].keys()
    assert any("lineRef" in n for n in pred["notes"])
    rows = by_line_hour(incidents_table(v), 1)  # unfiltered cells keep the round-1 schema
    assert {r["hourBucket"] for r in rows} == {8, 9}  # 07:00-09:00Z = 08:00-10:00 Zurich
    for r in rows:
        assert r.keys() == {"key", "hourBucket", "lateProbability", "expectedDelayMin", "sampleSize", "confidence"}
        assert r["key"] == "Bern - Thun" and 0 <= r["lateProbability"] <= 1
        assert 0 <= r["hourBucket"] <= 23 and r["confidence"] in {"low", "medium", "high"}


# ---- round 2: corridors / hourBaseline / aliases ----
from analytics import corridors as cor  # noqa: E402


def _inc(rows):
    """rows: (id, start, end, [stops]) in UTC -> incident table with stops/csource set."""
    df = pd.DataFrame([dict(start=T(s), end=T(e), stops=st, csource="line") for _, s, e, st in rows],
                      index=[r[0] for r in rows])
    df["duration_min"] = (df.end - df.start).dt.total_seconds() / 60
    return df


def test_parse_corridor_two_and_multi_stop():
    assert cor.parse_corridor("on the Genève - Lausanne line between", None) == (["Genève", "Lausanne"], "line")
    s, src = cor.parse_corridor("Train service interrupted on the line Murten/Morat - Kerzers, between Murten/Morat "
                                "and Kerzers, as well as on the line X - Y.", None)
    assert (s, src) == (["Murten/Morat", "Kerzers"], "line")
    assert cor.parse_corridor("on the Lausanne - Vevey - Brig line between", None)[0] == ["Lausanne", "Vevey", "Brig"]
    assert cor.parse_corridor("Limited train service between Etoy and Allaman are subject", None) == \
        (["Etoy", "Allaman"], "segment")
    assert cor.parse_corridor("Limited train service at Bex.", "Im Bahnhof Bex") == (None, None)


def test_station_normalisation_and_aliases():
    canon, aliases = cor.build_station_table(["Genève", "Genève", "Geneve ", "  Zürich  HB", "Zürich HB", "Bern"])
    assert cor.normalize_station("Genf", canon) == "Genève"
    assert cor.normalize_station(" geneva ", canon) == "Genève"
    assert cor.normalize_station("Zurich", canon) == "Zürich HB" and cor.normalize_station("ZÜRICH hb", canon) == "Zürich HB"
    assert cor.normalize_station("Biel", canon) == "Biel/Bienne"
    assert cor.normalize_station("Unknown  Halt", canon) == "Unknown Halt"
    assert aliases["Genf"] == "Genève" and aliases["Geneve"] == "Genève"


def test_corridor_pooling_math():
    # 4 incidents over A-B/B-A: two on the same local day (UTC 08-09 & 10-11 Jan 5), one Jan 7; 1 on C-D (kept, very_low)
    inc = _inc([("1", "2026-01-05 08:00", "2026-01-05 09:00", ["A", "B"]),
                ("2", "2026-01-05 10:00", "2026-01-05 12:00", ["B", "A"]),
                ("3", "2026-01-07 08:00", "2026-01-07 11:00", ["A", "X", "B"]),
                ("4", "2026-01-05 08:00", "2026-01-05 09:00", ["C", "D"])])
    rows, st = cor.corridors(inc, window_days=10)
    assert st == {"poolsTotal": 4, "dropped": 0, "coveredIds": {"1", "2", "3", "4"}} and len(rows) == 4
    r = next(r for r in rows if r["corridor"] == "A - B")
    assert (r["from"], r["to"], r["sampleSize"], r["disruptionDays"]) == ("A", "B", 3, 2)
    assert r["disruptionProbabilityPerDay"] == 0.2 and r["expectedDelayMin"] == 120.0  # median(60,120,180)
    assert r["confidence"] == "low" and "via" not in r  # modal via is empty


def test_hour_baseline_24_rows_and_probability():
    inc = _inc([("1", "2026-01-05 08:00", "2026-01-05 09:00", None), ("2", "2026-01-06 08:00", "2026-01-06 08:30", None)])
    hb = cor.hour_baseline(inc, window_days=4)
    assert [r["hourBucket"] for r in hb] == list(range(24))
    h9 = hb[9]  # 08:00Z = 09:00 Zurich
    assert h9["sampleSize"] == 2 and h9["disruptionProbabilityPerHour"] == 0.5 and h9["expectedDelayMin"] == 45.0
    assert hb[3]["sampleSize"] == 0 and hb[3]["disruptionProbabilityPerHour"] == 0.0


def test_artifact_drops_noise_and_is_joinable():
    v = dm.add_start_end(versions())
    pred = build_predictions(v, dm.build_snapshots(v), "2026-01-01T00:00:00Z")
    assert pred["byLineHour"] == [] and pred["stats"]["byLineHourCellsDropped"] == 2  # n=1 cells < 8
    assert len(pred["corridors"]) == 1 and pred["corridors"][0]["confidence"] == "very_low"  # n=1 kept
    assert pred["stats"]["coverageShare"] == 1.0 and pred["unmatchedSamples"] == []
    assert len(pred["hourBaseline"]) == 24 and {"joinHints", "stationAliases"} <= pred.keys()
    json.dumps(pred, ensure_ascii=False)


def test_segments_full_and_very_low():
    inc = _inc([("1", "2026-01-05 08:00", "2026-01-05 09:00", ["A", "X", "B"]),
                ("2", "2026-01-06 08:00", "2026-01-06 09:00", ["X", "B"]),
                ("3", "2026-01-07 08:00", "2026-01-07 09:00", ["C", "D"])])
    rows, st = cor.corridors(inc, window_days=10)
    by = {r["corridor"]: r for r in rows}
    assert by["X - B"]["sampleSize"] == 2 and by["X - B"]["confidence"] == "low" and by["X - B"]["source"] == "full"
    assert by["A - B"]["source"] == "full" and by["A - B"]["via"] == ["X"]
    assert by["A - X"]["source"] == "segment" and by["A - X"]["confidence"] == "very_low"
    assert by["C - D"]["confidence"] == "very_low" and by["C - D"]["sampleSize"] == 1
    assert st["dropped"] == 0 and st["coveredIds"] == {"1", "2", "3"}


def test_no_invented_pairs_and_coverage():
    # A-X-B-Y: only adjacent pairs + the full A-Y; never A-B or X-Y
    inc = _inc([("1", "2026-01-05 08:00", "2026-01-05 09:00", ["A", "X", "B", "Y"]),
                ("2", "2026-01-06 08:00", "2026-01-06 09:00", None)])
    rows, st = cor.corridors(inc, window_days=10)
    assert {frozenset((r["from"], r["to"])) for r in rows} == {frozenset(p) for p in
           [("A", "Y"), ("A", "X"), ("X", "B"), ("B", "Y")]}
    assert len(st["coveredIds"]) / len(inc) == 0.5  # coverageShare: incident 2 has no stops


def test_unmatched_samples_capped_and_distinct():
    n = cor.UNMATCHED_CAP + 5
    inc = pd.DataFrame({"stops": [None] * n + [["A", "B"]],
                        "desc_en": [f"Train services at S{i} station are interrupted.\nx" for i in range(n)] + ["ok"],
                        "desc_de": [None] * (n + 1)})
    u = cor.unmatched_samples(inc)
    assert len(u) == cor.UNMATCHED_CAP and u[0]["reason"] == "single station / no A-B pair"
    assert len({x["text"] for x in u}) == len(u)


def test_ojp_places_parse_and_score():
    from analytics import ojp_validate as o
    xml = (b'<O xmlns="http://www.vdv.de/ojp"><Place><StopPlace><StopPlaceRef>ch:1:sloid:1008</StopPlaceRef>'
           b'<StopPlaceName><Text>Gen\xc3\xa8ve</Text></StopPlaceName></StopPlace></Place></O>')
    assert o.parse_places(xml) == [("Genève", "ch:1:sloid:1008")]
    assert o.score("Geneve", "Genève") == 1.0 and o.score("Zürich", "Zürich HB") == 0.9 and o.score("Bern", "Chur") < 0.8


import os  # noqa: E402

import pytest  # noqa: E402


@pytest.mark.skipif(not __import__("analytics.ojp_validate", fromlist=["x"]).load_key(), reason="OJP_API_KEY absent")
def test_ojp_validate_live_five_stations():
    from analytics import ojp_validate as o
    key = o.load_key()
    got = {n: o.validate(n, key) for n in ["Genève", "Zürich HB", "Biel/Bienne", "Lausanne", "Bern"]}
    assert all(v["matched"] and v["sloid"].startswith("ch:1:sloid:") for v in got.values()), got
