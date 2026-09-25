"""Explanations must quote what the engine decided, never a story fitted afterwards."""
import pandas as pd
import pytest

from conftest import make_minute_frame
from engine.bars import resample
from engine.explain import explain_bar
from engine.pillars import PillarConfig
from engine.review import signal_bar_index, voting_pillars
from engine.simulator import SimConfig, simulate
from engine.strategies.confluence import OppositeConfluenceExit, TimeExit

SPEC = {
    "currency": "USD", "lot_size": 1, "min_deal_size": 0.01, "size_increment": 0.01,
    "overnight_fee": {"long_rate": -0.0215402, "short_rate": -0.000682,
                      "charge_time_utc": "21:00", "interval_minutes": 1440},
}
EASY = PillarConfig(confluence_threshold=2)


@pytest.fixture(scope="module")
def traced_run():
    minutes = make_minute_frame(n=20_000)
    bars = resample(minutes, "15min")
    strategy = OppositeConfluenceExit(bars, config=EASY)
    trace: dict = {}
    trades, _ = simulate(strategy, minutes, bars, SPEC, SimConfig(), trace=trace)
    assert trades, "no trades on the synthetic series: nothing to explain"
    return bars, strategy, trace, trades


def test_entry_explanation_reproduces_the_recorded_votes(traced_run):
    bars, strategy, trace, trades = traced_run
    for trade in trades:
        bar = signal_bar_index(bars.index, trade.entry_time)
        story = explain_bar(strategy, trace, bar)
        side = story["sides"][trade.side]
        voted = [p["name"] for p in side["pillars"] if p["vote"]]
        assert voted == voting_pillars(strategy.votes, bar, trade.side)
        assert side["score"] == len(voted)
        assert side["gate"] is True
        assert story["action"] == f"ENTER {trade.side}"


def test_every_pillar_quotes_its_readings(traced_run):
    bars, strategy, trace, _ = traced_run
    story = explain_bar(strategy, trace, strategy.warmup + 5)
    pillars = {p["name"]: p for p in story["sides"]["LONG"]["pillars"]}
    assert set(pillars) == set(strategy.votes.names)
    assert pillars["RSI+BB"]["readings"]["rsi"] == pytest.approx(
        strategy.votes.readings["rsi"][strategy.warmup + 5], abs=1e-3)
    assert "rule" in pillars["S/R"]
    assert story["sides"]["LONG"]["threshold"] == 2


def test_exit_explanation_carries_the_trigger(traced_run):
    bars, strategy, trace, trades = traced_run
    for trade in trades:
        if trade.exit_reason != "SIGNAL":
            continue
        bar = signal_bar_index(bars.index, trade.exit_time)
        story = explain_bar(strategy, trace, bar)
        assert story["action"] == "EXIT"
        exit_event = next(e for e in story["events"] if e["kind"] == "exit")
        assert "bearish" in exit_event["why"] or "bullish" in exit_event["why"]
        return
    pytest.skip("no signal exit in this series")


def test_a_qualifying_signal_while_in_a_position_is_reported_as_blocked(traced_run):
    bars, strategy, trace, _ = traced_run
    for bar, record in trace.items():
        if record["position"] is None:
            continue
        side = record["position"]["side"]
        score = strategy.votes.bullish_score if side == "LONG" else strategy.votes.bearish_score
        gate = strategy.votes.long_gate if side == "LONG" else strategy.votes.short_gate
        if score[bar] >= 2 and gate[bar]:
            story = explain_bar(strategy, trace, bar)
            assert any("position" in reason for reason in story["blocked_by"])
            return
    pytest.skip("no same-side signal arrived while a position was open")


def test_a_quiet_bar_says_which_side_fell_short_and_why():
    minutes = make_minute_frame(n=20_000)
    bars = resample(minutes, "15min")
    strategy = TimeExit(bars, config=PillarConfig())      # real threshold: most bars are quiet
    trace: dict = {}
    simulate(strategy, minutes, bars, SPEC, SimConfig(), trace=trace)
    quiet = next(i for i in range(strategy.warmup, len(bars))
                 if i not in trace and strategy.votes.bullish_score[i] < 3
                 and strategy.votes.bearish_score[i] < 3)
    story = explain_bar(strategy, trace, quiet)
    assert story["action"] == "NONE"
    assert story["position"] is None
    assert any("LONG" in reason and "3" in reason for reason in story["blocked_by"])


def test_warmup_bars_say_so():
    minutes = make_minute_frame()
    bars = resample(minutes, "15min")
    strategy = TimeExit(bars)
    story = explain_bar(strategy, {}, 10)
    assert story["action"] == "NONE"
    assert any("warm-up" in reason for reason in story["blocked_by"])
    assert story["time"] == pd.Timestamp(bars.index[10]).isoformat()
