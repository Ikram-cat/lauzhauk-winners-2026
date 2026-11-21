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
    assert "serviceResumed" not in extract("On est en train de vérifier les premiers trains", AT)


def _moment(code, announced=None, bus=False):
    return {"trigger": {"source": "VIA"}, "outlook": {"advice": {"code": code}},
            "public": {"announcedEndAt": announced} if announced else None,
            "via": {"replacementBusPublishedAt": None}, "internal": {"replacementBus": {"busCount": 2} if bus else None}}


def test_notify_rules():
    moments = [_moment("UNKNOWN"), _moment("TAKE_DETOUR", "2026-09-17T18:30:00+02:00"),
               _moment("TAKE_DETOUR", "2026-09-17T18:40:00+02:00"),  # +10 min: no
               _moment("WAIT", "2026-09-17T19:00:00+02:00", bus=True)]  # +30 min, advice change, bus
    add_notifications(moments)
    codes = [[r["code"] for r in m["notify"]["reasons"]] for m in moments]
    assert codes == [["FIRST_DETECTION"], [], [],
                     ["ADVICE_CHANGED", "ANNOUNCED_END_SHIFTED", "REPLACEMENT_BUS_ANNOUNCED"]]


def test_sloid_rule_for_swiss_uic_codes():
    stations = Stations(None)
    assert stations.get(8507000)["sloid"] == "ch:1:sloid:7000"
    assert stations.get(8301307)["sloid"] is None  # Italian code: no Swiss sloid
