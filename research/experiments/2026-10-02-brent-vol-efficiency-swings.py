"""Brent 5m: is there information before or near the start of big swings that separates them from noise?

The owner's brief (2026-10-02): test volatility expansion (ATR7/ATR21), compression -> expansion,
Kaufman efficiency ratio, their combination, and whether any of it makes Supertrend 7/3 flips more
selective. Research only; no strategy or production code changes. Negative results are reported.

DEFINITIONS (fixed before any result was seen)

Data: Brent 5m bid, 2024-01-11 .. 2025-12-31; 2024 and 2025 reported separately. Data from
RESERVED_FROM (2026-02-01) is never loaded; January 2026 is used only as warm-up/out of scope.

Swings (zigzag on the 5m bid close; a swing ends when price comes back h x ATR14 against it):
  big turn    a start of a swing of the 6 ATR zigzag (~2.3 per day, median swing ~$1)
  small turn  a start of a 2 ATR zigzag swing that is more than 6 candles from any big turn (a turn
              that never grew into a big swing)
  control     5 random candles per big turn, same year and same UTC hour, random direction
A turn is a hindsight label (the extreme candle). Features at a turn use only candles up to it; the
offsets -48..+24 show how the feature evolves around the turn (positive offsets = after the turn).

Features at candle t (only candles up to and including t):
  r21       ATR7 / ATR21                       slope6   r21(t) - r21(t-6)
  r100      ATR7 / ATR100 (known from before)   a21_100  ATR21 / ATR100 (longer-scale compression)
  squeeze   mean r21 over t-36 .. t-7 (how compressed it was before)
  release   r21(t) - min r21 over t-36 .. t-1 (how far short-term vol has expanded off its recent low)
  erN       Kaufman ER over N = 7, 14, 21: |c(t) - c(t-N)| / sum |c(i) - c(i-1)|
  d_erN     erN(t) - erN(t-N) (efficiency now vs the window before)
  serN      signed ER: (c(t) - c(t-N)) / path, times the trade direction (Supertrend flips only)

Effect size: AUC = chance a random positive scores higher than a random negative (0.5 = nothing,
0.6 = modest, 0.7 = strong). Computed within each UTC hour and pooled ("hour-matched"), so time of
day cannot create it. ~95% noise band for ~600 vs ~3000 is about +/-0.025.

Supertrend 7/3 flip classes, against the big (6 ATR) swing S in progress at the flip candle i,
direction D, ST exit x (the open after the next 7/3 flip), progress f = how much of S was already
done at the flip close (in S's direction, as a share of S's size):
  EARLY      S goes D and f <= 1/3; or S goes against D but ends (turns to D) within 12 candles,
             before our exit, and price only went <= 1/3 of the next swing's size further against us
  LATE       S goes D, f > 1/3, and S is still going when the ST exits
  TURNED     S goes D, f > 1/3, and S ends (the next big swing, against us, starts) before our exit
  FALSE      S goes against D and does not turn within 12 candles: a counter-wiggle inside a big
             swing (chop / false reversal)

    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-10-02-brent-vol-efficiency-swings.py
"""
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.dayrun import RESERVED_FROM
from engine.flips import flip_trades
from engine.indicators import atr, supertrend
from engine.instruments import load_instruments

R = Path(__file__).resolve().parents[2]
spec = load_instruments(R / "research/engine/instruments.yaml")["OIL_BRENT"]
m = load_cached_minutes(R / "data/axe-trader.sqlite", "OIL_BRENT", R / "research/engine/.cache")
b = resample(m[m.index < RESERVED_FROM], "5min")
idx = b.index
h, l, c = b.high_bid.to_numpy(), b.low_bid.to_numpy(), b.close_bid.to_numpy()
YEAR = idx.year.to_numpy()
HOUR = idx.hour.to_numpy()
START = pd.Timestamp("2024-01-11", tz="UTC")
in_scope = (idx >= START) & (YEAR <= 2025)
rng = np.random.default_rng(7)

a7, a14, a21, a100 = (atr(h, l, c, n) for n in (7, 14, 21, 100))
s = pd.Series
r21 = a7 / a21
path = {n: s(np.abs(np.diff(c, prepend=np.nan))).rolling(n).sum().to_numpy() for n in (7, 14, 21)}
signed = {n: (c - s(c).shift(n).to_numpy()) / path[n] for n in (7, 14, 21)}
F = {
    "r21": r21,
    "slope6": r21 - s(r21).shift(6).to_numpy(),
    "r100": a7 / a100,
    "a21_100": a21 / a100,
    "squeeze": s(r21).shift(7).rolling(30).mean().to_numpy(),
    "release": r21 - s(r21).shift(1).rolling(36).min().to_numpy(),
}
for n in (7, 14, 21):
    F[f"er{n}"] = np.abs(signed[n])
    F[f"d_er{n}"] = F[f"er{n}"] - s(F[f"er{n}"]).shift(n).to_numpy()
FEATS = list(F)
warm = np.arange(len(c)) >= 300
# ATR (Wilder, seeded on the first candle) needs a long warm-up; candles before 300 are never used


def zigzag(threshold):
    """Swings as rows: start, end, dir (+1 up), size (price), start_known (candle the start turn is confirmed)."""
    rows = []
    turn_i, ext_i, up, turn_known = 0, 0, None, 0
    for i in range(1, len(c)):
        if up is None:
            if abs(c[i] - c[0]) >= threshold[i]:
                up, ext_i = c[i] > c[0], i
            continue
        if (up and c[i] > c[ext_i]) or (not up and c[i] < c[ext_i]):
            ext_i = i
        elif abs(c[ext_i] - c[i]) >= threshold[i]:
            rows.append((turn_i, ext_i, 1 if up else -1, abs(c[ext_i] - c[turn_i]), turn_known))
            turn_i, ext_i, up, turn_known = ext_i, i, not up, i
    return pd.DataFrame(rows[1:], columns=["start", "end", "dir", "size", "known"])


big = zigzag(np.nan_to_num(6 * a14, nan=np.inf))
small_all = zigzag(np.nan_to_num(2 * a14, nan=np.inf))
big_starts = big.start.to_numpy()
near_big = np.array([np.abs(big_starts - p).min() <= 6 for p in small_all.start])
small = small_all[~near_big]
ok = lambda p: in_scope[p] & warm[p]
big_s = big[[ok(p) and p + 24 < len(c) for p in big.start]]
small_s = small[[ok(p) and p + 24 < len(c) for p in small.start]]

pool = {}
for y in (2024, 2025):
    for hr in range(24):
        pool[(y, hr)] = np.nonzero(in_scope & warm & (YEAR == y) & (HOUR == hr))[0]
ctrl = np.concatenate([rng.choice(pool[(YEAR[p], HOUR[p])], 5) for p in big_s.start])
ctrl = ctrl[ctrl + 24 < len(c)]


def auc(pos, neg):
    pos, neg = pos[~np.isnan(pos)], neg[~np.isnan(neg)]
    if not len(pos) or not len(neg):
        return np.nan, 0.0
    ranks = s(np.concatenate([pos, neg])).rank().to_numpy()
    return (ranks[:len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg)), len(pos) * len(neg)


def strat_auc(fp, fn, sp, sn):
    """AUC pooled over strata (weights n_pos x n_neg). sp/sn are stratum labels."""
    total = weight = 0.0
    for key in np.unique(np.concatenate([sp, sn])):
        a, w = auc(fp[sp == key], fn[sn == key])
        if w and not np.isnan(a):
            total, weight = total + a * w, weight + w
    return total / weight if weight else np.nan


def hour_auc(feat, pos_i, neg_i, offset=0):
    return strat_auc(feat[pos_i + offset], feat[neg_i + offset], HOUR[pos_i], HOUR[neg_i])


def fmt(x, w=6, p=2):
    return f"{x:>{w}.{p}f}" if not np.isnan(x) else f"{'-':>{w}}"


P = lambda df, y: df.start.to_numpy()[YEAR[df.start.to_numpy()] == y]
C = lambda y: ctrl[YEAR[ctrl] == y]

print("=" * 110)
print("0. SAMPLE")
for y in (2024, 2025):
    print(f"  {y}: big turns {len(P(big_s, y))}, small turns {len(P(small_s, y))}, controls {len(C(y))}")
known_lag = (big_s.known - big_s.start).to_numpy()
print(f"  a big turn is confirmed by the 6 ATR zigzag a median {np.median(known_lag):.0f} candles after it"
      f" (middle half {np.percentile(known_lag, 25):.0f}-{np.percentile(known_lag, 75):.0f})")

print("\n" + "=" * 110)
print("1-3. FEATURES AT THE TURN CANDLE (t=0). Medians, and hour-matched AUC (big vs control, big vs small turn)")
print(f"{'feature':<9}| {'2024 med: big':>13} {'small':>6} {'ctrl':>6} {'AUC b/c':>8} {'AUC b/s':>8} "
      f"| {'2025 med: big':>13} {'small':>6} {'ctrl':>6} {'AUC b/c':>8} {'AUC b/s':>8}")
for f in FEATS:
    cells = []
    for y in (2024, 2025):
        bp, sp_, cp = P(big_s, y), P(small_s, y), C(y)
        cells.append(f"{fmt(np.nanmedian(F[f][bp]), 13)} {fmt(np.nanmedian(F[f][sp_]))} {fmt(np.nanmedian(F[f][cp]))}"
                     f" {fmt(hour_auc(F[f], bp, cp), 8)} {fmt(hour_auc(F[f], bp, sp_), 8)}")
    print(f"{f:<9}| " + " | ".join(cells))

OFFS = [-48, -36, -24, -12, -6, -3, 0, 3, 6, 12, 24]
print("\n" + "=" * 110)
print("2/6. TRAJECTORIES AROUND THE TURN: median feature by offset (candles), and hour-matched AUC big vs small turn")
for f in ["r21", "release", "squeeze", "r100", "er14", "d_er14", "er21"]:
    print(f"-- {f}")
    print(f"   {'offset':<16}" + "".join(f"{o:>7}" for o in OFFS))
    for y in (2024, 2025):
        bp, sp_, cp = P(big_s, y), P(small_s, y), C(y)
        for name, pts in (("big", bp), ("small", sp_), ("ctrl", cp)):
            print(f"   {y} {name:<11}" + "".join(fmt(np.nanmedian(F[f][pts + o]), 7) for o in OFFS))
        print(f"   {y} AUC big/sml" + "".join(fmt(hour_auc(F[f], bp, sp_, o), 7) for o in OFFS))
        print(f"   {y} AUC big/ctl" + "".join(fmt(hour_auc(F[f], bp, cp, o), 7) for o in OFFS))

print("\n" + "=" * 110)
print("7. HOW MUCH IS ALREADY THE 12-16 UTC / ATR7/ATR100 EFFECT? big vs small turn AUC at t=0")
q_edges = np.nanpercentile(F["r100"][ctrl], [20, 40, 60, 80])
qbin = lambda pts: np.digitize(F["r100"][pts], q_edges)
print(f"{'feature':<9}| {'2024 raw':>9} {'hour':>6} {'hour+r100q':>11} | {'2025 raw':>9} {'hour':>6} {'hour+r100q':>11}")
for f in FEATS:
    cells = []
    for y in (2024, 2025):
        bp, sp_ = P(big_s, y), P(small_s, y)
        raw = auc(F[f][bp], F[f][sp_])[0]
        hr = hour_auc(F[f], bp, sp_)
        both = strat_auc(F[f][bp], F[f][sp_], HOUR[bp] * 10 + qbin(bp), HOUR[sp_] * 10 + qbin(sp_))
        cells.append(f"{fmt(raw, 9)} {fmt(hr)} {fmt(both, 11)}")
    print(f"{f:<9}| " + " | ".join(cells))
for y in (2024, 2025):
    bp, sp_ = P(big_s, y), P(small_s, y)
    share = lambda pts: np.mean((HOUR[pts] >= 12) & (HOUR[pts] < 16)) * 100
    print(f"  {y}: share of turns in 12-16 UTC: big {share(bp):.0f}%, small {share(sp_):.0f}%, controls {share(C(y)):.0f}%")


def logistic(X, y, l2=1e-2, iters=50):
    X = np.column_stack([np.ones(len(X)), X])
    w = np.zeros(X.shape[1])
    for _ in range(iters):
        p = 1 / (1 + np.exp(-X @ w))
        g = X.T @ (p - y) + l2 * np.r_[0, w[1:]]
        H = X.T @ (X * (p * (1 - p))[:, None]) + l2 * np.diag(np.r_[0, np.ones(len(w) - 1)])
        w -= np.linalg.solve(H, g)
    return w


def cross_year(sets, pos_by_year, neg_by_year, feats_at=lambda f, pts: F[f][pts]):
    """Fit on one year, AUC on the other (standardised with the training year)."""
    out = {}
    for name, fs in sets:
        res = []
        for train, test in ((2024, 2025), (2025, 2024)):
            def xy(year):
                X = np.column_stack([np.concatenate([feats_at(f, pos_by_year[year]), feats_at(f, neg_by_year[year])]) for f in fs])
                yv = np.r_[np.ones(len(pos_by_year[year])), np.zeros(len(neg_by_year[year]))]
                good = ~np.isnan(X).any(axis=1)
                return X[good], yv[good]
            Xa, ya = xy(train)
            mu, sd = Xa.mean(0), Xa.std(0)
            w = logistic((Xa - mu) / sd, ya)
            Xb, yb = xy(test)
            score = np.column_stack([np.ones(len(Xb)), (Xb - mu) / sd]) @ w
            res.append(auc(score[yb == 1], score[yb == 0])[0])
        out[name] = res
    return out


SETS = [("r100", ["r100"]), ("r21", ["r21"]), ("slope6", ["slope6"]), ("release", ["release"]),
        ("squeeze+release", ["squeeze", "release"]), ("er14", ["er14"]), ("d_er14", ["d_er14"]),
        ("r100+r21", ["r100", "r21"]), ("release+d_er14", ["release", "d_er14"]),
        ("r21+er14", ["r21", "er14"]), ("r100+release+d_er14", ["r100", "release", "d_er14"]),
        ("all features", FEATS)]

print("\n" + "=" * 110)
print("4. COMBINATIONS: logistic fit on one year, AUC on the other (not hour-matched)")
for label, neg in (("big turn vs small turn", {y: P(small_s, y) for y in (2024, 2025)}),
                   ("big turn vs control", {y: C(y) for y in (2024, 2025)})):
    res = cross_year(SETS, {y: P(big_s, y) for y in (2024, 2025)}, neg)
    print(f"-- {label}:  {'fit24->test25':>14} {'fit25->test24':>14}")
    for name, (a, b2) in res.items():
        print(f"   {name:<22}{fmt(a, 14)} {fmt(b2, 14)}")

print("\n" + "=" * 110)
print("4b. REGIME QUADRANTS at t=0, split at the control medians (pooled years). Share of each group per quadrant;"
      " enrichment = big share / control share")
for xf, yf in (("release", "d_er14"), ("r21", "er14"), ("slope6", "d_er7")):
    xm, ym = np.nanmedian(F[xf][ctrl]), np.nanmedian(F[yf][ctrl])
    print(f"-- x = {xf} (median {xm:.3f}), y = {yf} (median {ym:.3f})")
    names = {(0, 0): "A low vol, low eff", (1, 0): "B high vol, low eff", (0, 1): "C low vol, high eff",
             (1, 1): "D high vol, high eff"}
    print(f"   {'quadrant':<22}| {'2024 big':>8} {'small':>6} {'ctrl':>6} {'enrich':>7} | {'2025 big':>8} {'small':>6} {'ctrl':>6} {'enrich':>7}")
    for (qx, qy), name in names.items():
        cells = []
        for y in (2024, 2025):
            def share(pts):
                vx, vy = F[xf][pts], F[yf][pts]
                good = ~np.isnan(vx) & ~np.isnan(vy)
                return np.mean(((vx[good] > xm) == qx) & ((vy[good] > ym) == qy)) * 100
            sb, ss, sc = share(P(big_s, y)), share(P(small_s, y)), share(C(y))
            cells.append(f"{sb:>8.0f} {ss:>6.0f} {sc:>6.0f} {sb / sc:>7.2f}")
        print(f"   {name:<22}| " + " | ".join(cells))

print("\n" + "=" * 110)
print("5. SUPERTREND 7/3 FLIPS")
_, st = supertrend(b.high_bid, b.low_bid, b.close_bid, 7, 3.0)
st = np.asarray(st)
flips = [i for i in range(1, len(c) - 1) if st[i] != st[i - 1]]
trades = flip_trades(b, st, spec, start=START).set_index("signal_time")
bs, be, bd, bz = (big[k].to_numpy() for k in ("start", "end", "dir", "size"))
rows = []
for k in range(len(flips) - 1):
    i = flips[k]
    if not (in_scope[i] and warm[i]):
        continue
    D, x = int(st[i]), flips[k + 1] + 1
    j = int(np.searchsorted(bs, i, side="right")) - 1
    if j < 0 or j + 1 >= len(bs) or not (bs[j] <= i < be[j]):
        continue
    if bd[j] == D:
        f = D * (c[i] - c[bs[j]]) / bz[j]
        kind = "EARLY" if f <= 1 / 3 else ("TURNED" if be[j] < x else "LATE")
    else:
        f = -(D * (c[i] - c[bs[j]]) / bz[j])
        near = be[j] - i <= 12 and be[j] < x and D * (c[i] - c[be[j]]) <= bz[j + 1] / 3
        kind = "EARLY" if near else "FALSE"
    net = trades.net_pts.get(idx[i], np.nan)
    rows.append({"i": i, "D": D, "year": YEAR[i], "kind": kind, "net": net})
fl = pd.DataFrame(rows)
FI = fl.i.to_numpy()
for n in (7, 14, 21):
    F[f"ser{n}"] = np.full(len(c), np.nan)
    F[f"ser{n}"][FI] = signed[n][FI] * fl.D.to_numpy()
FLIP_FEATS = FEATS + ["ser7", "ser14", "ser21"]
KINDS = ["EARLY", "LATE", "TURNED", "FALSE"]
print("class mix and the flip-to-flip trade result per class (net pts after spread and funding)")
print(f"   {'class':<8}| {'2024 n':>7} {'share':>6} {'net':>8} {'/trade':>7} | {'2025 n':>7} {'share':>6} {'net':>8} {'/trade':>7}")
for kind in KINDS + ["ALL"]:
    cells = []
    for y in (2024, 2025):
        fy = fl[fl.year == y]
        x = fy if kind == "ALL" else fy[fy.kind == kind]
        cells.append(f"{len(x):>7} {len(x) / len(fy) * 100:>5.0f}% {x.net.sum():>8.1f} {x.net.mean():>7.3f}")
    print(f"   {kind:<8}| " + " | ".join(cells))

ex = lambda y, kind: FI[(fl.year == y).to_numpy() & (fl.kind == kind).to_numpy()]
rest = lambda y, kind: FI[(fl.year == y).to_numpy() & (fl.kind != kind).to_numpy()]
print("\nfeatures at the flip candle: median per class, hour-matched AUC EARLY vs the rest, and FALSE vs the rest")
print(f"{'feature':<9}| {'2024 EARLY':>10} {'LATE':>6} {'TURNED':>6} {'FALSE':>6} {'E/rest':>7} {'F/rest':>7}"
      f" | {'2025 EARLY':>10} {'LATE':>6} {'TURNED':>6} {'FALSE':>6} {'E/rest':>7} {'F/rest':>7}")
for f in FLIP_FEATS:
    cells = []
    for y in (2024, 2025):
        med = [np.nanmedian(F[f][ex(y, kd)]) for kd in KINDS]
        cells.append(f"{fmt(med[0], 10)} " + " ".join(fmt(v) for v in med[1:])
                     + f" {fmt(hour_auc(F[f], ex(y, 'EARLY'), rest(y, 'EARLY')), 7)}"
                     + f" {fmt(hour_auc(F[f], ex(y, 'FALSE'), rest(y, 'FALSE')), 7)}")
    print(f"{f:<9}| " + " | ".join(cells))

print("\nterciles (edges from the 2024 flips, applied unchanged to 2025): EARLY share, FALSE share, net per trade")
for f in ["r100", "r21", "slope6", "release", "er14", "d_er14", "ser14"]:
    edges = np.nanpercentile(F[f][FI[(fl.year == 2024).to_numpy()]], [100 / 3, 200 / 3])
    print(f"-- {f} (edges {edges[0]:.3f}, {edges[1]:.3f})")
    for t, name in enumerate(["low", "mid", "high"]):
        cells = []
        for y in (2024, 2025):
            fy = fl[(fl.year == y).to_numpy()]
            v = F[f][fy.i.to_numpy()]
            x = fy[(np.digitize(v, edges) == t) & ~np.isnan(v)]
            cells.append(f"n {len(x):>4}  EARLY {np.mean(x.kind == 'EARLY') * 100:>3.0f}%  FALSE {np.mean(x.kind == 'FALSE') * 100:>3.0f}%"
                         f"  net/trade {x.net.mean():>7.3f}")
        print(f"   {name:<5} 2024 {cells[0]}  |  2025 {cells[1]}")

print("\ncombinations for EARLY vs the rest: logistic fit on one year, AUC on the other")
res = cross_year(SETS + [("ser14", ["ser14"]), ("r100+release+d_er14+ser14", ["r100", "release", "d_er14", "ser14"]),
                         ("all + signed ER", FLIP_FEATS)],
                 {y: ex(y, "EARLY") for y in (2024, 2025)}, {y: rest(y, "EARLY") for y in (2024, 2025)})
print(f"   {'set':<28}{'fit24->test25':>14} {'fit25->test24':>14}")
for name, (a, b2) in res.items():
    print(f"   {name:<28}{fmt(a, 14)} {fmt(b2, 14)}")

print("\nhow late is the 7/3 flip into a big swing? first flip in the swing's direction after the turn")
lag, done = [], []
for sw in big_s.itertuples():
    start, end, d = int(sw.start), int(sw.end), int(sw.dir)
    after = [i for i in flips[np.searchsorted(flips, start):] if i < end][:8]
    hit = [i for i in after if st[i] == d]
    if hit:
        lag.append(hit[0] - start)
        done.append(d * (c[hit[0]] - c[start]) / sw.size)
lag, done = np.array(lag), np.array(done)
print(f"   {len(lag)} big swings: median lag {np.median(lag):.0f} candles (middle half {np.percentile(lag, 25):.0f}-"
      f"{np.percentile(lag, 75):.0f}); median share of the swing already done {np.median(done) * 100:.0f}%"
      f" (middle half {np.percentile(done, 25) * 100:.0f}-{np.percentile(done, 75) * 100:.0f}%)")
