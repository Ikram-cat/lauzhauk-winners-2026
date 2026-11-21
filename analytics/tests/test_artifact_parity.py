"""The JSON artifact evaluator matches sklearn on the 100 golden cases."""
import json
import math

import pytest

from sbb_duration.artifact import load_artifact, predict
from sbb_duration.data import OUTPUT_DIR
from sbb_duration.export import ARTIFACT_FILE, GOLDEN_FILE
from sbb_duration.features import build_features_from_input
from sbb_duration.predict import BUNDLE_FILE, load_bundle, predict_remaining

TOLERANCE = 1e-6

if not all((OUTPUT_DIR / f).exists() for f in (ARTIFACT_FILE, GOLDEN_FILE, BUNDLE_FILE)):
    pytest.skip("run `python -m sbb_duration.train` then `python -m sbb_duration.export` first",
                allow_module_level=True)


def _nan(features: dict) -> dict:
    return {k: "nan" if v is None or (isinstance(v, float) and math.isnan(v)) else v for k, v in features.items()}


def test_json_evaluator_matches_sklearn_on_golden_cases():
    artifact = load_artifact(OUTPUT_DIR / ARTIFACT_FILE)
    bundle = load_bundle(OUTPUT_DIR / BUNDLE_FILE)
    cases = json.loads((OUTPUT_DIR / GOLDEN_FILE).read_text(encoding="utf-8"))["cases"]
    assert len(cases) == 100
    for case in cases:
        # Features are re-derived from the raw API-shaped input, as the backend will do.
        features = build_features_from_input(case["input"])
        assert _nan(features) == _nan(case["features"]), case["case"]
        from_json = predict(artifact, features)
        from_sklearn = predict_remaining(features, bundle)
        expected = (case["expected"]["p50Minutes"], case["expected"]["pessimisticMinutes"])
        assert from_json == pytest.approx(from_sklearn, abs=TOLERANCE), case["case"]
        assert from_json == pytest.approx(expected, abs=TOLERANCE), case["case"]
