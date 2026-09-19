import pytest

from conftest import make_bar_frame
from engine.lookahead import check_lookahead
from engine.pillars import PillarConfig
from engine.strategies.confluence import (ConfluenceBase, ReversalExit, SymmetricExit,
                                          TimeExit, TrailingExit)
from engine.strategy import BarsView, Enter, Exit, MoveStop, PositionView

# A 3-of-4 confluence fires on ~0.6% of real bars and almost never on a short synthetic frame.
# Tests that need an entry to EXIST use threshold 1, so they exercise the entry mechanics
# deterministically instead of silently skipping; tests about the vote itself keep the real
# threshold.
EASY = PillarConfig(confluence_threshold=1)


def test_entry_requires_three_pillars_and_the_trend_gate():
    f = make_bar_frame()
    strategy = ConfluenceBase(f)
    for i in range(len(f)):
        intents = strategy.on_bar(BarsView(f, i), PositionView.flat())
        if intents:
            assert isinstance(intents[0], Enter)
            if intents[0].side == "LONG":
                assert strategy.votes.bullish_score[i] >= 3
                assert strategy.votes.long_gate[i]
            else:
                assert strategy.votes.bearish_score[i] >= 3
                assert strategy.votes.short_gate[i]


def test_no_entry_during_warmup():
    f = make_bar_frame()
    strategy = ConfluenceBase(f)
    for i in range(min(strategy.warmup, len(f))):
        assert strategy.on_bar(BarsView(f, i), PositionView.flat()) == []


def test_brake_is_placed_at_the_configured_atr_multiple():
    f = make_bar_frame(n=2000)
    strategy = ConfluenceBase(f, config=EASY, brake_atr=10.0)
    mid = ((f["close_bid"] + f["close_ask"]) / 2.0).to_numpy()
    for i in range(len(f)):
        intents = strategy.on_bar(BarsView(f, i), PositionView.flat())
        if intents and isinstance(intents[0], Enter):
            distance = 10.0 * strategy.votes.atr[i]
            expected = mid[i] - distance if intents[0].side == "LONG" else mid[i] + distance
            assert intents[0].stop == pytest.approx(expected)
            return
    pytest.fail("no entry fired: the test cannot verify brake placement")


def test_base_emits_no_exit_of_its_own():
    f = make_bar_frame()
    strategy = ConfluenceBase(f)
    held = PositionView(side="LONG", entry_price=100.0, stop=90.0, target=None, size=1.0)
    assert all(strategy.on_bar(BarsView(f, i), held) == [] for i in range(len(f)))


def test_symmetric_exit_sets_a_target_at_the_stop_distance():
    f = make_bar_frame(n=2000)
    strategy = SymmetricExit(f, stop_atr=1.0, config=EASY)
    mid = ((f["close_bid"] + f["close_ask"]) / 2.0).to_numpy()
    for i in range(len(f)):
        intents = strategy.on_bar(BarsView(f, i), PositionView.flat())
        if intents and isinstance(intents[0], Enter):
            entry = intents[0]
            assert entry.target is not None
            assert abs(entry.target - mid[i]) == pytest.approx(abs(entry.stop - mid[i]))
            return
    pytest.fail("no entry fired: the test cannot verify target symmetry")


def test_strategy_passes_the_lookahead_guard_as_a_factory():
    f = make_bar_frame()
    # An INSTANCE would pass this check even if it leaked, because its precomputed arrays are
    # never truncated. The factory form is the only one that actually tests anything.
    check_lookahead(lambda frame_: SymmetricExit(frame_, stop_atr=1.0), f)


def test_time_exit_fires_exactly_n_bars_after_entry():
    f = make_bar_frame(n=2000)
    strategy = TimeExit(f, max_bars=6, config=EASY)
    held = PositionView(side="LONG", entry_price=100.0, stop=90.0, target=None, size=1.0)
    entered = None
    for i in range(len(f)):
        if entered is None:
            if strategy.on_bar(BarsView(f, i), PositionView.flat()):
                entered = i
            continue
        intents = strategy.on_bar(BarsView(f, i), held)
        if i - entered < 6:
            assert intents == [], f"exited early at bar {i - entered}"
        else:
            assert intents == [Exit()]
            return
    pytest.fail("no entry fired: the test cannot verify the time stop")


def test_trailing_stop_ratchets_up_and_never_down_for_a_long():
    f = make_bar_frame(n=2000)
    strategy = TrailingExit(f, trail_atr=2.0)
    strategy.entry_index = 700
    strategy.high_water = float(strategy.mid_high[700])
    held = PositionView(side="LONG", entry_price=100.0, stop=0.0, target=None, size=1.0)

    seen = [0.0]
    for i in range(701, 900):
        intents = strategy.on_bar(BarsView(f, i), held)
        for intent in intents:
            assert isinstance(intent, MoveStop)
            assert intent.price > seen[-1], "stop moved away from price"
            seen.append(intent.price)
            held = PositionView(side="LONG", entry_price=100.0, stop=intent.price,
                                target=None, size=1.0)
    assert seen == sorted(seen)
    assert len(seen) > 1, "the trail never moved at all"


def test_trailing_stop_emits_nothing_when_the_level_would_not_improve():
    f = make_bar_frame(n=2000)
    strategy = TrailingExit(f, trail_atr=2.0)
    strategy.entry_index = 700
    strategy.high_water = float(strategy.mid_high[700])
    # A stop already far above anything the trail would produce must produce no intent.
    held = PositionView(side="LONG", entry_price=100.0, stop=1e9, target=None, size=1.0)
    assert strategy.on_bar(BarsView(f, 701), held) == []


def test_reversal_exit_fires_when_the_held_side_stops_voting():
    f = make_bar_frame(n=2000)
    strategy = ReversalExit(f)
    held = PositionView(side="LONG", entry_price=100.0, stop=90.0, target=None, size=1.0)
    for i in range(strategy.warmup, len(f)):
        intents = strategy.on_bar(BarsView(f, i), held)
        if strategy.votes.bullish_score[i] >= strategy.config.confluence_threshold:
            assert intents == []
        else:
            assert intents == [Exit()]


def test_reversal_exit_reads_the_held_side_not_the_other_one():
    f = make_bar_frame(n=2000)
    strategy = ReversalExit(f)
    short = PositionView(side="SHORT", entry_price=100.0, stop=110.0, target=None, size=1.0)
    for i in range(strategy.warmup, len(f)):
        expected = [] if strategy.votes.bearish_score[i] >= 3 else [Exit()]
        assert strategy.on_bar(BarsView(f, i), short) == expected
