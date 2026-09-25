"""The frozen 4-pillar confluence entry, and the five exit arms built on it (spec 4).

Every arm shares one entry so that differences between them are attributable to the exit.
Indicators are precomputed over the whole frame in __init__, which is why these classes MUST be
handed to `check_lookahead` as a factory: an instance's captured arrays are never truncated, so
an instance-based check silently passes a leak.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from engine.pillars import PillarConfig, compute_pillars
from engine.strategy import BarsView, Enter, Exit, Intent, MoveStop, PositionView

WARMUP_BARS = 600


class ConfluenceBase:
    """Entry only. Subclasses add an exit by overriding `exit_intents`."""

    def __init__(self, frame: pd.DataFrame, config: PillarConfig | None = None,
                 brake_atr: float = 10.0, timeframe: str = "15min") -> None:
        self.timeframe = timeframe
        self.config = config or PillarConfig()
        self.brake_atr = brake_atr
        self.votes = compute_pillars(frame, self.config)
        self.bar_times = frame.index          # for explanations only; never read by a decision
        self.mid_close = ((frame["close_bid"] + frame["close_ask"]) / 2.0).to_numpy()
        self.mid_high = ((frame["high_bid"] + frame["high_ask"]) / 2.0).to_numpy()
        self.mid_low = ((frame["low_bid"] + frame["low_ask"]) / 2.0).to_numpy()
        self.warmup = WARMUP_BARS
        self.entry_index: int | None = None
        self.high_water: float = 0.0
        self.low_water: float = 0.0

    # -- entry ------------------------------------------------------------------
    def entry_side(self, index: int) -> str | None:
        if index < self.warmup or not np.isfinite(self.votes.atr[index]):
            return None
        threshold = self.config.confluence_threshold
        if self.votes.bullish_score[index] >= threshold and self.votes.long_gate[index]:
            return "LONG"
        if self.votes.bearish_score[index] >= threshold and self.votes.short_gate[index]:
            return "SHORT"
        return None

    def brake_price(self, index: int, side: str) -> float:
        distance = self.brake_atr * float(self.votes.atr[index])
        price = float(self.mid_close[index])
        return price - distance if side == "LONG" else price + distance

    def enter(self, index: int, side: str) -> list[Intent]:
        """Arms with a target override this; the default is a brake and no target."""
        return [Enter(side=side, stop=self.brake_price(index, side), target=None)]

    # -- exit -------------------------------------------------------------------
    def exit_intents(self, index: int, position: PositionView) -> list[Intent]:
        return []

    # -- driver -----------------------------------------------------------------
    def on_bar(self, history: BarsView, position: PositionView) -> list[Intent]:
        index = len(history) - 1
        if position:
            self.high_water = max(self.high_water, float(self.mid_high[index]))
            self.low_water = min(self.low_water, float(self.mid_low[index]))
            return self.exit_intents(index, position)

        self.entry_index = None
        side = self.entry_side(index)
        if side is None:
            return []
        self.entry_index = index
        self.high_water = float(self.mid_high[index])
        self.low_water = float(self.mid_low[index])
        return self.enter(index, side)


class SymmetricExit(ConfluenceBase):
    """E4: a tight stop with a target at the same distance. 1:1 by construction."""

    def __init__(self, frame: pd.DataFrame, stop_atr: float = 1.0, **kwargs) -> None:
        super().__init__(frame, brake_atr=stop_atr, **kwargs)
        self.stop_atr = stop_atr

    def enter(self, index: int, side: str) -> list[Intent]:
        distance = self.stop_atr * float(self.votes.atr[index])
        price = float(self.mid_close[index])
        if side == "LONG":
            return [Enter(side=side, stop=price - distance, target=price + distance)]
        return [Enter(side=side, stop=price + distance, target=price - distance)]


class TimeExit(ConfluenceBase):
    """E2: out after `max_bars` signal bars, win or lose. The brake still applies throughout."""

    def __init__(self, frame: pd.DataFrame, max_bars: int = 6, **kwargs) -> None:
        super().__init__(frame, **kwargs)
        self.max_bars = max_bars

    def exit_intents(self, index: int, position: PositionView) -> list[Intent]:
        if self.entry_index is None:
            return []
        held = index - self.entry_index
        if held < self.max_bars:
            return []
        return [Exit(why=f"time: held {held} bars, limit {self.max_bars} bars")]


class TrailingExit(ConfluenceBase):
    """E1: the stop ratchets behind price and never away from it.

    Uses the CURRENT bar's ATR. That is only safe because of the one-way ratchet below: a
    volatility spike raises the trail distance but can never widen an existing stop.
    """

    def __init__(self, frame: pd.DataFrame, trail_atr: float = 2.0, **kwargs) -> None:
        super().__init__(frame, **kwargs)
        self.trail_atr = trail_atr

    def exit_intents(self, index: int, position: PositionView) -> list[Intent]:
        distance = self.trail_atr * float(self.votes.atr[index])
        if not np.isfinite(distance):
            return []
        if position.side == "LONG":
            candidate = self.high_water - distance
            return [MoveStop(price=candidate)] if candidate > position.stop else []
        candidate = self.low_water + distance
        return [MoveStop(price=candidate)] if candidate < position.stop else []


class ReversalExit(ConfluenceBase):
    """E3: out when the confluence score for the side actually held falls below the threshold."""

    def exit_intents(self, index: int, position: PositionView) -> list[Intent]:
        score = (self.votes.bullish_score if position.side == "LONG"
                 else self.votes.bearish_score)
        threshold = self.config.confluence_threshold
        if score[index] >= threshold:
            return []
        held = "bullish" if position.side == "LONG" else "bearish"
        return [Exit(why=f"reversal: {held} score {int(score[index])} fell below {threshold}")]


class OppositeConfluenceExit(ConfluenceBase):
    """E5: out when the OPPOSITE side's confluence reaches the entry threshold.

    Holding LONG, leave when enough bearish pillars agree to have opened a SHORT (the trend gate
    is not required: this is a warning, not an entry). Unlike ReversalExit, the held side merely
    losing its votes is no reason to leave, which matters because two of the four pillars are
    one-bar events and drop out almost immediately. The brake stays as the protective stop.
    """

    def __init__(self, frame: pd.DataFrame, target_atr: float | None = None, **kwargs) -> None:
        super().__init__(frame, **kwargs)
        self.target_atr = target_atr

    def enter(self, index: int, side: str) -> list[Intent]:
        """The brake as the stop; with `target_atr`, a take-profit that many ATR from the signal
        close (v002, owner's first agreed change). Without it, exactly the v001 entry."""
        if self.target_atr is None:
            return super().enter(index, side)
        distance = self.target_atr * float(self.votes.atr[index])
        price = float(self.mid_close[index])
        target = price + distance if side == "LONG" else price - distance
        return [Enter(side=side, stop=self.brake_price(index, side), target=target)]

    def exit_intents(self, index: int, position: PositionView) -> list[Intent]:
        against = "bearish" if position.side == "LONG" else "bullish"
        table = self.votes.bearish if position.side == "LONG" else self.votes.bullish
        score = self.votes.bearish_score if position.side == "LONG" else self.votes.bullish_score
        threshold = self.config.confluence_threshold
        if score[index] < threshold:
            return []
        voted = "+".join(name for name in self.votes.names if table[name][index])
        return [Exit(why=f"confluence: {against} score {int(score[index])}/{threshold} ({voted})")]
