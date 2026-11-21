"""Per-cause historical facts that explain a prediction. Values only: the UI formats them per language.

Computed on training snapshots only (the train split for the evaluation models, all snapshots for
the exported model). For a cause with fewer than MIN_INCIDENTS incidents, the all-causes row is used.
"""
import math

import pandas as pd

MIN_INCIDENTS = 5


def _row(incidents: pd.DataFrame, announced: pd.DataFrame) -> dict:
    gap = announced.end_minus_announced
    return {
        "similarIncidents": int(len(incidents)),
        "medianDurationMinutes": int(round(float(incidents.duration.median()))),
        "announcementsCompared": int(len(gap)),
        # actual end minus announced end, over every announcement made during these incidents (> 0: later)
        "medianEndMinusAnnouncedMinutes": None if gap.empty else int(round(float(gap.median()))),
    }


def explanation_table(snapshots: pd.DataFrame) -> dict:
    s = snapshots.assign(reason=snapshots.reason_id_fk.astype(str))
    incidents = s.groupby("id").agg(reason=("reason", "first"), start=("start", "first"), end=("end", "first"))
    incidents["duration"] = (incidents.end - incidents.start).dt.total_seconds() / 60
    announced = s[s.has_announcement == 1].assign(end_minus_announced=lambda d: d.y_remaining - d.announced_remaining)
    return {
        "minIncidents": MIN_INCIDENTS,
        "all": _row(incidents, announced),
        "byReason": {reason: _row(group, announced[announced.reason == reason])
                     for reason, group in incidents.groupby("reason")},
    }


def explain(table: dict, reason_code) -> dict:
    """Explanation for one cause, falling back on all causes when the cause is rare or unknown."""
    reason = None if reason_code is None or (isinstance(reason_code, float) and math.isnan(reason_code)) \
        else str(reason_code)
    row = table["byReason"].get(reason) if reason else None
    if row and row["similarIncidents"] >= table["minIncidents"]:
        return {"reasonCode": reason, "scope": "REASON", **row, "source": "TRAINING_DATA"}
    return {"reasonCode": reason, "scope": "ALL_REASONS", **table["all"], "source": "TRAINING_DATA"}

