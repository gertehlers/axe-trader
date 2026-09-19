"""Export a completed run as the review page's data payload.

Spec §7.3: the review surface needs, per trade, a candle window around the trade, the entry and
exit markers, the stop and brake lines, the exit reason, R, and which pillars voted. `Trade` carries
everything except the candle window and the votes, so this module supplies those from the signal
bars and the frozen pillar computation.

The output format is the one `research/review-spike/run-data.json` already proves against a
hand-exported Java run: compact keys, because a 500-trade payload with 60-bar windows is the whole
page weight.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from engine.pillars import PILLAR_NAMES, PillarConfig, compute_pillars

# Bars of context drawn either side of the trade. Enough to see what the entry reacted to and
# whether the exit left anything on the table, without making the payload unreadable.
PAD_BEFORE = 24
PAD_AFTER = 12

# H-0005 measured this entry's edge by forward horizon and found it rising: 50th percentile at 15m,
# 97.9 at 2h, 100.0 at 4h, 89.4 at 8h. 4h is 16 bars at 15m. Every window reaches at least this far
# past the entry, so an exit that cut the horizon short is visible rather than inferred.
EDGE_HORIZON_BARS = 16


def signal_bar_index(bar_index: pd.DatetimeIndex, moment: pd.Timestamp) -> int:
    """The signal bar that was closed and actionable at `moment`.

    Entries fill at the open of the minute after a signal bar closes, so the bar that caused a
    trade is the last one strictly before the fill. `searchsorted(..., "left") - 1` is that bar;
    it is -1 only for a moment before the first bar, which a simulated trade cannot be.
    """
    return int(bar_index.searchsorted(moment, side="left")) - 1


def candle_window(bars: pd.DataFrame, entry_index: int, exit_index: int,
                  pad_before: int = PAD_BEFORE, pad_after: int = PAD_AFTER) -> list[list[float]]:
    """`[[epoch_seconds, open, high, low, close], ...]` at mid, padded either side of the trade."""
    start = max(0, entry_index - pad_before)
    stop = min(len(bars), exit_index + pad_after + 1)
    window = bars.iloc[start:stop]
    mid_open = (window["open_bid"] + window["open_ask"]) / 2.0
    mid_high = (window["high_bid"] + window["high_ask"]) / 2.0
    mid_low = (window["low_bid"] + window["low_ask"]) / 2.0
    mid_close = (window["close_bid"] + window["close_ask"]) / 2.0
    # Timestamp.timestamp() rather than astype("int64") // 1e9: the index resolution varies
    # (resample yields microseconds here, not nanoseconds) and the divisor would be wrong.
    epochs = [int(ts.timestamp()) for ts in window.index]
    return [[int(e), round(float(o), 5), round(float(h), 5), round(float(l), 5), round(float(c), 5)]
            for e, o, h, l, c in zip(epochs, mid_open, mid_high, mid_low, mid_close)]


def voting_pillars(votes, bar: int, side: str) -> list[str]:
    """The pillar names that voted on `side` at `bar`, in the frozen pillar order."""
    table = votes.bullish if side == "LONG" else votes.bearish
    return [name for name in PILLAR_NAMES if bool(table[name][bar])]


def trade_payloads(trades: pd.DataFrame, bars: pd.DataFrame, votes, epic: str) -> list[dict]:
    index = bars.index
    atr_values = votes.atr
    scores = votes.bullish_score, votes.bearish_score
    payloads = []
    for i, trade in enumerate(trades.itertuples(index=False)):
        entry_bar = signal_bar_index(index, trade.entry_time)
        exit_bar = max(entry_bar, signal_bar_index(index, trade.exit_time))
        side = str(trade.side)
        score = scores[0 if side == "LONG" else 1]
        atr_at_entry = float(atr_values[entry_bar]) if entry_bar < len(atr_values) else float("nan")
        target = None if trade.target is None or pd.isna(trade.target) else round(float(trade.target), 5)
        payloads.append({
            "i": i,
            "k": f"{epic}|{pd.Timestamp(trade.entry_time).isoformat()}|{side}",
            "e": pd.Timestamp(trade.entry_time).isoformat().replace("+00:00", "Z"),
            "x": pd.Timestamp(trade.exit_time).isoformat().replace("+00:00", "Z"),
            "d": side,
            "ep": round(float(trade.entry_price), 5),
            "xp": round(float(trade.exit_price), 5),
            "sp": round(float(trade.stop), 5),
            "tp": target,
            "sz": round(float(trade.size), 3),
            "pnl": round(float(trade.gross_usd), 4),
            "npnl": round(float(trade.net_usd), 4),
            "spread": round(float(trade.spread_cost_usd), 4),
            "r": round(float(trade.r), 4),
            "xr": str(trade.exit_reason),
            "w": 1 if float(trade.net_usd) > 0 else 0,
            "atr": None if np.isnan(atr_at_entry) else round(atr_at_entry, 5),
            "hr": int(pd.Timestamp(trade.entry_time).hour),
            "dow": int(pd.Timestamp(trade.entry_time).dayofweek),
            "conf": int(score[entry_bar]),
            "pil": "+".join(voting_pillars(votes, entry_bar, side)) or "none",
            "hold_h": round((pd.Timestamp(trade.exit_time) - pd.Timestamp(trade.entry_time))
                            .total_seconds() / 3600.0, 2),
            "b": candle_window(bars, entry_bar, exit_bar),
            "eb": entry_bar - max(0, entry_bar - PAD_BEFORE),   # entry offset within the window
            "xb": exit_bar - max(0, entry_bar - PAD_BEFORE),    # exit offset within the window
        })
    return payloads


def build_payload(run_dir: Path, bars: pd.DataFrame, epic: str,
                  pillar_config: PillarConfig | None = None) -> dict:
    """Read one run directory and return the review payload for it."""
    trades = pd.read_parquet(run_dir / "trades.parquet")
    summary = json.loads((run_dir / "summary.json").read_text())
    votes = compute_pillars(bars, pillar_config or PillarConfig())
    return {
        "arm": summary["arm"],
        "epic": epic,
        "summary": summary,
        "trades": trade_payloads(trades, bars, votes, epic),
    }


# --- entry-aligned export -------------------------------------------------------------------
# The entry is frozen across arms (spec §4.1), so the same setup is usually traded by all four and
# the arms differ only in where they got out. Aligning on the entry is what makes an exit
# difference visible: one candle window, four exit markers. 84% of entries are shared by all four
# on OIL_CRUDE 15m; the rest are taken by fewer, because an arm still holding a position cannot
# take the next signal.

def align_entries(per_arm: dict[str, pd.DataFrame], bars: pd.DataFrame, votes,
                  epic: str) -> list[dict]:
    """One record per (entry time, side), carrying each arm's exit of that entry."""
    index = bars.index
    keys: dict[tuple, dict] = {}

    for arm, trades in per_arm.items():
        for trade in trades.itertuples(index=False):
            key = (pd.Timestamp(trade.entry_time), str(trade.side))
            record = keys.get(key)
            if record is None:
                entry_bar = signal_bar_index(index, trade.entry_time)
                side = str(trade.side)
                score = votes.bullish_score if side == "LONG" else votes.bearish_score
                atr_at_entry = float(votes.atr[entry_bar])
                record = keys[key] = {
                    "e": key[0].isoformat().replace("+00:00", "Z"),
                    "d": side,
                    "ep": round(float(trade.entry_price), 5),
                    "eb": entry_bar,
                    "atr": None if np.isnan(atr_at_entry) else round(atr_at_entry, 5),
                    "hr": int(key[0].hour),
                    "dow": int(key[0].dayofweek),
                    "conf": int(score[entry_bar]),
                    "pil": voting_pillars(votes, entry_bar, side),
                    "arms": {},
                }
            exit_bar = max(record["eb"], signal_bar_index(index, trade.exit_time))
            target = None if trade.target is None or pd.isna(trade.target) else round(float(trade.target), 5)
            record["arms"][arm] = {
                "x": pd.Timestamp(trade.exit_time).isoformat().replace("+00:00", "Z"),
                "xp": round(float(trade.exit_price), 5),
                "xb": exit_bar,
                "sp": round(float(trade.stop), 5),
                "tp": target,
                "xr": str(trade.exit_reason),
                "r": round(float(trade.r), 4),
                "npnl": round(float(trade.net_usd), 4),
                "hold_h": round((pd.Timestamp(trade.exit_time) - key[0]).total_seconds() / 3600.0, 2),
            }

    records = sorted(keys.values(), key=lambda r: r["e"])
    # The window spans the entry, the LATEST exit any arm took, and the H-0005 horizon beyond both,
    # so every arm's marker is inside it AND the reader can see where price went at the horizon the
    # entry's edge was actually measured at. That comparison is the point: the arms' mean holds
    # (0.3-2.2h) all fall short of the 4h horizon where the edge peaked.
    for i, record in enumerate(records):
        last_exit = max(a["xb"] for a in record["arms"].values())
        horizon_bar = record["eb"] + EDGE_HORIZON_BARS
        start = max(0, record["eb"] - PAD_BEFORE)
        record["b"] = candle_window(bars, record["eb"], max(last_exit, horizon_bar))
        record["i"] = i
        record["eb"] -= start
        record["h"] = horizon_bar - start          # the 4h horizon marker
        for arm in record["arms"].values():
            arm["xb"] -= start
    return records
