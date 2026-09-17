"""Support/resistance bounce-vs-random test. Definitions: see README.md (pre-registered)."""
import argparse
import sqlite3
import sys
from collections import defaultdict
from datetime import date, timedelta

import numpy as np

ATR_PERIOD = 14
PIVOT_SIDE_BARS = 6
ARM_A = 0.5
TIMEOUT_MIN = 120
R_VALUES = (0.5, 1.0, 2.0)
PRIMARY_R = 1.0
PLACEBO_A_OFFSETS = (-4, -3, -2, 2, 3, 4)
ROUND_STEP = 50
ROUND_PLACEBO_POINTS = (10, 20, 30, 40)
BOOTSTRAP = 2000
SEED = 20260917
SPLIT_DAY = (date(2025, 12, 31) - date(1970, 1, 1)).days
MIN_EFFECT = 0.03
FAMILIES = ("prior_day", "pivot", "round50")


def load(db_path):
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    rows = con.execute(
        "SELECT CAST(strftime('%s', snapshot_time_utc) AS INTEGER) / 60,"
        " (high_bid + high_ask) / 2, (low_bid + low_ask) / 2, (close_bid + close_ask) / 2,"
        " (open_bid + open_ask) / 2"
        " FROM historical_price WHERE epic = 'US500' AND resolution = 'MINUTE'"
        " ORDER BY snapshot_time_utc").fetchall()
    arr = np.array(rows, dtype=np.float64)
    return arr[:, 0].astype(np.int64), arr[:, 1], arr[:, 2], arr[:, 3], arr[:, 4]


def five_minute_bars(t, h, l, c, o):
    bucket = (t // 5) * 5
    starts = np.flatnonzero(np.r_[True, bucket[1:] != bucket[:-1]])
    ends = np.r_[starts[1:], len(t)]
    bh = np.maximum.reduceat(h, starts)
    bl = np.minimum.reduceat(l, starts)
    bc = c[ends - 1]
    return bucket[starts], bh, bl, bc


def wilder_atr(h, l, c):
    prev = np.r_[c[0], c[:-1]]
    tr = np.maximum(h - l, np.maximum(np.abs(h - prev), np.abs(l - prev)))
    atr = np.full(len(tr), np.nan)
    atr[ATR_PERIOD - 1] = tr[:ATR_PERIOD].mean()
    for i in range(ATR_PERIOD, len(tr)):
        atr[i] = (atr[i - 1] * (ATR_PERIOD - 1) + tr[i]) / ATR_PERIOD
    return atr


def run_level(level, s, e, t, h, l, c, a, r_mult):
    """Yield (side, touch_index, outcome) for one level over 1m bars [s, e)."""
    hs, ls, cs, as_, ts = h[s:e], l[s:e], c[s:e], a[s:e], t[s:e]
    n = e - s
    armed_sup = cs >= level + ARM_A * as_
    armed_res = cs <= level - ARM_A * as_
    touch_sup = ls <= level
    touch_res = hs >= level
    pos = 0
    while pos < n:
        arm_idx = np.flatnonzero((armed_sup[pos:] | armed_res[pos:]))
        if arm_idx.size == 0:
            return
        i = pos + arm_idx[0]
        support = bool(armed_sup[i])
        touches = np.flatnonzero((touch_sup if support else touch_res)[i + 1:])
        if touches.size == 0:
            return
        j = i + 1 + touches[0]
        dist = r_mult * as_[j]
        up, dn = level + dist, level - dist
        side = "support" if support else "resistance"
        if (support and ls[j] <= dn) or (not support and hs[j] >= up):
            yield side, s + j, "break"
            pos = j + 1
            continue
        stop = j + 1 + np.searchsorted(ts[j + 1:], ts[j] + TIMEOUT_MIN, side="right")
        win_h, win_l = hs[j + 1:stop], ls[j + 1:stop]
        good = (win_h >= up) if support else (win_l <= dn)
        bad = (win_l <= dn) if support else (win_h >= up)
        hit = np.flatnonzero(good | bad)
        if hit.size == 0:
            yield side, s + j, "unresolved"
            pos = stop
            continue
        k = hit[0]
        outcome = "ambiguous" if good[k] and bad[k] else ("bounce" if good[k] else "break")
        yield side, s + j, outcome
        pos = j + 1 + k + 1


def build_levels(t, h, l, c, a1, day, day_ids, day_start, day_end, b_t, b_h, b_l, b_atr):
    """Return list of (family, sub, is_real, level, start_idx, end_idx)."""
    levels = []
    day_pos = {d: k for k, d in enumerate(day_ids)}

    for k in range(1, len(day_ids)):
        ps, pe = day_start[k - 1], day_end[k - 1]
        s, e = day_start[k], day_end[k]
        a0 = a1[s]
        if not np.isfinite(a0):
            continue
        for sub, lv in (("high", h[ps:pe].max()), ("low", l[ps:pe].min()), ("close", c[pe - 1])):
            levels.append(("prior_day", sub, True, lv, s, e))
            for off in PLACEBO_A_OFFSETS:
                levels.append(("prior_day", sub, False, lv + off * a0, s, e))

    n5 = len(b_t)
    w = PIVOT_SIDE_BARS
    for j in range(w, n5 - w):
        left_h, right_h = b_h[j - w:j].max(), b_h[j + 1:j + w + 1].max()
        left_l, right_l = b_l[j - w:j].min(), b_l[j + 1:j + w + 1].min()
        kinds = []
        if b_h[j] > left_h and b_h[j] > right_h:
            kinds.append(("pivot_high", b_h[j]))
        if b_l[j] < left_l and b_l[j] < right_l:
            kinds.append(("pivot_low", b_l[j]))
        if not kinds:
            continue
        a0 = b_atr[j + w]
        if not np.isfinite(a0):
            continue
        known = b_t[j + w] + 5
        s = int(np.searchsorted(t, known, side="left"))
        if s >= len(t):
            continue
        kd = day_pos[day[s]]
        if kd + 1 >= len(day_ids):
            continue
        e = day_end[kd + 1]
        for sub, lv in kinds:
            levels.append(("pivot", sub, True, lv, s, e))
            for off in PLACEBO_A_OFFSETS:
                levels.append(("pivot", sub, False, lv + off * a0, s, e))

    for k in range(len(day_ids)):
        s, e = day_start[k], day_end[k]
        lo = np.floor(l[s:e].min() / ROUND_STEP) * ROUND_STEP - ROUND_STEP
        hi = np.ceil(h[s:e].max() / ROUND_STEP) * ROUND_STEP + ROUND_STEP
        for base in np.arange(lo, hi + 1, ROUND_STEP):
            sub = "x00" if base % 100 == 0 else "x50"
            levels.append(("round50", sub, True, float(base), s, e))
            for p in ROUND_PLACEBO_POINTS:
                levels.append(("round50", sub, False, float(base + p), s, e))
    return levels


def bootstrap_effect(day_counts):
    """day_counts: array (D, 4) = real_bounce, real_n, placebo_bounce, placebo_n."""
    rng = np.random.default_rng(SEED)
    d = len(day_counts)
    idx = rng.integers(0, d, size=(BOOTSTRAP, d))
    sums = day_counts[idx].sum(axis=1)
    with np.errstate(divide="ignore", invalid="ignore"):
        eff = sums[:, 0] / sums[:, 1] - sums[:, 2] / sums[:, 3]
    eff = eff[np.isfinite(eff)]
    return np.percentile(eff, 2.5), np.percentile(eff, 97.5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    t, h, l, c, o = load(args.db)
    print(f"loaded {len(t):,} 1m bars", file=sys.stderr)
    b_t, b_h, b_l, b_c = five_minute_bars(t, h, l, c, o)
    b_atr = wilder_atr(b_h, b_l, b_c)
    completed = np.searchsorted(b_t + 5, t, side="right") - 1
    a1 = np.where(completed >= 0, b_atr[np.clip(completed, 0, None)], np.nan)

    day = (t + 120) // 1440
    day_ids, day_start = np.unique(day, return_index=True)
    day_end = np.r_[day_start[1:], len(t)]

    levels = build_levels(t, h, l, c, a1, day, day_ids, day_start, day_end, b_t, b_h, b_l, b_atr)
    print(f"{len(levels):,} level windows", file=sys.stderr)

    # counts[(period, family, sub, side, R)][day] = [real_b, real_n, plc_b, plc_n]
    counts = defaultdict(lambda: defaultdict(lambda: np.zeros(4)))
    excluded = defaultdict(int)
    for n_done, (family, sub, real, lv, s, e) in enumerate(levels):
        if n_done % 50000 == 0:
            print(f"  {n_done:,}/{len(levels):,}", file=sys.stderr)
        for r in R_VALUES:
            for side, idx, outcome in run_level(lv, s, e, t, h, l, c, a1, r):
                if outcome in ("ambiguous", "unresolved"):
                    excluded[(family, real, r, outcome)] += 1
                    continue
                d = int(day[idx])
                period = "2024-2025" if d <= SPLIT_DAY else "2026"
                col = 0 if real else 2
                b = 1.0 if outcome == "bounce" else 0.0
                for key in ((period, family, "all", "both", r), (period, family, sub, "both", r),
                            (period, family, "all", side, r)):
                    arr = counts[key][d]
                    arr[col] += b
                    arr[col + 1] += 1

    lines = ["# Results — support/resistance bounce-vs-random", "",
             "Generated by `bounce_test.py`; definitions in `README.md`.", "",
             "| period | family | sub | side | R | real n | real bounce | placebo n | placebo bounce "
             "| effect | 95% CI | pass |", "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    verdict = defaultdict(list)
    for key in sorted(counts):
        period, family, sub, side, r = key
        days = counts[key]
        mat = np.array([days[d] for d in sorted(days)])
        rb, rn, pb, pn = mat.sum(axis=0)
        if rn == 0 or pn == 0:
            continue
        eff = rb / rn - pb / pn
        lo, hi = bootstrap_effect(mat)
        primary = sub == "all" and side == "both" and r == PRIMARY_R
        passed = lo > 0 and eff >= MIN_EFFECT
        if primary:
            verdict[family].append(passed)
        mark = ("PASS" if passed else "fail") if primary else ""
        lines.append(f"| {period} | {family} | {sub} | {side} | {r} | {int(rn):,} | {rb / rn:.1%} "
                     f"| {int(pn):,} | {pb / pn:.1%} | {eff * 100:+.1f} pp | "
                     f"[{lo * 100:+.1f}, {hi * 100:+.1f}] | {mark} |")

    lines += ["", "## Verdict (R = 1.0 A, both sides, both periods)", ""]
    for family in FAMILIES:
        v = verdict.get(family, [])
        ok = len(v) == 2 and all(v)
        lines.append(f"- **{family}**: {'PASS' if ok else 'FAIL'}")
    lines += ["", "## Excluded events (ambiguous / unresolved)", "",
              "| family | real | R | kind | count |", "|---|---|---|---|---|"]
    for (family, real, r, kind), n in sorted(excluded.items()):
        lines.append(f"| {family} | {real} | {r} | {kind} | {n:,} |")

    with open(args.out, "w") as fh:
        fh.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
