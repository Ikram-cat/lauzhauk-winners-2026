import pytest

from sbb_duration.advice import Advice, advice


@pytest.mark.parametrize("detour, expected", [
    (29.9, Advice.TAKE_DETOUR),
    (30.0, Advice.DETOUR_IF_DEADLINE),  # detour == p50: no longer "probably outlasts"
    (59.9, Advice.DETOUR_IF_DEADLINE),
    (60.0, Advice.WAIT),                # detour == pessimistic
    (None, Advice.UNKNOWN),
])
def test_advice_at_thresholds(detour, expected):
    assert advice(30.0, 60.0, detour) is expected


def test_advice_without_estimate_is_unknown():
    assert advice(None, None, 10) is Advice.UNKNOWN
