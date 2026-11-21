"""Rebuild analytics artifacts.

    python -m analytics.build_artifacts --data analytics/Daten/Daten/extern-liveticker --out analytics/artifacts
"""
from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path

import joblib

from . import duration_model as dm
from . import corridors as cor
from .line_hour import by_line_hour, incidents_table

DEFAULT_DATA = "analytics/Daten/Daten/extern-liveticker"
VERSION = "2026-11-duration-v1"


def build_predictions(vend, snap, generated_at) -> dict:
    start, end = vend.start.min(), vend.end.max()
    window_days = max(1, (end.normalize() - start.normalize()).days + 1)
    inc = incidents_table(vend)
    all_rows = by_line_hour(inc, window_days)
    rows = [r for r in all_rows if r["sampleSize"] >= cor.MIN_CELL_N]
    parsed = [cor.parse_corridor(a, b)[0] for a, b in zip(inc.desc_en, inc.desc_de)]
    canon, aliases = cor.build_station_table(n for p in parsed if p for n in p)
    inc2 = cor.add_corridors(inc, canon)
    crows, cstats = cor.corridors(inc2, window_days)
    covered_ids = {r.name for _, r in inc2[inc2.stops.notna()].iterrows()}
    kept_pairs = {tuple(sorted((c["from"], c["to"]))) for c in crows}
    in_kept = sum(tuple(sorted((s[0], s[-1]))) in kept_pairs for s in inc2.stops.dropna())
    return {
        "modelVersion": VERSION, "generatedAt": generated_at,
        "coverage": {"from": start.isoformat(), "to": end.isoformat(),
                     "scope": "SBB extern-liveticker incidents (ENDED, <24h), all of Switzerland"},
        "notes": [
            "key is the line corridor text as printed in the incident description (e.g. 'Lausanne - Brig'). "
            "The CSVs contain NO OJP lineRef (ojp:91008:E) and no train/route numbers, so keys cannot be "
            "joined to OJP lineRefs without a separate corridor->line mapping.",
            "lateProbability = share of days in the coverage window on which the corridor had an active "
            "unplanned disruption during that local (Europe/Zurich) hour. It is NOT a per-train lateness "
            "rate (no non-disrupted trips in the data).",
            "expectedDelayMin = median total duration (minutes) of the disruptions active in that bucket; "
            "an upper bound on passenger delay, not a measured delay.",
            "sampleSize = distinct incidents in the bucket; confidence: <8 low, 8-19 medium, >=20 high.",
            "corridors: pooled over hours and both directions (A-B + B-A); see stats, joinHints, "
            "hourBaseline (nationwide, 24 rows) and stationAliases. Corridors with <3 incidents are dropped; "
            "byLineHour cells with <8 incidents are dropped.",
            f"{inc.key.isna().sum()} of {len(inc)} incidents had no parsable line corridor and are excluded.",
        ],
        "byLineHour": rows,
        "corridors": crows,
        "hourBaseline": cor.hour_baseline(inc, window_days),
        "stationAliases": aliases,
        "joinHints": cor.JOIN_HINTS,
        "stats": {
            "incidents": len(inc), "incidentsWithCorridor": len(covered_ids),
            "incidentsInKeptCorridors": in_kept,
            "shareInKeptCorridors": round(in_kept / len(inc), 4),
            "corridorsKept": len(crows), "corridorsKeptSampleGe20": sum(c["sampleSize"] >= 20 for c in crows),
            "corridorsDroppedSampleLt3": cstats["droppedSampleLt3"],
            "byLineHourCellsKept": len(rows), "byLineHourCellsDropped": len(all_rows) - len(rows),
            "byLineHourMinSampleSize": cor.MIN_CELL_N, "windowDays": window_days,
        },
    }


def main(argv=None) -> dict:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--data", default=os.environ.get("LIVETICKER_DATA", DEFAULT_DATA))
    ap.add_argument("--out", default="analytics/artifacts")
    a = ap.parse_args(argv)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    raw = dm.load_versions(a.data)
    v = dm.add_start_end(raw)
    snap = dm.build_snapshots(v)
    print(f"{raw.id.nunique()} incidents, {len(raw)} versions; "
          f"{v.id.nunique()} with known end; {len(snap)} snapshots from {snap.id.nunique()} incidents")

    train, test, cutoff = dm.chrono_split(snap)
    cats = dm.categories_of(snap)
    metrics = dm.evaluate(dm.train_models(train, cats), cats, train, test)
    metrics.update(cutoff=cutoff.isoformat(), n_train_incidents=int(train.id.nunique()),
                   n_test_incidents=int(test.id.nunique()), n_train_snapshots=len(train),
                   n_test_snapshots=len(test))
    print(json.dumps(metrics, indent=2))

    # final model: refit on all data after evaluating on the chronological split
    models = dm.train_models(snap, cats)
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    joblib.dump({"models": models, "features": dm.CAT + dm.NUM, "categories": cats},
                out / "incident_duration.joblib")
    (out / "incident_duration.json").write_text(json.dumps({
        "modelVersion": VERSION, "generatedAt": now, "features": dm.CAT + dm.NUM,
        "categories": cats, "quantiles": list(dm.QUANTILES), "target": "minutes remaining until ENDED",
        "trainingWindow": {"from": snap.start.min().isoformat(), "to": snap.end.max().isoformat()},
        "n_incidents": int(raw.id.nunique()), "n_versions": len(raw), "n_samples": len(snap),
        "metrics": metrics,
        "note": "Metrics come from a chronological split; the shipped joblib is refit on all snapshots.",
    }, indent=2))
    pred = build_predictions(v, snap, now)
    (out / "predictions.json").write_text(json.dumps(pred, indent=1, ensure_ascii=False))
    n20 = sum(r["sampleSize"] >= 20 for r in pred["byLineHour"])
    print(f"stats: {json.dumps(pred['stats'])}")
    print("hourBaseline sampleSize:", [r["sampleSize"] for r in pred["hourBaseline"]])
    return {"metrics": metrics, "n_keys": len(pred["byLineHour"]), "n_keys_ge20": n20}


if __name__ == "__main__":
    main()
