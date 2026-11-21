"""Load the trained bundle and predict remaining minutes with sklearn."""
from pathlib import Path

import joblib
import pandas as pd

from .artifact import finalize
from .data import OUTPUT_DIR

BUNDLE_FILE = "duration_model.joblib"
_cache: dict = {}


def load_bundle(path: Path | None = None) -> dict:
    path = Path(path or OUTPUT_DIR / BUNDLE_FILE)
    if not path.exists():
        raise FileNotFoundError(
            f"{path} not found. Run `python -m sbb_duration.train` from analytics/ first.")
    key = path.resolve()
    if key not in _cache:
        _cache[key] = joblib.load(path)
    return _cache[key]


def to_frame(rows: list[dict], bundle: dict) -> pd.DataFrame:
    """Feature dicts -> model input. Categories unknown to training become missing."""
    frame = pd.DataFrame(rows)[bundle["features"]]
    for column, categories in bundle["categories"].items():
        known = frame[column].where(frame[column].isin(categories))
        frame[column] = pd.Categorical(known, categories=categories)
    for column in frame.columns.difference(list(bundle["categories"])):
        frame[column] = frame[column].astype(float)
    return frame


def predict_raw(rows: list[dict], bundle: dict) -> list[tuple[float, float]]:
    frame = to_frame(rows, bundle)
    raw50, raw_pess = (bundle["models"][name].predict(frame) for name in ("p50", "pessimistic"))
    return [(float(a), float(b)) for a, b in zip(raw50, raw_pess)]


def predict_remaining(features: dict, bundle: dict | None = None) -> tuple[float, float]:
    """features: dict with the FEATURES keys. Returns (P50, pessimistic) in minutes."""
    return finalize(*predict_raw([features], bundle or load_bundle())[0])
