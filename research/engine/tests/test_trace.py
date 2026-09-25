"""The decision trace: what the simulator did on each bar, recorded as it happened.

The trace exists so explanations quote the engine rather than reconstruct it after the fact, so its
first obligation is to change nothing: a traced run must produce exactly the untraced trades.
"""
import pandas as pd
import pytest

from conftest import make_minute_frame
from engine.bars import resample
from engine.pillars import PillarConfig
from engine.simulator import SimConfig, simulate
from engine.strategies.confluence import (OppositeConfluenceExit, ReversalExit, SymmetricExit,
                                          TimeExit, TrailingExit)

SPEC = {
    "currency": "USD", "lot_size": 1, "min_deal_size": 0.01, "size_increment": 0.01,
    "overnight_fee": {"long_rate": -0.0215402, "short_rate": -0.000682,
                      "charge_time_utc": "21:00", "interval_minutes": 1440},
}
# Threshold 1 so a synthetic walk actually trades; the trace mechanics are what is under test.
EASY = PillarConfig(confluence_threshold=1)


ARMS = [
    lambda bars: SymmetricExit(bars, stop_atr=1.0, config=EASY, timeframe="15min"),
    lambda bars: TrailingExit(bars, trail_atr=2.0, config=EASY, timeframe="15min"),
    lambda bars: TimeExit(bars, max_bars=6, config=EASY, timeframe="15min"),
    lambda bars: ReversalExit(bars, config=EASY, timeframe="15min"),
    lambda bars: OppositeConfluenceExit(bars, config=EASY, timeframe="15min"),
]


@pytest.fixture(scope="module")
def market():
    minutes = make_minute_frame()
    return minutes, resample(minutes, "15min")


@pytest.mark.parametrize("build", ARMS)
def test_tracing_changes_no_trade(market, build):
    minutes, bars = market
    plain, plain_skipped = simulate(build(bars), minutes, bars, SPEC, SimConfig())
    trace: dict = {}
    traced, traced_skipped = simulate(build(bars), minutes, bars, SPEC, SimConfig(), trace=trace)
    assert plain, "no trades: the comparison proves nothing"
    assert traced == plain
    assert traced_skipped == plain_skipped


@pytest.mark.parametrize("build", ARMS)
def test_every_trade_has_a_matching_entry_fill_and_exit_in_the_trace(market, build):
    minutes, bars = market
    trace: dict = {}
    trades, _ = simulate(build(bars), minutes, bars, SPEC, SimConfig(), trace=trace)
    events = [e for bar in sorted(trace) for e in trace[bar]["events"]]
    fills = [e for e in events if e["kind"] == "entry_fill"]
    exits = [e for e in events if e["kind"] == "exit"]
    assert len(exits) == len(trades)
    # A position still open when the data ends is never closed, so it is not a trade.
    assert len(fills) - len(trades) in (0, 1)
    for trade, fill, done in zip(trades, fills, exits):
        assert fill["price"] == trade.entry_price
        assert pd.Timestamp(fill["time"]) == trade.entry_time
        assert done["reason"] == trade.exit_reason
        assert pd.Timestamp(done["time"]) == trade.exit_time
        assert done["price"] == trade.exit_price


def test_trailing_stop_moves_are_recorded_with_old_and_new_level(market):
    minutes, bars = market
    trace: dict = {}
    simulate(TrailingExit(bars, trail_atr=2.0, config=EASY), minutes, bars, SPEC, SimConfig(),
             trace=trace)
    moves = [e for record in trace.values() for e in record["events"] if e["kind"] == "stop_move"]
    assert moves
    for move in moves:
        assert move["from"] != move["to"]


def test_signal_exits_carry_the_strategy_reason(market):
    minutes, bars = market
    trace: dict = {}
    simulate(TimeExit(bars, max_bars=6, config=EASY), minutes, bars, SPEC, SimConfig(), trace=trace)
    signal_exits = [e for record in trace.values() for e in record["events"]
                    if e["kind"] == "exit" and e["reason"] == "SIGNAL"]
    assert signal_exits
    assert all("6 bars" in e["why"] for e in signal_exits)


def test_bars_holding_a_position_record_the_position_the_strategy_saw(market):
    minutes, bars = market
    trace: dict = {}
    trades, _ = simulate(SymmetricExit(bars, stop_atr=1.0, config=EASY), minutes, bars, SPEC,
                         SimConfig(), trace=trace)
    held = [record for record in trace.values() if record["position"] is not None]
    assert held
    assert {record["position"]["side"] for record in held} <= {"LONG", "SHORT"}
