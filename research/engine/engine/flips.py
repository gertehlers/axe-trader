"""Flip-to-flip trades: always in the market on the side a trend line points to.

A flip is known only when its bar closes, so each trade enters at the next bar's open and exits
at the open after the next flip, where the reverse trade begins. Fills cross the real spread: a
long buys the Ask and sells the Bid, a short sells the Bid and buys the Ask. Overnight funding is
charged per cut-off inside the hold at the instrument's current Capital.com rate, applied to past
holds as an approximation. A trade still open at the end of the data is not counted.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from engine.costs import charge_times, financing_usd

COLUMNS = ["side", "signal_time", "entry_time", "entry_price", "exit_time", "exit_price", "bars",
           "gross_pts", "financing_pts", "net_pts", "spread_pts", "mfe_pts", "mae_pts"]


def flip_trades(bars: pd.DataFrame, direction: np.ndarray, spec: dict,
                start: pd.Timestamp | None = None) -> pd.DataFrame:
    direction = np.asarray(direction)
    idx = bars.index
    flips = [i for i in range(1, len(bars) - 1)
             if direction[i] != direction[i - 1] and (start is None or idx[i] >= start)]
    ob, oa = bars["open_bid"].to_numpy(), bars["open_ask"].to_numpy()
    hb, la = bars["high_bid"].to_numpy(), bars["low_ask"].to_numpy()
    lb, ha = bars["low_bid"].to_numpy(), bars["high_ask"].to_numpy()
    cut_off = spec["overnight_fee"]["charge_time_utc"]
    rows = []
    for signal, next_signal in zip(flips, flips[1:]):
        e, x = signal + 1, next_signal + 1
        long = direction[signal] == 1
        side = "LONG" if long else "SHORT"
        entry, exit_ = (oa[e], ob[x]) if long else (ob[e], oa[x])
        gross = exit_ - entry if long else entry - exit_
        if long:
            mfe, mae = hb[e:x].max() - entry, entry - lb[e:x].min()
        else:
            mfe, mae = entry - la[e:x].min(), ha[e:x].max() - entry
        financing = financing_usd(side, 1.0, entry, spec, charge_times(idx[e], idx[x], cut_off), 1.0)
        rows.append([side, idx[signal], idx[e], entry, idx[x], exit_, x - e, gross, financing,
                     gross + financing, ((oa[e] - ob[e]) + (oa[x] - ob[x])) / 2.0, mfe, mae])
    return pd.DataFrame(rows, columns=COLUMNS)


def summarise(trades: pd.DataFrame) -> dict:
    net = trades["net_pts"].to_numpy(dtype=float)
    wins, losses = net[net > 0], net[net <= 0]
    equity = np.cumsum(net)
    peak = np.maximum.accumulate(np.concatenate([[0.0], equity]))[1:]
    streak = longest = 0
    for value in net:
        streak = streak + 1 if value <= 0 else 0
        longest = max(longest, streak)
    return {
        "trades": int(len(net)),
        "wins": int(len(wins)),
        "win_rate": float(len(wins) / len(net)) if len(net) else 0.0,
        "net_pts": float(net.sum()),
        "avg_pts": float(net.mean()) if len(net) else 0.0,
        "avg_win_pts": float(wins.mean()) if len(wins) else 0.0,
        "avg_loss_pts": float(losses.mean()) if len(losses) else 0.0,
        "profit_factor": float(wins.sum() / -losses.sum()) if losses.sum() < 0 else float("inf"),
        "longest_losing_streak": int(longest),
        "max_drawdown_pts": float((peak - equity).max()) if len(net) else 0.0,
        "spread_pts": float(trades["spread_pts"].sum()),
        "financing_pts": float(trades["financing_pts"].sum()),
        "avg_bars": float(trades["bars"].mean()) if len(net) else 0.0,
    }
