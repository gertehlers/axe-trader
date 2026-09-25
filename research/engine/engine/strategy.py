"""What a strategy is allowed to see, and what it is allowed to ask for.

BarsView is the lookahead barrier: it slices to `index` inclusive, so a strategy physically
cannot read a bar that has not closed. Intents are declarative — a strategy never computes a
fill price; only the simulator does that, from the 1-minute bid/ask series.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol, Union

import numpy as np
import pandas as pd

SIDES = ("LONG", "SHORT")


@dataclass(frozen=True)
class Enter:
    side: str
    stop: float
    target: float | None = None
    risk_r: float = 1.0

    def __post_init__(self) -> None:
        if self.side not in SIDES:
            raise ValueError(f"side must be one of {SIDES}, got {self.side!r}")


@dataclass(frozen=True)
class Exit:
    # Why the strategy asked to leave, for the decision trace. Excluded from equality so an
    # explanation can never change what counts as the same decision.
    why: str = field(default="", compare=False)


@dataclass(frozen=True)
class MoveStop:
    price: float


Intent = Union[Enter, Exit, MoveStop]


@dataclass(frozen=True)
class PositionView:
    side: str | None
    entry_price: float
    stop: float
    target: float | None
    size: float

    @classmethod
    def flat(cls) -> "PositionView":
        return cls(side=None, entry_price=0.0, stop=0.0, target=None, size=0.0)

    def __bool__(self) -> bool:
        return self.side is not None


class BarsView:
    """Closed bars up to and including `index`. Never further."""

    def __init__(self, frame: pd.DataFrame, index: int) -> None:
        self._frame = frame
        self._index = index

    def __len__(self) -> int:
        return self._index + 1

    def _column(self, name: str) -> np.ndarray:
        return self._frame[name].to_numpy()[: self._index + 1]

    @property
    def time(self) -> pd.Timestamp:
        return self._frame.index[self._index]

    @property
    def open_bid(self) -> np.ndarray:
        return self._column("open_bid")

    @property
    def open_ask(self) -> np.ndarray:
        return self._column("open_ask")

    @property
    def high_bid(self) -> np.ndarray:
        return self._column("high_bid")

    @property
    def high_ask(self) -> np.ndarray:
        return self._column("high_ask")

    @property
    def low_bid(self) -> np.ndarray:
        return self._column("low_bid")

    @property
    def low_ask(self) -> np.ndarray:
        return self._column("low_ask")

    @property
    def close_bid(self) -> np.ndarray:
        return self._column("close_bid")

    @property
    def close_ask(self) -> np.ndarray:
        return self._column("close_ask")

    @property
    def volume(self) -> np.ndarray:
        return self._column("volume")

    @property
    def complete(self) -> np.ndarray:
        return self._column("complete")

    @property
    def minutes_present(self) -> np.ndarray:
        return self._column("minutes_present")


class Strategy(Protocol):
    timeframe: str

    def on_bar(self, history: BarsView, position: PositionView) -> list[Intent]: ...
