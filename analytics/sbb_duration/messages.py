"""Internal liveticker messages: sanitize them and extract traveller-relevant facts with keyword rules.

No language model. The rules cover German, French and Italian wording seen in the
September 2026 export. Accented letters in the patterns accept any character, so
spellings with or without accents ("prevision", "prévision") both match.
"""
import html
import re
from datetime import datetime, timedelta

# --- Sanitizing -------------------------------------------------------------------------------

_TAGS = re.compile(r"<[^>]+>")
_SPACES = re.compile(r"\s+")
_PHONE = re.compile(r"(?:\+|00)\d{2}[\s./-]?\d{2}[\s./-]?\d{3}[\s./-]?\d{2}[\s./-]?\d{2}\b"
                    r"|\b0\d{2}[\s./-]?\d{3}[\s./-]?\d{2}[\s./-]?\d{2}\b")
_EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")
_HONORIFIC_NAME = re.compile(r"\b(?:Herr|Frau|Hr\.|Fr\.|M\.|Mme|Monsieur|Madame|Sig\.|Signor|Signora)"
                             r"\s+[A-ZÀ-Ý][\w-]+(?:\s+[A-ZÀ-Ý][\w-]+)?")
_USER_ID = re.compile(r"\bu\d{5,}\b")

# Senders kept in the traveller timeline. Other senders are individual staff accounts.
SENDER_ROLES = {"TCC": "TRAFFIC_CONTROL", "KOO Bhf": "STATION"}


def sanitize(message) -> str:
    """Plain text without HTML, phone numbers, e-mails, honorific + name, or user ids."""
    text = _TAGS.sub(" ", html.unescape(message if isinstance(message, str) else ""))
    text = _PHONE.sub("[phone]", text)
    text = _EMAIL.sub("[email]", text)
    text = _HONORIFIC_NAME.sub("[name]", text)
    text = _USER_ID.sub("[user]", text)
    return _SPACES.sub(" ", text).strip()


# --- Extraction --------------------------------------------------------------------------------

_BUS = re.compile(r"(?i)\b(?:bus|busse|buse|autobus|bahnersatz|ersatzbus|ersatzverkehr|bus de remplacement"
                  r"|cars? de remplacement|bus sostitutivi?)\b")
_BUS_COUNT = re.compile(r"(?i)\b(\d{1,3})\s*(?:x\s*)?(?:bus|busse|buse|autobus|cars)\b")
_STOP_HINT = re.compile(r"(?i)(?:vor ort|sur place|abfahrt|d.part|haltestelle|arr.t|ab\s|en gare|in [A-Z])")
_TIME = re.compile(r"\b([01]?\d|2[0-3])\s?(?:[:.]\s?([0-5]\d)|h\s?([0-5]\d)?)(?![\d])")
_FORECAST_UNKNOWN = re.compile(r"(?i)(?:keine prognose|noch keine prognose|prognose[^.]{0,20}(?:unbekannt|offen)"
                               r"|pronostic de reprise pas encore|pronostic[^.]{0,30}pas encore connu"
                               r"|pas (?:encore )?de pronostic(?! d.arriv)|nessuna previsione)")
_FORECAST = re.compile(r"(?i)(?:prognose|pronostic|pr.vision|previsione|voraussichtlich|bis ca\.?|bis um"
                       r"|jusqu.{0,2}.|fin de perturbation|wiederaufnahme|reprise)[^.\d]{0,30}?"
                       r"\b(?:[01]?\d|2[0-3])\s?(?:[:.]\s?[0-5]\d|h)")
_NOT_YET = re.compile(r"(?i)(?:en train de v.rifier|en cours de|pas encore|wird gepr.ft|noch nicht|in vorbereitung"
                      r"|non ancora|proposition|vorschlag|proposta)")
# Partial reopening (one track, trains running "with measures") is not the end of the disruption.
_PARTIAL = re.compile(r"(?i)(?:\b(?:1|ein|un|une|uno)\s+(?:gleis|voie|binario)\b|\bgleis\s*\d|\bvoie\s*\d"
                      r"|\bbinario\s*\d|avec mesures|mit massnahmen|con misure|einspurig|voie unique)")
_RESUMED = re.compile(r"(?i)(?:premiers trains|erste[n]? z.ge|circulent . nouveau|reprise du trafic"
                      r"|wieder (?:fahrbar|befahrbar|verkehren)|k.nnen (?:alle |wieder )?[^.]{0,20}verkehren"
                      r"|strecke (?:ist )?(?:wieder )?frei|concept d.exploitation est termin|konzept (?:ist )?beendet"
                      r"|riprende|circolazione ripresa)")


def _clock_after(text_from: str, sent_at: datetime) -> datetime | None:
    """First HH:MM / HHhMM / HHh after the match start, as the next occurrence after sent_at - 2 h."""
    m = _TIME.search(text_from)
    if not m:
        return None
    hour, minute = int(m.group(1)), int(m.group(2) or m.group(3) or 0)
    at = sent_at.replace(hour=hour, minute=minute, second=0, microsecond=0)
    if at < sent_at - timedelta(hours=2):
        at += timedelta(days=1)
    return at


class StationGazetteer:
    """Finds station names or abbreviations (case-sensitive, accent-tolerant) in a text."""

    def __init__(self, names: dict[str, dict]):
        # names: surface form -> station dict (uic, sloid, name, abbreviation)
        self.names = names
        forms = sorted(names, key=len, reverse=True)
        pattern = "|".join(re.sub(r"[^\x00-\x7f]", ".", re.escape(f)) for f in forms)
        self._regex = re.compile(rf"(?<![\w])(?:{pattern})(?![\w])") if forms else None
        self._lookup = [(re.compile("^" + re.sub(r"[^\x00-\x7f]", ".", re.escape(f)) + "$"), f) for f in forms]

    def find(self, text: str) -> list[dict]:
        if self._regex is None:
            return []
        found, seen = [], set()
        for m in self._regex.finditer(text):
            form = next(f for rx, f in self._lookup if rx.match(m.group(0)))
            station = self.names[form]
            if station["uic"] not in seen:
                seen.add(station["uic"])
                found.append(station)
        return found


def extract(text: str, sent_at: datetime, gazetteer: StationGazetteer | None = None) -> dict:
    """Facts useful to a traveller. Keys are present only when found."""
    facts: dict = {}
    if _BUS.search(text):
        counts = [int(n) for n in _BUS_COUNT.findall(text)]
        facts["replacementBus"] = {"busCount": sum(counts) if counts else None}
        stations = gazetteer.find(text) if gazetteer else []
        if stations and _STOP_HINT.search(text):
            times = [_clock_after(text[m.start():], sent_at) for m in _TIME.finditer(text)]
            facts["stops"] = {"stations": stations,
                              "times": sorted({t.isoformat() for t in times if t is not None})}
    if _FORECAST_UNKNOWN.search(text):
        facts["resumptionForecast"] = {"known": False, "at": None}
    else:
        m = _FORECAST.search(text)
        at = _clock_after(text[m.start():], sent_at) if m else None
        if at is not None:
            facts["resumptionForecast"] = {"known": True, "at": at.isoformat()}
    if _RESUMED.search(text) and not _NOT_YET.search(text) and not _PARTIAL.search(text):
        facts["serviceResumed"] = True
    return facts
