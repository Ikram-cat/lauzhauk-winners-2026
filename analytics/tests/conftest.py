"""Make the `sbb_duration` package importable when pytest runs from the repository root (CI:
`python -m pytest analytics`) as well as from `analytics/`. The colleague's tests import
`analytics.<module>` from the root and are unaffected."""
import sys
from pathlib import Path

ANALYTICS_DIR = str(Path(__file__).resolve().parents[1])
if ANALYTICS_DIR not in sys.path:
    sys.path.insert(0, ANALYTICS_DIR)
