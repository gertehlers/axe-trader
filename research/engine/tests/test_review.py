import numpy as np
import pandas as pd
import pytest

from engine.pillars import PillarConfig, compute_pillars
from engine.review import (PAD_BEFORE, candle_window, signal_bar_index, trade_payloads,
                           voting_pillars)
from conftest import make_bar_frame


def test_signal_bar_index_picks_the_last_bar_strictly_before_the_fill():
    bars = make_bar_frame(n=10)
    # A fill one minute after bars[3] closed was caused by bars[3], not bars[4].
    moment = bars.index[3] + pd.Timedelta(minutes=1)
    assert signal_bar_index(bars.index, moment) == 3


def test_signal_bar_index_does_not_use_a_bar_that_had_not_closed():
    bars = make_bar_frame(n=10)
    # A fill exactly at a bar's timestamp cannot have been caused by that bar: at that instant the
    # bar is only opening. This is the lookahead boundary, so it gets its own test.
    assert signal_bar_index(bars.index, bars.index[5]) == 4


def test_candle_window_is_padded_either_side_and_carries_ohlc_at_mid():
    bars = make_bar_frame(n=200)
    window = candle_window(bars, entry_index=100, exit_index=104, pad_before=10, pad_after=5)
    assert len(window) == 10 + (104 - 100) + 5 + 1
    epoch, open_, high, low, close = window[10]          # the entry bar itself
    assert epoch == int(bars.index[100].timestamp())
    expected_mid = (bars["close_bid"].iloc[100] + bars["close_ask"].iloc[100]) / 2.0
    assert close == pytest.approx(expected_mid, abs=1e-5)
    assert low <= open_ <= high and low <= close <= high


def test_candle_window_clamps_at_the_frame_edges():
    bars = make_bar_frame(n=30)
    assert len(candle_window(bars, 0, 1, pad_before=10, pad_after=2)) == 4      # no bars before 0
    assert candle_window(bars, 28, 29, pad_before=2, pad_after=10)[-1][0] == \
        int(bars.index[29].timestamp())                                        # stops at the end


def test_voting_pillars_reports_the_side_specific_votes():
    bars = make_bar_frame(n=400)
    votes = compute_pillars(bars, PillarConfig())
    bar = int(np.argmax(votes.bullish_score))
    names = voting_pillars(votes, bar, "LONG")
    assert len(names) == int(votes.bullish_score[bar])
    assert all(votes.bullish[name][bar] for name in names)
    # The short table is consulted for a SHORT trade, not the long one.
    assert voting_pillars(votes, bar, "SHORT") == \
        [n for n in votes.names if votes.bearish[n][bar]]


def test_trade_payloads_places_the_markers_where_the_trade_happened():
    bars = make_bar_frame(n=300)
    votes = compute_pillars(bars, PillarConfig())
    entry_bar, exit_bar = 100, 103
    trades = pd.DataFrame([{
        "entry_time": bars.index[entry_bar] + pd.Timedelta(minutes=1),
        "entry_price": 100.0,
        "exit_time": bars.index[exit_bar] + pd.Timedelta(minutes=1),
        "exit_price": 101.0,
        "side": "LONG", "size": 10.0, "stop": 98.0, "target": 101.5,
        "exit_reason": "TARGET", "gross_usd": 10.0, "spread_cost_usd": 0.2,
        "financing_usd": 0.0, "slippage_usd": 0.0, "net_usd": 9.8, "r": 0.5,
    }])
    payload, = trade_payloads(trades, bars, votes, "OIL_CRUDE")

    assert payload["eb"] == PAD_BEFORE                  # entry offset inside its own window
    assert payload["xb"] == PAD_BEFORE + (exit_bar - entry_bar)
    assert payload["b"][payload["eb"]][0] == int(bars.index[entry_bar].timestamp())
    assert payload["b"][payload["xb"]][0] == int(bars.index[exit_bar].timestamp())
    assert payload["w"] == 1 and payload["xr"] == "TARGET"
    assert payload["conf"] == int(votes.bullish_score[entry_bar])


def test_trade_payloads_tolerates_an_arm_with_no_target():
    bars = make_bar_frame(n=300)
    votes = compute_pillars(bars, PillarConfig())
    trades = pd.DataFrame([{
        "entry_time": bars.index[50] + pd.Timedelta(minutes=1), "entry_price": 100.0,
        "exit_time": bars.index[52] + pd.Timedelta(minutes=1), "exit_price": 99.0,
        "side": "SHORT", "size": 10.0, "stop": 102.0, "target": None,
        "exit_reason": "SIGNAL", "gross_usd": -1.0, "spread_cost_usd": 0.2,
        "financing_usd": 0.0, "slippage_usd": 0.0, "net_usd": -1.2, "r": -0.06,
    }])
    payload, = trade_payloads(trades, bars, votes, "OIL_CRUDE")
    assert payload["tp"] is None                        # E2-E4 have no target line to draw
    assert payload["w"] == 0


def _trade(bars, entry_bar, exit_bar, side="LONG", reason="TARGET", r=0.5, target=101.5):
    return {
        "entry_time": bars.index[entry_bar] + pd.Timedelta(minutes=1), "entry_price": 100.0,
        "exit_time": bars.index[exit_bar] + pd.Timedelta(minutes=1), "exit_price": 101.0,
        "side": side, "size": 10.0, "stop": 98.0, "target": target,
        "exit_reason": reason, "gross_usd": 10.0, "spread_cost_usd": 0.2,
        "financing_usd": 0.0, "slippage_usd": 0.0, "net_usd": 9.8, "r": r,
    }


def test_align_entries_puts_every_arms_exit_on_one_shared_window():
    from engine.review import align_entries
    bars = make_bar_frame(n=300)
    votes = compute_pillars(bars, PillarConfig())
    per_arm = {
        "symmetric": pd.DataFrame([_trade(bars, 100, 102, reason="STOP", r=-1.0)]),
        "time": pd.DataFrame([_trade(bars, 100, 110, reason="SIGNAL", r=0.2, target=None)]),
    }
    record, = align_entries(per_arm, bars, votes, "OIL_CRUDE")

    assert set(record["arms"]) == {"symmetric", "time"}
    assert record["arms"]["symmetric"]["xr"] == "STOP"
    assert record["arms"]["time"]["tp"] is None
    # One window, and both arms' exit markers land inside it at the right bars.
    assert record["b"][record["eb"]][0] == int(bars.index[100].timestamp())
    assert record["b"][record["arms"]["symmetric"]["xb"]][0] == int(bars.index[102].timestamp())
    assert record["b"][record["arms"]["time"]["xb"]][0] == int(bars.index[110].timestamp())
    # The window must reach the LATEST exit, not just the first one.
    assert record["arms"]["time"]["xb"] < len(record["b"])


def test_align_entries_keeps_an_entry_only_some_arms_took():
    from engine.review import align_entries
    bars = make_bar_frame(n=300)
    votes = compute_pillars(bars, PillarConfig())
    per_arm = {
        "symmetric": pd.DataFrame([_trade(bars, 100, 102), _trade(bars, 150, 152)]),
        "time": pd.DataFrame([_trade(bars, 100, 104)]),      # busy, missed the 150 signal
    }
    records = align_entries(per_arm, bars, votes, "OIL_CRUDE")
    assert [sorted(r["arms"]) for r in records] == [["symmetric", "time"], ["symmetric"]]


def test_align_entries_separates_the_two_sides_at_one_timestamp():
    from engine.review import align_entries
    bars = make_bar_frame(n=300)
    votes = compute_pillars(bars, PillarConfig())
    per_arm = {"symmetric": pd.DataFrame([_trade(bars, 100, 102, side="LONG"),
                                          _trade(bars, 100, 103, side="SHORT")])}
    records = align_entries(per_arm, bars, votes, "OIL_CRUDE")
    assert [r["d"] for r in records] == ["LONG", "SHORT"] or [r["d"] for r in records] == ["SHORT", "LONG"]
    assert len(records) == 2


def test_align_entries_window_reaches_the_edge_horizon_even_for_a_fast_exit():
    from engine.review import EDGE_HORIZON_BARS, align_entries
    bars = make_bar_frame(n=300)
    votes = compute_pillars(bars, PillarConfig())
    # The reversal arm's mean hold is 0.27h — around one bar. The window must still show the 4h
    # horizon, because "the exit cut the measured edge short" is the comparison the page exists for.
    per_arm = {"reversal": pd.DataFrame([_trade(bars, 100, 101, reason="SIGNAL")])}
    record, = align_entries(per_arm, bars, votes, "OIL_CRUDE")

    assert record["h"] == record["eb"] + EDGE_HORIZON_BARS
    assert record["h"] < len(record["b"]), "the horizon marker must be inside the shipped window"
    assert record["b"][record["h"]][0] == int(bars.index[100 + EDGE_HORIZON_BARS].timestamp())
    assert record["arms"]["reversal"]["xb"] < record["h"], "this exit is inside the horizon"
