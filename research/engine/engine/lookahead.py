"""The mandatory lookahead guard (spec 3.3).

Run the strategy on the full series and on the series truncated at random cut points. Any
intent that differs before a cut means the strategy read data it could not have had. A
strategy that fails this is rejected outright — no result from it is admissible.

Two shapes of leak exist and the guard must catch both:

1. Reading past the current bar in `on_bar` — e.g. reaching into the underlying frame instead
   of the view. Truncation changes what is there to read, so intents diverge.
2. Capturing the whole series at construction — a precomputed indicator over all the data.
   Re-running the *same instance* on a truncated frame cannot catch this, because the captured
   array never changes. So pass a factory: a callable taking the frame and returning a fresh
   strategy. The factory is rebuilt per truncation and the capture is truncated with it.

Passing a bare instance only tests shape 1. Prefer a factory.
"""

from __future__ import annotations

from typing import Callable

import numpy as np
import pandas as pd

from engine.strategy import BarsView, Intent, PositionView


class LookaheadError(AssertionError):
    pass


def collect_intents(strategy, frame: pd.DataFrame) -> list[tuple[pd.Timestamp, list[Intent]]]:
    flat = PositionView.flat()
    out = []
    for index in range(len(frame)):
        out.append((frame.index[index], list(strategy.on_bar(BarsView(frame, index), flat))))
    return out


def _as_factory(strategy_or_factory) -> Callable[[pd.DataFrame], object]:
    # A class also has `on_bar`, so an instance is "has on_bar AND is not a class".
    if not isinstance(strategy_or_factory, type) and hasattr(strategy_or_factory, "on_bar"):
        return lambda _frame: strategy_or_factory
    return strategy_or_factory


def check_lookahead(strategy_or_factory, frame: pd.DataFrame, cuts: int = 20,
                    seed: int = 20260917) -> None:
    build = _as_factory(strategy_or_factory)
    full = collect_intents(build(frame), frame)
    rng = np.random.default_rng(seed)
    points = rng.choice(np.arange(2, len(frame)), size=min(cuts, len(frame) - 2), replace=False)
    for cut in sorted(int(p) for p in points):
        window = frame.iloc[:cut]
        truncated = collect_intents(build(window), window)
        for (full_time, full_intents), (cut_time, cut_intents) in zip(full[:cut], truncated):
            if full_time != cut_time or full_intents != cut_intents:
                raise LookaheadError(
                    f"intents differ at {cut_time} when data is truncated at bar {cut}: "
                    f"full={full_intents!r} truncated={cut_intents!r}")
