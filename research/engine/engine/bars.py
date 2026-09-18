"""Resample minute bars onto the UTC clock grid.

A bar is `complete` only when every minute of its bucket is present. Incomplete bars are
kept — a thin bar is information about the market, not a defect — and strategies decide
what to do with the flag.
"""

from __future__ import annotations

import pandas as pd

AGGREGATION = {
    "open_bid": "first", "open_ask": "first",
    "high_bid": "max", "high_ask": "max",
    "low_bid": "min", "low_ask": "min",
    "close_bid": "last", "close_ask": "last",
    "volume": "sum",
}


def timeframe_minutes(timeframe: str) -> int:
    return int(pd.Timedelta(timeframe) / pd.Timedelta(minutes=1))


def resample(minutes: pd.DataFrame, timeframe: str) -> pd.DataFrame:
    grouped = minutes.resample(timeframe, label="left", closed="left")
    out = grouped.agg(AGGREGATION)
    out["minutes_present"] = grouped.size().astype("int64")
    out = out[out["minutes_present"] > 0]
    out["complete"] = out["minutes_present"] == timeframe_minutes(timeframe)
    return out
