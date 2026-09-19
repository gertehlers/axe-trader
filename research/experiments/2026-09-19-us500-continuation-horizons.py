"""H-0003 layer-2 retest: does the continuation effect clear costs at a longer horizon?

H-0002 (2026-09-18-us500-momentum-grid.py) closed `uneconomic`: the family edge among
adequately-sized configurations was +0.565 pts/hour against a 0.561-pt spread. Spec 6.2.3 layer 2
permits one follow-up on a longer timeframe, because spread is paid once per trade however long the
trade is held — so if the continuation is a drift, the edge-to-cost ratio improves with horizon.

The signal is NOT re-tuned. Same 96-cell grid, same instrument, same development window, same
placebo method. The only thing that changes is the holding horizon, evaluated at 120m and 240m with
a placebo computed AT EACH HORIZON. H-0002's raw 120m column had no placebo, and US500 drifts up,
so a longer horizon inflates any long-side mean for free; only a matched placebo decides it.

Pass/fail criteria are pre-registered in research/ledger/H-0003-continuation-longer-horizon.md and
are restated in CRITERIA below so the script and the ledger cannot drift apart.

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-us500-continuation-horizons.py
"""

from __future__ import annotations

import itertools
import json
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.diagnose import forward_returns, percentile_of, trades_for_power
from engine.indicators import bollinger, ema, rsi

ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data" / "axe-trader.sqlite"
CACHE = ROOT / "research" / "engine" / ".cache"
OUT = Path(__file__).with_suffix(".json")

EPIC = "US500"
TIMEFRAME = "5min"
DEV_START = pd.Timestamp("2024-01-01T00:00:00Z")
DEV_END = pd.Timestamp("2026-07-31T23:59:00Z")
HOLDOUT_NOTE = "2026-08-01 onward is held out and untouched"

HORIZONS = {"120m": 24, "240m": 48}   # bars at 5min; the parent tested 60m (12 bars)
PLACEBO_SETS = 200
SHIFT_WEEKS = 26
MIN_FIRES = 200
BIG_FIRES = 500
SPREAD_MULTIPLE_REQUIRED = 2.0        # criterion 1: family edge >= 2 x spread
FAMILY_PERCENTILE_REQUIRED = 95.0     # criterion 2
PLATEAU_SHARE_REQUIRED = 0.70         # criterion 3

RSI_PERIODS = [7, 14]
EXTREMITY = [60, 65, 70, 75]
REQUIRE_BAND = [False, True]
TREND = [-1, 0, 1]
SIDES = ["LONG", "SHORT"]
GRID_KEYS = ("side", "rsi_period", "extremity", "require_band", "trend")


def grid_key(config):
    """Identity of a configuration: the grid axes only, never the measured results.

    H-0002's first run built this key from the whole result row, so no neighbour ever matched and
    every configuration was silently reported as not on a plateau. Keep this narrow.
    """
    return tuple((k, config[k]) for k in GRID_KEYS)


def build_grid():
    return [dict(side=s, rsi_period=p, extremity=e, require_band=b, trend=t)
            for s, p, e, b, t in itertools.product(SIDES, RSI_PERIODS, EXTREMITY, REQUIRE_BAND, TREND)]


def fires_for(config, cache, warm, mid_close, band_upper, band_lower, trend_ema):
    rsi_values = cache[config["rsi_period"]]
    if config["side"] == "LONG":
        condition = rsi_values > (50 + config["extremity"] / 2.0)
        if config["require_band"]:
            condition &= mid_close >= band_upper
    else:
        condition = rsi_values < (50 - config["extremity"] / 2.0)
        if config["require_band"]:
            condition &= mid_close <= band_lower
    if config["trend"] == 1:
        condition &= mid_close > trend_ema
    elif config["trend"] == -1:
        condition &= mid_close < trend_ema
    return warm & condition


def signed_return(mid, entries, horizon, side):
    raw = forward_returns(mid, entries, horizon)
    return raw if side == "LONG" else -raw


def neighbours(config, by_key):
    out = []
    for axis, values in (("rsi_period", RSI_PERIODS), ("extremity", EXTREMITY),
                         ("require_band", REQUIRE_BAND), ("trend", TREND)):
        position = values.index(config[axis])
        for step in (-1, 1):
            neighbour = position + step
            if 0 <= neighbour < len(values):
                candidate = {k: config[k] for k in GRID_KEYS}
                candidate[axis] = values[neighbour]
                key = grid_key(candidate)
                if key in by_key:
                    out.append(by_key[key])
    return out


def evaluate(horizon_label, horizon_bars, grid, mid_close, spread, rsi_cache, warm,
             band_upper, band_lower, trend_ema, times, sorted_times, order, shifts, week):
    """One horizon: every configuration against 200 shifted placebos at that same horizon."""
    rows = []
    best_placebo_per_set = np.full(PLACEBO_SETS, -np.inf)
    family_placebo, family_real, family_n = [], [], []

    for config in grid:
        entries = np.flatnonzero(fires_for(config, rsi_cache, warm, mid_close,
                                           band_upper, band_lower, trend_ema))
        record = dict(config)
        record["fires"] = int(len(entries))
        if len(entries) < MIN_FIRES:
            record.update(status="inconclusive", reason="too few fires", mean=None)
            rows.append(record)
            continue

        real = signed_return(mid_close, entries, horizon_bars, config["side"])
        record["mean"] = float(real.mean())
        record["sd"] = float(real.std(ddof=1))
        record["needed_for_spread_edge"] = trades_for_power(effect=spread, sd=record["sd"])
        record["powered"] = bool(len(real) >= record["needed_for_spread_edge"])

        placebo_means = np.full(PLACEBO_SETS, np.nan)
        for i, weeks in enumerate(shifts):
            wanted = times[entries] + weeks * week
            found = np.clip(np.searchsorted(sorted_times, wanted), 0, len(sorted_times) - 1)
            exact = sorted_times[found] == wanted
            shifted = order[found[exact]]
            if len(shifted) < 0.8 * len(entries):
                continue
            placebo_means[i] = signed_return(mid_close, shifted, horizon_bars, config["side"]).mean()

        usable = ~np.isnan(placebo_means)
        record["placebo_mean"] = float(placebo_means[usable].mean())
        record["edge"] = record["mean"] - record["placebo_mean"]
        record["percentile"] = percentile_of(record["mean"], placebo_means[usable])
        record["edge_in_spreads"] = record["edge"] / spread
        record["status"] = "candidate" if record["edge"] > 0 else "negative"
        rows.append(record)

        best_placebo_per_set[usable] = np.maximum(best_placebo_per_set[usable], placebo_means[usable])
        family_placebo.append(placebo_means)
        family_real.append(record["mean"])
        family_n.append(len(real))

    by_key = {grid_key(r): r for r in rows}
    for row in rows:
        if row.get("mean") is None:
            row["plateau"] = None
            continue
        peers = [n for n in neighbours(row, by_key) if n.get("edge") is not None]
        positive = [n for n in peers if n["edge"] > 0]
        row["neighbours"] = len(peers)
        row["neighbours_positive"] = len(positive)
        row["plateau"] = bool(peers) and len(positive) / len(peers) >= PLATEAU_SHARE_REQUIRED

    tested = [r for r in rows if r.get("edge") is not None]
    best = max(tested, key=lambda r: r["edge"])
    finite = best_placebo_per_set[np.isfinite(best_placebo_per_set)]
    best_placebo_95 = float(np.percentile(finite, 95))

    # Family level. The placebo family mean is recomputed per placebo SET (a whole-week shift
    # applied to every configuration at once), so the family distribution keeps the same
    # cross-configuration correlation the real family has.
    stack = np.vstack(family_placebo)
    reals = np.array(family_real)
    counts = np.array(family_n)
    family = {}
    for label, keep in (("all tested", counts > 0), (f"fires >= {BIG_FIRES}", counts >= BIG_FIRES)):
        if keep.sum() == 0:
            continue
        per_set = np.nanmean(stack[keep], axis=0)
        per_set = per_set[~np.isnan(per_set)]
        observed = float(reals[keep].mean())
        placebo_mean = float(per_set.mean())
        family[label] = {
            "configs": int(keep.sum()),
            "real_family_mean": observed,
            "placebo_family_mean": placebo_mean,
            "family_edge": observed - placebo_mean,
            "family_edge_in_spreads": (observed - placebo_mean) / spread,
            "percentile": percentile_of(observed, per_set),
        }

    positive = [r for r in tested if r["edge"] > 0]
    plateau = [r for r in positive if r["plateau"]]
    big = family.get(f"fires >= {BIG_FIRES}", {})

    criteria = {
        "1_economics": {
            "required": f"family edge (fires >= {BIG_FIRES}) >= {SPREAD_MULTIPLE_REQUIRED} x spread",
            "observed_in_spreads": big.get("family_edge_in_spreads"),
            "pass": bool(big.get("family_edge_in_spreads", -1) >= SPREAD_MULTIPLE_REQUIRED),
        },
        "2_significance": {
            "required": f"family percentile >= {FAMILY_PERCENTILE_REQUIRED}",
            "observed": big.get("percentile"),
            "pass": bool(big.get("percentile", -1) >= FAMILY_PERCENTILE_REQUIRED),
        },
        "3_plateau": {
            "required": f"share of positive configs on a plateau >= {PLATEAU_SHARE_REQUIRED}",
            "observed": (len(plateau) / len(positive)) if positive else 0.0,
            "pass": bool(positive and len(plateau) / len(positive) >= PLATEAU_SHARE_REQUIRED),
        },
        "4_best_vs_best": {
            "required": "best real mean > 95th percentile of best-placebo distribution",
            "observed_best_real": best["mean"],
            "observed_placebo_95th": best_placebo_95,
            "pass": bool(best["mean"] > best_placebo_95),
        },
    }
    return {
        "horizon": horizon_label,
        "horizon_bars": horizon_bars,
        "horizon_minutes": horizon_bars * 5,
        "tested": len(tested),
        "positive": len(positive),
        "on_plateau": len(plateau),
        "best_real_mean": best["mean"],
        "best_placebo_95th": best_placebo_95,
        "family_level": family,
        "criteria": criteria,
        "verdict": verdict_for(criteria),
        "configurations": rows,
    }


def verdict_for(criteria):
    """First failing criterion sets the status, in the order pre-registered in the ledger."""
    if not criteria["2_significance"]["pass"] or not criteria["4_best_vs_best"]["pass"]:
        return "rejected: no signal"
    if not criteria["1_economics"]["pass"]:
        return "uneconomic"
    if not criteria["3_plateau"]["pass"]:
        return "inconclusive"
    return "pass: promote to G1 candidate"


def main() -> None:
    bars = resample(load_cached_minutes(DB, EPIC, CACHE), TIMEFRAME)
    bars = bars[(bars.index >= DEV_START) & (bars.index <= DEV_END)]

    mid_close = ((bars["close_bid"] + bars["close_ask"]) / 2.0).to_numpy()
    spread = float((bars["close_ask"] - bars["close_bid"]).mean())
    _, band_upper, band_lower = bollinger(mid_close, 20, 2.0)
    trend_ema = ema(mid_close, 200)
    rsi_cache = {period: rsi(mid_close, period) for period in RSI_PERIODS}

    warm = np.zeros(len(bars), dtype=bool)
    warm[600:] = True
    warm &= ~np.isnan(band_lower) & ~np.isnan(trend_ema)
    for values in rsi_cache.values():
        warm &= ~np.isnan(values)

    index = bars.index
    times = index.to_numpy().astype("datetime64[m]")
    order = np.argsort(times)
    sorted_times = times[order]
    week = np.timedelta64(7 * 24 * 60, "m")

    rng = np.random.default_rng(20260919)
    shifts = rng.choice(np.concatenate([np.arange(-SHIFT_WEEKS, 0), np.arange(1, SHIFT_WEEKS + 1)]),
                        size=PLACEBO_SETS, replace=True)

    grid = build_grid()
    days = (index[-1] - index[0]).days
    print(f"{EPIC} {TIMEFRAME} {index[0].date()} -> {index[-1].date()} ({days} days, {len(bars):,} bars)")
    print(f"{HOLDOUT_NOTE}. mean spread {spread:.3f} pts")
    print(f"grid: {len(grid)} configurations x {len(HORIZONS)} horizons x {PLACEBO_SETS} placebos")
    print(f"parent H-0002 at 60m: family edge +0.565 vs spread 0.561 -> 1.01x, uneconomic\n")

    results = []
    for label, bars_ahead in HORIZONS.items():
        result = evaluate(label, bars_ahead, grid, mid_close, spread, rsi_cache, warm,
                          band_upper, band_lower, trend_ema, times, sorted_times, order, shifts, week)
        results.append(result)

        print(f"=== horizon {label} ({bars_ahead} bars) ===")
        print(f"{'side':>5} {'rsiP':>5} {'ext':>4} {'band':>5} {'trend':>6} {'fires':>7} "
              f"{'mean':>7} {'plac':>7} {'edge':>7} {'sprds':>6} {'pct':>6} {'plat':>5}")
        print("-" * 84)
        tested = [r for r in result["configurations"] if r.get("edge") is not None]
        for row in sorted(tested, key=lambda r: -r["edge"])[:10]:
            print(f"{row['side']:>5} {row['rsi_period']:>5} {row['extremity']:>4} "
                  f"{str(row['require_band']):>5} {row['trend']:>6} {row['fires']:>7,} "
                  f"{row['mean']:>7.3f} {row['placebo_mean']:>7.3f} {row['edge']:>7.3f} "
                  f"{row['edge_in_spreads']:>6.2f} {row['percentile']:>6.1f} {str(row['plateau']):>5}")
        for label_, stats in result["family_level"].items():
            print(f"family ({label_}, {stats['configs']} configs): real {stats['real_family_mean']:+.3f} "
                  f"vs placebo {stats['placebo_family_mean']:+.3f} -> edge {stats['family_edge']:+.3f} "
                  f"({stats['family_edge_in_spreads']:.2f} spreads), percentile {stats['percentile']:.1f}")
        print(f"positive {result['positive']}/{result['tested']}, on a plateau {result['on_plateau']}")
        for name, check in result["criteria"].items():
            print(f"  {'PASS' if check['pass'] else 'FAIL'}  {name}: {check['required']}")
        print(f"  --> {result['verdict']}\n")

    OUT.write_text(json.dumps({
        "hypothesis": "H-0003",
        "parent": "H-0002",
        "epic": EPIC, "timeframe": TIMEFRAME,
        "window": [str(index[0]), str(index[-1])], "holdout": HOLDOUT_NOTE,
        "trial_count": 97 + len(grid) * len(HORIZONS),
        "placebo_sets": PLACEBO_SETS,
        "mean_spread": spread,
        "criteria_thresholds": {
            "spread_multiple_required": SPREAD_MULTIPLE_REQUIRED,
            "family_percentile_required": FAMILY_PERCENTILE_REQUIRED,
            "plateau_share_required": PLATEAU_SHARE_REQUIRED,
        },
        "horizons": results,
    }, indent=2, default=str))
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
