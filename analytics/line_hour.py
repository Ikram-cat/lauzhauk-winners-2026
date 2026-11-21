"""byLineHour table: observed disruption frequency per (line corridor, local hour)."""
from __future__ import annotations

import re

import pandas as pd

from .duration_model import TZ

# "The train service on the Genève - Lausanne line between ..." / "auf der Strecke X - Y ..."
_EN = re.compile(r"\bon the (.+?) line\b")
_DE = re.compile(r"\bauf der Strecke (.+?) (?:ist|sind|wurde|zwischen|ab|nach)\b")


def line_key(desc_en, desc_de):
    """Line corridor as printed in the text ('Lausanne - Brig'), or None. NOT an OJP lineRef."""
    for rx, s in ((_EN, desc_en), (_DE, desc_de)):
        if isinstance(s, str) and (m := rx.search(s)):
            k = re.sub(r"^line ", "", m.group(1).split(",")[0].strip())  # "line A - B, between ..." glitch
            return k
    return None


def confidence(n: int) -> str:
    return "high" if n >= 20 else "medium" if n >= 8 else "low"


def incidents_table(v: pd.DataFrame) -> pd.DataFrame:
    """One row per ENDED incident with key, start, end (v: output of add_start_end)."""
    v = v[v.lifecycle != "DELETED"]
    g = v.groupby("id")
    inc = pd.DataFrame({
        "start": g.start.first(), "end": g.end.first(),
        "desc_en": g.description_en.agg(lambda s: s.dropna().iloc[0] if s.notna().any() else None),
        "desc_de": g.description_de.agg(lambda s: s.dropna().iloc[0] if s.notna().any() else None),
    })
    inc["key"] = [line_key(a, b) for a, b in zip(inc.desc_en, inc.desc_de)]
    inc["duration_min"] = (inc.end - inc.start).dt.total_seconds() / 60
    return inc[(inc.duration_min > 0) & (inc.duration_min < 24 * 60)]


def by_line_hour(inc: pd.DataFrame, window_days: int) -> list[dict]:
    """lateProbability = share of days in the window on which the line had an active
    disruption during that local hour; expectedDelayMin = median duration of those
    disruptions; sampleSize = distinct incidents."""
    inc = inc.dropna(subset=["key"])
    rows = []
    for key, grp in inc.groupby("key"):
        cells: dict = {}  # hour -> {"days": set, "ids": set, "dur": []}
        for iid, r in grp.iterrows():
            s, e = r.start.tz_convert(TZ), r.end.tz_convert(TZ)
            for slot in pd.date_range(s.floor("h"), e - pd.Timedelta(seconds=1), freq="h", tz=TZ):
                c = cells.setdefault(slot.hour, {"days": set(), "ids": set(), "dur": {}})
                c["days"].add(slot.date())
                c["ids"].add(iid)
                c["dur"][iid] = r.duration_min
        for h, c in sorted(cells.items()):
            n = len(c["ids"])
            durs = sorted(c["dur"].values())
            med = durs[n // 2] if n % 2 else (durs[n // 2 - 1] + durs[n // 2]) / 2
            rows.append({"key": key, "hourBucket": h,
                         "lateProbability": round(min(1.0, len(c["days"]) / window_days), 4),
                         "expectedDelayMin": round(float(med), 1),
                         "sampleSize": n, "confidence": confidence(n)})
    return rows
