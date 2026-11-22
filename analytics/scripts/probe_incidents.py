"""Abort-check before the Phase-2 build: reconstruct incident windows from the audit trail and count
how many overlap the Ist-Daten window (2026-01-01 .. 2026-10-31). Plan §6 step 1: stop if < 20."""
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
import pandas as pd
from analytics.corridors import add_corridors, build_station_table, fold
from analytics.duration_model import add_start_end, load_versions

DATA = Path(os.path.expanduser("~/Downloads/Daten/extern-liveticker"))

v = add_start_end(load_versions(DATA))
inc = v.groupby("id").first().reset_index()
print("incidents total:", len(inc))
print("windows: start", inc.start.min(), "..", inc.end.max())

lo = pd.Timestamp("2026-01-01", tz="UTC")
hi = pd.Timestamp("2026-11-01", tz="UTC")
ov = inc[(inc.start < hi) & (inc.end > lo)]
print("overlapping Jan-Oct 2026:", len(ov))
print("with duration>=1min:", int(((ov.end - ov.start).dt.total_seconds() >= 60).sum()))

canon, _ = build_station_table(
    list(inc.description_en.fillna("")) + list(inc.description_de.fillna(""))
)
inc = inc.rename(columns={"description_en": "desc_en", "description_de": "desc_de"})
from analytics.scripts.build_delay_estimates import STATIONS  # noqa: E402

wl = {fold(s) for s in STATIONS}
inc2 = add_corridors(inc, canon)
inc2["stops_wl"] = inc2.stops.apply(lambda ss: [s for s in ss if fold(s) in wl] if ss else [])
inc2["n_wl"] = inc2.stops_wl.apply(len)
ov2 = inc2[(inc2.start < hi) & (inc2.end > lo)]
print("overlapping with >=1 whitelisted corridor station:", int((ov2.n_wl >= 1).sum()))
print("with >=2 whitelisted:", int((ov2.n_wl >= 2).sum()))
print("by month:")
print(ov2.groupby(ov2.start.dt.tz_convert("Europe/Zurich").dt.month).size().to_string())
