"""Minimal decoder for GTFS-Realtime Service Alerts (la/gtfs-sa), separate from the team's TripUpdates decoder.

    python -m sbb_duration.gtfs_alerts [path/to/la_gtfs-sa.pb]

Field numbers from the official gtfs-realtime.proto:
FeedMessage{1 header, 2 entity}; FeedHeader{3 timestamp}; FeedEntity{1 id, 2 is_deleted, 5 alert};
Alert{1 active_period, 5 informed_entity, 6 cause, 7 effect, 10 header_text, 11 description_text};
TimeRange{1 start, 2 end} (POSIX seconds); EntitySelector{1 agency_id, 2 route_id, 3 route_type, 5 stop_id};
TranslatedString{1 translation}; Translation{1 text, 2 language}.
"""
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

CAUSES = {1: "UNKNOWN_CAUSE", 2: "OTHER_CAUSE", 3: "TECHNICAL_PROBLEM", 4: "STRIKE", 5: "DEMONSTRATION",
          6: "ACCIDENT", 7: "HOLIDAY", 8: "WEATHER", 9: "MAINTENANCE", 10: "CONSTRUCTION",
          11: "POLICE_ACTIVITY", 12: "MEDICAL_EMERGENCY"}
EFFECTS = {1: "NO_SERVICE", 2: "REDUCED_SERVICE", 3: "SIGNIFICANT_DELAYS", 4: "DETOUR", 5: "ADDITIONAL_SERVICE",
           6: "MODIFIED_SERVICE", 7: "OTHER_EFFECT", 8: "UNKNOWN_EFFECT", 9: "STOP_MOVED", 10: "NO_EFFECT",
           11: "ACCESSIBILITY_ISSUE"}


def _varint(b: bytes, i: int) -> tuple[int, int]:
    shift = value = 0
    while True:
        byte = b[i]
        i += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, i
        shift += 7


def _fields(b: bytes):
    """(field number, wire type, value) for each field; value is int (varint) or bytes (length-delimited)."""
    i = 0
    while i < len(b):
        key, i = _varint(b, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = _varint(b, i)
        elif wire == 2:
            n, i = _varint(b, i)
            value, i = b[i:i + n], i + n
        elif wire == 1:
            value, i = b[i:i + 8], i + 8
        elif wire == 5:
            value, i = b[i:i + 4], i + 4
        else:
            raise ValueError(f"unsupported wire type {wire}")
        yield number, wire, value


def _translations(b: bytes) -> dict:
    out = {}
    for n, _, v in _fields(b):
        if n == 1:
            parts = {k: val for k, _, val in _fields(v)}
            text = parts.get(1, b"").decode("utf-8", "replace")
            out[parts.get(2, b"").decode() or "und"] = text
    return out


@dataclass
class Alert:
    id: str
    periods: list = field(default_factory=list)       # (start, end) POSIX seconds, None when absent
    stop_ids: list = field(default_factory=list)
    route_ids: list = field(default_factory=list)
    agency_ids: list = field(default_factory=list)
    cause: str | None = None
    effect: str | None = None
    header: dict = field(default_factory=dict)        # language -> text
    description: dict = field(default_factory=dict)


def parse(feed: bytes) -> tuple[int | None, list[Alert]]:
    """(header timestamp, alerts). Entities without an alert, or deleted, are skipped."""
    timestamp, alerts = None, []
    for n, _, v in _fields(feed):
        if n == 1:
            timestamp = next((val for k, _, val in _fields(v) if k == 3), None)
        elif n == 2:
            eid, deleted, raw = None, False, None
            for k, _, val in _fields(v):
                if k == 1:
                    eid = val.decode()
                elif k == 2:
                    deleted = bool(val)
                elif k == 5:
                    raw = val
            if raw is None or deleted:
                continue
            a = Alert(id=eid)
            for k, _, val in _fields(raw):
                if k == 1:
                    p = {kk: vv for kk, _, vv in _fields(val)}
                    a.periods.append((p.get(1), p.get(2)))
                elif k == 5:
                    sel = {kk: vv for kk, _, vv in _fields(val)}
                    if 5 in sel:
                        a.stop_ids.append(sel[5].decode())
                    if 2 in sel:
                        a.route_ids.append(sel[2].decode())
                    if 1 in sel:
                        a.agency_ids.append(sel[1].decode())
                elif k == 6:
                    a.cause = CAUSES.get(val, str(val))
                elif k == 7:
                    a.effect = EFFECTS.get(val, str(val))
                elif k == 10:
                    a.header = _translations(val)
                elif k == 11:
                    a.description = _translations(val)
            alerts.append(a)
    return timestamp, alerts


def summary(alerts: list[Alert]) -> dict:
    with_stops = [a for a in alerts if a.stop_ids]
    sloid = [s for a in alerts for s in a.stop_ids]
    return {
        "alerts": len(alerts),
        "cause": Counter(a.cause for a in alerts).most_common(),
        "effect": Counter(a.effect for a in alerts).most_common(),
        "withActivePeriodEnd": sum(any(e for _, e in a.periods) for a in alerts),
        "withStopIds": len(with_stops),
        "stopIdsSloid": sum(s.startswith("ch:1:sloid:") for s in sloid),
        "stopIds": len(sloid),
        "agencies": Counter(x for a in alerts for x in set(a.agency_ids)).most_common(8),
        "headerLanguages": Counter(lang for a in alerts for lang in a.header).most_common(),
    }


if __name__ == "__main__":
    path = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "data/gtfs/la_gtfs-sa.pb")
    ts, alerts = parse(path.read_bytes())
    print(ts, summary(alerts))
