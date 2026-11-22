"""The Service Alerts decoder reads a hand-encoded GTFS-RT FeedMessage (official field numbers)."""
from sbb_duration.gtfs_alerts import parse, summary


def _varint(n: int) -> bytes:
    out = b""
    while True:
        byte, n = n & 0x7F, n >> 7
        out += bytes([byte | (0x80 if n else 0)])
        if not n:
            return out


def _f(number: int, value) -> bytes:
    if isinstance(value, int):
        return _varint(number << 3) + _varint(value)
    if isinstance(value, str):
        value = value.encode()
    return _varint(number << 3 | 2) + _varint(len(value)) + value


def _text(lang: str, text: str) -> bytes:
    return _f(1, _f(1, text) + _f(2, lang))


def test_alert_fields_are_decoded():
    alert = (_f(1, _f(1, 1795300000) + _f(2, 1795303600))
             + _f(5, _f(1, "11") + _f(5, "ch:1:sloid:1118:0:2"))
             + _f(5, _f(1, "11") + _f(5, "ch:1:sloid:1037"))
             + _f(6, 3) + _f(7, 1)
             + _f(10, _text("en", "Interrupted service: Renens VD - Morges") + _text("fr", "Interruption"))
             )
    feed = _f(1, _f(1, "2.0") + _f(3, 1795300100)) + _f(2, _f(1, "uuid-1") + _f(5, alert)) \
        + _f(2, _f(1, "deleted") + _f(2, 1) + _f(5, alert))
    ts, alerts = parse(feed)
    assert ts == 1795300100 and len(alerts) == 1
    a = alerts[0]
    assert a.id == "uuid-1" and a.periods == [(1795300000, 1795303600)]
    assert a.stop_ids == ["ch:1:sloid:1118:0:2", "ch:1:sloid:1037"] and a.agency_ids == ["11", "11"]
    assert (a.cause, a.effect) == ("TECHNICAL_PROBLEM", "NO_SERVICE")
    assert a.header == {"en": "Interrupted service: Renens VD - Morges", "fr": "Interruption"}
    assert summary(alerts)["stopIdsSloid"] == 2
