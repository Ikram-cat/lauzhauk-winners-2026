"""Feature derivation, shared by training, prediction and export.

Only information known at the observation instant is used. The Kotlin backend
re-implements `build_features`; golden_vectors.json checks both agree.
"""
import math
import re

import pandas as pd

LOCAL_TZ = "Europe/Zurich"
MISSING_CATEGORY = "NA"

CATEGORICAL = ["reason_id_fk", "duration_type"]
NUMERIC = ["elapsed", "announced_remaining", "has_announcement", "hour", "weekday",
           "n_updates_so_far", "is_interruption", "is_restriction"]
FEATURES = CATEGORICAL + NUMERIC

# Matched with re.search on the lower-cased English summary.
TEXT_FLAGS = {
    "is_interruption": "interrupt|disruption|no service",
    "is_restriction": "limited|restrict",
}


def _timestamp(value):
    """UTC timestamp, or None when the value is missing."""
    if value is None or (isinstance(value, float) and math.isnan(value)) or pd.isna(value):
        return None
    ts = pd.Timestamp(value)
    if ts.tzinfo is None:
        raise ValueError(f"timestamp without offset: {value!r}")
    return ts.tz_convert("UTC")


def _minutes(delta: pd.Timedelta) -> float:
    return delta.total_seconds() / 60


def announced_end(duration_to=None):
    """Official end announced at the observation instant, or None.

    Only `duration_to` is used: in every source version where the time-of-day
    field `duration_at_least` is set, `duration_to` is set too (checked on all
    5,305 versions), so that field adds no information.
    """
    return _timestamp(duration_to)


def build_features(*, observed_at, started_at, reason_id_fk, duration_type,
                   duration_to=None, n_updates_so_far=0,
                   summary_en=None) -> dict:
    """Model features of one incident version observed at `observed_at`."""
    t = _timestamp(observed_at)
    start = _timestamp(started_at)
    end = announced_end(duration_to)
    local = t.tz_convert(LOCAL_TZ)
    summary = "" if summary_en is None or pd.isna(summary_en) else str(summary_en).lower()
    features = {
        "reason_id_fk": MISSING_CATEGORY if reason_id_fk is None or pd.isna(reason_id_fk) else str(reason_id_fk),
        "duration_type": MISSING_CATEGORY if duration_type is None or pd.isna(duration_type) else str(duration_type),
        "elapsed": _minutes(t - start),
        "announced_remaining": math.nan if end is None else _minutes(end - t),
        "has_announcement": 0 if end is None else 1,
        "hour": local.hour,
        "weekday": local.weekday(),  # Monday = 0
        "n_updates_so_far": int(n_updates_so_far),
    }
    for name, pattern in TEXT_FLAGS.items():
        features[name] = 1 if re.search(pattern, summary) else 0
    return features


def build_features_from_input(inp: dict) -> dict:
    """Same as build_features, from the camelCase input used by the API and golden vectors."""
    return build_features(
        observed_at=inp["observedAt"], started_at=inp["startedAt"],
        reason_id_fk=inp.get("reasonCode"), duration_type=inp.get("durationType"),
        duration_to=inp.get("announcedEndAt"),
        n_updates_so_far=inp.get("updatesSoFar", 0), summary_en=inp.get("summaryEn"))
