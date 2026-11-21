"""Demo: how much longer will this incident last?  Loads the joblib bundle only (no pandas at call time).

    python -m analytics.predict_duration --elapsed-min 95 --announced-remaining-min 60 --hour 14 --weekday 5
    python -m analytics.predict_duration --from-snapshot-file incident.json

Advice rule (see README): "consider detour" if pessimistic (P80) remaining >= DETOUR_EXTRA_MIN, else "wait".
"""
from __future__ import annotations

import argparse
import json
import warnings
from datetime import datetime
from pathlib import Path

import joblib
import numpy as np

ART = Path(__file__).parent / "artifacts"
DETOUR_EXTRA_MIN = 60  # extra minutes a detour costs; unmeasured default, see README. Override: --detour-extra-min
FIELDS = ("elapsed_min", "announced_remaining_min", "hour", "weekday", "reason_id", "duration_type")


def predict(bundle: dict, f: dict, detour_extra_min: float = DETOUR_EXTRA_MIN) -> tuple[float, float, str]:
    r = f.get("reason_id")
    reason = f"EG_{int(r):03d}" if str(r).isdigit() else (r or "NA")  # unknown ids are encoded as NaN by the model
    ann = f.get("announced_remaining_min")
    now = datetime.now()
    row = {"reason_id_fk": reason, "duration_type": f.get("duration_type") or ("UNTIL" if ann is not None else "UNKNOWN"),
           "elapsed": f["elapsed_min"], "announced_remaining": np.nan if ann is None else ann,
           "has_announcement": int(ann is not None), "hour": f.get("hour", now.hour),
           "weekday": f.get("weekday", now.weekday()), "n_updates_so_far": f.get("n_updates_so_far", 0),
           "is_interruption": f.get("is_interruption", 1), "is_restriction": f.get("is_restriction", 0)}
    x = np.array([[row[c] for c in bundle["features"]]], dtype=object)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")  # fitted on a DataFrame, predicted on an array: same column order
        p50, p80 = (float(np.expm1(bundle["models"][q].predict(x))[0]) for q in (0.5, 0.8))
    p80 = max(p80, p50)  # independent quantile models can cross
    return p50, p80, ("consider detour" if p80 >= detour_extra_min else "wait")


def main(argv=None) -> dict:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--elapsed-min", type=float)
    ap.add_argument("--announced-remaining-min", type=float)
    ap.add_argument("--hour", type=int, help="local hour 0-23 (default: now)")
    ap.add_argument("--weekday", type=int, help="0=Mon..6=Sun (default: today)")
    ap.add_argument("--reason-id", help="e.g. 3 or EG_003")
    ap.add_argument("--from-snapshot-file", help="JSON with the same fields in snake_case (elapsed_min, ...)")
    ap.add_argument("--detour-extra-min", type=float, default=DETOUR_EXTRA_MIN)
    ap.add_argument("--artifacts", default=str(ART))
    a = ap.parse_args(argv)
    f = json.loads(Path(a.from_snapshot_file).read_text()) if a.from_snapshot_file else {}
    f.update({k: v for k, v in vars(a).items() if k in FIELDS and v is not None})
    if "elapsed_min" not in f:
        ap.error("--elapsed-min (or elapsed_min in --from-snapshot-file) is required")
    bundle = joblib.load(Path(a.artifacts) / "incident_duration.joblib")
    version = json.loads((Path(a.artifacts) / "incident_duration.json").read_text())["modelVersion"]
    p50, p80, adv = predict(bundle, f, a.detour_extra_min)
    out = {"medianRemainingMin": round(p50), "pessimisticRemainingMin": round(p80), "advice": adv, "modelVersion": version}
    print(json.dumps(out))
    return out


if __name__ == "__main__":
    main()
