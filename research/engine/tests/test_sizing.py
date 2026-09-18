import pytest

from engine.sizing import floor_to_step, position_size


def test_size_is_risk_divided_by_stop_distance_times_value_per_point():
    # 20 USD of risk, a 4.0-point stop, 1 USD per point -> 5 units
    assert position_size(20.0, 4.0, 1.0, min_deal_size=0.01, size_increment=0.01) == 5.0


def test_size_is_floored_to_the_increment_never_rounded_up():
    # 20 / (3.0 * 1.0) = 6.666... -> 6.66 at a 0.01 step
    assert position_size(20.0, 3.0, 1.0, min_deal_size=0.01, size_increment=0.01) == 6.66


def test_size_below_min_deal_size_returns_zero():
    # 20 / (4.0 * 1.0) = 5, but the instrument needs at least 10
    assert position_size(20.0, 4.0, 1.0, min_deal_size=10.0, size_increment=0.1) == 0.0


def test_value_per_point_scales_the_size():
    # each point is worth 2 USD, so the same risk buys half the size
    assert position_size(20.0, 4.0, 2.0, min_deal_size=0.01, size_increment=0.01) == 2.5


def test_zero_stop_distance_is_rejected():
    with pytest.raises(ValueError):
        position_size(20.0, 0.0, 1.0, min_deal_size=0.01, size_increment=0.01)


def test_floor_to_step_is_not_fooled_by_binary_division_error():
    # naive float: 0.29 / 0.01 == 28.999999999999996, which floors to 0.28 and loses a step
    assert floor_to_step(0.29, 0.01) == 0.29
    assert floor_to_step(0.1 + 0.2, 0.01) == 0.3


def test_floor_to_step_really_floors():
    assert floor_to_step(0.29999999999, 0.01) == 0.29
