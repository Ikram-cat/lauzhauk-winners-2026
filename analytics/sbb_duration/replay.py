"""Replay real past incidents version by version: SBB announcement vs our prediction vs actual end.

    python -m sbb_duration.replay [--detour MINUTES] [--data-dir DIR] [--output-dir DIR]

Run after `python -m sbb_duration.train`. Incidents are chosen by a fixed rule
from the test period (never seen by the evaluation models used here):
the biggest model win over the SBB announcement, the biggest win among
incidents whose advice changes over time, and the biggest loss (a counterexample). Prints a table per incident
and writes output/replay/replay-0N.json, labelled MOCK, shaped like the
POST /api/disruptions/outlook response so the frontend can render it.
"""
import argparse
import json
import math
from pathlib import Path

import numpy as np
import pandas as pd

from .advice import advice
from .artifact import finalize
from .data import DATA_DIR, OUTPUT_DIR, load_reason_labels
from .features import LOCAL_TZ
from .predict import load_bundle, predict_raw
from .train import prepare

DEFAULT_DETOUR_MIN = 60
MIN_STEPS, MAX_STEPS = 4, 12


def _round(x: float) -> int:  # half up, like Kotlin roundToInt
    return int(math.floor(x + 0.5))


def _local(ts) -> pd.Timestamp:
    return pd.Timestamp(ts).tz_convert(LOCAL_TZ)


def _iso(ts) -> str | None:
    return None if pd.isna(ts) else _local(ts).isoformat()


def _predict(rows: pd.DataFrame, bundle: dict) -> pd.DataFrame:
    """P50 and pessimistic minutes from the evaluation (split) models."""
    split_bundle = {**bundle, "models": bundle["evaluation_models"]}
    features = rows[bundle["features"]].astype(object).where(rows[bundle["features"]].notna(), None)
    raws = predict_raw([{k: (str(v) if k in bundle["categories"] else v) for k, v in r.items()}
                        for r in features.to_dict("records")], split_bundle)
    out = pd.DataFrame([finalize(*raw) for raw in raws], columns=["p50", "pessimistic"], index=rows.index)
    return rows.join(out)


def select_incidents(test: pd.DataFrame, detour: int) -> list:
    """Biggest win, biggest win whose advice changes, biggest loss (model vs SBB announcement)."""
    t = test.copy()
    t["advice"] = [advice(a, b, detour).value for a, b in zip(t.p50, t.pessimistic)]
    t["official"] = t.announced_remaining.clip(lower=0)
    t["err_official"] = (t.official - t.y_remaining).abs()
    t["err_model"] = (t.p50 - t.y_remaining).abs()
    per = t[t.has_announcement.eq(1)].groupby("id").agg(
        steps=("t", "size"), err_official=("err_official", "mean"), err_model=("err_model", "mean"),
        advice_codes=("advice", "nunique"))
    per = per[per.steps.between(MIN_STEPS, MAX_STEPS)]
    per["gain"] = per.err_official - per.err_model
    per = per.sort_values("gain", ascending=False)
    best = per.index[0]
    changing = per[(per.advice_codes > 1) & (per.index != best)]
    second = changing.index[0] if len(changing) else per.index[1]
    return [best, second, per.index[-1]]


def incident_replay(rows: pd.DataFrame, number: int, detour: int, reasons: dict, bundle: dict) -> dict:
    rows = rows.sort_values("t").drop_duplicates(["t", "announced_remaining"], keep="last")
    first = rows.iloc[0]
    steps = []
    for r in rows.itertuples():
        p50, pessimistic = _round(r.p50), math.ceil(r.pessimistic)
        observed = _local(r.t)
        official = None if math.isnan(r.announced_remaining) else _round(max(r.announced_remaining, 0))
        steps.append({
            "observedAt": observed.isoformat(),
            "updatesSoFar": int(r.n_updates_so_far),
            "announcedEndAt": _iso(r.duration_to),
            "officialRemainingMinutes": official,
            "outlook": {
                "status": "AVAILABLE",
                "remainingMinutes": {"p50": p50, "pessimistic": pessimistic},
                "expectedEndAt": {"p50": (observed + pd.Timedelta(minutes=p50)).isoformat(),
                                  "pessimistic": (observed + pd.Timedelta(minutes=pessimistic)).isoformat()},
                "advice": {"code": advice(r.p50, r.pessimistic, detour).value, "detourExtraMinutes": detour},
            },
            "actualRemainingMinutes": _round(r.y_remaining),
        })
    errors = lambda key: [abs(s[key] - s["actualRemainingMinutes"]) for s in steps if s[key] is not None]
    model_err = [abs(s["outlook"]["remainingMinutes"]["p50"] - s["actualRemainingMinutes"]) for s in steps]
    return {
        "dataStatus": "MOCK",
        "kind": "HISTORICAL_REPLAY",
        "replay": number,
        "note": "Past SBB liveticker incident replayed version by version. Not live data. The detour cost "
                "is hypothetical. Predictions come from models trained only on incidents before "
                f"{bundle['metrics']['evaluation']['split']['cutoff'][:10]}, which never saw this incident.",
        "incident": {
            "reasonCode": first.src_reason_id_fk,
            "reasonLabel": reasons.get(first.src_reason_id_fk),
            "durationType": first.src_duration_type,
            "summaryEn": first.summary_en,
            "startedAt": _iso(first.start),
            "actualEndAt": _iso(first.end),
            "actualDurationMinutes": _round((first.end - first.start).total_seconds() / 60),
        },
        "detourExtraMinutes": detour,
        "model": {"modelVersion": "duration-v1 (evaluation split)", "kind": "ESTIMATED",
                  "pessimisticQuantile": bundle["metrics"]["quantiles"]["pessimistic"],
                  "pessimisticCoverage": bundle["metrics"]["evaluation"]["test"]["pessimisticCoverage"]},
        "summary": {"steps": len(steps),
                    "meanAbsErrorMinutes": {"officialSbb": float(np.mean(errors("officialRemainingMinutes"))),
                                            "modelP50": float(np.mean(model_err))}},
        "steps": steps,
    }


def print_replay(r: dict) -> None:
    inc = r["incident"]
    start, end = pd.Timestamp(inc["startedAt"]), pd.Timestamp(inc["actualEndAt"])
    print(f"\n=== Replay {r['replay']} [MOCK, historical]: {inc['summaryEn']}")
    print(f"    cause: {inc['reasonLabel']} ({inc['reasonCode']}) | started {start:%Y-%m-%d %H:%M} | "
          f"actually ended {end:%H:%M} ({inc['actualDurationMinutes']} min) | hypothetical detour "
          f"+{r['detourExtraMinutes']} min")
    print(f"    {'time':>5} | {'SBB says left':>13} | {'model p50':>9} | {'pessimistic':>11} | "
          f"{'advice':<18} | {'actually left':>13}")
    for s in r["steps"]:
        official = "-" if s["officialRemainingMinutes"] is None else f"{s['officialRemainingMinutes']} min"
        rm = s["outlook"]["remainingMinutes"]
        print(f"    {pd.Timestamp(s['observedAt']):%H:%M} | {official:>13} | {rm['p50']:>5} min | "
              f"{rm['pessimistic']:>7} min | {s['outlook']['advice']['code']:<18} | "
              f"{s['actualRemainingMinutes']:>9} min")
    mae = r["summary"]["meanAbsErrorMinutes"]
    print(f"    mean error: SBB announcement {mae['officialSbb']:.0f} min | model P50 {mae['modelP50']:.0f} min")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--detour", type=int, default=DEFAULT_DETOUR_MIN,
                        help="hypothetical extra travel time of the detour, in minutes")
    parser.add_argument("--data-dir", type=Path, default=DATA_DIR)
    parser.add_argument("--output-dir", type=Path, default=OUTPUT_DIR)
    args = parser.parse_args(argv)

    bundle = load_bundle(args.output_dir / "duration_model.joblib")
    _, _, _, test, _ = prepare(args.data_dir)
    test = _predict(test, bundle)
    reasons = load_reason_labels(args.data_dir)
    out_dir = args.output_dir / "replay"
    out_dir.mkdir(parents=True, exist_ok=True)
    for number, incident_id in enumerate(select_incidents(test, args.detour), start=1):
        replay = incident_replay(test[test.id == incident_id], number, args.detour, reasons, bundle)
        print_replay(replay)
        path = out_dir / f"replay-{number:02d}.json"
        path.write_text(json.dumps(replay, indent=1, ensure_ascii=False, allow_nan=False), encoding="utf-8")
        print(f"    -> {path}")


if __name__ == "__main__":
    main()
