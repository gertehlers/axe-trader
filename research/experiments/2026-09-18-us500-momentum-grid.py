"""Layer-1 grid test: does a short-horizon extreme predict CONTINUATION on US500?

The layer-1 test of the old rule (2026-09-18-us500-layer1.py) found that after RSI(7)<25 at the
lower Bollinger band, price kept FALLING — and the mirror (RSI>75 at the upper band) kept RISING.
Both point one way: on US500 5-minute bars the short-term move continues rather than reverting.

This tests that as a family rather than as the two variants that happened to be looked at, because
two hand-picked configurations are an anecdote. Spec 6.2.2 rules applied:

  rule 1  Development data only: 2024-01-01 -> 2026-07-31. The Aug/Sep 2026 holdout is NOT touched.
  rule 2  Plateau, not peak: a configuration counts as a candidate only if >=70% of its immediate
          grid neighbours are also positive.
  rule 3  Placebo grid: the SAME grid is run on 200 placebo datasets, and the best real
          configuration must beat the 95th percentile of the best-placebo distribution. This is
          what stops "best of 96 tries" from looking like a discovery.

Placebos preserve clustering: each placebo dataset is a whole-week shift of every configuration's
entry set, so weekday/hour and the bunching of entries are held fixed and only the alignment with
price is destroyed.

Trial count for the ledger: the full grid size, recorded in the JSON, carried into any G1
Bonferroni adjustment.

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-18-us500-momentum-grid.py
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

HORIZON = 12            # 60 minutes, the horizon where the layer-1 effect was clearest
SECOND_HORIZON = 24     # 120 minutes, reported as a consistency check
PLACEBO_SETS = 200
SHIFT_WEEKS = 26
MIN_FIRES = 200         # below this a configuration is `inconclusive` (layer 0), never `rejected`

# Grid axes, ordered so "immediate neighbour" means one step along one axis.
RSI_PERIODS = [7, 14]
EXTREMITY = [60, 65, 70, 75]     # how far past neutral RSI must be; mirrored for the short side
REQUIRE_BAND = [False, True]
TREND = [-1, 0, 1]               # close below EMA(200) / no gate / above EMA(200)
SIDES = ["LONG", "SHORT"]
GRID_KEYS = ("side", "rsi_period", "extremity", "require_band", "trend")


def grid_key(config):
    """Identity of a configuration: the grid axes only, never the measured results."""
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
    """Return in the direction of the trade: a SHORT profits when price falls."""
    raw = forward_returns(mid, entries, horizon)
    return raw if side == "LONG" else -raw


def neighbours(config, by_key):
    """Configurations one step away along exactly one axis."""
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


def main() -> None:
    bars = resample(load_cached_minutes(DB, EPIC, CACHE), TIMEFRAME)
    bars = bars[(bars.index >= DEV_START) & (bars.index <= DEV_END)]

    mid_close = ((bars["close_bid"] + bars["close_ask"]) / 2.0).to_numpy()
    spread = float((bars["close_ask"] - bars["close_bid"]).mean())
    band_middle, band_upper, band_lower = bollinger(mid_close, 20, 2.0)
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

    rng = np.random.default_rng(20260918)
    shifts = rng.choice(np.concatenate([np.arange(-SHIFT_WEEKS, 0), np.arange(1, SHIFT_WEEKS + 1)]),
                        size=PLACEBO_SETS, replace=True)

    grid = build_grid()
    days = (index[-1] - index[0]).days
    print(f"{EPIC} {TIMEFRAME} {index[0].date()} -> {index[-1].date()} ({days} days, {len(bars):,} bars)")
    print(f"{HOLDOUT_NOTE}. mean spread {spread:.3f} pts")
    print(f"grid: {len(grid)} configurations x {PLACEBO_SETS} placebo datasets, horizon {HORIZON * 5}m\n")

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

        real = signed_return(mid_close, entries, HORIZON, config["side"])
        real_second = signed_return(mid_close, entries, SECOND_HORIZON, config["side"])
        record["mean"] = float(real.mean())
        record["mean_second_horizon"] = float(real_second.mean())
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
            placebo_means[i] = signed_return(mid_close, shifted, HORIZON, config["side"]).mean()

        usable = ~np.isnan(placebo_means)
        record["placebo_mean"] = float(placebo_means[usable].mean())
        record["edge"] = record["mean"] - record["placebo_mean"]
        record["percentile"] = percentile_of(record["mean"], placebo_means[usable])
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
        row["plateau"] = bool(peers) and len(positive) / len(peers) >= 0.70

    tested = [r for r in rows if r.get("edge") is not None]
    best = max(tested, key=lambda r: r["edge"])
    finite = best_placebo_per_set[np.isfinite(best_placebo_per_set)]
    best_placebo_95 = float(np.percentile(finite, 95))
    beats_placebo_grid = best["mean"] > best_placebo_95

    print(f"{'side':>5} {'rsiP':>5} {'ext':>4} {'band':>5} {'trend':>6} {'fires':>7} "
          f"{'mean':>7} {'plac':>7} {'edge':>7} {'pct':>6} {'plat':>5} {'pow':>5}")
    print("-" * 86)
    for row in sorted(tested, key=lambda r: -r["edge"])[:14]:
        print(f"{row['side']:>5} {row['rsi_period']:>5} {row['extremity']:>4} "
              f"{str(row['require_band']):>5} {row['trend']:>6} {row['fires']:>7,} "
              f"{row['mean']:>7.3f} {row['placebo_mean']:>7.3f} {row['edge']:>7.3f} "
              f"{row['percentile']:>6.1f} {str(row['plateau']):>5} {str(row['powered']):>5}")

    positive = [r for r in tested if r["edge"] > 0]
    plateau = [r for r in positive if r["plateau"]]
    print(f"\ntested {len(tested)}/{len(grid)} configurations "
          f"({len(rows) - len(tested)} inconclusive: under {MIN_FIRES} fires)")
    print(f"positive edge: {len(positive)}  |  on a plateau: {len(plateau)}")
    print(f"best real mean {best['mean']:+.3f} pts vs best-placebo 95th percentile "
          f"{best_placebo_95:+.3f} -> {'BEATS' if beats_placebo_grid else 'DOES NOT BEAT'} the placebo grid")

    # Family-level test. The best-vs-best rule above is dominated by whichever configuration is
    # noisiest, because the maximum of many noisy means is large. This asks a different and less
    # fragile question: is the WHOLE family tilted relative to placebo? It does not replace rule 3.
    family = {}
    stack = np.vstack(family_placebo)
    reals = np.array(family_real)
    counts = np.array(family_n)
    for label, keep in (("all tested", counts > 0), ("fires >= 500", counts >= 500)):
        if keep.sum() == 0:
            continue
        per_set = np.nanmean(stack[keep], axis=0)
        per_set = per_set[~np.isnan(per_set)]
        observed = float(reals[keep].mean())
        family[label] = {"configs": int(keep.sum()), "real_family_mean": observed,
                         "placebo_family_mean": float(per_set.mean()),
                         "percentile": percentile_of(observed, per_set)}
        print(f"family mean ({label}, {keep.sum()} configs): real {observed:+.3f} vs "
              f"placebo {per_set.mean():+.3f} -> percentile {percentile_of(observed, per_set):.1f}")

    OUT.write_text(json.dumps({
        "epic": EPIC, "timeframe": TIMEFRAME,
        "window": [str(index[0]), str(index[-1])], "holdout": HOLDOUT_NOTE,
        "horizon_bars": HORIZON, "horizon_minutes": HORIZON * 5,
        "trial_count": len(grid), "placebo_sets": PLACEBO_SETS,
        "mean_spread": spread,
        "best_placebo_95th": best_placebo_95,
        "best_real_mean": best["mean"],
        "beats_placebo_grid": bool(beats_placebo_grid),
        "positive": len(positive), "on_plateau": len(plateau),
        "family_level": family,
        "configurations": rows,
    }, indent=2, default=str))
    print(f"\nwrote {OUT}")


if __name__ == "__main__":
    main()
