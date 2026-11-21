"""Link the public liveticker, VIA event messages and the internal (KULT) liveticker into one timeline.

    python -m sbb_duration.sources [--detour MINUTES] [--data-dir DIR] [--output-dir DIR]

Run after `python -m sbb_duration.train`. Linkage:
- VIA `alea_fall_id` = public incident id (exact).
- Internal case <-> VIA event: same cause code (Ereigniskategorie.name = VIA ereignisgrund) and
  creation times within 30 minutes, resolved one-to-one by smallest time difference.
For every incident present in the three sources it writes output/timelines/timeline-<id>.json
(dataStatus MOCK): at each moment, the public announcement, the model outlook (evaluation models
trained before the test cutoff, which never saw these incidents), the advice, the affected stations
(UIC + sloid) and lines, the facts extracted from internal messages, and a notify flag.
Names, first names and phone numbers of the internal liveticker are never read into the output.
"""
import argparse
import json
import math
import urllib.request
from pathlib import Path

import numpy as np
import pandas as pd

from .advice import Advice, advice
from .artifact import finalize
from .data import ANALYTICS_DIR, DATA_DIR, OUTPUT_DIR, load_reason_labels, load_versions
from .features import LOCAL_TZ, build_features
from .messages import SENDER_ROLES, StationGazetteer, extract, sanitize
from .predict import load_bundle, predict_raw

DATEN_DIR = DATA_DIR.parent  # analytics/Daten/Daten
VIA_FILE = DATEN_DIR / "Ereignismeldungen" / "Ereignismeldungen Via.xlsx"
INTERNAL_DIR = DATEN_DIR / "intern-liveticker"
SERVICE_POINTS_FILE = ANALYTICS_DIR / "data" / "service_points" / "permalink.zip"
SERVICE_POINTS_URL = "https://data.opentransportdata.swiss/en/dataset/service-points-actual-date/permalink"

LINK_WINDOW = pd.Timedelta(minutes=30)
SHIFT_THRESHOLD_MIN = 15
DEFAULT_DETOUR_MIN = 60
DEMO_PRIMARY = "2195005188970"  # Renens VD - Morges, 2026-09-17
DEMO_BACKUP = "2195005195846"   # Zürich Stadelhofen - Stettbach, 2026-09-25
NON_STATION_ABBREVIATIONS = {"TCC", "ICE", "TGV", "KOO", "SBB", "CFF", "FFS", "BLS", "VIA", "ALEA", "KULT",
                             "RJX", "BUS", "VTS", "COLGEN", "EVU", "INFO"}


# --- Loading -----------------------------------------------------------------------------------

def _local(series: pd.Series) -> pd.Series:
    """VIA and KULT timestamps are naive Swiss local time (checked against the public feed)."""
    return pd.to_datetime(series).dt.tz_localize(LOCAL_TZ, ambiguous="NaT", nonexistent="NaT")


def load_via(path: Path = VIA_FILE) -> pd.DataFrame:
    via = pd.read_excel(path, sheet_name=0)
    for column in [c for c in via.columns if c.startswith(("zeitpunkt", "alea_zeitpunkt", "alea_erstellung"))] + [
            "logging_date_time", "modified"]:
        via[column] = _local(via[column])
    via["alea_fall_id"] = via.alea_fall_id.astype(str)
    return via.sort_values(["alea_fall_id", "logging_date_time"]).reset_index(drop=True)


def load_internal(directory: Path = INTERNAL_DIR):
    """Internal cases (with cause code) and messages. Personal columns are dropped on read."""
    read = lambda name, **kw: pd.read_csv(directory / name, encoding="utf-8-sig", **kw)
    cases = read("1__Ereignisfaelle_ohne_Zeilenschaltungen.csv")
    categories = read("2__Ereigniskategorien_ohne_Zeilenschaltungen.csv")
    messages = read("3_liveticker_messages_ohne_Zeilenschaltungen.csv",
                    usecols=["id", "ereignisfall_id", "timestamp", "sender", "message"])
    cases["created_at"] = _local(cases.created_at)
    cases = cases.merge(categories[["id", "name"]].rename(columns={"id": "ereigniskategorie_id", "name": "cause"}),
                        on="ereigniskategorie_id", how="left")
    messages["timestamp"] = _local(messages.timestamp)
    messages = messages.dropna(subset=["ereignisfall_id"]).astype({"ereignisfall_id": "int64"})
    return cases, messages


def load_service_points(path: Path = SERVICE_POINTS_FILE, fetch: bool = True) -> pd.DataFrame | None:
    """Swiss service points (opentransportdata.swiss, 'service-points-actual-date'), or None."""
    if not path.exists() and fetch:
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            urllib.request.urlretrieve(SERVICE_POINTS_URL, path)
        except OSError as error:
            print(f"service points not downloaded ({error}); sloids derived by rule only")
            return None
    if not path.exists():
        return None
    points = pd.read_csv(path, sep=";", dtype=str, encoding="utf-8-sig")
    return points[points.validTo.fillna("9999-12-31") >= "2026-09-01"]


class Stations:
    """UIC code -> station facts. sloid rule verified on all Swiss service points: ch:1:sloid:<UIC - 8500000>."""

    def __init__(self, points: pd.DataFrame | None):
        self.points = {} if points is None else {
            int(r.number): {"name": r.designationOfficial, "abbreviation": r.abbreviation if isinstance(r.abbreviation, str) else None,
                            "sloid": r.sloid if isinstance(r.sloid, str) else None, "country": r.isoCountryCode}
            for r in points.drop_duplicates("number").itertuples()}
        self.source = None if points is None else SERVICE_POINTS_FILE.name

    def get(self, uic) -> dict | None:
        if uic is None or (isinstance(uic, float) and math.isnan(uic)):
            return None
        uic = int(uic)
        known = self.points.get(uic)
        rule_sloid = f"ch:1:sloid:{uic - 8500000}" if 8500000 < uic < 8600000 else None
        return {"uic": uic, "sloid": known["sloid"] if known else rule_sloid,
                "name": known["name"] if known else None,
                "abbreviation": known["abbreviation"] if known else None,
                "inServicePoints": known is not None}

    def gazetteer(self, points: pd.DataFrame | None) -> StationGazetteer:
        if points is None:
            return StationGazetteer({})
        train = points[points.meansOfTransport.fillna("").str.contains("TRAIN") & points.uicCountryCode.eq("85")]
        forms: dict[str, dict] = {}
        short_count: dict[str, int] = {}
        for r in train.itertuples():
            name = r.designationOfficial
            short = name[:-3] if len(name) > 6 and name[-3] == " " and name[-2:].isupper() else None
            if short:
                short_count[short] = short_count.get(short, 0) + 1
        for r in train.itertuples():
            station = self.get(int(r.number))
            name = r.designationOfficial
            forms[name] = station
            short = name[:-3] if len(name) > 6 and name[-3] == " " and name[-2:].isupper() else None
            if short and len(short) >= 4 and short_count[short] == 1 and short not in forms:
                forms[short] = station
            abbr = r.abbreviation if isinstance(r.abbreviation, str) else ""
            if len(abbr) >= 3 and abbr.isalpha() and abbr.isupper() and abbr not in NON_STATION_ABBREVIATIONS:
                forms.setdefault(abbr, station)
        return StationGazetteer(forms)


# --- Linkage -----------------------------------------------------------------------------------

def via_events(via: pd.DataFrame) -> pd.DataFrame:
    return via.groupby("alea_fall_id").agg(created=("alea_erstellungszeitpunkt", "first"),
                                           cause=("ereignisgrund", "first")).reset_index()


def link_internal(events: pd.DataFrame, cases: pd.DataFrame) -> pd.DataFrame:
    """One-to-one VIA event <-> internal case: same cause, |created difference| <= 30 min, nearest first."""
    pairs = events.merge(cases[["id", "created_at", "cause"]], on="cause")
    pairs["delta"] = (pairs.created_at - pairs.created).abs()
    pairs = pairs[pairs.delta <= LINK_WINDOW].sort_values("delta")
    used_via, used_case, kept = set(), set(), []
    for r in pairs.itertuples():
        if r.alea_fall_id not in used_via and r.id not in used_case:
            used_via.add(r.alea_fall_id); used_case.add(r.id); kept.append(r.Index)
    return pairs.loc[kept, ["alea_fall_id", "id", "delta"]].rename(columns={"id": "internal_case_id"})


# --- Timeline ----------------------------------------------------------------------------------

def _iso(ts) -> str | None:
    return None if ts is None or pd.isna(ts) else pd.Timestamp(ts).tz_convert(LOCAL_TZ).isoformat()


def _round(x: float) -> int:
    return int(math.floor(x + 0.5))


def _lines(value) -> list[str]:
    return [] if not isinstance(value, str) else [p.strip() for p in value.split(",") if p.strip()]


def _affected(row, stations: Stations) -> dict:
    points = [("DISRUPTION_POINT", row.stoerungs_ort1), ("DISRUPTION_POINT", row.stoerungs_ort2),
              ("LINE_FROM", row.stoerungs_linie_von), ("LINE_TO", row.stoerungs_linie_bis)]
    by_uic: dict[int, dict] = {}
    for role, uic in points:
        station = stations.get(uic)
        if station:
            entry = by_uic.setdefault(station["uic"], {**station, "roles": []})
            if role not in entry["roles"]:
                entry["roles"].append(role)
    listed = list(by_uic.values())
    section = lambda a, b: None if stations.get(a) is None or stations.get(b) is None else {
        "from": stations.get(a)["uic"], "to": stations.get(b)["uic"]}
    return {"stations": listed,
            "sections": [s for s in (section(row.stoerungs_ort1, row.stoerungs_ort2),
                                     section(row.stoerungs_linie_von, row.stoerungs_linie_bis)) if s],
            "lines": _lines(row.stoerungs_liniennummer)}


def _public_versions(versions: pd.DataFrame) -> pd.DataFrame:
    v = versions.copy()
    v["observed"] = v.updated_at_source.dt.tz_convert(LOCAL_TZ)
    return v[v.lifecycle.isin(["INFO", "ENDED"])].sort_values(["observed", "last_modified_date"]).drop_duplicates(
        "observed", keep="last")


def _outlook(public: pd.Series, info_index: int, start, at, bundle: dict, detour: int) -> dict:
    features = build_features(observed_at=at, started_at=start, reason_id_fk=public.reason_id_fk,
                              duration_type=public.duration_type, duration_to=public.duration_to,
                              n_updates_so_far=info_index, summary_en=public.summary_en)
    p50, pessimistic = finalize(*predict_raw([features], {**bundle, "models": bundle["evaluation_models"]})[0])
    rounded = (_round(p50), math.ceil(pessimistic))
    return {"status": "AVAILABLE",
            "remainingMinutes": {"p50": rounded[0], "pessimistic": rounded[1]},
            "expectedEndAt": {"p50": _iso(at + pd.Timedelta(minutes=rounded[0])),
                              "pessimistic": _iso(at + pd.Timedelta(minutes=rounded[1]))},
            "advice": {"code": advice(p50, pessimistic, detour).value, "detourExtraMinutes": detour}}


def _no_outlook(detour: int, reason: str) -> dict:
    return {"status": "UNAVAILABLE", "reason": reason, "remainingMinutes": None, "expectedEndAt": None,
            "advice": {"code": Advice.UNKNOWN.value, "detourExtraMinutes": detour}}


def add_notifications(moments: list[dict]) -> None:
    """notify = first detection, advice change, announced end shifted > 15 min, replacement bus announced.

    The shift rule applies to the SBB announced end only: the predicted end moves at every moment, and
    the changes that matter to the traveller already show up as ADVICE_CHANGED.
    """
    last_code, announced_base, bus_seen = None, None, False
    for i, m in enumerate(moments):
        reasons = []
        if i == 0:
            reasons.append({"code": "FIRST_DETECTION", "detail": f"first seen in {m['trigger']['source']}"})
        code = m["outlook"]["advice"]["code"]
        if code != Advice.UNKNOWN.value:
            if last_code is not None and code != last_code:
                reasons.append({"code": "ADVICE_CHANGED", "detail": f"{last_code} -> {code}"})
            last_code = code
        announced = m["public"]["announcedEndAt"] if m["public"] else None
        if announced:
            if announced_base is None:
                announced_base = announced
            else:
                shift = (pd.Timestamp(announced) - pd.Timestamp(announced_base)).total_seconds() / 60
                if abs(shift) > SHIFT_THRESHOLD_MIN:
                    reasons.append({"code": "ANNOUNCED_END_SHIFTED", "detail": f"{shift:+.0f} min vs last notified"})
                    announced_base = announced
        bus = bool(m["via"]["replacementBusPublishedAt"] or m["internal"]["replacementBus"])
        if bus and not bus_seen:
            reasons.append({"code": "REPLACEMENT_BUS_ANNOUNCED", "detail": m["trigger"]["source"]})
            bus_seen = True
        m["notify"] = {"value": bool(reasons), "reasons": reasons}


def build_timeline(alea_id: str, internal_case_id: int, delta, via: pd.DataFrame, versions: pd.DataFrame,
                   messages: pd.DataFrame, stations: Stations, gazetteer: StationGazetteer, bundle: dict,
                   reasons: dict, detour: int) -> dict:
    v_rows = via[via.alea_fall_id == alea_id]
    p_all = versions[versions.id == alea_id]
    public = _public_versions(p_all)
    start = p_all.created_at_source.min()
    ended = public[public.lifecycle == "ENDED"]
    end_at = ended.observed.min() if len(ended) else None
    msgs = messages[messages.ereignisfall_id == internal_case_id].sort_values(["timestamp", "id"])

    # Trigger events from the three sources.
    events = []
    via_keys = ["stoerungs_ort1", "stoerungs_ort2", "stoerungs_linie_von", "stoerungs_linie_bis",
                "stoerungs_liniennummer", "zeitpunkt_erstpublikation_reiseempfehlung",
                "zeitpunkt_erstpublikation_bahnersatz", "zeitpunkt_globale_endmeldung"]
    previous = None
    for r in v_rows.itertuples():
        key = tuple(str(getattr(r, k)) for k in via_keys)
        if key != previous:
            events.append((r.logging_date_time, 0, "VIA", "VIA_UPDATE", r))
            previous = key
    for r in public.itertuples():
        events.append((r.observed, 1, "PUBLIC_LIVETICKER", "PUBLIC_ENDED" if r.lifecycle == "ENDED" else "PUBLIC_VERSION", r))
    skipped_staff = 0
    for r in msgs.itertuples():
        if r.sender not in SENDER_ROLES:
            skipped_staff += 1
            continue
        events.append((r.timestamp, 2, "INTERNAL_LIVETICKER", "INTERNAL_MESSAGE", r))
    events.sort(key=lambda e: (e[0], e[1]))
    if end_at is not None:
        events = [e for e in events if e[0] <= end_at]

    info_versions = public[public.lifecycle == "INFO"].reset_index(drop=True)
    state = {"via": None, "public": None, "info_index": -1, "ended": False,
             "internal": {"replacementBus": None, "stops": [], "resumptionForecast": None, "serviceResumedAt": None}}
    moments = []
    for at, _, source, event, row in events:
        message = None
        if source == "VIA":
            state["via"] = row
        elif source == "PUBLIC_LIVETICKER":
            state["public"] = row
            state["ended"] = row.lifecycle == "ENDED"
            if not state["ended"]:
                state["info_index"] = int(np.flatnonzero(info_versions.observed == row.observed)[0])
        else:
            text = sanitize(row.message)
            facts = extract(text, at.to_pydatetime(), gazetteer)
            message = {"senderRole": SENDER_ROLES[row.sender], "text": text, "facts": facts}
            internal = state["internal"]
            if "replacementBus" in facts:
                previous_bus = internal["replacementBus"] or {"firstAt": _iso(at), "busCount": None}
                count = facts["replacementBus"]["busCount"]
                counts = [c for c in (count, previous_bus["busCount"]) if c is not None]
                internal["replacementBus"] = {"firstAt": previous_bus["firstAt"],  # busCount: most mentioned at once
                                              "busCount": max(counts) if counts else None}
            if "stops" in facts:
                known = {s["uic"] for s in internal["stops"]}
                internal["stops"] += [{**s, "firstMentionedAt": _iso(at), "times": facts["stops"]["times"]}
                                      for s in facts["stops"]["stations"] if s["uic"] not in known]
            if "resumptionForecast" in facts:
                internal["resumptionForecast"] = {**facts["resumptionForecast"], "messageAt": _iso(at)}
            if facts.get("serviceResumed") and internal["serviceResumedAt"] is None:
                internal["serviceResumedAt"] = _iso(at)

        pub = state["public"]
        public_view = None if pub is None else {
            "lifecycle": pub.lifecycle, "summaryEn": pub.summary_en, "durationType": pub.duration_type,
            "announcedEndAt": _iso(pub.duration_to), "versionAt": _iso(pub.observed),
            "officialRemainingMinutes": None if pd.isna(pub.duration_to) else
            _round(max((pub.duration_to - at).total_seconds() / 60, 0))}
        if state["ended"]:
            outlook = _no_outlook(detour, "ENDED")
        elif pub is None:
            outlook = _no_outlook(detour, "NO_PUBLIC_ANNOUNCEMENT_YET")
        else:
            outlook = _outlook(pub, state["info_index"], start, at, bundle, detour)
        via_row = state["via"]
        moments.append({
            "at": _iso(at),
            "trigger": {"source": source, "event": event},
            "public": public_view,
            "outlook": outlook,
            "affected": None if via_row is None else _affected(via_row, stations),
            "via": {"travelRecommendationPublishedAt": None if via_row is None else
                    _iso(via_row.zeitpunkt_erstpublikation_reiseempfehlung),
                    "replacementBusPublishedAt": None if via_row is None else
                    _iso(via_row.zeitpunkt_erstpublikation_bahnersatz)},
            "internal": json.loads(json.dumps(state["internal"])),
            "message": message,
            "actualRemainingMinutes": None if end_at is None else _round(max((end_at - at).total_seconds() / 60, 0)),
        })
    add_notifications(moments)

    last_via = v_rows.iloc[-1]
    union = {"stations": [], "sections": [], "lines": []}
    by_uic = {}
    for r in v_rows.itertuples():
        a = _affected(r, stations)
        for station in a["stations"]:
            entry = by_uic.setdefault(station["uic"], {**station, "roles": []})
            entry["roles"] += [role for role in station["roles"] if role not in entry["roles"]]
        for key in ("sections", "lines"):
            union[key] += [x for x in a[key] if x not in union[key]]
    union["stations"] = list(by_uic.values())
    reason = p_all.reason_id_fk.dropna().iloc[0] if p_all.reason_id_fk.notna().any() else last_via.ereignisgrund
    return {
        "schemaVersion": 1,
        "dataStatus": "MOCK",
        "kind": "HISTORICAL_MULTI_SOURCE_TIMELINE",
        "note": "Past incident rebuilt from the public SBB liveticker, VIA event messages and the internal "
                "liveticker. Not live data. The detour cost is hypothetical. Predictions come from models trained "
                "only on incidents before the test cutoff, which never saw this incident. actualRemainingMinutes "
                "is hindsight.",
        "incident": {
            "id": alea_id,
            "summaryEn": public.summary_en.dropna().iloc[-1] if public.summary_en.notna().any() else None,
            "reasonCode": reason, "reasonLabel": reasons.get(reason),
            "startedAt": _iso(start), "actualEndAt": _iso(end_at),
            "actualDurationMinutes": None if end_at is None else _round((end_at - start).total_seconds() / 60),
        },
        "linkage": {
            "via": {"rule": "alea_fall_id == public incident id", "events": int(len(v_rows))},
            "internal": {"rule": "same cause code and creation time within 30 min (one-to-one, nearest)",
                         "creationDeltaMinutes": round(delta.total_seconds() / 60, 1),
                         "messagesUsed": int(len(msgs) - skipped_staff), "staffMessagesSkipped": skipped_staff},
        },
        "detourExtraMinutes": detour,
        "model": {"kind": "ESTIMATED", "modelVersion": "duration-v1 (evaluation split)",
                  "pessimisticQuantile": bundle["metrics"]["quantiles"]["pessimistic"],
                  "pessimisticCoverage": bundle["metrics"]["evaluation"]["test"]["pessimisticCoverage"]},
        "affected": union,
        "notifications": sum(m["notify"]["value"] for m in moments),
        "moments": moments,
    }


def print_timeline(t: dict) -> None:
    inc = t["incident"]
    print(f"\n=== [MOCK] {inc['summaryEn']} ({inc['id']}) | {inc['reasonLabel']} | "
          f"{inc['startedAt'][:16]} -> {(inc['actualEndAt'] or '?')[:16]} | {len(t['moments'])} moments, "
          f"{t['notifications']} notifications")
    print("    stations: " + ", ".join(f"{s['name'] or '?'} ({s['uic']}, {s['sloid']})" for s in t["affected"]["stations"]))
    for m in t["moments"]:
        if not m["notify"]["value"]:
            continue
        o = m["outlook"]
        pred = "-" if o["remainingMinutes"] is None else f"{o['remainingMinutes']['p50']}/{o['remainingMinutes']['pessimistic']}"
        off = m["public"]["officialRemainingMinutes"] if m["public"] else None
        print(f"    {m['at'][11:16]} {m['trigger']['source'][:8]:<8} SBB {str(off):>4} | model {pred:>9} | "
              f"{o['advice']['code']:<18} | actual {m['actualRemainingMinutes']} | "
              + ", ".join(r["code"] for r in m["notify"]["reasons"]))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--detour", type=int, default=DEFAULT_DETOUR_MIN)
    parser.add_argument("--data-dir", type=Path, default=DATA_DIR)
    parser.add_argument("--output-dir", type=Path, default=OUTPUT_DIR)
    parser.add_argument("--no-fetch", action="store_true", help="do not download service points if missing")
    args = parser.parse_args(argv)

    bundle = load_bundle(args.output_dir / "duration_model.joblib")
    daten = Path(args.data_dir).parent
    via = load_via(daten / "Ereignismeldungen" / "Ereignismeldungen Via.xlsx")
    cases, messages = load_internal(daten / "intern-liveticker")
    versions = load_versions(args.data_dir)
    points = load_service_points(fetch=not args.no_fetch)
    stations = Stations(points)
    gazetteer = stations.gazetteer(points)

    links = link_internal(via_events(via), cases)
    public_ids = set(versions.id)
    common = links[links.alea_fall_id.isin(public_ids)].sort_values("alea_fall_id")
    reasons = load_reason_labels(args.data_dir)
    out_dir = args.output_dir / "timelines"
    out_dir.mkdir(parents=True, exist_ok=True)
    index = []
    for r in common.itertuples():
        timeline = build_timeline(r.alea_fall_id, int(r.internal_case_id), r.delta, via, versions, messages,
                                  stations, gazetteer, bundle, reasons, args.detour)
        name = f"timeline-{r.alea_fall_id}.json"
        (out_dir / name).write_text(json.dumps(timeline, indent=1, ensure_ascii=False, allow_nan=False),
                                    encoding="utf-8")
        print_timeline(timeline)
        index.append({"id": r.alea_fall_id, "file": name, "summaryEn": timeline["incident"]["summaryEn"],
                      "startedAt": timeline["incident"]["startedAt"], "moments": len(timeline["moments"]),
                      "notifications": timeline["notifications"]})
    via_ids = set(via.alea_fall_id)
    summary = {
        "dataStatus": "MOCK",
        "demo": {"primary": DEMO_PRIMARY, "backup": DEMO_BACKUP},
        "linkage": {"viaEvents": len(via_ids), "viaInPublic": len(via_ids & public_ids),
                    "internalCases": int(cases.id.nunique()), "internalLinkedToVia": int(len(links)),
                    "inAllThreeSources": int(len(common))},
        "servicePoints": {"source": "opentransportdata.swiss service-points-actual-date",
                          "file": stations.source,
                          "sloidRule": "ch:1:sloid:<UIC - 8500000> for Swiss UIC codes (85xxxxx)"},
        "incidents": index,
    }
    (out_dir / "index.json").write_text(json.dumps(summary, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"\n{json.dumps(summary['linkage'])} -> {out_dir}")


if __name__ == "__main__":
    main()
