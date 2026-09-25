"""Run one strategy version on one review session and build the day-review page's data.

For each timeframe x exit arm in the version: run `engine.dayrun.run_day`, write the run record
append-only under `research/review/runs/<run_id>/` (committed), then emit `day-review-data.js`
(gitignored, reproducible from the records) for `day-review.html`.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../review/build_day.py \
        --day 2024-01-11 --strategy v001

The page data holds only engine output: bars, the pillar votes and the raw readings they were
decided from, and each arm's trades and per-bar decisions from the simulator's trace. The page
renders these; it computes no explanation of its own.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import subprocess
from pathlib import Path

import numpy as np
import pandas as pd

from engine.cache import load_cached_minutes
from engine.dayrun import load_version, run_day, write_run
from engine.explain import GATE_RULES, PILLAR_READINGS, RULES
from engine.instruments import load_instruments

REVIEW = Path(__file__).resolve().parent
ENGINE = REVIEW.parent / "engine"
READINGS = ["close", "rsi", "bb_upper", "bb_lower", "ema_fast", "ema_trend", "atr", "support",
            "resistance", "proximity", "volume", "volume_baseline"]
PATTERNS = ["bullish_engulfing", "bullish_harami", "hammer", "bearish_engulfing",
            "bearish_harami", "shooting_star"]


def engine_state() -> tuple[str, bool]:
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True,
                            check=True, cwd=ENGINE).stdout.strip()
    dirty = bool(subprocess.run(["git", "status", "--porcelain", "--", "."], capture_output=True,
                                text=True, check=True, cwd=ENGINE).stdout.strip())
    return commit, dirty


def _num(value, digits=2):
    value = float(value)
    return None if math.isnan(value) else round(value, digits)


def excursions(minutes: pd.DataFrame, trade: dict) -> dict:
    """Best and worst mid price reached while the trade was open, in points from the fill.

    Hindsight: this is what happened during the hold, not what was known at any decision.
    """
    window = minutes[(minutes.index >= pd.Timestamp(trade["entry_time"]))
                     & (minutes.index <= pd.Timestamp(trade["exit_time"]))]
    if window.empty:
        return {"mfe_pts": None, "mae_pts": None, "giveback_pts": None}
    high = ((window["high_bid"] + window["high_ask"]) / 2.0).max()
    low = ((window["low_bid"] + window["low_ask"]) / 2.0).min()
    entry, exit_ = trade["entry_price"], trade["exit_price"]
    if trade["side"] == "LONG":
        mfe, mae, realised = high - entry, entry - low, exit_ - entry
    else:
        mfe, mae, realised = entry - low, high - entry, entry - exit_
    return {"mfe_pts": _num(mfe), "mae_pts": _num(mae), "giveback_pts": _num(mfe - realised)}


def timeframe_payload(day_runs: dict, minutes: pd.DataFrame, version) -> dict:
    """Bars, votes and readings once per timeframe; trades and decisions per arm."""
    first_run = next(iter(day_runs.values()))
    first = min(r.display[0] for r in day_runs.values())
    last = max(r.display[1] for r in day_runs.values())
    bars = first_run.bars.iloc[first:last + 1]
    votes = first_run.strategy.votes
    span = slice(first, last + 1)
    mid = lambda col: ((bars[f"{col}_bid"] + bars[f"{col}_ask"]) / 2.0).to_numpy()
    candles = [[int(t.timestamp()), _num(o), _num(h), _num(l), _num(c)]
               for t, o, h, l, c in zip(bars.index, mid("open"), mid("high"), mid("low"), mid("close"))]

    def mask(table):
        return [int(sum(1 << k for k, name in enumerate(votes.names) if table[name][i]))
                for i in range(first, last + 1)]

    payload = {
        "bars": candles,
        "bull": mask(votes.bullish), "bear": mask(votes.bearish),
        "gate_long": [bool(x) for x in votes.long_gate[span]],
        "gate_short": [bool(x) for x in votes.short_gate[span]],
        "readings": {k: [_num(v, 3) for v in votes.readings[k][span]] for k in READINGS},
        "patterns": {k: [bool(v) for v in votes.readings[k][span]] for k in PATTERNS},
        "warm_from": int(first_run.strategy.warmup - first),
        "arms": {},
    }
    for arm, run in day_runs.items():
        offset = run.display[0] - first
        decisions = [None] * len(candles)
        for i, d in enumerate(run.decisions):
            decisions[offset + i] = {"a": d["action"], "b": d["blocked_by"], "e": d["events"],
                                     "p": d["position"]}
        trades = []
        for t in run.trades:
            entry = pd.Timestamp(t["entry_time"])
            exit_ = pd.Timestamp(t["exit_time"])
            index = run.bars.index
            entry_bar = int(index.searchsorted(entry, side="left")) - 1 - first
            exit_bar = int(index.searchsorted(exit_, side="left")) - 1 - first
            trade_id = f"{run.record['run_id']}--T{entry.strftime('%Y%m%dT%H%M')}-{t['side']}"
            exit_event = next((e for d in run.decisions for e in d["events"]
                               if e["kind"] == "exit" and pd.Timestamp(e["time"]) == exit_), None)
            trades.append({
                "id": trade_id, "side": t["side"],
                "entry_time": t["entry_time"], "entry_price": _num(t["entry_price"]),
                "exit_time": t["exit_time"], "exit_price": _num(t["exit_price"]),
                "entry_bar": entry_bar, "exit_bar": exit_bar,
                "stop": _num(t["stop"]), "target": None if t["target"] is None else _num(t["target"]),
                "exit_reason": t["exit_reason"], "exit_why": exit_event["why"] if exit_event else "",
                "r": _num(t["r"], 3), "net_usd": _num(t["net_usd"]),
                "financing_usd": _num(t["financing_usd"]), "size": t["size"],
                **excursions(minutes, t),
            })
        payload["arms"][arm] = {"run_id": run.record["run_id"], "parameters": run.record["arm_parameters"],
                                "trades": trades, "decisions": decisions}
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--day", required=True, type=dt.date.fromisoformat)
    parser.add_argument("--strategy", default="v001")
    parser.add_argument("--db", type=Path, default=REVIEW.parents[1] / "data" / "axe-trader.sqlite")
    parser.add_argument("--epic", default="US500")
    parser.add_argument("--out", type=Path, default=REVIEW / "day-review-data.js")
    args = parser.parse_args()

    version = load_version(REVIEW / "strategies" / f"{args.strategy}.yaml")
    minutes = load_cached_minutes(args.db, args.epic, ENGINE / ".cache")
    spec = load_instruments(ENGINE / "instruments.yaml")[args.epic]
    commit, dirty = engine_state()
    if dirty:
        print("WARNING: research/engine has uncommitted changes; runs are marked engine.dirty")

    timeframes = {}
    run_ids = []
    session = None
    for timeframe in version.execution["timeframes"]:
        day_runs = {}
        for arm in version.arms:
            run = run_day(minutes, version, args.day, timeframe, arm, spec, commit,
                          epic=args.epic, engine_dirty=dirty)
            write_run(REVIEW / "runs", run)
            day_runs[arm] = run
            run_ids.append(run.record["run_id"])
            session = run.record["session"] | {"window": run.record["window"]}
            print(f"{timeframe:>5} {arm:<10} {len(run.trades)} trade(s)  {run.record['run_id']}")
        timeframes[timeframe] = timeframe_payload(day_runs, minutes, version)

    data = {
        "epic": args.epic, "session": session,
        "strategy": {"version": version.version, "parent": version.parent,
                     "hypothesis": version.hypothesis, "sha256": version.sha256,
                     "arms": version.arms, "execution": version.execution},
        "pillars": {"names": ["RSI+BB", "Candle", "S/R", "Vol+Trend"],
                    "rules": {side: {n: r.format(**vars(version.pillars)) for n, r in RULES[side].items()}
                              for side in RULES},
                    "gate": {side: g.format(**vars(version.pillars)) for side, g in GATE_RULES.items()},
                    "readings": PILLAR_READINGS, "threshold": version.pillars.confluence_threshold},
        "timeframes": timeframes,
        "run_ids": run_ids,
        "engine": {"commit": commit, "dirty": dirty},
        "built": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
    }
    args.out.write_text("window.DAY = " + json.dumps(data, separators=(",", ":"), default=str) + ";\n")
    print(f"wrote {args.out} ({args.out.stat().st_size / 1024:.0f} KiB)")


if __name__ == "__main__":
    main()
