import math

import pytest

from sbb_duration.artifact import finalize


@pytest.mark.parametrize("raw50, raw_pessimistic", [
    (math.log1p(60), math.log1p(90)),  # normal case
    (math.log1p(60), math.log1p(40)),  # quantile crossing
    (-0.5, -0.7),                      # both below zero minutes
])
def test_pessimistic_is_never_below_p50(raw50, raw_pessimistic):
    p50, pessimistic = finalize(raw50, raw_pessimistic)
    assert 0.0 <= p50 <= pessimistic
