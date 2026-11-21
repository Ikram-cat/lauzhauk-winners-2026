"""Synthetic checks of message sanitizing, keyword extraction, notify rules and the sloid rule."""
from datetime import datetime

from sbb_duration.messages import extract, sanitize
from sbb_duration.sources import Stations, add_notifications

AT = datetime(2026, 9, 17, 18, 0)


def test_sanitize_removes_contact_details_and_html():
    text = sanitize("<b>Contact</b> Frau Muster, 079 123 45 67 / +41 79 123 45 67, a.b@example.ch, u123456")
    assert text == "Contact [name], [phone] / [phone], [email], [user]"


def test_extract_bus_forecast_and_resumption():
    assert extract("14 bus commandés, départ 18h30", AT)["replacementBus"] == {"busCount": 14}
    assert extract("La perturbation durera au minimum jusqu'à 18h30.", AT)["resumptionForecast"] == {
        "known": True, "at": "2026-09-17T18:30:00"}
    assert extract("Der Fachdienst ist vor Ort. Noch keine Prognose", AT)["resumptionForecast"]["known"] is False
    assert extract("Premiers trains Lausanne - Genève 730", AT)["serviceResumed"] is True
    for not_resumed in ("On est en train de vérifier les premiers trains", "1 Gleis ist wieder fahrbar.",
                        "Proposition pour les premiers trains", "Premiers trains avec mesures: RV 24211"):
        assert "serviceResumed" not in extract(not_resumed, AT), not_resumed


def _moment(minute, code, p50=None, pessimistic=None, announced=None, bus=False):
    return {"at": f"2026-09-17T18:{minute:02d}:00+02:00", "trigger": {"source": "VIA"},
            "outlook": {"remainingMinutes": None if p50 is None else {"p50": p50, "pessimistic": pessimistic},
                        "advice": {"code": code, "detourExtraMinutes": 60}},
            "public": {"announcedEndAt": announced} if announced else None,
            "via": {"replacementBusPublishedAt": None}, "internal": {"replacementBus": {"busCount": 2} if bus else None}}


def _codes(moments):
    add_notifications(moments)
    return [[r["code"] for r in m["notify"]["reasons"]] for m in moments]


def test_advice_change_needs_confirmation_or_margin():
    # 58/90 -> DETOUR_IF_DEADLINE close to p50 (2 min): flip-flop is ignored, a confirmed change notifies.
    moments = [_moment(0, "TAKE_DETOUR", 70, 90), _moment(20, "DETOUR_IF_DEADLINE", 58, 90),
               _moment(40, "TAKE_DETOUR", 62, 90), _moment(41, "DETOUR_IF_DEADLINE", 58, 90),
               _moment(59, "DETOUR_IF_DEADLINE", 57, 90)]
    assert _codes(moments) == [["FIRST_DETECTION"], [], [], [], ["ADVICE_CHANGED"]]
    # Far from the prediction (detour 60 vs p50 30): immediate.
    assert _codes([_moment(0, "TAKE_DETOUR", 70, 90), _moment(20, "DETOUR_IF_DEADLINE", 30, 90)])[1] == [
        "ADVICE_CHANGED"]


def test_rate_limit_except_replacement_bus():
    moments = [_moment(0, "UNKNOWN", announced="2026-09-17T18:30:00+02:00"),
               _moment(5, "UNKNOWN", announced="2026-09-17T19:00:00+02:00"),           # withheld (< 15 min)
               _moment(6, "UNKNOWN", announced="2026-09-17T19:00:00+02:00", bus=True),  # bus goes through
               _moment(30, "UNKNOWN", announced="2026-09-17T19:00:00+02:00", bus=True)]  # nothing new to notify
    assert _codes(moments) == [["FIRST_DETECTION"], [], ["ANNOUNCED_END_SHIFTED", "REPLACEMENT_BUS_ANNOUNCED"], []]
    assert moments[1]["notify"]["withheld"] == ["ANNOUNCED_END_SHIFTED"]


def test_sloid_rule_for_swiss_uic_codes():
    stations = Stations(None)
    assert stations.get(8507000)["sloid"] == "ch:1:sloid:7000"
    assert stations.get(8301307)["sloid"] is None  # Italian code: no Swiss sloid
