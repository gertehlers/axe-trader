"""Build the trade-review page's data payload from completed runs.

Spec §7.3. Reads the run directories `engine.run` wrote and emits the entry-aligned payload the
page loads as a same-origin script (a `<script src>` rather than a fetch, because the artifact CSP
governs fetches and a script tag is unambiguous).

    cd research/engine && PYTHONPATH=. .venv/bin/python ../review/export_trade_review.py \
        --runs ../runs/2026-09-19-oil-15m --epic OIL_CRUDE --timeframe 15min

The generated `trade-review-data.js` is gitignored: it is ~1.4 MB and fully reproducible from the
runs, which are themselves reproducible from the CLI. The page and this script are the source.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.pillars import PillarConfig, compute_pillars
from engine.review import EDGE_HORIZON_BARS, align_entries

ARMS = ["symmetric", "trailing", "time", "reversal"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", type=Path, required=True, help="directory holding one subdir per arm")
    parser.add_argument("--db", type=Path, default=Path("../../data/axe-trader.sqlite"))
    parser.add_argument("--epic", default="OIL_CRUDE")
    parser.add_argument("--timeframe", default="15min")
    parser.add_argument("--out", type=Path, default=Path("../review/trade-review-data.js"))
    parser.add_argument("--arms", nargs="*", default=ARMS)
    args = parser.parse_args()

    engine_dir = Path(__file__).resolve().parents[1] / "engine"   # research/engine, not the repo root
    minutes = load_cached_minutes(args.db, args.epic, engine_dir / ".cache")
    bars = resample(minutes, args.timeframe)
    votes = compute_pillars(bars, PillarConfig())

    per_arm = {a: pd.read_parquet(args.runs / a / "trades.parquet") for a in args.arms}
    summaries = {a: json.loads((args.runs / a / "summary.json").read_text()) for a in args.arms}
    entries = align_entries(per_arm, bars, votes, args.epic)

    payload = {
        "epic": args.epic,
        "timeframe": args.timeframe,
        "bars": len(bars),
        "window": [bars.index[0].isoformat(), bars.index[-1].isoformat()],
        "git_commit": summaries[args.arms[0]]["git_commit"][:7],
        "config": summaries[args.arms[0]]["config"],
        "horizon_bars": EDGE_HORIZON_BARS,
        "arms": list(args.arms),
        "summaries": summaries,
        "entries": entries,
    }
    args.out.write_text("window.REVIEW_DATA=" + json.dumps(payload, separators=(",", ":")) + ";")
    print(f"{len(entries)} entries across {len(args.arms)} arms -> {args.out} "
          f"({args.out.stat().st_size / 1e6:.2f} MB)")


if __name__ == "__main__":
    main()
