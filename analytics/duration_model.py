"""Snapshot-based model: how much longer will an SBB disruption last?

Data: extern-liveticker `incident` + `incident_audit` (every row = one version).
All functions are pure and importable; `build_artifacts.py` is the entry point.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor

TZ = "Europe/Zurich"
QUANTILES = (0.5, 0.8)
CAT = ["reason_id_fk", "duration_type"]
NUM = ["elapsed", "announced_remaining", "has_announcement", "hour", "weekday",
       "n_updates_so_far", "is_interruption", "is_restriction"]
COLS = ["id", "duration_type", "duration_to", "duration_at_least", "reason_id_fk",
        "last_modified_date", "created_at_source", "updated_at_source",
        "lifecycle", "summary_en", "description_en", "description_de"]
TIME_COLS = ["duration_to", "last_modified_date", "created_at_source", "updated_at_source"]


def load_versions(data_dir) -> pd.DataFrame:
    """All versions of all incidents (audit = old, incident = current), time-parsed."""
    from pathlib import Path
    d = Path(data_dir)
    inc = pd.read_csv(next(d.glob("incident_2*.csv")), dtype={"id": str})
    aud = pd.read_csv(next(d.glob("incident_audit_*.csv")), dtype={"id": str})
    v = pd.concat([aud[COLS], inc[COLS]], ignore_index=True)
    for c in TIME_COLS:
        v[c] = pd.to_datetime(v[c], utc=True, errors="coerce")
    # README: order by last_modified_date, not `version`
    return v.sort_values(["id", "last_modified_date"]).drop_duplicates(["id", "last_modified_date"])


def add_start_end(v: pd.DataFrame) -> pd.DataFrame:
    """start = first source record; end = first ENDED version. Drops incidents without a known end."""
    start = v.groupby("id").created_at_source.min().rename("start")
    end = v[v.lifecycle == "ENDED"].groupby("id").updated_at_source.min().rename("end")
    return v.join(start, on="id").join(end, on="id").dropna(subset=["end"])


def announced_end(duration_to, duration_at_least, t):
    """Official end announced at time t (UTC Timestamp), or NaT. `duration_at_least` is a time of day."""
    if pd.notna(duration_to):
        return duration_to
    if pd.notna(duration_at_least):
        local = t.tz_convert(TZ)
        hh, mm, *_ = map(int, str(duration_at_least).split(":"))
        guess = local.normalize() + pd.Timedelta(hours=hh, minutes=mm)
        if guess < local:
            guess += pd.Timedelta(days=1)
        return guess.tz_convert("UTC")
    return pd.NaT


def build_snapshots(v: pd.DataFrame) -> pd.DataFrame:
    """One row per still-ongoing (INFO) version strictly before the incident's end.

    Only fields known at time t are features; `end` is used for the target only.
    """
    snap = v[(v.lifecycle == "INFO") & (v.updated_at_source < v.end)].copy()
    snap["t"] = snap.updated_at_source
    snap["announced_end"] = [announced_end(a, b, t) for a, b, t in
                             zip(snap.duration_to, snap.duration_at_least, snap.t)]
    mins = lambda d: d.dt.total_seconds() / 60
    snap["y_remaining"] = mins(snap.end - snap.t)
    snap["elapsed"] = mins(snap.t - snap.start)
    snap["announced_remaining"] = mins(snap.announced_end - snap.t)
    snap["has_announcement"] = snap.announced_remaining.notna().astype(int)
    local_t = snap.t.dt.tz_convert(TZ)
    snap["hour"] = local_t.dt.hour
    snap["weekday"] = local_t.dt.day_of_week
    snap["n_updates_so_far"] = snap.groupby("id").cumcount()
    s = snap.summary_en.fillna("").str.lower()
    snap["is_interruption"] = s.str.contains("interrupt|disruption|no service").astype(int)
    snap["is_restriction"] = s.str.contains("limited|restrict").astype(int)
    # drop data errors and multi-day disruptions
    return snap[(snap.y_remaining > 0) & (snap.y_remaining < 24 * 60) & (snap.elapsed >= 0)]


def chrono_split(snap: pd.DataFrame, test_frac=0.2):
    """Train on the past, test on the most recent incidents.

    Train incidents must also have ENDED before the cutoff, so no training label
    lies in the test period.
    """
    cutoff = snap.groupby("id").start.first().quantile(1 - test_frac)
    train = snap[(snap.start < cutoff) & (snap.end <= cutoff)]
    test = snap[snap.start >= cutoff]
    return train, test, cutoff


def _prep(df: pd.DataFrame, categories: dict) -> pd.DataFrame:
    X = df[CAT + NUM].copy()
    for c in CAT:
        X[c] = pd.Categorical(X[c].fillna("NA"), categories=categories[c])
    return X


def categories_of(snap: pd.DataFrame) -> dict:
    return {c: sorted(snap[c].fillna("NA").astype(str).unique()) for c in CAT}


def train_models(train: pd.DataFrame, categories: dict, seed: int = 0) -> dict:
    X, y = _prep(train, categories), np.log1p(train.y_remaining)  # skewed target
    return {q: HistGradientBoostingRegressor(
        loss="quantile", quantile=q, max_iter=300, learning_rate=0.05, max_depth=4,
        min_samples_leaf=20, categorical_features="from_dtype", random_state=seed).fit(X, y)
        for q in QUANTILES}


def predict_df(models: dict, categories: dict, df: pd.DataFrame) -> dict:
    X = _prep(df, categories)
    return {q: np.expm1(m.predict(X)) for q, m in models.items()}


def evaluate(models, categories, train, test) -> dict:
    p = predict_df(models, categories, test)
    y = test.y_remaining.to_numpy()
    has = (test.has_announcement == 1).to_numpy()
    official = test.announced_remaining.clip(lower=0).to_numpy()
    by_reason = train.groupby("reason_id_fk", observed=True).y_remaining.median()
    base = test.reason_id_fk.map(by_reason).astype(float).fillna(train.y_remaining.median()).to_numpy()
    ptr = predict_df(models, categories, train)
    mae = lambda a, b: float(np.mean(np.abs(a - b)))
    return {
        "mae_min_test_p50": mae(p[0.5], y),
        "mae_min_train_p50": mae(ptr[0.5], train.y_remaining.to_numpy()),
        "mae_min_test_baseline_median_per_reason": mae(base, y),
        "p80_coverage_test": float((y <= p[0.8]).mean()),
        "p80_coverage_train": float((train.y_remaining.to_numpy() <= ptr[0.8]).mean()),
        "n_test_with_announcement": int(has.sum()),
        "mae_min_test_announced_p50": mae(p[0.5][has], y[has]) if has.any() else None,
        "mae_min_test_announced_official": mae(official[has], y[has]) if has.any() else None,
        "mae_min_test_announced_baseline": mae(base[has], y[has]) if has.any() else None,
        "official_too_optimistic_share": float((official[has] < y[has]).mean()) if has.any() else None,
    }


def predict_remaining(features: dict, bundle: dict):
    """features: dict with CAT + NUM keys. Returns (P50, P80) minutes remaining."""
    row = pd.DataFrame([features])
    X = _prep(row, bundle["categories"])
    return tuple(float(np.expm1(bundle["models"][q].predict(X))[0]) for q in QUANTILES)


def advice(p50, p80, detour_extra_min):
    """detour_extra_min: extra travel time of the best alternative (from OJP)."""
    if detour_extra_min < p50:
        return "Take the detour: the disruption will probably last longer."
    if detour_extra_min < p80:
        return "Detour recommended if you can't afford to be late (real risk it lasts longer)."
    return "Wait: the disruption should be over before the detour pays off."
