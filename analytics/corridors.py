"""Joinable risk tables: pooled corridors (no hour split), 24-row hourBaseline, station aliases."""
from __future__ import annotations

import re
import unicodedata
from collections import Counter

import pandas as pd

from .duration_model import TZ
from .line_hour import confidence, line_key

MIN_CORRIDOR_N = 3  # corridors below this are dropped
MIN_CELL_N = 8      # byLineHour cells below this are dropped

# Variants NOT observed in the CSVs (checked 2026-11-21), added for OJP/user-typed names.
# Keys and values are display names; matching is done on fold(), see joinHints.
STATIC_ALIASES = {
    "Genf": "Genève", "Geneva": "Genève", "Geneve": "Genève", "Genf Flughafen": "Genève-Aéroport",
    "Geneva Airport": "Genève-Aéroport", "Zürich": "Zürich HB", "Zurich": "Zürich HB",
    "Zurich HB": "Zürich HB", "Zürich Hauptbahnhof": "Zürich HB", "Freiburg": "Fribourg/Freiburg",
    "Fribourg": "Fribourg/Freiburg", "Biel": "Biel/Bienne", "Bienne": "Biel/Bienne",
    "Murten": "Murten/Morat", "Morat": "Murten/Morat", "Neuenburg": "Neuchâtel", "Basel": "Basel SBB",
    "Bâle": "Basel SBB", "Lucerne": "Luzern", "Berne": "Bern", "Delsberg": "Delémont", "Brigue": "Brig",
    "Sitten": "Sion", "Coire": "Chur", "Saint-Maurice": "St-Maurice", "Sankt Gallen": "St. Gallen",
}

_BETWEEN_EN = re.compile(r"\bbetween (.+?) and (.+?)(?= are\b| is\b|\s*\.|,| as well)")
_BETWEEN_DE = re.compile(r"\bzwischen (.+?) und (.+?)(?= ist\b| sind\b|\s*\.|,| sowie)")


def tidy(s: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", s)).strip()


def fold(s: str) -> str:
    """Matching key: NFKD, drop accents, casefold, non-alphanumerics -> space, collapse."""
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c)).replace("ß", "ss")
    return re.sub(r"[^a-z0-9]+", " ", s.casefold()).strip()


def parse_corridor(desc_en, desc_de):
    """-> (stops, source) with raw ordered stop names; ('line' = "line A - B", 'segment' = "between A and B")."""
    if (k := line_key(desc_en, desc_de)):
        stops = [tidy(p) for p in k.split(" - ")]
        if len(stops) >= 2 and all(stops):
            return stops, "line"
    for rx, s in ((_BETWEEN_EN, desc_en), (_BETWEEN_DE, desc_de)):
        if isinstance(s, str) and (m := rx.search(s)):
            return [tidy(m.group(1)), tidy(m.group(2))], "segment"
    return None, None


def build_station_table(raw_names) -> tuple[dict, dict]:
    """-> (canon: fold -> display name, aliases: variant -> display name).
    Canonical form = static alias target, else most frequent observed spelling per fold group."""
    cnt = Counter(tidy(n) for n in raw_names)
    canon = {fold(v): v for v in STATIC_ALIASES.values()}
    groups: dict = {}
    for n, c in cnt.items():
        groups.setdefault(fold(n), Counter())[n] += c
    for f, g in groups.items():
        canon.setdefault(f, g.most_common(1)[0][0])
    aliases = {a: t for a, t in STATIC_ALIASES.items()}
    for f, g in groups.items():  # observed spelling variants
        aliases.update({n: canon[f] for n in g if n != canon[f]})
    for a, t in STATIC_ALIASES.items():  # observed names that a static alias redirects
        canon.setdefault(fold(a), t)
    canon.update({fold(a): t for a, t in STATIC_ALIASES.items()})
    aliases.update({n: canon[fold(n)] for n in cnt if canon[fold(n)] != n})
    return canon, dict(sorted(aliases.items()))


def normalize_station(name: str, canon: dict) -> str:
    return canon.get(fold(name), tidy(name))


def _median(xs):
    xs = sorted(xs)
    n = len(xs)
    return xs[n // 2] if n % 2 else (xs[n // 2 - 1] + xs[n // 2]) / 2


def _days(r):
    s, e = r.start.tz_convert(TZ), r.end.tz_convert(TZ)
    return {d.date() for d in pd.date_range(s.normalize(), (e - pd.Timedelta(seconds=1)).normalize(), freq="D")}


def add_corridors(inc: pd.DataFrame, canon: dict) -> pd.DataFrame:
    """Adds `stops` (normalised list) and `csource` columns."""
    parsed = [parse_corridor(a, b) for a, b in zip(inc.desc_en, inc.desc_de)]
    inc = inc.copy()
    inc["stops"] = [[normalize_station(s, canon) for s in p[0]] if p[0] else None for p in parsed]
    inc["csource"] = [p[1] for p in parsed]
    return inc


def corridors(inc: pd.DataFrame, window_days: int) -> tuple[list[dict], dict]:
    """Pool by UNDIRECTED endpoint pair (A-B and B-A merge; hours merged). disruptionDays = distinct local
    calendar days with >=1 active disruption on the corridor; sampleSize = distinct incidents."""
    inc = inc[inc.stops.notna()]
    pools: dict = {}
    for iid, r in inc.iterrows():
        a, b = r.stops[0], r.stops[-1]
        if a == b:
            continue
        p = pools.setdefault(tuple(sorted((a, b))), {"ori": Counter(), "via": Counter(), "src": Counter(),
                                                      "days": set(), "dur": {}})
        p["ori"][(a, b)] += 1
        p["via"][tuple(r.stops[1:-1])] += 1
        p["src"][r.csource] += 1
        p["days"] |= _days(r)
        p["dur"][iid] = r.duration_min
    rows, dropped = [], 0
    for p in pools.values():
        n = len(p["dur"])
        if n < MIN_CORRIDOR_N:
            dropped += 1
            continue
        a, b = p["ori"].most_common(1)[0][0]
        row = {"from": a, "to": b, "corridor": f"{a} - {b}", "disruptionDays": len(p["days"]),
               "windowDays": window_days,
               "disruptionProbabilityPerDay": round(min(1.0, len(p["days"]) / window_days), 4),
               "expectedDelayMin": round(float(_median(p["dur"].values())), 1),
               "sampleSize": n, "confidence": confidence(n),
               "source": "line" if p["src"]["line"] else "segment"}
        if via := list(p["via"].most_common(1)[0][0]):
            row["via"] = via
        rows.append(row)
    rows.sort(key=lambda r: (-r["sampleSize"], r["corridor"]))
    return rows, {"poolsTotal": len(pools), "droppedSampleLt3": dropped}


def hour_baseline(inc: pd.DataFrame, window_days: int) -> list[dict]:
    """Nationwide. disruptionProbabilityPerHour(h) = share of the window's days on which >=1 incident was active
    anywhere during local hour h. Always 24 rows."""
    cells = {h: {"days": set(), "dur": {}} for h in range(24)}
    for iid, r in inc.iterrows():
        s, e = r.start.tz_convert(TZ), r.end.tz_convert(TZ)
        for slot in pd.date_range(s.floor("h"), e - pd.Timedelta(seconds=1), freq="h", tz=TZ):
            cells[slot.hour]["days"].add(slot.date())
            cells[slot.hour]["dur"][iid] = r.duration_min
    return [{"hourBucket": h, "disruptionProbabilityPerHour": round(min(1.0, len(c["days"]) / window_days), 4),
             "expectedDelayMin": round(float(_median(c["dur"].values())), 1) if c["dur"] else 0.0,
             "sampleSize": len(c["dur"])} for h, c in cells.items()]


JOIN_HINTS = {
    "normalisation": "fold(x): Unicode NFKD, drop combining marks, replace 'ß' with 'ss', casefold, replace every "
                     "run of non [a-z0-9] with one space, trim. Apply to journey leg station names AND to "
                     "stationAliases keys/values and corridor from/to/via. Two names are the same station iff "
                     "fold() is equal after alias resolution.",
    "aliases": "Resolve a name by looking up fold(name) in fold(stationAliases key); the value is the display name. "
               "Then compare fold(display). Aliases like Genf/Geneva/Zurich/Basel were NOT observed in the CSVs "
               "(CSV names are already display forms); they are provided for OJP/user input only.",
    "corridorMatch": "For a journey, take origin and destination of the whole journey (and, if no match, of each "
                     "leg). Match a corridor when {fold(from),fold(to)} equals {fold(origin),fold(destination)} "
                     "(direction ignored: A-B and B-A were pooled). Prefer the highest sampleSize if several match.",
    "multiStop": "Corridors with `via` (text with >2 stops) are keyed by their first and last stop only; via is "
                 "informational. A journey also matches when origin/destination equal the endpoints. Do NOT match "
                 "on a via stop alone and do not chain corridors.",
    "noMatch": "Use hourBaseline[hourBucket of the departure, Europe/Zurich]. It is nationwide and NOT journey "
               "specific: show it as a weak fallback, never as a per-journey probability.",
    "confidence": "Never display a probability for confidence 'low' as a number without caveat; sampleSize counts "
                  "distinct incidents, not days.",
    "limits": "Exact endpoint match only: a Lausanne-Genève journey will not match a Lausanne-Brig corridor even "
              "though it shares track. No geometry in the data.",
}
