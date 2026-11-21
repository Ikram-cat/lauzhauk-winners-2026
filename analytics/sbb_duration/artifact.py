"""Dependency-free evaluator of duration_model.v1.json.

This is the reference the Kotlin evaluator mirrors: same traversal, same
post-processing. It must match sklearn on golden_vectors.json.
"""
import json
import math
from pathlib import Path

SCHEMA_VERSION = 1


def finalize(raw_p50: float, raw_pessimistic: float) -> tuple[float, float]:
    """log1p-scale outputs -> minutes, with 0 <= p50 <= pessimistic (no quantile crossing)."""
    p50 = max(math.expm1(raw_p50), 0.0)
    pessimistic = max(math.expm1(raw_pessimistic), p50)
    return p50, pessimistic


def load_artifact(path: Path) -> dict:
    artifact = json.loads(Path(path).read_text(encoding="utf-8"))
    if artifact.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"unsupported schemaVersion {artifact.get('schemaVersion')!r}")
    return artifact


def _encode(artifact: dict, features: dict) -> list:
    """Feature dict -> model row. Categories become codes, unknown/missing -> None."""
    row = []
    for spec in artifact["features"]:
        value = features.get(spec["name"])
        if spec["type"] == "categorical":
            row.append(spec["categories"].index(value) if value in spec["categories"] else None)
        else:
            row.append(None if value is None or (isinstance(value, float) and math.isnan(value)) else float(value))
    return row


def _tree_value(nodes: list, row: list) -> float:
    node = nodes[0]
    while "leaf" not in node:
        x = row[node["feature"]]
        if x is None:
            go_left = node["missingLeft"]
        elif "leftCategories" in node:
            go_left = x in node["leftCategories"]
        else:
            go_left = x <= node["threshold"]
        node = nodes[node["left"] if go_left else node["right"]]
    return node["leaf"]


def predict_raw(artifact: dict, features: dict) -> tuple[float, float]:
    """Raw (log1p-scale) P50 and pessimistic estimate."""
    row = _encode(artifact, features)
    return tuple(
        model["baseline"] + sum(_tree_value(tree, row) for tree in model["trees"])
        for model in (artifact["models"]["p50"], artifact["models"]["pessimistic"])
    )


def predict(artifact: dict, features: dict) -> tuple[float, float]:
    """(P50, pessimistic) remaining minutes."""
    return finalize(*predict_raw(artifact, features))
