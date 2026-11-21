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
from . import ojp_validate as ojp
from .line_hour import by_line_hour, incidents_table

DEFAULT_DATA = "analytics/Daten/Daten/extern-liveticker"
VERSION = "2026-11-duration-v1"


def top_stations(crows, n=40) -> list[str]:
    """Endpoints of the best-sampled corridors, first n distinct."""
    seen: dict = {}
    for c in crows:
        seen.setdefault(c["from"], 1)
        seen.setdefault(c["to"], 1)
    return list(seen)[:n]


def build_predictions(vend, snap, generated_at, validate=None) -> dict:
    """validate: optional callable name -> {csvName, ojpName, sloid, matched, score} (OJP lookup)."""
    start, end = vend.start.min(), vend.end.max()
    window_days = max(1, (end.normalize() - start.normalize()).days + 1)
    inc = incidents_table(vend)
    all_rows = by_line_hour(inc, window_days)
    rows = [r for r in all_rows if r["sampleSize"] >= cor.MIN_CELL_N]
    parsed = [cor.parse_corridor(a, b)[0] for a, b in zip(inc.desc_en, inc.desc_de)]
    canon, aliases = cor.build_station_table(n for p in parsed if p for n in p)
    inc2 = cor.add_corridors(inc, canon)
    crows, cstats = cor.corridors(inc2, window_days)
    with_corridor = int(inc2.stops.notna().sum())
    in_kept = len(cstats["coveredIds"])
    sv = [validate(n) for n in top_stations(crows)] if validate else []
    for v in sv:  # OJP name differs from the CSV name -> alias csv -> ojp, so the backend can resolve both ways
        if v["matched"] and v["ojpName"] != v["csvName"]:
            aliases[v["csvName"]] = v["ojpName"]
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
            "hourBaseline (nationwide, 24 rows) and stationAliases. Corridors with a single incident are dropped; "
            "byLineHour cells with <8 incidents are dropped.",
            f"{inc.key.isna().sum()} of {len(inc)} incidents had no parsable line corridor and are excluded.",
        ],
        "byLineHour": rows,
        "corridors": crows,
        "hourBaseline": cor.hour_baseline(inc, window_days),
        "stationAliases": dict(sorted(aliases.items())),
        "stationValidation": sv,
        "joinHints": cor.JOIN_HINTS,
        "stats": {
            "incidents": len(inc), "incidentsWithCorridor": with_corridor,
            "incidentsInKeptCorridors": in_kept,
            "shareInKeptCorridors": round(in_kept / len(inc), 4),
            "coverageShare": round(in_kept / len(inc), 4),  # ENDED incidents (<24h) in a kept corridor
            "corridorsKept": len(crows), "corridorsKeptSampleGe20": sum(c["sampleSize"] >= 20 for c in crows),
            "corridorsDropped": cstats["dropped"], "corridorsDroppedSampleEq1": cstats["dropped"],
            "stationsValidated": sum(v["matched"] for v in sv), "stationsUnmatched": sum(not v["matched"] for v in sv),
            "byLineHourCellsKept": len(rows), "byLineHourCellsDropped": len(all_rows) - len(rows),
            "byLineHourMinSampleSize": cor.MIN_CELL_N, "windowDays": window_days,
        },
    }


def main(argv=None) -> dict:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--data", default=os.environ.get("LIVETICKER_DATA", DEFAULT_DATA))
    ap.add_argument("--out", default="analytics/artifacts")
    ap.add_argument("--no-ojp", action="store_true", help="skip OJP station validation (offline)")
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
    key = None if a.no_ojp else ojp.load_key()
    pred = build_predictions(v, snap, now, (lambda n: ojp.validate(n, key)) if key else None)
    (out / "predictions.json").write_text(json.dumps(pred, indent=1, ensure_ascii=False))
    n20 = sum(r["sampleSize"] >= 20 for r in pred["byLineHour"])
    print(f"stats: {json.dumps(pred['stats'])}")
    print("stationValidation:", [(v["csvName"], v["ojpName"]) for v in pred["stationValidation"] if not v["matched"]], "unmatched")
    print("hourBaseline sampleSize:", [r["sampleSize"] for r in pred["hourBaseline"]])
    return {"metrics": metrics, "n_keys": len(pred["byLineHour"]), "n_keys_ge20": n20}


if __name__ == "__main__":
    main()
