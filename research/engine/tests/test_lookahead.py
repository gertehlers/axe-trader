import numpy as np
import pandas as pd
import pytest

from engine.lookahead import LookaheadError, check_lookahead
from engine.strategy import Enter


def _frame(n=200):
    rng = np.random.default_rng(7)
    closes = 100 + np.cumsum(rng.normal(0, 0.5, n))
    index = pd.date_range("2024-01-01", periods=n, freq="5min", tz="UTC")
    return pd.DataFrame({
        "open_bid": closes, "open_ask": closes + 0.2,
        "high_bid": closes + 1, "high_ask": closes + 1.2,
        "low_bid": closes - 1, "low_ask": closes - 0.8,
        "close_bid": closes, "close_ask": closes + 0.2,
        "volume": np.ones(n), "minutes_present": np.full(n, 5), "complete": np.ones(n, dtype=bool),
    }, index=index)


class HonestStrategy:
    timeframe = "5min"

    def on_bar(self, history, position):
        if len(history) < 2:
            return []
        if history.close_bid[-1] > history.close_bid[-2]:
            return [Enter(side="LONG", stop=history.close_bid[-1] - 2)]
        return []


class PeekingStrategy:
    """Leak shape 1: reaches past the view into the underlying frame."""

    timeframe = "5min"

    def on_bar(self, history, position):
        frame = history._frame
        i = len(history) - 1
        nxt = frame["close_bid"].to_numpy()
        if i + 1 < len(nxt) and nxt[i + 1] > nxt[i]:
            return [Enter(side="LONG", stop=float(nxt[i]) - 2)]
        return []


class PrecomputingStrategy:
    """Leak shape 2: captures the whole series at construction."""

    timeframe = "5min"

    def __init__(self, frame):
        self._all = frame["close_bid"].to_numpy()

    def on_bar(self, history, position):
        i = len(history) - 1
        if i + 1 < len(self._all) and self._all[i + 1] > self._all[i]:
            return [Enter(side="LONG", stop=float(self._all[i]) - 2)]
        return []


def test_guard_passes_an_honest_strategy():
    check_lookahead(HonestStrategy(), _frame())


def test_guard_passes_an_honest_strategy_built_from_a_factory():
    check_lookahead(lambda frame: HonestStrategy(), _frame())


def test_guard_rejects_a_strategy_that_peeks_past_the_view():
    with pytest.raises(LookaheadError):
        check_lookahead(PeekingStrategy(), _frame())


def test_guard_rejects_a_strategy_that_precomputes_over_the_whole_series():
    with pytest.raises(LookaheadError):
        check_lookahead(PrecomputingStrategy, _frame())


def test_a_bare_instance_cannot_catch_a_precomputed_leak():
    """Documents the limit: reusing one instance leaves its captured array untruncated.

    This is why the factory form exists and why strategies with precomputed state must use it.
    """
    frame = _frame()
    check_lookahead(PrecomputingStrategy(frame), frame)  # passes despite leaking
