"""From a prediction to an advice code. The UI localizes the code."""
from enum import Enum


class Advice(str, Enum):
    TAKE_DETOUR = "TAKE_DETOUR"                # disruption will probably outlast the detour
    DETOUR_IF_DEADLINE = "DETOUR_IF_DEADLINE"  # real risk it lasts longer than the detour
    WAIT = "WAIT"                              # should be over before the detour pays off
    UNKNOWN = "UNKNOWN"                        # no estimate or no detour cost


def advice(p50: float | None, pessimistic: float | None, detour_extra_min: float | None) -> Advice:
    """detour_extra_min: extra travel time of the best alternative, in minutes."""
    if p50 is None or pessimistic is None or detour_extra_min is None:
        return Advice.UNKNOWN
    if detour_extra_min < p50:
        return Advice.TAKE_DETOUR
    if detour_extra_min < pessimistic:
        return Advice.DETOUR_IF_DEADLINE
    return Advice.WAIT
