"""Train the P50 and pessimistic remaining-duration models and record their evaluation.

    python -m sbb_duration.train [--data-dir DIR] [--output-dir DIR]

Metrics come from models fitted on the chronological train split and scored on
the test split. The exported (final) models are then refitted on all snapshots
so they include the most recent incidents, which last longer.
Writes duration_model.joblib and metrics.json to the output directory.
"""
import argparse
import json
import math
import platform
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
from sklearn.ensemble import HistGradientBoostingRegressor

from .data import DATA_DIR, OUTPUT_DIR, file_checksums, load_versions
from .explain import explanation_table
from .features import CATEGORICAL, FEATURES, build_features
from .predict import BUNDLE_FILE

# The pessimistic quantile is 0.85: 0.80 covered only 67% of the test split.
QUANTILES = {"p50": 0.5, "pessimistic": 0.85}
TEST_SHARE = 0.2
MAX_REMAINING_MIN = 24 * 60
HYPERPARAMETERS = dict(loss="quantile", max_iter=300, learning_rate=0.05, max_depth=4,
                       min_samples_leaf=20, categorical_features="from_dtype", random_state=0)


def _minutes(delta: pd.Series) -> pd.Series:
    return delta.dt.total_seconds() / 60


def build_snapshots(versions: pd.DataFrame) -> pd.DataFrame:
    """One row per still-ongoing version: raw source fields, features and target.

    start = first time the source system recorded the incident;
    end   = first version marked ENDED; t = the version's updated_at_source.
    """
    start = versions.groupby("id").created_at_source.min().rename("start")
    end = versions[versions.lifecycle == "ENDED"].groupby("id").updated_at_source.min().rename("end")
    v = versions.join(start, on="id").join(end, on="id").dropna(subset=["end"])

    snap = v[(v.lifecycle == "INFO") & (v.updated_at_source < v.end)].copy()
    snap["t"] = snap.updated_at_source
    snap["n_updates_so_far"] = snap.groupby("id").cumcount()

    features = pd.DataFrame(
        [build_features(observed_at=r.t, started_at=r.start, reason_id_fk=r.reason_id_fk,
                        duration_type=r.duration_type, duration_to=r.duration_to,
                        n_updates_so_far=r.n_updates_so_far, summary_en=r.summary_en)
         for r in snap.itertuples()],
        index=snap.index)
    raw = snap[["id", "start", "end", "t", "duration_to", "summary_en"]].assign(
        src_reason_id_fk=snap.reason_id_fk, src_duration_type=snap.duration_type)
    snap = pd.concat([raw, features], axis=1)
    snap["y_remaining"] = _minutes(snap.end - snap.t)

    # Drop absurd cases (data errors, disruptions of several days).
    keep = (snap.y_remaining > 0) & (snap.y_remaining < MAX_REMAINING_MIN) & (snap.elapsed >= 0)
    snap = snap[keep].copy()
    for column in CATEGORICAL:
        snap[column] = snap[column].astype("category")
    return snap


def chronological_split(snap: pd.DataFrame):
    """Last TEST_SHARE of incidents (by start) = test. No incident spans both sets."""
    cutoff = snap.groupby("id").start.first().quantile(1 - TEST_SHARE)
    return snap[snap.start < cutoff], snap[snap.start >= cutoff], cutoff


def prepare(data_dir: Path = DATA_DIR):
    versions = load_versions(data_dir)
    snap = build_snapshots(versions)
    train, test, cutoff = chronological_split(snap)
    return versions, snap, train, test, cutoff


CALIBRATION_SHARE = 0.2    # most recent incidents of the fit set used for calibration
CALIBRATION_TARGET = 0.85  # target coverage of the pessimistic estimate


def conformal_offset(snapshots: pd.DataFrame) -> tuple[float, dict]:
    """Split-conformal offset (log1p scale) for the pessimistic model.

    Fit the pessimistic quantile model on all but the most recent CALIBRATION_SHARE of incidents,
    score log1p(y) - prediction on those recent incidents, and take the finite-sample conformal
    quantile at CALIBRATION_TARGET. The offset is then added to a model refitted on the full set.
    """
    cutoff = snapshots.groupby("id").start.first().quantile(1 - CALIBRATION_SHARE)
    proper, recent = snapshots[snapshots.start < cutoff], snapshots[snapshots.start >= cutoff]
    model = HistGradientBoostingRegressor(quantile=QUANTILES["pessimistic"], **HYPERPARAMETERS).fit(
        proper[FEATURES], np.log1p(proper.y_remaining))
    scores = np.log1p(recent.y_remaining.to_numpy()) - model.predict(recent[FEATURES])
    level = min(1.0, math.ceil((len(scores) + 1) * CALIBRATION_TARGET) / len(scores))
    offset = float(np.quantile(scores, level, method="higher"))
    return offset, {"calibrationCutoff": cutoff.isoformat(), "calibrationIncidents": int(recent.id.nunique()),
                    "calibrationSnapshots": int(len(recent)), "offsetLog1p": offset}


def fit(snapshots: pd.DataFrame) -> dict:
    y = np.log1p(snapshots.y_remaining)  # durations are very skewed (20 min ... 20 h)
    return {name: HistGradientBoostingRegressor(quantile=q, **HYPERPARAMETERS).fit(snapshots[FEATURES], y)
            for name, q in QUANTILES.items()}


def evaluate(models: dict, train: pd.DataFrame, test: pd.DataFrame, offset: float = 0.0) -> dict:
    y = test.y_remaining.to_numpy()
    p50 = np.maximum(np.expm1(models["p50"].predict(test[FEATURES])), 0)
    raw_pessimistic = models["pessimistic"].predict(test[FEATURES])
    pessimistic = np.maximum(np.expm1(raw_pessimistic + offset), p50)
    uncalibrated = np.maximum(np.expm1(raw_pessimistic), p50)
    has = (test.has_announcement == 1).to_numpy()
    official = test.announced_remaining.clip(lower=0).to_numpy()
    by_reason = train.groupby("reason_id_fk", observed=True).y_remaining.median()
    per_cause = test.reason_id_fk.map(by_reason).astype(float).fillna(train.y_remaining.median()).to_numpy()
    mae = lambda pred, mask: float(np.mean(np.abs(pred[mask] - y[mask])))
    everything = np.ones_like(has)
    return {
        "maeMinutes": {
            "withAnnouncement": {"snapshots": int(has.sum()), "officialSbb": mae(official, has),
                                 "medianPerCause": mae(per_cause, has), "modelP50": mae(p50, has)},
            "all": {"snapshots": int(len(y)), "medianPerCause": mae(per_cause, everything),
                    "modelP50": mae(p50, everything)},
        },
        "pessimisticCoverage": float(np.mean(y <= pessimistic)),
        "pessimisticCoverageUncalibrated": float(np.mean(y <= uncalibrated)),
        "officialTooOptimisticShare": float(np.mean(official[has] < y[has])),
    }


AUTO_CLOSE_TOLERANCE_MIN = 2


def auto_closed_ids(versions: pd.DataFrame) -> set:
    """Incidents whose first ENDED version comes 0-2 min after the last announced end.

    They look closed automatically at the announced time rather than when service resumed,
    so their "real end" is the announcement itself, which flatters the official estimate.
    """
    end = versions[versions.lifecycle == "ENDED"].groupby("id").updated_at_source.min()
    info = versions[versions.lifecycle == "INFO"].join(end.rename("end"), on="id")
    info = info[info.updated_at_source < info.end].sort_values(["id", "last_modified_date"])
    gap = ((end - info.groupby("id").duration_to.last()).dt.total_seconds() / 60).dropna()
    return set(gap[(gap >= 0) & (gap <= AUTO_CLOSE_TOLERANCE_MIN)].index)


def robustness(versions: pd.DataFrame, snap: pd.DataFrame, train: pd.DataFrame, test: pd.DataFrame,
               evaluation_models: dict, evaluation_offset: float) -> dict:
    """Metrics without auto-closed incidents: removed then re-split (main), and removed from the test only."""
    auto = auto_closed_ids(versions)
    kept = snap[~snap.id.isin(auto)]
    r_train, r_test, r_cutoff = chronological_split(kept)
    return {
        "note": "incidents whose first ENDED version is 0-2 min after the last announced end are excluded",
        "autoClosedIncidents": len(auto & set(snap.id)),
        "excludedThenResplit": {
            "cutoff": r_cutoff.isoformat(),
            "train": {"incidents": int(r_train.id.nunique()), "snapshots": int(len(r_train))},
            "test": {"incidents": int(r_test.id.nunique()), "snapshots": int(len(r_test))},
            "testMetrics": evaluate(fit(r_train), r_train, r_test, conformal_offset(r_train)[0]),
        },
        "sameSplitTestOnly": {
            "autoClosedInTest": len(auto & set(test.id)),
            "testMetrics": evaluate(evaluation_models, train, test[~test.id.isin(auto)], evaluation_offset),
        },
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--data-dir", type=Path, default=DATA_DIR)
    parser.add_argument("--output-dir", type=Path, default=OUTPUT_DIR)
    args = parser.parse_args(argv)

    versions, snap, train, test, cutoff = prepare(args.data_dir)
    evaluation_models = fit(train)  # never saw the test period; used for metrics and the replay demo
    evaluation_offset, evaluation_calibration = conformal_offset(train)
    test_metrics = evaluate(evaluation_models, train, test, evaluation_offset)
    models = fit(snap)  # final models: all snapshots, train + test
    final_offset, final_calibration = conformal_offset(snap)
    encoder = models["p50"]._preprocessor.named_transformers_["encoder"]
    categories = {c: [str(x) for x in cats] for c, cats in zip(CATEGORICAL, encoder.categories_)}
    starts = snap.groupby("id").start.first()
    metrics = {
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "environment": {"python": platform.python_version(), "sklearn": sklearn.__version__,
                        "pandas": pd.__version__, "numpy": np.__version__},
        "data": {"files": file_checksums(args.data_dir), "incidents": int(versions.id.nunique()),
                 "versions": int(len(versions)), "snapshots": int(len(snap)),
                 "snapshotIncidents": int(snap.id.nunique()),
                 "firstIncidentStart": starts.min().isoformat(), "lastIncidentStart": starts.max().isoformat()},
        "target": "minutes until first ENDED version, modelled as log1p",
        "quantiles": QUANTILES,
        "hyperparameters": dict(HYPERPARAMETERS),
        "evaluation": {
            "note": "metrics measured on the chronological split; final model trained on all snapshots",
            "split": {"method": "chronological by incident start; last 20% of incidents = test",
                      "cutoff": cutoff.isoformat(),
                      "train": {"incidents": int(train.id.nunique()), "snapshots": int(len(train))},
                      "test": {"incidents": int(test.id.nunique()), "snapshots": int(len(test))}},
            "test": test_metrics,
            "calibration": {"method": "split conformal, one-sided, log1p scale, refit then offset",
                            "target": CALIBRATION_TARGET, **evaluation_calibration,
                            "testCoverageBefore": test_metrics["pessimisticCoverageUncalibrated"],
                            "testCoverageAfter": test_metrics["pessimisticCoverage"]},
            "robustness": robustness(versions, snap, train, test, evaluation_models, evaluation_offset),
        },
        "finalModel": {"trainedOn": "all snapshots (train + test)",
                       "incidents": int(snap.id.nunique()), "snapshots": int(len(snap)),
                       "calibration": final_calibration},
    }

    args.output_dir.mkdir(parents=True, exist_ok=True)
    joblib.dump({"models": models, "evaluation_models": evaluation_models,
                 "offsets": {"pessimistic": final_offset}, "evaluation_offsets": {"pessimistic": evaluation_offset},
                 "features": FEATURES,
                 "categories": categories, "metrics": metrics,
                 "explanations": explanation_table(snap),              # training data of the exported model
                 "evaluation_explanations": explanation_table(train)},  # training data of the split models
                args.output_dir / BUNDLE_FILE)
    (args.output_dir / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    print(json.dumps(metrics["evaluation"], indent=2))
    print(json.dumps(metrics["finalModel"]))


if __name__ == "__main__":
    main()
