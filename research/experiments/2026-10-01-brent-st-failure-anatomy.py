"""Brent 5m Supertrend(7, 3) flip-to-flip: what separates the winners from the failures?

The owner keeps 7/3 as the entry and the exit, and wants to spot failures sooner (kill them) while
letting winners run. This run dissects every practice trade:

  * failure anatomy: winners, "reversal" losers (flipped back fast) and "sideways" losers;
  * candidate pillars known AT THE FLIP (before entering) and EARLY IN THE TRADE (after 3 and 6
    candles), each split into quintiles;
  * simple kill rules: at candle k, exit if the trade is not yet working.

Patterns are found on 2024 (discovery) and must hold on 2025 (check) with the 2024 quintile edges
reused unchanged. Practice period only; data from RESERVED_FROM (2026-02-01) is never loaded.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-10-01-brent-st-failure-anatomy.py
"""
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.dayrun import RESERVED_FROM
from engine.flips import flip_trades
from engine.indicators import adx, atr, ema, rsi, sma, supertrend
from engine.instruments import load_instruments

R = Path(__file__).resolve().parents[2]
EPIC = "OIL_BRENT"
spec = load_instruments(R / "research/engine/instruments.yaml")[EPIC]
m = load_cached_minutes(R / "data/axe-trader.sqlite", EPIC, R / "research/engine/.cache")
m = m[m.index < RESERVED_FROM]
b = resample(m, "5min")
H, L, C, O = (b[c].to_numpy() for c in ["high_bid", "low_bid", "close_bid", "open_bid"])
line, direction = supertrend(H, L, C, 7, 3.0)
A7 = atr(H, L, C, 7)
A100 = atr(H, L, C, 100)
ADX = adx(H, L, C, 14)
RSI7 = rsi(C, 7)
E50, E200 = ema(C, 50), ema(C, 200)
VOL = b["volume"].to_numpy(dtype=float)
VSMA = sma(VOL, 20)
flip_flag = np.r_[0, (direction[1:] != direction[:-1]).astype(int)]
FLIPS48 = pd.Series(flip_flag).rolling(48, min_periods=1).sum().shift(1).to_numpy()  # flips in prior 4 h


def htf_direction(rule: str, length: int, factor: float) -> np.ndarray:
    """Higher-timeframe Supertrend direction known at each 5m bar's close (last COMPLETED HTF bar)."""
    hb = resample(m, rule)
    _, d = supertrend(hb.high_bid, hb.low_bid, hb.close_bid, length, factor)
    known_at = hb.index + pd.Timedelta(rule)  # an HTF bar is known once it has closed
    s = pd.Series(d, index=known_at)
    five_close = b.index + pd.Timedelta("5min")
    return s.reindex(s.index.union(five_close)).ffill().reindex(five_close).to_numpy()


HTF15 = htf_direction("15min", 7, 3.0)
HTF60 = htf_direction("1h", 10, 3.0)

trades = flip_trades(b, direction, spec, start=pd.Timestamp("2024-01-11", tz="UTC"))
pos = b.index.get_indexer(trades.signal_time)
side = np.where(trades.side == "LONG", 1, -1)
t = trades.assign(i=pos, s=side, year=trades.exit_time.dt.year, win=trades.net_pts > 0)
a = A7[t.i]
t["mfe_atr"] = t.mfe_pts / a

# ---- failure anatomy -------------------------------------------------------------------------
t["kind"] = np.select([t.win, t.bars <= 6, t.mfe_atr < 1.0],
                      ["winner", "reversal (flipped back within 30 min)", "sideways (never got 1 ATR)"],
                      "gave it back (got >= 1 ATR, ended a loser)")
print("FAILURE ANATOMY (all practice trades)")
an = t.groupby("kind").agg(trades=("net_pts", "size"), net=("net_pts", "sum"), avg=("net_pts", "mean"),
                           avg_bars=("bars", "mean"), avg_mfe_atr=("mfe_atr", "mean"))
an["share"] = an.trades / len(t)
print(an.round(3).to_string(), "\n")

# ---- pillars known at the flip candle (before entry) ------------------------------------------
i, s = t.i.to_numpy(), t.s.to_numpy()
prev_line = line[i - 1]
F = pd.DataFrame({
    "flip candle body (ATR)": np.abs(C[i] - O[i]) / A7[i],
    "close beyond old line (ATR)": (C[i] - prev_line) * s / A7[i],
    "flips in prior 4h": FLIPS48[i],
    "ATR7 / ATR100 (volatility)": A7[i] / A100[i],
    "ADX14": ADX[i],
    "RSI7 in trade direction": np.where(s == 1, RSI7[i], 100 - RSI7[i]),
    "EMA50 slope 1h, trade dir (ATR)": (E50[i] - E50[i - 12]) * s / A7[i],
    "close vs EMA200, trade dir (ATR)": (C[i] - E200[i]) * s / A7[i],
    "tick volume / 20-bar avg": VOL[i] / VSMA[i],
    "hour UTC": b.index[i].hour,
}, index=t.index)
F["15m ST(7,3) agrees"] = (HTF15[i] == s).astype(int)
F["1h ST(10,3) agrees"] = (HTF60[i] == s).astype(int)

# ---- pillars known early in the trade ----------------------------------------------------------
for k in (3, 6):
    j = t.i.to_numpy() + k  # entry is bar i+1; after k candles we know bar i+k's close
    ok = t.bars.to_numpy() > k
    jj = np.minimum(j, len(C) - 1)
    entry = t.entry_price.to_numpy()
    F[f"P&L after {k} candles (ATR)"] = np.where(ok, (C[jj] - entry) * s / a, np.nan)
    best = np.array([((H[e:e + k].max() - en) if sd == 1 else (en - L[e:e + k].min())) if o else np.nan
                     for e, en, sd, o in zip(t.i.to_numpy() + 1, entry, s, ok)])
    F[f"best after {k} candles (ATR)"] = best / a
    F[f"cushion to line after {k} (ATR)"] = np.where(ok, (C[jj] - line[jj]) * s / A7[jj], np.nan)


def quintile_table(name: str) -> None:
    col = F[name]
    disc = t.year == 2024
    if col.nunique() <= 2:
        bins = sorted(col.dropna().unique())
        label = col
    else:
        edges = np.unique(np.nanquantile(col[disc], [0, .2, .4, .6, .8, 1]))
        edges[0], edges[-1] = -np.inf, np.inf
        label = pd.cut(col, edges)
        bins = label.cat.categories
    rows = []
    for q in bins:
        sel = label == q
        r = [str(q)]
        for y in (2024, 2025):
            g = t[sel & (t.year == y)]
            r += [len(g), g.win.mean() * 100 if len(g) else np.nan, g.net_pts.mean() if len(g) else np.nan]
        rows.append(r)
    out = pd.DataFrame(rows, columns=["bucket", "n24", "win%24", "avg24", "n25", "win%25", "avg25"])
    print(f"-- {name}  (2024 = discovery, 2025 = check with the same bucket edges)")
    print(out.round({"win%24": 1, "avg24": 3, "win%25": 1, "avg25": 3}).to_string(index=False), "\n")


print("PILLARS: average net per trade (pts) and win rate by bucket. Baseline: "
      f"2024 {t[t.year == 2024].net_pts.mean():.3f} / {t[t.year == 2024].win.mean():.1%}, "
      f"2025 {t[t.year == 2025].net_pts.mean():.3f} / {t[t.year == 2025].win.mean():.1%}\n")
for name in F.columns:
    quintile_table(name)

# ---- kill rules: at candle k, exit at the next open if the trade is not working ---------------
print("KILL RULES: exit at the open after candle k if P&L (close vs entry, ATR) < threshold; else keep 7/3")
ob, oa = b.open_bid.to_numpy(), b.open_ask.to_numpy()
for k in (3, 6, 12):
    for thr in (-0.5, 0.0, 0.5):
        net = t.net_pts.to_numpy().copy()
        killed = np.zeros(len(t), bool)
        for n_, (ii, sd, en, bars_, fund) in enumerate(zip(t.i, t.s, t.entry_price, t.bars, t.financing_pts)):
            if bars_ <= k:
                continue
            jj = ii + k
            if (C[jj] - en) * sd / A7[ii] < thr:
                xo = ob[jj + 1] if sd == 1 else oa[jj + 1]
                net[n_] = (xo - en) * sd  # funding ignored for the few 5m kills that span a cut-off
                killed[n_] = True
        res = [f"k={k:<2} thr={thr:+.1f}  killed {killed.mean():5.1%}"]
        for y in (2024, 2025):
            sel = (t.year == y).to_numpy()
            res.append(f"{y}: net {net[sel].sum():8.1f} (flip {t.net_pts[sel].sum():7.1f}) win {np.mean(net[sel] > 0):5.1%}")
        print("  ".join(res))
