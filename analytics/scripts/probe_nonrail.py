#!/usr/bin/env python3
"""Probe Ist-Daten non-rail coverage (Bus/Tram/Metro) for the 2b propagation feasibility check."""
from __future__ import annotations
import csv, io, os, sys, zipfile
from collections import Counter

ZIPS = [
    os.path.expanduser("~/Downloads/ist-daten/ist-daten-v2-2026-03.zip"),
    os.path.expanduser("~/Downloads/ist-daten/ist-daten-v2-2026-07.zip"),
    os.path.expanduser("~/Downloads/ist-daten/ist-daten-v2-2026-10.zip"),
]
TOWNS = ["Morges", "Lausanne", "Renens", "Allaman", "Nyon", "Vevey", "Montreux",
         "Genève", "Zürich", "Bern", "Fribourg", "Yverdon"]


def reader(zf, name):
    return csv.DictReader(io.TextIOWrapper(zf.open(name), encoding="utf-8-sig", newline=""), delimiter=";")


def main():
    for zp in ZIPS:
        if not os.path.exists(zp):
            print("missing", zp); continue
        with zipfile.ZipFile(zp) as zf:
            names = sorted(n for n in zf.namelist() if n.lower().endswith(".csv"))
            for name in names[:2]:
                prod = Counter(); real = Counter(); townrows = Counter(); lines = {}
                n = 0
                for row in reader(zf, name):
                    n += 1
                    p = row["PRODUKT_ID"].strip()
                    prod[p] += 1
                    st = row["HALTESTELLEN_NAME"].strip()
                    is_real = "REAL" in (row["AB_PROGNOSE_STATUS"].strip().upper(),
                                         "REAL" in row["AN_PROGNOSE_STATUS"].strip().upper())
                    if p.upper() != "ZUG" and is_real:
                        real[p] += 1
                    for t in TOWNS:
                        if st == t or st.startswith(t + ",") or st.startswith(t + " "):
                            townrows[t] += 1
                            if p.upper() != "ZUG":
                                key = (p.upper(), t)
                                lines.setdefault(key, set()).add(row["LINIEN_TEXT"].strip())
                            break
                print(f"\n=== {os.path.basename(zp)} :: {name}  rows={n:,}")
                print("  products:", dict(prod))
                print("  non-rail REAL rows:", dict(real))
                print("  rows by town prefix:", dict(townrows))
                for key in sorted(lines):
                    print(f"   {key[0]:<6} {key[1]:<10} lines={sorted(lines[key])[:14]}{'...' if len(lines[key])>14 else ''} ({len(lines[key])})")


if __name__ == "__main__":
    main()
