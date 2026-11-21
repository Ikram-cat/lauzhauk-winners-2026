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
    rows = pred["byLineHour"]
    assert {r["hourBucket"] for r in rows} == {8, 9}  # 07:00-09:00Z = 08:00-10:00 Zurich
    for r in rows:
        assert r.keys() == {"key", "hourBucket", "lateProbability", "expectedDelayMin", "sampleSize", "confidence"}
        assert r["key"] == "Bern - Thun" and 0 <= r["lateProbability"] <= 1
        assert 0 <= r["hourBucket"] <= 23 and r["confidence"] in {"low", "medium", "high"}
