"""Self-audit of research/experiments/2026-10-02-brent-vol-efficiency-swings.py.

Runs the ORIGINAL experiment unchanged (via runpy), checks its printed output is byte-identical to
the committed .txt, then audits data, indicators, zigzag, Supertrend, classification and statistics,
and writes the machine-readable files of the audit package. Nothing in the original is modified.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../audits/2026-10-02-brent-vol-efficiency/audit_checks.py

Deterministic: SEED below drives every bootstrap, permutation and sample draw.
"""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import platform
import runpy
import subprocess
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
R = HERE.parents[2]
ORIG = R / "research/experiments/2026-10-02-brent-vol-efficiency-swings.py"
ORIG_TXT = ORIG.with_suffix(".txt")
OUT = HERE / "outputs"
OUT.mkdir(exist_ok=True)
SEED = 20261002
B_MAIN, B_SENS = 1000, 500
LOG = []


def say(*parts):
    line = " ".join(str(p) for p in parts)
    print(line)
    LOG.append(line)


# ---------------------------------------------------------------- 0. run the original, unchanged
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    g = runpy.run_path(str(ORIG), run_name="__main__")
(OUT / "original_rerun.txt").write_text(buf.getvalue())
identical = buf.getvalue() == ORIG_TXT.read_text()
say("=" * 100)
say("0. ORIGINAL RE-RUN")
say(f"   original printed output identical to committed {ORIG_TXT.name}: {identical}")

b, idx, c, h, l = g["b"], g["idx"], g["c"], g["h"], g["l"]
F, FEATS, FLIP_FEATS = g["F"], g["FEATS"], g["FLIP_FEATS"]
a7, a14, a21, a100 = g["a7"], g["a14"], g["a21"], g["a100"]
st, flips, trades, fl = g["st"], g["flips"], g["trades"], g["fl"]
HOUR, YEAR, in_scope, warm = g["HOUR"], g["YEAR"], g["in_scope"], g["warm"]
zigzag, logistic, SETS = g["zigzag"], g["logistic"], g["SETS"]
m, RESERVED_FROM, START = g["m"], g["RESERVED_FROM"], g["START"]

# ---------------------------------------------------------------- 1. data audit
say("=" * 100)
say("1. DATA")
mm = m[m.index < RESERVED_FROM]
PRICE = ["open_bid", "open_ask", "high_bid", "high_ask", "low_bid", "low_ask", "close_bid", "close_ask"]
digest = hashlib.sha256(mm[PRICE].to_csv(float_format="%.6f").encode()).hexdigest()
db = R / "data/axe-trader.sqlite"
import sqlite3
with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as con:
    db_rows = con.execute("SELECT COUNT(*) FROM historical_price WHERE epic='OIL_BRENT' AND resolution='MINUTE' "
                          "AND snapshot_time_utc < '2026-02-01T00:00:00Z'").fetchone()[0]
    excl = con.execute("SELECT COUNT(DISTINCT snapshot_time_utc) FROM price_exclusion WHERE epic='OIL_BRENT' "
                       "AND snapshot_time_utc < '2026-02-01T00:00:00Z'").fetchone()[0]
say(f"   source: {db} table historical_price, epic OIL_BRENT, resolution MINUTE, minus price_exclusion rows")
say(f"   DB minute rows < 2026-02-01: {db_rows}; excluded minutes (price_exclusion) < 2026-02-01: {excl}")
say(f"   minutes used: {len(mm)}  first {mm.index[0]}  last {mm.index[-1]}  tz {mm.index.tz}")
say(f"   sha256 of minute price rows used (to_csv %.6f): {digest}")
say(f"   duplicate minute timestamps: {int(mm.index.duplicated().sum())}; non-whole-minute stamps: "
    f"{int((mm.index.second != 0).sum())}")
for side in ("bid", "ask"):
    o, hi, lo, cl = (mm[f"{k}_{side}"] for k in ("open", "high", "low", "close"))
    bad = ((hi < np.maximum(o, cl)) | (lo > np.minimum(o, cl)) | (hi < lo)).sum()
    say(f"   minute OHLC sanity ({side}): violations {int(bad)}")
crossed = (mm.close_ask < mm.close_bid).sum()
spread = mm.close_ask - mm.close_bid
say(f"   ask < bid at close: {int(crossed)}; close spread median {spread.median():.4f}, max {spread.max():.4f}")
gaps = mm.index.to_series().diff().dropna()
gap_min = (gaps / pd.Timedelta(minutes=1)).astype(int)
buckets = pd.cut(gap_min, [1, 2, 6, 61, 180, 2000, 10**7], right=False).value_counts().sort_index()
say("   gaps between consecutive minutes (minutes): " + ", ".join(f"{k}: {v}" for k, v in buckets.items()))
big_gaps = gaps[gaps > pd.Timedelta(hours=3)]
odd = big_gaps[~((big_gaps.index.dayofweek == 6) & (big_gaps > pd.Timedelta(hours=40)))]
say(f"   gaps > 3h: {len(big_gaps)} (weekends ~49h); gaps > 3h that are not weekend closes: {len(odd)}")
for t, gsize in odd.head(15).items():
    say(f"      gap ending {t} lasting {gsize}")
b_scope = b[(b.index >= START) & (b.index.year <= 2025)]
say(f"   5m candles total (to 2026-01-31): {len(b)}; in scope 2024-01-11..2025-12-31: {len(b_scope)} "
    f"(2024: {(b_scope.index.year == 2024).sum()}, 2025: {(b_scope.index.year == 2025).sum()})")
say(f"   5m candles with < 5 minutes present (kept by resample): {int((~b_scope.complete).sum())} "
    f"({(~b_scope.complete).mean() * 100:.1f}%)")
five_gap = b.index.to_series().diff().dropna()
say(f"   5m candle gaps > 5 min: {(five_gap > pd.Timedelta(minutes=5)).sum()} (ATR/ER/zigzag run straight across them)")
say(f"   data before RESERVED_FROM only: max candle used {b.index.max()}; note the cache loads all rows into memory"
    f" and the script filters m.index < RESERVED_FROM before any computation")

# ---------------------------------------------------------------- 2. indicator checks
say("=" * 100)
say("2. INDICATORS (independent re-derivation for sampled candles)")
rng = np.random.default_rng(SEED)
scope_i = np.nonzero(in_scope & warm)[0]
sample_i = np.sort(rng.choice(scope_i, 25, replace=False))
pc = np.r_[c[0], c[:-1]]
TR = np.maximum(h - l, np.maximum(np.abs(h - pc), np.abs(l - pc)))


def atr_from_seed(i, n, back=3000):
    """Wilder ATR at i re-derived from raw TR only: SMA seed of n TR values `back` candles earlier, then recursion."""
    s0 = i - back
    val = TR[s0 - n + 1:s0 + 1].mean()
    for t in range(s0 + 1, i + 1):
        val = (val * (n - 1) + TR[t]) / n
    return val


def er_manual(i, n):
    return abs(c[i] - c[i - n]) / sum(abs(c[t] - c[t - 1]) for t in range(i - n + 1, i + 1))


worst = {}
for n, arr in ((7, a7), (14, a14), (21, a21), (100, a100)):
    worst[f"ATR{n}"] = max(abs(atr_from_seed(i, n) - arr[i]) for i in sample_i)
for n in (7, 14, 21):
    worst[f"ER{n}"] = max(abs(er_manual(i, n) - F[f"er{n}"][i]) for i in sample_i)
for k, v in worst.items():
    say(f"   {k}: max |implementation - re-derivation| over 25 sampled candles = {v:.2e}")
say("   ATR: TR = max(H-L, |H-prevC|, |L-prevC|) on bid 5m candles, prevC of candle 0 = its own close;"
    " Wilder recursion ATR_t = (ATR_{t-1}(n-1) + TR_t)/n seeded ATR_0 = TR_0; current candle included")

# lookahead proof: recompute features with every candle after i removed
say("   lookahead test: features recomputed on data truncated at the flip candle (30 sampled flips)")
from engine.indicators import atr as eng_atr, supertrend as eng_st
probe = np.sort(rng.choice(fl.i.to_numpy(), 30, replace=False))
max_diff = 0.0
st_mismatch = 0
for i in probe:
    hh, ll, cc = h[:i + 1], l[:i + 1], c[:i + 1]
    t7, t21, t100 = eng_atr(hh, ll, cc, 7), eng_atr(hh, ll, cc, 21), eng_atr(hh, ll, cc, 100)
    tr21 = t7 / t21
    vals = {"r21": tr21[i], "r100": t7[i] / t100[i], "slope6": tr21[i] - tr21[i - 6],
            "squeeze": tr21[i - 36:i - 6].mean(), "release": tr21[i] - tr21[i - 36:i].min()}
    for n in (7, 14, 21):
        e_now = abs(cc[i] - cc[i - n]) / np.abs(np.diff(cc[i - n:i + 1])).sum()
        e_then = abs(cc[i - n] - cc[i - 2 * n]) / np.abs(np.diff(cc[i - 2 * n:i - n + 1])).sum()
        vals[f"er{n}"], vals[f"d_er{n}"] = e_now, e_now - e_then
    for k, v in vals.items():
        max_diff = max(max_diff, abs(v - F[k][i]))
    _, tst = eng_st(hh, ll, cc, 7, 3.0)
    st_mismatch += int(tst[i] != st[i] or tst[i - 1] != st[i - 1])
say(f"   max |full-series feature - truncated-series feature| = {max_diff:.2e}; Supertrend state/flip mismatches = {st_mismatch}")

# ---------------------------------------------------------------- 3. zigzag checks
def zigzag_corrected(threshold):
    """AUDIT FIX. Same rules as the original zigzag, with one change: when a reversal is confirmed at candle i,
    the new swing's running extreme is the most extreme close since the old pivot (pivot+1 .. i), not candle i,
    and the candles after that extreme are replayed so a reversal they confirm is not skipped. The original
    misses an earlier, more extreme close whenever the time-varying threshold (6 x ATR14 of the current
    candle) was larger at that earlier candle."""
    rows = []
    turn_i, ext_i, up, turn_known = 0, 0, None, 0
    i = 1
    while i < len(c):
        if up is None:
            if abs(c[i] - c[0]) >= threshold[i]:
                up, ext_i = c[i] > c[0], i
            i += 1
            continue
        if (up and c[i] > c[ext_i]) or (not up and c[i] < c[ext_i]):
            ext_i = i
        elif abs(c[ext_i] - c[i]) >= threshold[i]:
            rows.append((turn_i, ext_i, 1 if up else -1, abs(c[ext_i] - c[turn_i]), turn_known))
            seg = c[ext_i + 1:i + 1]
            new_ext = ext_i + 1 + int(np.argmin(seg) if up else np.argmax(seg))
            turn_i, ext_i, up, turn_known = ext_i, new_ext, not up, i
            i = new_ext + 1  # replay the candles after the new extreme (turn_i strictly increases, so this ends)
            continue
        i += 1
    return pd.DataFrame(rows[1:], columns=["start", "end", "dir", "size", "known"])


ZIGZAGS = {"original": zigzag, "corrected": zigzag_corrected}
say("=" * 100)
say("3. ZIGZAG (6 ATR) property checks")
thr6 = np.nan_to_num(6 * a14, nan=np.inf)
for version, zfn in ZIGZAGS.items():
    zz6 = g["big"] if version == "original" else zfn(thr6)
    viol = {"alternation": 0, "end_not_extreme": 0, "end_not_extreme_before_tracking_start": 0,
            "confirm_short": 0, "earlier_confirm": 0}
    bs_, be_, bd_, bk_ = (zz6[k].to_numpy() for k in ("start", "end", "dir", "known"))
    for j in range(len(zz6) - 1):
        s0, e0, d0 = bs_[j], be_[j], bd_[j]
        conf = bk_[j + 1]  # candle that confirmed the end of swing j
        if bd_[j + 1] == d0:
            viol["alternation"] += 1
        seg = c[s0:conf + 1]
        if (d0 == 1 and c[e0] < seg.max()) or (d0 == -1 and c[e0] > seg.min()):
            viol["end_not_extreme"] += 1
            t_ext = s0 + int(np.argmax(seg) if d0 == 1 else np.argmin(seg))
            viol["end_not_extreme_before_tracking_start"] += int(t_ext < bk_[j])
        if abs(c[e0] - c[conf]) < thr6[conf]:
            viol["confirm_short"] += 1
        for t in range(e0 + 1, conf):
            if abs(c[e0] - c[t]) >= thr6[t] and d0 * (c[t] - c[e0]) < 0:
                viol["earlier_confirm"] += 1
                break
    say(f"   {version}: swings {len(zz6)}; violations: {viol}")
    lag = bk_[1:] - be_[:-1]
    say(f"   {version}: confirmation delay (pivot -> confirming candle): median {np.median(lag):.0f}, "
        f"p10 {np.percentile(lag, 10):.0f}, p90 {np.percentile(lag, 90):.0f}, max {lag.max()}")
say("   BUG (original): 'end_not_extreme' swings all have their true extreme BEFORE the candle that confirmed the"
    " swing's start, i.e. in the stretch the original never tracks. See AUDIT_REPORT.md finding B1.")
say("   threshold = 6 x ATR14 of the CURRENT candle being tested (it changes during a swing); close-based;"
    " a new extreme must be strictly beyond the old one; the first swing is discarded")

# ---------------------------------------------------------------- 4. Supertrend / trades
say("=" * 100)
say("4. SUPERTREND 7/3 AND TRADES")
t_idx = {t: k for k, t in enumerate(idx)}
ent_ok = all(t_idx[row.entry_time] == t_idx[s] + 1 for s, row in trades.iterrows())
say(f"   trades: {len(trades)}; every entry is the candle after the flip candle: {ent_ok}")
nxt = {flips[k]: flips[k + 1] for k in range(len(flips) - 1)}
ex_ok = all(t_idx[row.exit_time] == nxt[t_idx[s]] + 1 for s, row in trades.iterrows())
say(f"   every exit is the candle after the next flip candle: {ex_ok}")
say("   long: buy open_ask(entry), sell open_bid(exit); short: sell open_bid(entry), buy open_ask(exit);"
    " funding = entry price x long/short_rate% (-0.01096%) per 21:00 UTC cut-off strictly inside the hold;"
    " no extra slippage or commission")
say(f"   flips in scope with a class: {len(fl)}; classified flips without a trade row: {int(fl.net.isna().sum())}")


# ---------------------------------------------------------------- 5. classification, parametrised
def classify(k_atr, zfn=zigzag):
    zz = zfn(np.nan_to_num(k_atr * a14, nan=np.inf))
    bs, be, bd, bz, bk = (zz[x].to_numpy() for x in ("start", "end", "dir", "size", "known"))
    rows = []
    for n in range(len(flips) - 1):
        i = flips[n]
        if not (in_scope[i] and warm[i]):
            continue
        D, x = int(st[i]), flips[n + 1] + 1
        j = int(np.searchsorted(bs, i, side="right")) - 1
        if j < 0 or j + 1 >= len(bs) or not (bs[j] <= i < be[j]):
            continue
        adverse, near = np.nan, None
        if bd[j] == D:
            f = D * (c[i] - c[bs[j]]) / bz[j]
            kind = "EARLY" if f <= 1 / 3 else ("TURNED" if be[j] < x else "LATE")
        else:
            f = -(D * (c[i] - c[bs[j]]) / bz[j])
            adverse = D * (c[i] - c[be[j]])
            near = be[j] - i <= 12 and be[j] < x and adverse <= bz[j + 1] / 3
            kind = "EARLY" if near else "FALSE"
        rows.append({"i": i, "D": D, "year": YEAR[i], "kind": kind, "swing": j, "swing_start": bs[j],
                     "swing_end": be[j], "swing_dir": bd[j], "swing_size": bz[j], "next_size": bz[j + 1],
                     "progress": f, "exit_i": x, "adverse_to_turn": adverse,
                     "end_minus_flip": be[j] - i, "net": trades.net_pts.get(idx[i], np.nan)})
    return pd.DataFrame(rows), zz


say("=" * 100)
say("5. CLASSIFICATION")
cls6, _ = classify(6)
same = (len(cls6) == len(fl)) and (cls6.kind.to_numpy() == fl.kind.to_numpy()).all() and (cls6.i.to_numpy() == fl.i.to_numpy()).all()
say(f"   parametrised re-implementation at 6 ATR reproduces the original labels exactly: {same}")
early_pre = cls6[(cls6.kind == "EARLY") & (cls6.swing_dir != cls6.D)]
say(f"   EARLY flips that came BEFORE the turn (opposite swing, turn within 12 candles): {len(early_pre)} of "
    f"{(cls6.kind == 'EARLY').sum()}")
say("   circularity: share of each class with net P&L > 0 (labels use the same future path as the trade)")
for y in (2024, 2025):
    x = cls6[cls6.year == y]
    say("     " + str(y) + ": " + ", ".join(f"{k} {np.mean(x[x.kind == k].net > 0) * 100:.0f}% win"
                                         f" (net/trade {x[x.kind == k].net.mean():+.3f})" for k in ("EARLY", "LATE", "TURNED", "FALSE")))
per_swing = cls6.groupby("swing").size()
say(f"   flips per big swing (classified flips): mean {per_swing.mean():.2f}, max {per_swing.max()}; "
    f"swings holding >1 FALSE flip: {(cls6[cls6.kind == 'FALSE'].groupby('swing').size() > 1).sum()}")
false_by_swing = cls6[cls6.kind == "FALSE"].groupby("swing").size()
say(f"   FALSE flips share a swing with another FALSE flip: {(false_by_swing[false_by_swing > 1].sum() / false_by_swing.sum()) * 100:.0f}%")


# ---------------------------------------------------------------- 6. statistics
def auc_fast(pos, neg):
    pos, neg = pos[~np.isnan(pos)], neg[~np.isnan(neg)]
    if not len(pos) or not len(neg):
        return np.nan, 0
    sn = np.sort(neg)
    lt = np.searchsorted(sn, pos, "left")
    le = np.searchsorted(sn, pos, "right")
    return float((lt + 0.5 * (le - lt)).sum() / (len(pos) * len(neg))), len(pos) * len(neg)


def strat(feat, pos, neg, strata):
    tot = wt = 0.0
    for s in np.unique(strata):
        k = strata == s
        a, w = auc_fast(feat[k & pos], feat[k & neg])
        if w:
            tot, wt = tot + a * w, wt + w
    return tot / wt if wt else np.nan


def hanley(a, n1, n2):
    q1, q2 = a / (2 - a), 2 * a * a / (1 + a)
    return np.sqrt((a * (1 - a) + (n1 - 1) * (q1 - a * a) + (n2 - 1) * (q2 - a * a)) / (n1 * n2))


def cluster_boot(fn, groups, B, rng_):
    uniq = np.unique(groups)
    members = [np.nonzero(groups == u)[0] for u in uniq]
    out = np.empty(B)
    for r in range(B):
        ix = np.concatenate([members[p] for p in rng_.integers(0, len(uniq), len(uniq))])
        out[r] = fn(ix)
    return out


def shift_null(fn_labels, labels, B, rng_, min_shift=50):
    n = len(labels)
    return np.array([fn_labels(np.roll(labels, int(rng_.integers(min_shift, n - min_shift)))) for _ in range(B)])


say("=" * 100)
say("6. STATISTICS: EARLY vs FALSE, hour-stratified AUC, day- and swing-block bootstrap, circular-shift null")
headline, nulls = [], []
SENS_FEATS = ["r21", "slope6", "r100", "release", "er14", "er21", "d_er14"]
expected = {}
for version, k_atr in [(v, k) for v in ZIGZAGS for k in (4, 6, 8, 10)]:
    cls, _ = classify(k_atr, ZIGZAGS[version])
    if version == "corrected" and k_atr == 6:
        cls6_corr = cls
    feats = FLIP_FEATS if k_atr == 6 else SENS_FEATS
    B = B_MAIN if k_atr == 6 else B_SENS
    for y in (2024, 2025):
        x = cls[cls.year == y].sort_values("i").reset_index(drop=True)
        ii = x.i.to_numpy()
        hours, days = HOUR[ii], idx[ii].normalize().asi8
        swings = x.swing.to_numpy()
        lab = x.kind.to_numpy()
        for kind in ("EARLY", "LATE", "TURNED", "FALSE"):
            z = x[x.kind == kind]
            headline.append({"zigzag": version, "year": y, "swing_atr": k_atr, "feature": "", "comparison": f"class_{kind}",
                             "n_pos": len(z), "n_neg": len(x), "auc_hour": "", "auc_raw": "", "ci_day_lo": "",
                             "ci_day_hi": "", "ci_swing_lo": "", "ci_swing_hi": "", "null_p": "", "hanley_se": "",
                             "net_sum": round(z.net.sum(), 3), "net_per_trade": round(z.net.mean(), 4),
                             "win_rate": round(np.mean(z.net > 0), 4)})
        if k_atr == 6:
            expected.setdefault(version, {})[str(y)] = {"n_EARLY": int((lab == "EARLY").sum()), "n_FALSE": int((lab == "FALSE").sum()),
                                "net_EARLY": float(x[x.kind == "EARLY"].net.sum()),
                                "net_FALSE": float(x[x.kind == "FALSE"].net.sum())}
        for f in feats:
            v = F[f][ii]
            pos, neg = lab == "EARLY", lab == "FALSE"
            obs = strat(v, pos, neg, hours)
            raw, _ = auc_fast(v[pos], v[neg])
            rng_f = np.random.default_rng([SEED, k_atr, y, FLIP_FEATS.index(f), list(ZIGZAGS).index(version)])
            bd = cluster_boot(lambda ix: strat(v[ix], pos[ix], neg[ix], hours[ix]), days, B, rng_f)
            bsw = cluster_boot(lambda ix: strat(v[ix], pos[ix], neg[ix], hours[ix]), swings, B, rng_f)
            nul = shift_null(lambda L: strat(v, L == "EARLY", L == "FALSE", hours), lab, B, rng_f)
            p = (1 + np.sum(np.abs(nul - 0.5) >= abs(obs - 0.5))) / (B + 1)
            headline.append({"zigzag": version, "year": y, "swing_atr": k_atr, "feature": f, "comparison": "EARLY_vs_FALSE",
                             "n_pos": int(pos.sum()), "n_neg": int(neg.sum()), "auc_hour": round(obs, 4),
                             "auc_raw": round(raw, 4), "ci_day_lo": round(np.nanpercentile(bd, 2.5), 4),
                             "ci_day_hi": round(np.nanpercentile(bd, 97.5), 4),
                             "ci_swing_lo": round(np.nanpercentile(bsw, 2.5), 4),
                             "ci_swing_hi": round(np.nanpercentile(bsw, 97.5), 4), "null_p": round(p, 4),
                             "hanley_se": round(hanley(raw, pos.sum(), neg.sum()), 4), "net_sum": "",
                             "net_per_trade": "", "win_rate": ""})
            nulls.append({"zigzag": version, "year": y, "swing_atr": k_atr, "feature": f, "comparison": "EARLY_vs_FALSE",
                          "method": "circular shift of the class-label sequence within the year (shift 50..n-50 flips),"
                                    " hour-stratified AUC", "B": B, "seed": f"[{SEED},{k_atr},{y},{FLIP_FEATS.index(f)},{list(ZIGZAGS).index(version)}]",
                          "observed": round(obs, 4), "null_mean": round(nul.mean(), 4), "null_sd": round(nul.std(), 4),
                          "null_q025": round(np.percentile(nul, 2.5), 4), "null_q975": round(np.percentile(nul, 97.5), 4),
                          "p_two_sided": round(p, 4), "boot_day_sd": round(np.nanstd(bd), 4),
                          "boot_swing_sd": round(np.nanstd(bsw), 4)})
            if k_atr == 6 and f in ("r21", "er21"):
                expected[version][str(y)][f"auc_hour_{f}"] = obs
                expected[version][str(y)][f"auc_raw_{f}"] = raw
            if k_atr == 6 and version == "original":
                for kind, other in (("EARLY", "rest"), ("FALSE", "rest")):
                    pp, nn_ = lab == kind, lab != kind
                    headline.append({"zigzag": version, "year": y, "swing_atr": 6, "feature": f, "comparison": f"{kind}_vs_REST",
                                     "n_pos": int(pp.sum()), "n_neg": int(nn_.sum()),
                                     "auc_hour": round(strat(v, pp, nn_, hours), 4),
                                     "auc_raw": round(auc_fast(v[pp], v[nn_])[0], 4), "ci_day_lo": "", "ci_day_hi": "",
                                     "ci_swing_lo": "", "ci_swing_hi": "", "null_p": "", "hanley_se": "",
                                     "net_sum": "", "net_per_trade": "", "win_rate": ""})
    say(f"   done {version} {k_atr} ATR")
H = pd.DataFrame(headline)
ef = H[H.comparison == "EARLY_vs_FALSE"]
for version, k_atr in [(v, k) for v in ZIGZAGS for k in (4, 6, 8, 10)]:
    say(f"-- [{version} zigzag] {k_atr} ATR big swings: EARLY vs FALSE, hour-stratified AUC [day-block 95% CI] null p")
    for f in (FLIP_FEATS if k_atr == 6 else SENS_FEATS):
        cells = []
        for y in (2024, 2025):
            r = ef[(ef.zigzag == version) & (ef.swing_atr == k_atr) & (ef.year == y) & (ef.feature == f)].iloc[0]
            cells.append(f"{y} {r.auc_hour:.3f} [{r.ci_day_lo:.3f},{r.ci_day_hi:.3f}] p={r.null_p:.3f} (nE {r.n_pos}, nF {r.n_neg})")
        say(f"   {f:<8} " + " | ".join(cells))
    cl = H[(H.zigzag == version) & (H.swing_atr == k_atr) & H.comparison.str.startswith("class_")]
    for y in (2024, 2025):
        z = cl[cl.year == y]
        say(f"   classes {y}: " + ", ".join(f"{r.comparison[6:]} n={r.n_pos} net/trade={r.net_per_trade:+.3f}" for r in z.itertuples()))
for version in ZIGZAGS:
    e = ef[ef.zigzag == version]
    say(f"   [{version}] EARLY vs FALSE tests: {len(e)}; null p < 0.05: {int((e.null_p < 0.05).sum())}"
        f" (expected by chance ~{0.05 * len(e):.1f}); p < 0.05 in BOTH years with the same direction: " +
        str([f"{k}ATR {f} {z.auc_hour.round(3).tolist()}" for (k, f), z in e.groupby(["swing_atr", "feature"])
             if len(z) == 2 and (z.null_p < 0.05).all() and np.sign(z.auc_hour - 0.5).nunique() == 1] or "none"))

# noise band check on the big-turn analysis (the original +/-0.025 claim)
say("-- the original '+/-0.025' noise band (big turn vs control at t=0), checked by day-block bootstrap")
big_s, small_s, ctrl = g["big_s"], g["small_s"], g["ctrl"]
for f in ("r21", "er21"):
    for y in (2024, 2025):
        bp = big_s.start.to_numpy()[YEAR[big_s.start.to_numpy()] == y]
        cp = ctrl[YEAR[ctrl] == y]
        pts = np.r_[bp, cp]
        pos = np.r_[np.ones(len(bp), bool), np.zeros(len(cp), bool)]
        v, hrs, dd = F[f][pts], HOUR[pts], idx[pts].normalize().asi8
        obs = strat(v, pos, ~pos, hrs)
        bt = cluster_boot(lambda ix: strat(v[ix], pos[ix], ~pos[ix], hrs[ix]), dd, B_MAIN, np.random.default_rng([SEED, 99, y]))
        a_raw, _ = auc_fast(v[pos], v[~pos])
        say(f"   {f} {y}: AUC {obs:.3f}, day-block 95% CI half-width {(np.percentile(bt, 97.5) - np.percentile(bt, 2.5)) / 2:.3f},"
            f" i.i.d. Hanley-McNeil 1.96*SE {1.96 * hanley(a_raw, len(bp), len(cp)):.3f}")

# ---------------------------------------------------------------- 7. matching on 12-16 UTC and ATR7/ATR100
say("=" * 100)
say("7. EARLY vs FALSE with ATR7/ATR100 quintile + hour strata (6 ATR), and inside/outside 12-16 UTC")
for y in (2024, 2025):
    x = cls6[cls6.year == y]
    ii, lab = x.i.to_numpy(), x.kind.to_numpy()
    q = np.digitize(F["r100"][ii], np.nanpercentile(F["r100"][cls6.i.to_numpy()], [20, 40, 60, 80]))
    cells = []
    for f in ("r21", "release", "er21", "d_er14"):
        v = F[f][ii]
        both = strat(v, lab == "EARLY", lab == "FALSE", HOUR[ii] * 10 + q)
        inside = (HOUR[ii] >= 12) & (HOUR[ii] < 16)
        a_in = auc_fast(v[inside & (lab == "EARLY")], v[inside & (lab == "FALSE")])[0]
        a_out = auc_fast(v[~inside & (lab == "EARLY")], v[~inside & (lab == "FALSE")])[0]
        cells.append(f"{f} hour+r100q {both:.3f} / 12-16 {a_in:.3f} / other {a_out:.3f}")
    say(f"   {y}: " + "; ".join(cells))
    share = lambda kind: np.mean(((HOUR[x[x.kind == kind].i] >= 12) & (HOUR[x[x.kind == kind].i] < 16))) * 100
    say(f"   {y}: share in 12-16 UTC: EARLY {share('EARLY'):.0f}%, FALSE {share('FALSE'):.0f}%, all {np.mean((HOUR[ii] >= 12) & (HOUR[ii] < 16)) * 100:.0f}%")

# ---------------------------------------------------------------- 8. cross-year model
say("=" * 100)
say("8. CROSS-YEAR LOGISTIC MODEL (original `logistic`: Newton-Raphson, 50 iterations, L2 0.01 on standardised"
    " non-intercept weights; standardise with TRAIN-year mean/sd; drop rows with any NaN; no feature selection)")
model_rows = []
sets = SETS + [("ser14", ["ser14"]), ("r100+release+d_er14+ser14", ["r100", "release", "d_er14", "ser14"]),
               ("all + signed ER", FLIP_FEATS)]
for version, labels, task, pos_kind, neg_kinds in (
        ("original", cls6, "EARLY_vs_REST (as originally reported)", "EARLY", ("LATE", "TURNED", "FALSE")),
        ("original", cls6, "EARLY_vs_FALSE (the audited claim)", "EARLY", ("FALSE",)),
        ("corrected", cls6_corr, "EARLY_vs_FALSE (the audited claim)", "EARLY", ("FALSE",))):
    say(f"-- [{version} zigzag] {task}: test AUC raw [day-block 95% CI of the test year]")
    for name, fs in sets:
        res = []
        for train, test in ((2024, 2025), (2025, 2024)):
            def xy(year):
                x = labels[(labels.year == year) & labels.kind.isin((pos_kind,) + neg_kinds)]
                X = np.column_stack([F[f][x.i.to_numpy()] for f in fs])
                yv = (x.kind == pos_kind).to_numpy().astype(float)
                good = ~np.isnan(X).any(axis=1)
                return X[good], yv[good], x.i.to_numpy()[good]
            Xa, ya, _ = xy(train)
            mu, sd = Xa.mean(0), Xa.std(0)
            w = logistic((Xa - mu) / sd, ya)
            Xb, yb, ib = xy(test)
            score = np.column_stack([np.ones(len(Xb)), (Xb - mu) / sd]) @ w
            a = auc_fast(score[yb == 1], score[yb == 0])[0]
            dd = idx[ib].normalize().asi8
            bt = cluster_boot(lambda ix: auc_fast(score[ix][yb[ix] == 1], score[ix][yb[ix] == 0])[0], dd, B_SENS,
                              np.random.default_rng([SEED, 7, train]))
            res.append(f"{train}->{test} {a:.3f} [{np.percentile(bt, 2.5):.3f},{np.percentile(bt, 97.5):.3f}]")
            model_rows.append({"zigzag": version, "task": task, "set": name, "train": train, "test": test, "auc_test": round(a, 4),
                               "ci_lo": round(np.percentile(bt, 2.5), 4), "ci_hi": round(np.percentile(bt, 97.5), 4),
                               "n_test": len(yb), "n_test_pos": int(yb.sum())})
        say(f"   {name:<28} " + " | ".join(res))
pd.DataFrame(model_rows).to_csv(OUT / "cross_year_models.csv", index=False)
for r in model_rows:
    headline.append({"zigzag": r["zigzag"], "year": r["test"], "swing_atr": 6, "feature": r["set"],
                     "comparison": "crossyear_logit_" + r["task"].split(" ")[0] + f"_train{r['train']}",
                     "n_pos": r["n_test_pos"], "n_neg": r["n_test"] - r["n_test_pos"], "auc_hour": "",
                     "auc_raw": r["auc_test"], "ci_day_lo": r["ci_lo"], "ci_day_hi": r["ci_hi"], "ci_swing_lo": "",
                     "ci_swing_hi": "", "null_p": "", "hanley_se": "", "net_sum": "", "net_per_trade": "", "win_rate": ""})

# ---------------------------------------------------------------- 9. package files
pd.DataFrame(headline).to_csv(HERE / "HEADLINE_RESULTS.csv", index=False)
pd.DataFrame(nulls).to_csv(HERE / "NULL_TESTS.csv", index=False)
(HERE / "expected_from_original.json").write_text(json.dumps(expected, indent=2))

# sample cases: 7 per class per year
picks = []
for y in (2024, 2025):
    for kind in ("EARLY", "LATE", "TURNED", "FALSE"):
        pool = cls6[(cls6.year == y) & (cls6.kind == kind)]
        picks.append(pool.sample(7, random_state=SEED + y))
S = pd.concat(picks).sort_values("i")
rows = []
for r in S.itertuples():
    i = r.i
    t = trades.loc[idx[i]]
    rows.append({
        "flip_candle_utc": idx[i], "year": r.year, "direction": "LONG" if r.D == 1 else "SHORT",
        "st_before": int(st[i - 1]), "st_after": int(st[i]), "flip": int(st[i] != st[i - 1]),
        "open_bid": b.open_bid.iat[i], "high_bid": h[i], "low_bid": l[i], "close_bid": c[i],
        "prev_close_bid": c[i - 1], "TR": TR[i], "ATR7": a7[i], "ATR14": a14[i], "ATR21": a21[i], "ATR100": a100[i],
        "ATR7_ATR21": F["r21"][i], "ATR7_ATR100": F["r100"][i], "ER7": F["er7"][i], "ER14": F["er14"][i],
        "ER21": F["er21"][i], "hour_utc": HOUR[i],
        "swing_start_utc": idx[r.swing_start], "swing_start_close": c[r.swing_start],
        "swing_end_utc": idx[r.swing_end], "swing_end_close": c[r.swing_end],
        "swing_dir": "UP" if r.swing_dir == 1 else "DOWN", "swing_size": r.swing_size,
        "next_swing_size": r.next_size, "swing_size_in_ATR14_at_start": r.swing_size / a14[r.swing_start],
        "position_through_swing": r.progress, "candles_flip_to_swing_end": r.end_minus_flip,
        "adverse_close_to_turn": r.adverse_to_turn, "st_exit_candle_utc": idx[r.exit_i],
        "exit_before_swing_end": bool(r.exit_i <= r.swing_end), "class": r.kind,
        "entry_utc": t.entry_time, "entry_price": t.entry_price, "exit_utc": t.exit_time,
        "exit_price": t.exit_price, "gross_pts": t.gross_pts, "financing_pts": t.financing_pts, "net_pts": t.net_pts})
SC = pd.DataFrame(rows)
SC.to_csv(HERE / "SAMPLE_CASES.csv", index=False, float_format="%.6f")

# classification walk-through (20 cases, rule evaluated in words)
lines = ["# Classification walk-through (20 sampled flips)", "",
         "Generated by `audit_checks.py` from `SAMPLE_CASES.csv`. Rule (METHODOLOGY.md §6): S = the 6 ATR",
         "swing containing the flip candle; f = position through S; x = ST exit candle.", ""]
for _, r in SC.groupby(["year", "class"]).head(3).iterrows():
    same_dir = (r.direction == "LONG") == (r.swing_dir == "UP")
    if same_dir:
        why = (f"S goes {r.swing_dir} = trade direction; f = {r.position_through_swing:.3f} "
               + ("<= 1/3 -> EARLY" if r.position_through_swing <= 1 / 3 else
                  f"> 1/3; S ends {r.swing_end_utc} which is {'before' if not r.exit_before_swing_end else 'not before'}"
                  f" the ST exit candle {r.st_exit_candle_utc} -> {'TURNED' if not r.exit_before_swing_end else 'LATE'}"))
    else:
        cond = (r.candles_flip_to_swing_end <= 12, not r.exit_before_swing_end,
                r.adverse_close_to_turn <= r.next_swing_size / 3)
        why = (f"S goes {r.swing_dir}, against the trade; turn in {r.candles_flip_to_swing_end} candles (<=12: {cond[0]}),"
               f" turn before ST exit: {cond[1]}, price went {r.adverse_close_to_turn:.3f} further against us vs next swing/3 ="
               f" {r.next_swing_size / 3:.3f} ({cond[2]}) -> {'EARLY' if all(cond) else 'FALSE'}")
    lines.append(f"- **{r.flip_candle_utc} {r.direction}** (close {r.close_bid:.3f}); S {r.swing_start_utc} "
                 f"({r.swing_start_close:.3f}) -> {r.swing_end_utc} ({r.swing_end_close:.3f}), size {r.swing_size:.3f}. "
                 f"{why}. Labelled **{r['class']}**; net {r.net_pts:+.3f}.")
(HERE / "CLASSIFICATION_WALKTHROUGH.md").write_text("\n".join(lines) + "\n")

# manual calculations for 3 flips
mc = ["# Manual calculations", "",
      "Generated by `audit_checks.py`. Everything below is rebuilt from the source minute rows, not read",
      "from the implementation; the implementation's value is printed only for comparison at the end.", ""]
cases = [SC.iloc[0], SC.iloc[len(SC) // 2], SC.iloc[-1]]
for case in cases:
    i = t_idx[case.flip_candle_utc]
    t0 = idx[i]
    minutes = mm[(mm.index >= t0) & (mm.index < t0 + pd.Timedelta(minutes=5))]
    mc += [f"## Flip candle {t0} ({case.direction}, {case['class']})", "",
           "### 1-minute source rows -> 5-minute bid candle", "",
           "| minute | open_bid | high_bid | low_bid | close_bid |", "|---|---|---|---|---|"]
    mc += [f"| {t} | {row.open_bid:.3f} | {row.high_bid:.3f} | {row.low_bid:.3f} | {row.close_bid:.3f} |"
           for t, row in minutes.iterrows()]
    mc += ["", f"5m candle = open of first minute {minutes.open_bid.iloc[0]:.3f}, max high {minutes.high_bid.max():.3f},"
               f" min low {minutes.low_bid.min():.3f}, close of last minute {minutes.close_bid.iloc[-1]:.3f}"
               f" (implementation: {b.open_bid.iat[i]:.3f}, {h[i]:.3f}, {l[i]:.3f}, {c[i]:.3f}).", ""]
    rows_ = range(i - 21, i + 1)
    mc += ["### True range and ER inputs, last 22 candles", "",
           "| candle | high | low | close | prev close | H-L | abs(H-prevC) | abs(L-prevC) | TR | abs(dC) |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    for t in rows_:
        mc.append(f"| {idx[t]} | {h[t]:.3f} | {l[t]:.3f} | {c[t]:.3f} | {c[t - 1]:.3f} | {h[t] - l[t]:.3f} | "
                  f"{abs(h[t] - c[t - 1]):.3f} | {abs(l[t] - c[t - 1]):.3f} | {max(h[t] - l[t], abs(h[t] - c[t - 1]), abs(l[t] - c[t - 1])):.3f} | "
                  f"{abs(c[t] - c[t - 1]):.3f} |")
    mc += ["", "### Wilder ATR recursion (ATR_t = (ATR_{t-1} x (n-1) + TR_t) / n)", "",
           "The recursion needs a starting value. To keep it independent of the implementation it is seeded here",
           "with the simple mean of n TRs 3,000 candles earlier; the seed's weight by candle i is (1-1/n)^3000",
           "(1e-200 for n=7, 3e-64 for n=21, 5e-14 for n=100), so any seed gives the same value.", ""]
    for n in (7, 21, 100):
        s0 = i - 3000
        val = TR[s0 - n + 1:s0 + 1].mean()
        trail = []
        for t in range(s0 + 1, i + 1):
            prev = val
            val = (val * (n - 1) + TR[t]) / n
            if t > i - 4:
                trail.append(f"ATR{n}[{idx[t]}] = ({prev:.6f} x {n - 1} + {TR[t]:.3f}) / {n} = {val:.6f}")
        mc += [f"- seed (mean TR of 3,000 candles back) = {TR[s0 - n + 1:s0 + 1].mean():.6f}"] + [f"- {x}" for x in trail]
        mc.append(f"- **ATR{n} = {val:.6f}** (implementation {dict(((7, a7), (21, a21), (100, a100)))[n][i]:.6f})")
        mc.append("")
    mc.append(f"ATR7/ATR21 = {atr_from_seed(i, 7):.6f} / {atr_from_seed(i, 21):.6f} = "
              f"{atr_from_seed(i, 7) / atr_from_seed(i, 21):.6f} (implementation {F['r21'][i]:.6f})")
    mc += ["", "### Kaufman efficiency ratio", ""]
    for n in (7, 14, 21):
        path = sum(abs(c[t] - c[t - 1]) for t in range(i - n + 1, i + 1))
        mc.append(f"- ER{n} = abs(close {c[i]:.3f} - close {n} candles back {c[i - n]:.3f}) / sum of the last {n} abs(dC) "
                  f"{path:.3f} = {abs(c[i] - c[i - n]):.3f} / {path:.3f} = {abs(c[i] - c[i - n]) / path:.6f}"
                  f" (implementation {F[f'er{n}'][i]:.6f})")
    mc.append("")
(HERE / "MANUAL_CALCULATIONS.md").write_text("\n".join(mc) + "\n")

# environment
commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=R, capture_output=True, text=True).stdout.strip()
import numpy, pandas, pyarrow
env = {"python": sys.version.split()[0], "platform": platform.platform(), "numpy": numpy.__version__,
       "pandas": pandas.__version__, "pyarrow": pyarrow.__version__, "git_commit_at_run": commit,
       "seed": SEED, "bootstrap_B_main": B_MAIN, "bootstrap_B_sensitivity": B_SENS,
       "original_experiment_seed": "numpy default_rng(7) for control sampling",
       "data_db": str(db.relative_to(R)), "data_minutes_sha256": digest, "data_minutes_rows": int(len(mm)),
       "db_rows_before_exclusion": int(db_rows), "excluded_minutes": int(excl),
       "original_output_identical_to_committed": identical}
(HERE / "ENVIRONMENT.json").write_text(json.dumps(env, indent=2))
(OUT / "audit_checks_output.txt").write_text("\n".join(LOG) + "\n")
say("wrote HEADLINE_RESULTS.csv, NULL_TESTS.csv, SAMPLE_CASES.csv, CLASSIFICATION_WALKTHROUGH.md,"
    " MANUAL_CALCULATIONS.md, ENVIRONMENT.json, expected_from_original.json, outputs/")
