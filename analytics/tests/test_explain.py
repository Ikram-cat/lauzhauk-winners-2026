import pandas as pd

from sbb_duration.explain import explain, explanation_table


def test_explanation_falls_back_on_all_causes_for_rare_causes():
    t0 = pd.Timestamp("2026-01-01T08:00Z")
    rows = []
    for i in range(6):  # 5 incidents of EG_008, 1 of EG_011
        reason = "EG_008" if i < 5 else "EG_011"
        rows.append({"id": str(i), "reason_id_fk": reason, "start": t0, "end": t0 + pd.Timedelta(minutes=60 * (i + 1)),
                     "has_announcement": 1, "y_remaining": 50.0, "announced_remaining": 40.0})
    table = explanation_table(pd.DataFrame(rows))
    common = explain(table, "EG_008")
    assert common["scope"] == "REASON" and common["similarIncidents"] == 5 and common["medianDurationMinutes"] == 180
    assert common["medianEndMinusAnnouncedMinutes"] == 10
    rare = explain(table, "EG_011")
    assert rare["scope"] == "ALL_REASONS" and rare["similarIncidents"] == 6 and rare["reasonCode"] == "EG_011"
