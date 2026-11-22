"""Validate corridor station names against OJP LocationInformation (network; needs OJP_API_KEY, never logged)."""
from __future__ import annotations

import os
import re
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from difflib import SequenceMatcher
from xml.sax.saxutils import escape

from .corridors import fold

URL = "https://api.opentransportdata.swiss/ojp20"
MIN_SCORE = 0.8  # fold() similarity at or above this counts as the same station


def _request(name: str) -> bytes:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0"><OJPRequest><siri:ServiceRequest>
<siri:RequestTimestamp>{ts}</siri:RequestTimestamp><siri:RequestorRef>alt-analytics</siri:RequestorRef>
<OJPLocationInformationRequest><siri:RequestTimestamp>{ts}</siri:RequestTimestamp>
<InitialInput><Name>{escape(name)}</Name></InitialInput>
<Restrictions><Type>stop</Type><NumberOfResults>5</NumberOfResults></Restrictions>
</OJPLocationInformationRequest></siri:ServiceRequest></OJPRequest></OJP>""".encode()


def parse_places(xml: bytes) -> list[tuple[str, str]]:
    """-> [(StopPlaceName, StopPlaceRef/sloid)] in provider order."""
    out = []
    for sp in ET.fromstring(xml).findall(".//{*}StopPlace"):
        ref, nm = sp.find("{*}StopPlaceRef"), sp.find("{*}StopPlaceName/{*}Text")
        if ref is not None and nm is not None and ref.text and nm.text:
            out.append((nm.text.strip(), ref.text.strip()))
    return out


def score(a: str, b: str) -> float:
    """1.0 if fold() equal, 0.9 if one contains the other (e.g. 'Zürich HB' vs 'Zürich'), else similarity ratio."""
    fa, fb = fold(a), fold(b)
    if fa == fb:
        return 1.0
    return 0.9 if fa and fb and (fa in fb or fb in fa) else round(SequenceMatcher(None, fa, fb).ratio(), 3)


def validate(name: str, key: str | None = None) -> dict:
    key = key or os.environ["OJP_API_KEY"]
    req = urllib.request.Request(URL, _request(name), {"Authorization": f"Bearer {key}",
                                 "Content-Type": "application/xml; charset=utf-8"})
    for wait in (2, 5, 10, 20, 0):  # the free tier answers 429 under bursts
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                places = parse_places(r.read())
            break
        except urllib.error.HTTPError as e:
            if e.code != 429 or not wait:
                raise
            time.sleep(wait)
    time.sleep(0.5)
    best = max(((score(name, n), n, s) for n, s in places), default=None, key=lambda t: t[0])
    if best is None:
        return {"csvName": name, "ojpName": None, "sloid": None, "matched": False, "score": 0.0}
    sc, n, s = best
    return {"csvName": name, "ojpName": n, "sloid": s, "matched": sc >= MIN_SCORE, "score": sc}


def load_key() -> str | None:
    if k := os.environ.get("OJP_API_KEY"):
        return k
    p = os.path.expanduser("~/lauzhack-ojp.env")
    if os.path.exists(p):
        m = re.search(r"^\s*(?:export\s+)?OJP_API_KEY=(.+?)\s*$", open(p).read(), re.M)
        return m.group(1).strip("'\"") if m else None
    return None
