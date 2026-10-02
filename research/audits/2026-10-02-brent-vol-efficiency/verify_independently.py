"""Independent re-implementation of the three headline results of the Brent vol/efficiency experiment.

Written from METHODOLOGY.md, NOT from the original code. It imports nothing from research/engine or
research/experiments: it reads the raw SQLite rows itself and re-implements 5m aggregation, true range,
ATR (TradingView-style RMA seeded with an SMA, unlike the original's first-value seed), efficiency ratio,
Supertrend 7/3 (transcribed from Pine Script's ta.supertrend), the zigzag, the flip classification,
flip-to-flip P&L and a pairwise AUC.

Headline results reproduced, separately for 2024 and 2025:
  1. counts and net P&L of EARLY vs FALSE Supertrend 7/3 flips
  2. hour-stratified (and raw) AUC of ATR7/ATR21 for EARLY vs FALSE
  3. hour-stratified (and raw) AUC of ER21 for EARLY vs FALSE

Two zigzag variants are run: "as-coded" (the original experiment's behaviour: after a reversal, the new
swing's extreme is tracked from the confirming candle) and "textbook" (the new extreme is the most extreme
close since the old pivot; candles after it are replayed). They are compared with the original experiment
and with the audit's corrected zigzag respectively (numbers in expected_from_original.json, written by
audit_checks.py). A material disagreement is REPORTED and the script exits 1; nothing is adjusted.

    cd research/engine && .venv/bin/python ../audits/2026-10-02-brent-vol-efficiency/verify_independently.py
(any Python 3.11+ with numpy, pandas and pyyaml works; PYTHONPATH is not needed)
"""
from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
DB = ROOT / "data/axe-trader.sqlite"
CUTOFF = "2026-02-01T00:00:00Z"            # the reserved period starts here and is never read
SCOPE_FROM = pd.Timestamp("2024-01-11", tz="UTC")
SCOPE_TO = pd.Timestamp("2026-01-01", tz="UTC")
WARMUP = 300                               # candles never used as observations

# ------------------------------------------------------------------ raw data
with sqlite3.connect(f"file:{DB}?mode=ro", uri=True) as con:
    raw = pd.read_sql_query(
        "SELECT snapshot_time_utc AS t, open_bid, high_bid, low_bid, close_bid, open_ask, high_ask, low_ask, close_ask "
        "FROM historical_price WHERE epic = 'OIL_BRENT' AND resolution = 'MINUTE' AND snapshot_time_utc < ?",
        con, params=(CUTOFF,))
    excluded = {r[0] for r in con.execute("SELECT DISTINCT snapshot_time_utc FROM price_exclusion WHERE epic = 'OIL_BRENT'")}
raw = raw[~raw.t.isin(excluded)].copy()
raw["t"] = pd.to_datetime(raw.t, format="%Y-%m-%dT%H:%M:%SZ", utc=True)
raw = raw.sort_values("t").reset_index(drop=True)
print(f"raw minute rows used: {len(raw)} ({raw.t.iloc[0]} .. {raw.t.iloc[-1]}), excluded minutes removed: "
      f"{len(excluded & set(raw.t.dt.strftime('%Y-%m-%dT%H:%M:%SZ')))} remaining (should be 0)")

# ------------------------------------------------------------------ 5-minute candles (integer bucket groupby)
epoch_s = raw.t.dt.tz_convert(None).to_numpy().astype("datetime64[s]").astype("int64")  # explicit unit (pandas 3 may store us/s)
bucket = pd.Series(epoch_s // 300, index=raw.index)
grp = raw.groupby(bucket, sort=True)
bars = pd.DataFrame({
    "open_bid": grp.open_bid.first(), "high_bid": grp.high_bid.max(), "low_bid": grp.low_bid.min(),
    "close_bid": grp.close_bid.last(), "open_ask": grp.open_ask.first()})
bars.index = pd.to_datetime(bars.index.to_numpy() * 300, unit="s", utc=True)
T = bars.index
O, H, L, C = (bars[k].to_numpy(float) for k in ("open_bid", "high_bid", "low_bid", "close_bid"))
OA = bars.open_ask.to_numpy(float)
N = len(C)
print(f"5m candles: {N}")

# ------------------------------------------------------------------ indicators
prev_close = np.concatenate([[np.nan], C[:-1]])
TR = np.where(np.isnan(prev_close), H - L,
              np.max(np.vstack([H - L, np.abs(H - prev_close), np.abs(L - prev_close)]), axis=0))


def rma(x, n):
    """TradingView ta.rma: NaN until n values, seeded with their simple mean, then alpha = 1/n."""
    out = np.full(len(x), np.nan)
    out[n - 1] = x[:n].mean()
    for k in range(n, len(x)):
        out[k] = out[k - 1] + (x[k] - out[k - 1]) / n
    return out


ATR = {n: rma(TR, n) for n in (3, 7, 14, 21)}
cum_path = np.concatenate([[0.0], np.cumsum(np.abs(np.diff(C)))])  # cum_path[k] = sum |dC| up to candle k


def efficiency(n):
    out = np.full(N, np.nan)
    k = np.arange(n, N)
    out[k] = np.abs(C[k] - C[k - n]) / (cum_path[k] - cum_path[k - n])
    return out


ER21 = efficiency(21)
R21 = ATR[7] / ATR[21]


def pine_supertrend(factor, period):
    """Literal transcription of Pine v5 ta.supertrend. Pine direction -1 = up; returned as +1 up / -1 down."""
    atr = rma(TR, period)
    src = (H + L) / 2
    up_band, lo_band = src + factor * atr, src - factor * atr
    pine_dir = np.ones(N, dtype=int)  # Pine starts at 1 (down) while atr[1] is na
    prev_lo = prev_up = 0.0
    prev_st_is_upper = True
    for k in range(N):
        if np.isnan(atr[k]):
            continue
        lo = lo_band[k] if (lo_band[k] > prev_lo or C[k - 1] < prev_lo) else prev_lo
        up = up_band[k] if (up_band[k] < prev_up or C[k - 1] > prev_up) else prev_up
        if k == 0 or np.isnan(atr[k - 1]):
            d = 1
        elif prev_st_is_upper:
            d = -1 if C[k] > up else 1
        else:
            d = 1 if C[k] < lo else -1
        pine_dir[k] = d
        prev_st_is_upper = d == 1
        prev_lo, prev_up = lo, up
    return -pine_dir


DIR = pine_supertrend(3.0, 7)
flip_at = np.nonzero(DIR[1:] != DIR[:-1])[0] + 1  # candle k flipped if DIR[k] != DIR[k-1]


# ------------------------------------------------------------------ zigzag (close-based, threshold 6 x ATR14 of the tested candle)
def swings(threshold, mode):
    """List of (start_pivot, end_pivot, direction). The first (incomplete-history) swing is dropped."""
    out = []
    k = 1
    while k < N and not abs(C[k] - C[0]) >= threshold[k]:
        k += 1
    trend = 1 if C[k] > C[0] else -1
    anchor, best = 0, k
    k += 1
    while k < N:
        if trend * (C[k] - C[best]) > 0:
            best = k
        elif trend * (C[best] - C[k]) >= threshold[k]:
            out.append((anchor, best, trend))
            if mode == "as-coded":
                anchor, best, trend = best, k, -trend
            else:  # textbook: the new swing's extreme is the most extreme close since the old pivot
                window = C[best + 1:k + 1]
                new_best = best + 1 + int(np.argmax(-trend * window))  # -trend: new swing goes the other way
                anchor, best, trend = best, new_best, -trend
                k = new_best
        k += 1
    return out[1:]


# ------------------------------------------------------------------ trades (flip to flip, enter/exit at the next open)
rate = yaml.safe_load((ROOT / "research/engine/instruments.yaml").read_text())["instruments"]["OIL_BRENT"]["overnight_fee"]
assert rate["charge_time_utc"] == "21:00"


def trade_net(k_flip, k_next):
    e, x = k_flip + 1, k_next + 1
    long = DIR[k_flip] == 1
    entry = OA[e] if long else O[e]
    exit_ = O[x] if long else OA[x]
    gross = (exit_ - entry) if long else (entry - exit_)
    t_in, t_out = T[e], T[x]
    first_cut = t_in.normalize() + pd.Timedelta(hours=21)
    if first_cut <= t_in:
        first_cut += pd.Timedelta(days=1)
    cuts = 0 if first_cut >= t_out else int((t_out - pd.Timedelta(minutes=1) - first_cut) // pd.Timedelta(days=1)) + 1
    fee = entry * (rate["long_rate"] if long else rate["short_rate"]) / 100 * cuts
    return gross + fee


# ------------------------------------------------------------------ classify + AUC
def pairwise_auc(p, q):
    p, q = p[~np.isnan(p)], q[~np.isnan(q)]
    if len(p) == 0 or len(q) == 0:
        return np.nan, 0
    gt = (p[:, None] > q[None, :]).sum()
    eq = (p[:, None] == q[None, :]).sum()
    return (gt + 0.5 * eq) / (len(p) * len(q)), len(p) * len(q)


def by_hour_auc(values, hours, is_pos, is_neg):
    num = den = 0.0
    for hr in range(24):
        a, w = pairwise_auc(values[is_pos & (hours == hr)], values[is_neg & (hours == hr)])
        if w:
            num, den = num + a * w, den + w
    return num / den


threshold6 = 6 * ATR[14]
threshold6 = np.where(np.isnan(threshold6), np.inf, threshold6)
results = {}
for mode in ("as-coded", "textbook"):
    sw = swings(threshold6, mode)
    starts = np.array([s for s, _, _ in sw])
    recs = []
    for n_flip in range(len(flip_at) - 1):
        k = flip_at[n_flip]
        if k < WARMUP or not (SCOPE_FROM <= T[k] < SCOPE_TO):
            continue
        j = int(np.searchsorted(starts, k, side="right")) - 1
        if j < 0 or j + 1 >= len(sw):
            continue
        s, e, d = sw[j]
        if not (s <= k < e):
            continue
        D = DIR[k]
        exit_candle = flip_at[n_flip + 1] + 1
        size = abs(C[e] - C[s])
        if d == D:
            done = D * (C[k] - C[s]) / size
            label = "EARLY" if done <= 1 / 3 else ("TURNED" if e < exit_candle else "LATE")
        else:
            next_size = abs(C[sw[j + 1][1]] - C[sw[j + 1][0]])
            further_against = D * (C[k] - C[e])
            label = "EARLY" if (e - k <= 12 and e < exit_candle and further_against <= next_size / 3) else "FALSE"
        recs.append((T[k].year, label, trade_net(k, flip_at[n_flip + 1]), R21[k], ER21[k], T[k].hour))
    df = pd.DataFrame(recs, columns=["year", "label", "net", "r21", "er21", "hour"])
    results[mode] = {}
    for y in (2024, 2025):
        z = df[df.year == y]
        pos, neg = (z.label == "EARLY").to_numpy(), (z.label == "FALSE").to_numpy()
        hrs = z.hour.to_numpy()
        res = {"counts": z.label.value_counts().to_dict(),
               "n_EARLY": int(pos.sum()), "n_FALSE": int(neg.sum()),
               "net_EARLY": float(z.net[pos].sum()), "net_FALSE": float(z.net[neg].sum()),
               "net_per_trade_EARLY": float(z.net[pos].mean()), "net_per_trade_FALSE": float(z.net[neg].mean())}
        for f in ("r21", "er21"):
            v = z[f].to_numpy()
            res[f"auc_hour_{f}"] = float(by_hour_auc(v, hrs, pos, neg))
            res[f"auc_raw_{f}"] = float(pairwise_auc(v[pos], v[neg])[0])
        results[mode][str(y)] = res

# ------------------------------------------------------------------ report and compare
print("\nINDEPENDENT RESULTS (EARLY vs FALSE, Supertrend 7/3 flips, 6 ATR big swings)")
for mode, per_year in results.items():
    for y, r in per_year.items():
        print(f"  [{mode:<8}] {y}: classes {dict(sorted(r['counts'].items()))}")
        print(f"             EARLY n={r['n_EARLY']} net={r['net_EARLY']:+.2f} ({r['net_per_trade_EARLY']:+.4f}/trade) | "
              f"FALSE n={r['n_FALSE']} net={r['net_FALSE']:+.2f} ({r['net_per_trade_FALSE']:+.4f}/trade)")
        print(f"             AUC ATR7/ATR21 hour {r['auc_hour_r21']:.4f} raw {r['auc_raw_r21']:.4f} | "
              f"ER21 hour {r['auc_hour_er21']:.4f} raw {r['auc_raw_er21']:.4f}")
(HERE / "outputs").mkdir(exist_ok=True)
(HERE / "outputs/verify_independently.json").write_text(json.dumps(results, indent=2))

expected_path = HERE / "expected_from_original.json"
if not expected_path.exists():
    print("\nexpected_from_original.json not found: run audit_checks.py first to compare")
    sys.exit(0)
expected = json.loads(expected_path.read_text())
TOL = {"count": 2, "net_rel": 0.01, "auc": 0.005}
problems = []
print("\nCOMPARISON (as-coded vs ORIGINAL experiment; textbook vs audit's CORRECTED zigzag)")
for mode, ref_name in (("as-coded", "original"), ("textbook", "corrected")):
    for y in ("2024", "2025"):
        mine, ref = results[mode][y], expected[ref_name][y]
        rows = [("n_EARLY", mine["n_EARLY"], ref["n_EARLY"], abs(mine["n_EARLY"] - ref["n_EARLY"]) <= TOL["count"]),
                ("n_FALSE", mine["n_FALSE"], ref["n_FALSE"], abs(mine["n_FALSE"] - ref["n_FALSE"]) <= TOL["count"])]
        for k in ("net_EARLY", "net_FALSE"):
            rows.append((k, round(mine[k], 3), round(ref[k], 3), abs(mine[k] - ref[k]) <= TOL["net_rel"] * abs(ref[k])))
        for k in ("auc_hour_r21", "auc_raw_r21", "auc_hour_er21", "auc_raw_er21"):
            rows.append((k, round(mine[k], 4), round(ref[k], 4), abs(mine[k] - ref[k]) <= TOL["auc"]))
        for name, a, b2, ok in rows:
            print(f"  {mode:<8} vs {ref_name:<9} {y} {name:<14} independent {a!s:>10}  reference {b2!s:>10}  {'OK' if ok else 'DISAGREE'}")
            if not ok:
                problems.append(f"{mode} vs {ref_name} {y} {name}: independent {a} reference {b2}")
if problems:
    print("\nMATERIAL DISAGREEMENT - documented, not reconciled:")
    for p in problems:
        print("  " + p)
    sys.exit(1)
print(f"\nALL HEADLINE NUMBERS AGREE within tolerance {TOL}")
