import numpy as np
import pandas as pd
import pytest

from engine.flips import flip_trades, summarise

SPEC = {"lot_size": 1, "overnight_fee": {"charge_time_utc": "21:00", "long_rate": -0.01, "short_rate": -0.02}}


def _bars(opens, spread=1.0, highs=None, lows=None, start="2024-01-02 10:00"):
    opens = np.asarray(opens, dtype=float)
    highs = opens + 5 if highs is None else np.asarray(highs, dtype=float)
    lows = opens - 5 if lows is None else np.asarray(lows, dtype=float)
    idx = pd.date_range(start, periods=len(opens), freq="1h", tz="UTC")
    return pd.DataFrame({"open_bid": opens, "high_bid": highs, "low_bid": lows, "close_bid": opens,
                         "open_ask": opens + spread, "high_ask": highs + spread, "low_ask": lows + spread,
                         "close_ask": opens + spread}, index=idx)


def test_enters_at_the_next_open_and_reverses_on_each_flip():
    bars = _bars([100, 101, 110, 120, 115, 100, 90])
    direction = np.array([-1, 1, 1, 1, -1, -1, -1])
    trades = flip_trades(bars, direction, SPEC)
    # Flip up on bar 1's close -> long at bar 2's ask (111). Flip down on bar 4 -> exit at bar 5's bid (100)
    # and go short at that bid. Bar 6 has no later flip, so the short is still open and not counted.
    assert len(trades) == 1
    t = trades.iloc[0]
    assert t.side == "LONG"
    assert t.entry_time == bars.index[2] and t.entry_price == 111
    assert t.exit_time == bars.index[5] and t.exit_price == 100
    assert t.gross_pts == pytest.approx(-11)
    assert t.spread_pts == pytest.approx(1.0)  # half the spread on the way in, half on the way out


def test_short_buys_back_at_the_ask():
    bars = _bars([100, 99, 90, 80, 85, 95])
    direction = np.array([1, -1, -1, -1, 1, 1])
    t = flip_trades(bars, direction, SPEC).iloc[0]
    assert t.side == "SHORT"
    assert t.entry_price == 90 and t.exit_price == 96  # sold at bid 90, bought back at ask 95 + 1
    assert t.gross_pts == pytest.approx(-6)


def test_excursions_cover_the_bars_held():
    bars = _bars([100, 100, 100, 100, 100, 100], highs=[105, 105, 130, 104, 103, 105],
                 lows=[95, 95, 99, 70, 97, 95])
    direction = np.array([-1, 1, 1, 1, -1, -1])
    t = flip_trades(bars, direction, SPEC).iloc[0]
    # Long from bar 2's ask (101), held through bars 2..4, out at bar 5's open.
    assert t.mfe_pts == pytest.approx(130 - 101)
    assert t.mae_pts == pytest.approx(101 - 70)
    assert t.bars == 3


def test_overnight_funding_is_charged_per_cut_off_inside_the_hold():
    bars = _bars([100] * 40, spread=0.0, start="2024-01-02 10:00")
    direction = np.array([-1] + [1] * 35 + [-1] * 4)
    t = flip_trades(bars, direction, SPEC).iloc[0]
    # Long from 2024-01-02 11:00 to 2024-01-03 23:00: the 21:00 cut-offs on the 2nd and 3rd -> 2 charges
    # of 0.01% of 100.
    assert t.financing_pts == pytest.approx(-0.02)
    assert t.net_pts == pytest.approx(t.gross_pts - 0.02)


def test_flips_before_start_are_ignored():
    bars = _bars([100, 101, 110, 120, 115, 100, 90])
    direction = np.array([-1, 1, 1, 1, -1, -1, -1])
    assert flip_trades(bars, direction, SPEC, start=bars.index[3]).empty


def test_summary_counts_streaks_and_drawdown():
    trades = pd.DataFrame({"net_pts": [5.0, -2.0, -3.0, -1.0, 10.0, -4.0], "side": ["LONG", "SHORT"] * 3,
                           "mfe_pts": [8.0] * 6, "gross_pts": [5.0, -2.0, -3.0, -1.0, 10.0, -4.0],
                           "financing_pts": [0.0] * 6, "spread_pts": [0.5] * 6, "bars": [3] * 6})
    s = summarise(trades)
    assert s["trades"] == 6 and s["wins"] == 2
    assert s["net_pts"] == pytest.approx(5.0)
    assert s["longest_losing_streak"] == 3
    assert s["max_drawdown_pts"] == pytest.approx(6.0)  # peak 5 -> trough -1
    assert s["profit_factor"] == pytest.approx(15 / 10)
