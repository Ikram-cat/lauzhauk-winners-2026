"""Export the trained bundle to a JSON artifact the Kotlin backend can evaluate.

    python -m sbb_duration.export [--data-dir DIR] [--output-dir DIR]

Writes duration_model.v1.json (trees, categories, regexes, metadata) and
golden_vectors.json (100 test-period cases with expected outputs).
"""
import argparse
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

from .artifact import SCHEMA_VERSION, finalize, load_artifact, predict_raw as artifact_predict_raw
from .data import DATA_DIR, OUTPUT_DIR
from .features import CATEGORICAL, FEATURES, LOCAL_TZ, MISSING_CATEGORY, TEXT_FLAGS
from .predict import load_bundle, predict_raw
from .train import prepare

ARTIFACT_FILE = f"duration_model.v{SCHEMA_VERSION}.json"
GOLDEN_FILE = "golden_vectors.json"
N_GOLDEN = 100
MODEL_VERSION = "duration-v1"

NUMERIC_DEFINITIONS = {
    "elapsed": "minutes from startedAt to observedAt",
    "announced_remaining": "minutes from observedAt to the announced end; missing when none",
    "has_announcement": "1 when an announced end exists, else 0",
    "hour": "local hour of observedAt (0-23)",
    "weekday": "local weekday of observedAt, Monday = 0",
    "n_updates_so_far": "number of earlier ongoing versions of the incident (first = 0)",
    "is_interruption": "1 when TEXT_FLAGS pattern matches the lower-cased summaryEn",
    "is_restriction": "1 when TEXT_FLAGS pattern matches the lower-cased summaryEn",
}


def _bit_set(bitset: np.ndarray, code: int) -> bool:
    return bool((int(bitset[code // 32]) >> (code % 32)) & 1)


def export_tree(predictor, n_categories: list) -> list:
    """sklearn TreePredictor -> list of JSON nodes, indexed like predictor.nodes."""
    out = []
    for node in predictor.nodes:
        if node["is_leaf"]:
            out.append({"leaf": float(node["value"])})
            continue
        split = {"feature": int(node["feature_idx"]), "missingLeft": bool(node["missing_go_to_left"]),
                 "left": int(node["left"]), "right": int(node["right"])}
        if node["is_categorical"]:
            bitset = predictor.raw_left_cat_bitsets[node["bitset_idx"]]
            split["leftCategories"] = [c for c in range(n_categories[split["feature"]]) if _bit_set(bitset, c)]
        else:
            # sklearn uses +inf when every non-missing value goes left. JSON has no Infinity:
            # +-max double is equivalent for finite inputs (missing values never reach this test).
            split["threshold"] = float(np.clip(node["num_threshold"], -sys.float_info.max, sys.float_info.max))
        out.append(split)
    return out


def export_model(model, n_categories: list) -> dict:
    # The internal preprocessor outputs encoded categoricals first, then numerics:
    # that is the FEATURES order, so node feature indexes refer to FEATURES.
    mask = list(model._preprocessor.transformers_[0][2])
    assert mask == [f in CATEGORICAL for f in FEATURES] and FEATURES[:len(CATEGORICAL)] == CATEGORICAL
    assert all(len(trees) == 1 for trees in model._predictors)
    return {"quantile": float(model.quantile),
            "baseline": float(np.ravel(model._baseline_prediction)[0]),
            "trees": [export_tree(trees[0], n_categories) for trees in model._predictors]}


def build_artifact(bundle: dict) -> dict:
    categories = bundle["categories"]
    n_categories = [len(categories[f]) if f in categories else 0 for f in FEATURES]
    metrics = bundle["metrics"]
    return {
        "schemaVersion": SCHEMA_VERSION,
        "modelVersion": MODEL_VERSION,
        "kind": "ESTIMATED",
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "trainedAt": metrics["generatedAt"],
        "provenance": {
            "source": "SBB public liveticker export (extern-liveticker), incident + incident_audit",
            "files": metrics["data"]["files"],
            "firstIncidentStart": metrics["data"]["firstIncidentStart"],
            "lastIncidentStart": metrics["data"]["lastIncidentStart"],
        },
        "target": {"name": "remaining_minutes", "unit": "minutes", "modelScale": "log1p",
                   "postprocess": "p50 = max(expm1(models.p50.baseline + sum(trees)), 0); "
                                  "pessimistic = max(expm1(models.pessimistic.baseline + sum(trees)), p50)"},
        "features": [
            {"name": f, "type": "categorical", "categories": categories[f], "missingCategory": MISSING_CATEGORY,
             "unknown": "treated as missing"} if f in categories else
            {"name": f, "type": "numeric", "definition": NUMERIC_DEFINITIONS[f]}
            for f in FEATURES],
        "derivation": {
            "timeZone": LOCAL_TZ,
            "announcedEnd": "announcedEndAt; when absent there is no announcement",
            "textFlags": {name: {"field": "summaryEn", "lowercase": True, "pattern": pattern}
                          for name, pattern in TEXT_FLAGS.items()},
        },
        "traversal": "start at node 0; missing value -> missingLeft; categorical -> left if code in "
                     "leftCategories; numeric -> left if value <= threshold (infinite sklearn thresholds are clipped to +-max double); "
                     "stop at a node with 'leaf'",
        "training": {
            "note": "metrics measured on the chronological split; final model trained on all snapshots",
            "quantiles": metrics["quantiles"],
            "finalModel": metrics["finalModel"],
        },
        "metrics": metrics["evaluation"],
        "limitations": [
            "Trained on incidents whose end is known; snapshots with remaining time >= 24 h dropped.",
            "'pessimistic' is the 0.85 quantile model, not a calibrated probability; its coverage on the "
            "test split is metrics.test.pessimisticCoverage.",
            "Metrics come from split models; the exported models also saw the test period, so they have no "
            "held-out score of their own.",
            "End = first ENDED version, i.e. when SBB declares the disruption over.",
        ],
        "explanations": bundle["explanations"],
        "models": {name: export_model(bundle["models"][name], n_categories) for name in ("p50", "pessimistic")},
    }


def _iso(ts) -> str | None:
    return None if pd.isna(ts) else pd.Timestamp(ts).tz_convert(LOCAL_TZ).isoformat()


def _json_value(value):
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (float, np.floating)):
        return None if math.isnan(value) else float(value)
    return value


def build_golden(test: pd.DataFrame, bundle: dict) -> dict:
    sample = test.sample(N_GOLDEN, random_state=0).sort_values(["start", "t"])
    cases = []
    for i, r in enumerate(sample.itertuples(), start=1):
        inp = {
            "reasonCode": r.src_reason_id_fk, "durationType": r.src_duration_type,
            "startedAt": _iso(r.start), "observedAt": _iso(r.t),
            "announcedEndAt": _iso(r.duration_to),
            "updatesSoFar": int(r.n_updates_so_far),
            "summaryEn": None if pd.isna(r.summary_en) else r.summary_en,
        }
        features = {f: _json_value(str(getattr(r, f)) if f in CATEGORICAL else getattr(r, f)) for f in FEATURES}
        cases.append({"case": f"golden-{i:03d}", "input": inp, "features": features})
    raws = predict_raw([c["features"] for c in cases], bundle)
    for case, (raw50, raw_pess) in zip(cases, raws):
        p50, pessimistic = finalize(raw50, raw_pess)
        case["expected"] = {"rawP50": raw50, "rawPessimistic": raw_pess,
                            "p50Minutes": p50, "pessimisticMinutes": pessimistic}
    return {"schemaVersion": SCHEMA_VERSION, "modelVersion": MODEL_VERSION,
            "tolerance": 1e-6, "source": "final-model sklearn predict on snapshots sampled from the test period", "cases": cases}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--data-dir", type=Path, default=DATA_DIR)
    parser.add_argument("--output-dir", type=Path, default=OUTPUT_DIR)
    args = parser.parse_args(argv)

    bundle = load_bundle(args.output_dir / "duration_model.joblib")
    artifact_path = args.output_dir / ARTIFACT_FILE
    artifact_path.write_text(json.dumps(build_artifact(bundle), separators=(",", ":"), allow_nan=False), encoding="utf-8")
    _, _, _, test, _ = prepare(args.data_dir)
    golden = build_golden(test, bundle)
    (args.output_dir / GOLDEN_FILE).write_text(json.dumps(golden, indent=1, ensure_ascii=False, allow_nan=False), encoding="utf-8")

    # Self-check: the JSON evaluation agrees with sklearn (pytest checks it too).
    artifact = load_artifact(artifact_path)
    worst = 0.0
    for case in golden["cases"]:
        raw = artifact_predict_raw(artifact, case["features"])
        worst = max(worst, abs(raw[0] - case["expected"]["rawP50"]), abs(raw[1] - case["expected"]["rawPessimistic"]))
    print(f"{artifact_path} ({artifact_path.stat().st_size / 1e6:.2f} MB), {len(golden['cases'])} golden cases, "
          f"max |JSON - sklearn| on log scale = {worst:.2e}")


if __name__ == "__main__":
    main()
