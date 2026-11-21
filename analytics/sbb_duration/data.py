"""Load every published version of every SBB liveticker incident.

Each row of `incident_audit` (old versions) and `incident` (current version)
is one version of an incident, i.e. what the public knew at that moment.
"""
import hashlib
import os
from pathlib import Path

import pandas as pd

ANALYTICS_DIR = Path(__file__).resolve().parents[1]
DATA_DIR = Path(os.environ.get("SBB_DATA_DIR", ANALYTICS_DIR / "Daten" / "Daten" / "extern-liveticker"))
OUTPUT_DIR = Path(os.environ.get("SBB_OUTPUT_DIR", ANALYTICS_DIR / "output"))

INCIDENT_FILE = "incident_202610121914.csv"
AUDIT_FILE = "incident_audit_202610121914.csv"

COLUMNS = ["id", "duration_type", "duration_to", "reason_id_fk",
           "last_modified_date", "created_at_source", "updated_at_source",
           "lifecycle", "summary_en"]
TIME_COLUMNS = ["duration_to", "last_modified_date", "created_at_source", "updated_at_source"]


def load_versions(data_dir: Path = DATA_DIR) -> pd.DataFrame:
    """All versions of all incidents, ordered by last_modified_date per incident."""
    data_dir = Path(data_dir)
    frames = [pd.read_csv(data_dir / name, dtype={"id": str}, usecols=COLUMNS)
              for name in (AUDIT_FILE, INCIDENT_FILE)]
    versions = pd.concat(frames, ignore_index=True)
    for column in TIME_COLUMNS:
        versions[column] = pd.to_datetime(versions[column], utc=True, errors="coerce")
    # Source README: order versions by last_modified_date, not by `version`.
    versions = versions.sort_values(["id", "last_modified_date"])
    return versions.drop_duplicates(["id", "last_modified_date"]).reset_index(drop=True)


def file_checksums(data_dir: Path = DATA_DIR) -> dict:
    """SHA-256 of the input files, recorded as dataset provenance."""
    return {name: hashlib.sha256((Path(data_dir) / name).read_bytes()).hexdigest()
            for name in (INCIDENT_FILE, AUDIT_FILE)}
