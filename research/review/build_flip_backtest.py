"""Build the flip-to-flip backtest data for the Supertrend page, practice period only.

Always in the market on the side the Supertrend points to (`engine.flips`), entering at the open
after each flip candle, crossing the real spread and paying overnight funding. Data from
`RESERVED_FROM` (2026-02-01) onward is never loaded.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../review/build_flip_backtest.py \
        --epic OIL_BRENT --timeframe 5min --length 7 --factor 3

writes (gitignored) `research/review/supertrend/bt/<key>/summary.js` with the totals, the month table
and every trade, plus one `YYYY-MM.js` of candles and Supertrend per month, loaded on demand.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.dayrun import RESERVED_FROM
from engine.flips import flip_trades, summarise
from engine.indicators import atr, supertrend
from engine.instruments import load_instruments

REVIEW = Path(__file__).resolve().parent
ENGINE = REVIEW.parent / "engine"
START = pd.Timestamp("2024-01-11", tz="UTC")  # first practice session, as in the day-review plan


def _r(value, digits):
    value = float(value)
    return None if math.isnan(value) else round(value, digits)


def _js(path: Path, var: str, data: dict) -> None:
    path.write_text(f"(window.BT = window.BT || {{}})[{json.dumps(var)}] = "
                    f"{json.dumps(data, separators=(',', ':'))};\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--epic", required=True)
    parser.add_argument("--timeframe", required=True)
    parser.add_argument("--length", type=int, required=True)
    parser.add_argument("--factor", type=float, required=True)
    parser.add_argument("--exit-factor", type=float, default=None,
                        help="exit on a close through a tighter Supertrend(length, this); flat until the next flip")
    parser.add_argument("--db", type=Path, default=REVIEW.parents[1] / "data" / "axe-trader.sqlite")
    args = parser.parse_args()

    spec = load_instruments(ENGINE / "instruments.yaml")[args.epic]
    minutes = load_cached_minutes(args.db, args.epic, ENGINE / ".cache")
    minutes = minutes[minutes.index < RESERVED_FROM]
    bars = resample(minutes, args.timeframe)
    line, direction = supertrend(bars.high_bid, bars.low_bid, bars.close_bid, args.length, args.factor)
    width = atr(bars.high_bid.to_numpy(), bars.low_bid.to_numpy(), bars.close_bid.to_numpy(), args.length)
    exit_line = exit_dir = None
    if args.exit_factor is not None:
        exit_line, exit_dir = supertrend(bars.high_bid, bars.low_bid, bars.close_bid, args.length, args.exit_factor)
    trades = flip_trades(bars, direction, spec, start=START, exit_direction=exit_dir)
    summary = summarise(trades)

    digits = 3 if bars.close_bid.iloc[-1] < 1000 else 1
    key = f"{args.epic}-{args.timeframe}-{args.length}-{args.factor:g}"
    if args.exit_factor is not None:
        key += f"-exit{args.exit_factor:g}"
    out = REVIEW / "supertrend" / "bt" / key
    out.mkdir(parents=True, exist_ok=True)
    for stale in out.glob("*.js"):
        stale.unlink()

    shown = bars.index >= START.normalize().replace(day=1)
    frame = bars[shown].assign(st=line[shown], dir=direction[shown], atr=width[shown])
    if exit_line is not None:
        frame = frame.assign(st2=exit_line[shown], dir2=exit_dir[shown])
    months = []
    for month, chunk in frame.groupby(frame.index.strftime("%Y-%m")):
        rows = [[int(ts.timestamp()), _r(b.open_bid, digits), _r(b.high_bid, digits), _r(b.low_bid, digits),
                 _r(b.close_bid, digits), _r(b.st, digits), int(b.dir), _r(b.atr, digits + 1)]
                + ([_r(b.st2, digits), int(b.dir2)] if exit_line is not None else [])
                for ts, b in chunk.iterrows()]
        _js(out / f"{month}.js", f"{key}/{month}", {"bars": rows})
        months.append(month)

    ts = lambda s: [int(t.timestamp()) for t in s]
    rows = list(zip(
        [1 if s == "LONG" else -1 for s in trades.side], ts(trades.signal_time), ts(trades.entry_time),
        [_r(v, digits) for v in trades.entry_price], ts(trades.exit_time), [_r(v, digits) for v in trades.exit_price],
        [_r(v, digits + 1) for v in trades.net_pts], [_r(v, digits) for v in trades.mfe_pts],
        [_r(v, digits) for v in trades.mae_pts], [_r(v, digits + 1) for v in trades.financing_pts],
        [_r(v, digits + 1) for v in trades.spread_pts], trades.bars.astype(int).tolist()))
    by_month = trades.assign(month=trades.exit_time.dt.strftime("%Y-%m")).groupby("month").agg(
        trades=("net_pts", "size"), wins=("net_pts", lambda s: int((s > 0).sum())), net=("net_pts", "sum"))
    summary.update({
        "avg_mfe_pts": float(trades.mfe_pts.mean()),
        "first_signal": str(trades.signal_time.iloc[0]), "last_exit": str(trades.exit_time.iloc[-1]),
    })
    _js(out / "summary.js", key, {
        "key": key, "epic": args.epic, "timeframe": args.timeframe, "length": args.length,
        "factor": args.factor, "exit_factor": args.exit_factor, "digits": digits, "period": ["2024-01-11", "2026-01-31"],
        "summary": {k: (None if isinstance(v, float) and math.isinf(v) else v) for k, v in summary.items()},
        "months": months,
        "month_table": [[m, int(r.trades), int(r.wins), round(float(r.net), digits + 1)] for m, r in by_month.iterrows()],
        "columns": ["side", "signal", "entry_t", "entry", "exit_t", "exit", "net", "mfe", "mae", "funding", "spread", "bars"],
        "trades": [list(r) for r in rows],
    })
    keys = sorted(p.name for p in out.parent.iterdir() if p.is_dir())
    (out.parent / "index.js").write_text(f"window.BT_INDEX = {json.dumps(keys)};\n")
    print(f"{key}: {summary['trades']} trades, net {summary['net_pts']:.2f} pts, win {summary['win_rate']:.1%}, "
          f"months {months[0]}..{months[-1]} ({len(months)}), month-table sum {by_month.net.sum():.2f}")


if __name__ == "__main__":
    main()
