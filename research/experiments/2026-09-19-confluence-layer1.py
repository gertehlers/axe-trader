"""H-0005: does the 4-pillar confluence entry predict anything, before any exit exists?

Criteria are pre-registered in research/ledger/H-0005-confluence-entry-signal.md. Layer 1 is
deliberately cost-free and exit-free: a signal with no edge before costs has none after them.

Run at 15m, not 4h. The first run was at 4h, chosen from the cost ranking, and every cell came
back `inconclusive`: the confluence fires on ~0.6% of bars, and 4h has only ~4,100 bars in the
window, so it produced 11-25 entries per instrument. The rule needs a bar count 5m/15m provides
and 4h does not. Both runs are recorded in the ledger; the entry itself is unchanged.

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-confluence-layer1.py
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.diagnose import forward_returns, percentile_of
from engine.pillars import PillarConfig, compute_pillars, fire_rates

ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data" / "axe-trader.sqlite"
CACHE = ROOT / "research" / "engine" / ".cache"
OUT = Path(__file__).with_suffix(".json")

EPICS = ["US500", "OIL_CRUDE", "OIL_BRENT"]
TIMEFRAME = "15min"
DEV_START = pd.Timestamp("2024-01-01T00:00:00Z")
DEV_END = pd.Timestamp("2026-07-31T23:59:00Z")
HORIZONS = [1, 4, 8, 16, 32]   # 15m, 1h, 2h, 4h, 8h
PLACEBO_SETS = 200
SHIFT_WEEKS = 26
MIN_ENTRIES = 200
WARMUP = 600


def main() -> None:
    rng = np.random.default_rng(20260919)
    shifts = rng.choice(np.concatenate([np.arange(-SHIFT_WEEKS, 0), np.arange(1, SHIFT_WEEKS + 1)]),
                        size=PLACEBO_SETS, replace=True)
    week = np.timedelta64(7 * 24 * 60, "m")
    results = {}

    for epic in EPICS:
        bars = resample(load_cached_minutes(DB, epic, CACHE), TIMEFRAME)
        bars = bars[(bars.index >= DEV_START) & (bars.index <= DEV_END)]
        votes = compute_pillars(bars, PillarConfig())
        mid_close = ((bars["close_bid"] + bars["close_ask"]) / 2.0).to_numpy()

        warm = np.zeros(len(bars), dtype=bool)
        warm[WARMUP:] = True
        times = bars.index.to_numpy().astype("datetime64[m]")
        order = np.argsort(times)
        sorted_times = times[order]

        entry = {"LONG": warm & (votes.bullish_score >= 3) & votes.long_gate,
                 "SHORT": warm & (votes.bearish_score >= 3) & votes.short_gate}

        results[epic] = {"bars": int(len(bars)), "fire_rates": fire_rates(votes, WARMUP), "sides": {}}
        print(f"\n=== {epic} {TIMEFRAME} {bars.index[0].date()} -> {bars.index[-1].date()} "
              f"({len(bars):,} bars) ===")
        for name, rate in results[epic]["fire_rates"]["bullish"].items():
            print(f"  pillar {name:<10} bullish fires {rate:6.1%}  "
                  f"bearish {results[epic]['fire_rates']['bearish'][name]:6.1%}")
        print(f"  bullish score histogram {results[epic]['fire_rates']['bullish_score_histogram']}")
        print(f"  bearish score histogram {results[epic]['fire_rates']['bearish_score_histogram']}")

        for side, mask in entry.items():
            entries = np.flatnonzero(mask)
            record = {"entries": int(len(entries)), "horizons": {}}
            if len(entries) < MIN_ENTRIES:
                record["status"] = "inconclusive"
                record["reason"] = f"under {MIN_ENTRIES} entries"
                results[epic]["sides"][side] = record
                print(f"  {side}: {len(entries)} entries -> inconclusive")
                continue

            sign = 1.0 if side == "LONG" else -1.0
            for horizon in HORIZONS:
                real = sign * forward_returns(mid_close, entries, horizon)
                placebo = np.full(PLACEBO_SETS, np.nan)
                for i, weeks in enumerate(shifts):
                    wanted = times[entries] + weeks * week
                    found = np.clip(np.searchsorted(sorted_times, wanted), 0, len(sorted_times) - 1)
                    exact = sorted_times[found] == wanted
                    shifted = order[found[exact]]
                    if len(shifted) < 0.8 * len(entries):
                        continue
                    placebo[i] = (sign * forward_returns(mid_close, shifted, horizon)).mean()
                usable = ~np.isnan(placebo)
                record["horizons"][f"{horizon}bar"] = {
                    "mean": float(real.mean()),
                    "placebo_mean": float(placebo[usable].mean()),
                    "edge": float(real.mean() - placebo[usable].mean()),
                    "percentile": percentile_of(float(real.mean()), placebo[usable]),
                    "placebo_sets_used": int(usable.sum()),
                }
            means = [h["mean"] for h in record["horizons"].values()]
            best = max(record["horizons"].values(), key=lambda h: h["percentile"])
            record["positive_horizons"] = int(sum(m > 0 for m in means))
            record["best_percentile"] = best["percentile"]
            record["signal"] = bool(record["positive_horizons"] >= 3 and best["percentile"] >= 95.0)
            results[epic]["sides"][side] = record

            print(f"  {side}: {len(entries)} entries, "
                  f"{record['positive_horizons']}/5 horizons positive, "
                  f"best percentile {best['percentile']:.1f} -> "
                  f"{'SIGNAL' if record['signal'] else 'no signal'}")
            for label, h in record["horizons"].items():
                print(f"      {label:>6}  mean {h['mean']:+8.4f}  placebo {h['placebo_mean']:+8.4f}"
                      f"  edge {h['edge']:+8.4f}  pct {h['percentile']:5.1f}")

    cells = [s for e in results.values() for s in e["sides"].values()]
    tested = [s for s in cells if s.get("status") != "inconclusive"]
    # Layer 0 precedes layer 1: a cell with too few entries was never tested, so the family
    # cannot be called `rejected`. This is the same distinction H-0004 had to correct.
    if not tested:
        verdict = "inconclusive"
    elif any(s.get("signal") for s in tested):
        verdict = "signal present"
    else:
        verdict = "rejected: no signal"
    print(f"\n==> {verdict}")

    OUT.write_text(json.dumps({
        "hypothesis": "H-0005", "timeframe": TIMEFRAME,
        "window": [str(DEV_START.date()), str(DEV_END.date())],
        "holdout": "2026-08-01 onward is held out and untouched",
        "horizons_bars": HORIZONS, "placebo_sets": PLACEBO_SETS,
        "trial_count": len(EPICS) * 2 * len(HORIZONS),
        "instruments": results, "verdict": verdict,
    }, indent=2, default=str))
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
