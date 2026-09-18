import pandas as pd
import pytest

from engine.costs import charge_times, financing_usd, usd_per_point

SPEC = {
    "currency": "USD", "lot_size": 1, "min_deal_size": 0.01, "size_increment": 0.01,
    "overnight_fee": {"long_rate": -0.0215402, "short_rate": -0.000682,
                      "charge_time_utc": "21:00", "interval_minutes": 1440},
}


def test_no_charge_when_the_position_closes_before_the_cut_off():
    charges = charge_times(pd.Timestamp("2024-01-02T09:00Z"), pd.Timestamp("2024-01-02T15:00Z"), "21:00")
    assert len(charges) == 0


def test_one_charge_when_the_position_is_held_through_the_cut_off():
    charges = charge_times(pd.Timestamp("2024-01-02T20:00Z"), pd.Timestamp("2024-01-03T09:00Z"), "21:00")
    assert list(charges) == [pd.Timestamp("2024-01-02T21:00Z")]


def test_three_charges_across_a_long_hold():
    charges = charge_times(pd.Timestamp("2024-01-02T20:00Z"), pd.Timestamp("2024-01-05T09:00Z"), "21:00")
    assert len(charges) == 3


def test_long_financing_is_negative_and_scales_with_notional():
    # notional = 2 units * 5000 * 1 USD/point = 10,000; rate -0.0215402 % per charge
    fee = financing_usd("LONG", size=2.0, price=5000.0, spec=SPEC,
                        charges=pd.DatetimeIndex([pd.Timestamp("2024-01-02T21:00Z")]),
                        usd_per_unit_rate=1.0)
    assert fee == pytest.approx(-2.15402, rel=1e-6)


def test_two_charges_cost_twice_one():
    one = financing_usd("LONG", 2.0, 5000.0, SPEC,
                        pd.DatetimeIndex([pd.Timestamp("2024-01-02T21:00Z")]), 1.0)
    two = financing_usd("LONG", 2.0, 5000.0, SPEC,
                        pd.DatetimeIndex([pd.Timestamp("2024-01-02T21:00Z"),
                                          pd.Timestamp("2024-01-03T21:00Z")]), 1.0)
    assert two == pytest.approx(one * 2, rel=1e-9)


def test_short_uses_the_short_rate():
    fee = financing_usd("SHORT", 2.0, 5000.0, SPEC,
                        pd.DatetimeIndex([pd.Timestamp("2024-01-02T21:00Z")]), 1.0)
    assert fee == pytest.approx(-0.0682, rel=1e-6)


def test_weekend_multiplier_scales_a_single_charge():
    plain = financing_usd("LONG", 2.0, 5000.0, SPEC,
                          pd.DatetimeIndex([pd.Timestamp("2024-01-05T21:00Z")]), 1.0)
    tripled = financing_usd("LONG", 2.0, 5000.0, SPEC,
                            pd.DatetimeIndex([pd.Timestamp("2024-01-05T21:00Z")]), 1.0,
                            weekend_multiplier=3.0)
    assert tripled == pytest.approx(plain * 3, rel=1e-9)


def test_non_usd_instrument_converts_through_the_fx_rate():
    # a JPY instrument at 0.0067 USD/JPY: one point of one unit is worth 0.0067 USD
    jpy = dict(SPEC, currency="JPY")
    assert usd_per_point(jpy, fx_rate=0.0067) == pytest.approx(0.0067)
    assert usd_per_point(SPEC, fx_rate=1.0) == 1.0
