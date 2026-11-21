# %% [markdown]
# # Predicting the real end of an SBB disruption
#
# Goal: at any moment during a disruption, using only what is known at that
# moment, predict how much longer it will last. The prediction is given as a
# range (median and pessimistic estimate), so the app can decide
# "wait" or "take the detour".
#
# Data: public liveticker (Daten/extern-liveticker), 2024-2026.
# Every row of `incident_audit` + `incident` = one version of an incident,
# i.e. what the public knew at that moment.
#
# Open in VS Code: each `# %%` block is a cell (Run Cell).

# %% 1. Imports and paths
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor

DATA = Path(__file__).parent / "Daten/Daten/extern-liveticker" if "__file__" in globals() else Path("analytics/Daten/Daten/extern-liveticker")
OUT = Path("model")
OUT.mkdir(exist_ok=True)

# %% 2. Load every version of every incident
cols = ["id", "duration_type", "duration_to", "duration_at_least", "reason_id_fk",
        "last_modified_date", "created_at_source", "updated_at_source",
        "lifecycle", "summary_en"]

inc = pd.read_csv(DATA / "incident_202610121914.csv", dtype={"id": str})
aud = pd.read_csv(DATA / "incident_audit_202610121914.csv", dtype={"id": str})
reasons = pd.read_csv(DATA / "reason_202610121034.csv")

# Full history = old versions (audit) + current version (incident)
v = pd.concat([aud[cols], inc[cols]], ignore_index=True)
for c in ["duration_to", "last_modified_date", "created_at_source", "updated_at_source"]:
    v[c] = pd.to_datetime(v[c], utc=True, errors="coerce")
# README: order versions by last_modified_date, not by `version`
v = v.sort_values(["id", "last_modified_date"]).drop_duplicates(["id", "last_modified_date"])
print(f"{v.id.nunique()} incidents, {len(v)} versions")

# %% 3. Target: real start and real end of each incident
# start = first time the source system (VIA) recorded it
# end   = first version marked ENDED (SBB declares the disruption over)
start = v.groupby("id").created_at_source.min().rename("start")
end = v[v.lifecycle == "ENDED"].groupby("id").updated_at_source.min().rename("end")
v = v.join(start, on="id").join(end, on="id")
v = v.dropna(subset=["end"])  # keep only incidents whose end we know

# %% 4. Snapshots: what was known at each moment while still ongoing
# Each still-ongoing version becomes one training example.
# t = moment of the snapshot. Target = minutes remaining until the real end.
snap = v[(v.lifecycle == "INFO") & (v.updated_at_source < v.end)].copy()
snap["t"] = snap.updated_at_source


def announced_end(row):
    """Official end announced at that moment (None if unknown)."""
    if pd.notna(row.duration_to):
        return row.duration_to
    if pd.notna(row.duration_at_least):  # time of day only, e.g. "15:00:00"
        local = row.t.tz_convert("Europe/Zurich")
        hh, mm, *_ = map(int, str(row.duration_at_least).split(":"))
        guess = local.normalize() + pd.Timedelta(hours=hh, minutes=mm)
        if guess < local:
            guess += pd.Timedelta(days=1)
        return guess.tz_convert("UTC")
    return pd.NaT


snap["announced_end"] = snap.apply(announced_end, axis=1)

mins = lambda d: d.dt.total_seconds() / 60
snap["y_remaining"] = mins(snap.end - snap.t)                      # TARGET
snap["elapsed"] = mins(snap.t - snap.start)                        # how long it has lasted so far
snap["announced_remaining"] = mins(snap.announced_end - snap.t)    # what SBB says is left
snap["has_announcement"] = snap.announced_remaining.notna().astype(int)
local_t = snap.t.dt.tz_convert("Europe/Zurich")
snap["hour"] = local_t.dt.hour
snap["weekday"] = local_t.dt.weekday
snap["n_updates_so_far"] = snap.groupby("id").cumcount()
s = snap.summary_en.fillna("").str.lower()
snap["is_interruption"] = s.str.contains("interrupt|disruption|no service").astype(int)
snap["is_restriction"] = s.str.contains("limited|restrict").astype(int)

# Drop absurd cases (data errors, disruptions of several days)
snap = snap[(snap.y_remaining > 0) & (snap.y_remaining < 24 * 60) & (snap.elapsed >= 0)]
print(f"{len(snap)} snapshots from {snap.id.nunique()} incidents")

# %% 5. Features and time-based split
# Split by TIME, not randomly: train on the past, test on the most recent
# incidents. A random split would let versions of the same incident leak
# into both sets and give falsely good results.
CAT = ["reason_id_fk", "duration_type"]
NUM = ["elapsed", "announced_remaining", "has_announcement", "hour", "weekday",
       "n_updates_so_far", "is_interruption", "is_restriction"]
for c in CAT:
    snap[c] = snap[c].fillna("NA").astype("category")

cutoff = snap.groupby("id").start.first().quantile(0.8)  # last 20% of incidents = test
train = snap[snap.start < cutoff]
test = snap[snap.start >= cutoff]
print(f"train: {train.id.nunique()} incidents | test: {test.id.nunique()} incidents (after {cutoff:%Y-%m-%d})")

X_tr, X_te = train[CAT + NUM], test[CAT + NUM]
# Predict log(minutes): durations are very skewed (20 min ... 20 h)
y_tr, y_te = np.log1p(train.y_remaining), test.y_remaining

# %% 6. Models: median (P50) and pessimistic estimate (P80)
models = {}
for q in [0.5, 0.8]:
    m = HistGradientBoostingRegressor(loss="quantile", quantile=q, max_iter=300,
                                      learning_rate=0.05, max_depth=4,
                                      min_samples_leaf=20, categorical_features="from_dtype",
                                      random_state=0)
    m.fit(X_tr, y_tr)
    models[q] = m
pred50 = np.expm1(models[0.5].predict(X_te))
pred80 = np.expm1(models[0.8].predict(X_te))

# %% 7. Evaluation: are we better than the official SBB estimate?
mae = lambda p: np.nanmean(np.abs(p - y_te))
has = test.has_announcement == 1

# Baseline A: official announcement (only where one exists)
official = test.announced_remaining.clip(lower=0)
# Baseline B: median remaining time per cause, learned on train
by_reason = train.groupby("reason_id_fk", observed=True).y_remaining.median()
reason_base = test.reason_id_fk.map(by_reason).astype(float).fillna(train.y_remaining.median())

print("Mean error (minutes) on test, snapshots WITH an official announcement:")
print(f"  official SBB estimate : {np.nanmean(np.abs(official[has] - y_te[has])):6.0f}")
print(f"  median per cause      : {np.nanmean(np.abs(reason_base[has] - y_te[has])):6.0f}")
print(f"  our model (P50)       : {np.nanmean(np.abs(pred50[has.values] - y_te[has])):6.0f}")
print(f"All test snapshots: median-per-cause {mae(reason_base):.0f} | model {mae(pred50):.0f}")
print(f"P80 coverage (should be ~80%): {(y_te <= pred80).mean():.0%}")
print(f"Official estimate too optimistic in {(official[has] < y_te[has]).mean():.0%} of cases")

# %% 8. Save and prediction function for the app
joblib.dump({"models": models, "features": CAT + NUM,
             "categories": {c: list(snap[c].cat.categories) for c in CAT}},
            OUT / "duration_model.joblib")


def predict_remaining(features: dict, bundle=None):
    """features: dict with the CAT + NUM keys. Returns (P50, P80) in minutes."""
    bundle = bundle or joblib.load(OUT / "duration_model.joblib")
    row = pd.DataFrame([features])
    for c, cats in bundle["categories"].items():
        row[c] = pd.Categorical(row[c], categories=cats)
    row = row[bundle["features"]]
    return tuple(float(np.expm1(bundle["models"][q].predict(row))[0]) for q in (0.5, 0.8))


# Example: overhead-line failure, started 20 min ago, SBB announces 60 min left
p50, p80 = predict_remaining({
    "reason_id_fk": "EG_008", "duration_type": "UNTIL", "elapsed": 20,
    "announced_remaining": 60, "has_announcement": 1, "hour": 8, "weekday": 1,
    "n_updates_so_far": 2, "is_interruption": 1, "is_restriction": 0})
print(f"SBB says 60 min left -> model: probably {p50:.0f} min, up to {p80:.0f} min (P80)")

# %% 9. From prediction to advice (simple rule to plug into the app)
def advice(p50, p80, detour_extra_min):
    """detour_extra_min: extra travel time of the best alternative (from OJP)."""
    if detour_extra_min < p50:
        return "Take the detour: the disruption will probably last longer."
    if detour_extra_min < p80:
        return "Detour recommended if you can't afford to be late (real risk it lasts longer)."
    return "Wait: the disruption should be over before the detour pays off."


print(advice(p50, p80, detour_extra_min=35))