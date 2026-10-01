"""Build the data for the clean Supertrend page (`supertrend.html`).

Only candles and Supertrend: no pillars, no strategy, no `application.yaml` settings. The owner
asked for a clean look at the indicator as Capital.com shows it, so prices are Bid, like the
Capital.com chart.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../review/build_supertrend.py \
        --epic OIL_BRENT --timeframe 5min --length 7 --factor 3 --days 14

writes `research/review/supertrend/OIL_BRENT-5min-7-3.js` (gitignored, reproducible) and rewrites
`supertrend/index.js` from every dataset file present.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.indicators import atr, supertrend

REVIEW = Path(__file__).resolve().parent
OUT = REVIEW / "supertrend"
ENGINE = REVIEW.parent / "engine"
WARMUP_BARS = 500  # Wilder ATR is seeded on the first bar; this washes the seed out before the shown window


def _r(value: float, digits: int) -> float | None:
    return None if math.isnan(value) else round(float(value), digits)


def build(minutes: pd.DataFrame, timeframe: str, length: int, factor: float, days: int) -> dict:
    bars = resample(minutes, timeframe)
    end = bars.index[-1]
    shown_from = end - pd.Timedelta(days=days)
    first_shown = int(bars.index.searchsorted(shown_from))
    bars = bars.iloc[max(0, first_shown - WARMUP_BARS):]
    line, direction = supertrend(bars.high_bid, bars.low_bid, bars.close_bid, length, factor)
    width = atr(bars.high_bid.to_numpy(), bars.low_bid.to_numpy(), bars.close_bid.to_numpy(), length)
    keep = bars.index >= shown_from
    digits = 3 if bars.close_bid.iloc[-1] < 1000 else 1
    rows = []
    for (ts, bar), st, d, a in zip(bars[keep].iterrows(), line[keep], direction[keep], width[keep]):
        rows.append([int(ts.timestamp()), _r(bar.open_bid, digits), _r(bar.high_bid, digits),
                     _r(bar.low_bid, digits), _r(bar.close_bid, digits), _r(st, digits), int(d),
                     _r(a, digits + 1), bool(bar.complete)])
    return {"columns": ["t", "o", "h", "l", "c", "st", "dir", "atr", "complete"], "bars": rows,
            "digits": digits}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--epic", required=True)
    parser.add_argument("--timeframe", required=True, help="pandas offset, e.g. 5min or 1h")
    parser.add_argument("--length", type=int, required=True)
    parser.add_argument("--factor", type=float, required=True)
    parser.add_argument("--days", type=int, default=14, help="calendar days shown, ending at the newest bar")
    parser.add_argument("--db", type=Path, default=REVIEW.parents[1] / "data" / "axe-trader.sqlite")
    args = parser.parse_args()

    minutes = load_cached_minutes(args.db, args.epic, ENGINE / ".cache")
    data = build(minutes, args.timeframe, args.length, args.factor, args.days)
    factor = f"{args.factor:g}"
    key = f"{args.epic}-{args.timeframe}-{args.length}-{factor}"
    data.update({"key": key, "epic": args.epic, "timeframe": args.timeframe, "length": args.length,
                 "factor": args.factor, "price": "bid"})
    OUT.mkdir(exist_ok=True)
    (OUT / f"{key}.js").write_text(
        f"(window.ST = window.ST || {{}})[{json.dumps(key)}] = {json.dumps(data, separators=(',', ':'))};\n")
    keys = sorted(p.stem for p in OUT.glob("*.js") if p.stem != "index")
    (OUT / "index.js").write_text(f"window.ST_INDEX = {json.dumps(keys)};\n")
    bars = data["bars"]
    flips = sum(1 for a, b in zip(bars, bars[1:]) if a[6] != b[6])
    print(f"{key}: {len(bars)} bars, {flips} flips, "
          f"{pd.Timestamp(bars[0][0], unit='s', tz='UTC')} -> {pd.Timestamp(bars[-1][0], unit='s', tz='UTC')}")


if __name__ == "__main__":
    main()
