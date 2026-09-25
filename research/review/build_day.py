"""Run one strategy version on one review session and build the day-review page's data.

For each timeframe x exit arm in the version: run `engine.dayrun.run_day`, write the run record
append-only under `research/review/runs/<run_id>/` (committed), then emit `day-review-data.js`
`days/<date>.js` plus `days/index.js` (gitignored, reproducible from the records) for
`day-review.html`.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../review/build_day.py \
        --day 2024-01-11 --strategy v001

With `--compare v001 --feedback ../review/feedback/2024-01-11/iteration-001`, the parent version is
rerun on the same engine, checked against the run the owner reviewed (a regression check), and
matched trade by trade against the child for the before/after view.

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
from engine.compare import CATCH_AFTER_SHARE, CATCH_BEFORE_BARS, MOVE_BARS, match_trades, scorecard
from engine.dayrun import load_version, run_day, write_run
from engine.explain import GATE_RULES, PILLAR_READINGS, RULES
from engine.instruments import load_instruments

REVIEW = Path(__file__).resolve().parent
DAYS = REVIEW / "days"
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


def build_version(version, minutes, spec, args, commit, dirty):
    timeframes, runs, session = {}, {}, None
    for timeframe in version.execution["timeframes"]:
        day_runs = {}
        for arm in version.arms:
            run = run_day(minutes, version, args.day, timeframe, arm, spec, commit,
                          epic=args.epic, engine_dirty=dirty)
            write_run(REVIEW / "runs", run)
            day_runs[arm] = run
            session = run.record["session"] | {"window": run.record["window"]}
            print(f"{version.version} {timeframe:>5} {arm:<10} {len(run.trades)} trade(s)  {run.record['run_id']}")
        timeframes[timeframe] = timeframe_payload(day_runs, minutes, version)
        runs[timeframe] = day_runs
    return timeframes, runs, session


def _suffix(trade_id: str) -> str:
    """'<run_id>--T20240111T2025-LONG' -> 'T20240111T2025-LONG': the part a rerun shares."""
    return trade_id.rsplit("--", 1)[-1]


def load_feedback(folder: Path | None) -> tuple[list[dict], list[dict]]:
    if folder is None:
        return [], []
    read = lambda sub: [json.loads(p.read_text()) for p in sorted((folder / sub).glob("*.json"))]
    unwrap = lambda docs: [d.get("data", d) for d in docs]
    return unwrap(read("feedback")), unwrap(read("marks"))


def reviewed_run_matches(grades: list[dict], parent_tfs: dict) -> dict:
    """Regression check: is the rerun parent the same set of trades the owner reviewed?"""
    report = {}
    for run_id in sorted({g["run_id"] for g in grades}):
        path = REVIEW / "runs" / run_id / "trades.json"
        g = next(g for g in grades if g["run_id"] == run_id)
        reviewed = json.loads(path.read_text()) if path.exists() else None
        rerun = parent_tfs[g["timeframe"]]["arms"][g["arm"]]["trades"]
        same = reviewed is not None and [(t["entry_time"], t["side"], t["exit_time"], t["exit_reason"],
                                          round(t["exit_price"], 2)) for t in reviewed] == \
            [(t["entry_time"], t["side"], t["exit_time"], t["exit_reason"], t["exit_price"]) for t in rerun]
        report[run_id] = {"record_found": reviewed is not None, "trades_identical": bool(same)}
    return report


def compare_payload(parent, parent_tfs: dict, child_tfs: dict, feedback_dir: Path | None,
                    child_runs: dict) -> dict:
    grades, marks = load_feedback(feedback_dir)
    moves = [m for m in marks if m.get("status") == "active" and m.get("kind") == "missed_move"]
    out = {"parent": parent.version, "parent_sha256": parent.sha256, "timeframes": {},
           "tolerance": {"moved_within_bars": MOVE_BARS, "caught_from_bars_before": CATCH_BEFORE_BARS,
                         "caught_until_share_of_move": CATCH_AFTER_SHARE},
           "regression": reviewed_run_matches(grades, parent_tfs) if grades else {}}
    for tf, child in child_tfs.items():
        minutes_per_bar = int(pd.Timedelta(tf).total_seconds() // 60)
        out["timeframes"][tf] = {}
        for arm, child_arm in child["arms"].items():
            parent_arm = parent_tfs[tf]["arms"][arm]
            rows = match_trades(parent_arm["trades"], child_arm["trades"], minutes_per_bar)
            # A parent trade the child still takes, but exits outside the review day, is not
            # "removed": find it in the child's full run and report the changed exit.
            full = child_runs[tf][arm].all_trades
            for r in rows:
                if r["status"] != "removed":
                    continue
                p = r["parent"]
                same = next((t for t in full if t["side"] == p["side"]
                             and pd.Timestamp(t["entry_time"]) == pd.Timestamp(p["entry_time"])), None)
                if same is not None:
                    r["status"] = "exit_changed"
                    r["child"] = {"id": None, "side": same["side"], "entry_time": same["entry_time"],
                                  "exit_time": same["exit_time"], "exit_reason": same["exit_reason"],
                                  "exit_price": _num(same["exit_price"]), "entry_price": _num(same["entry_price"]),
                                  "r": _num(same["r"], 3), "outside_day": True}
            # grades attach to the parent trade the owner actually reviewed, via the shared suffix
            by_suffix = {f"{_suffix(g['trade_id'])}--{g['decision']}": g["grade"] for g in grades
                         if g["timeframe"] == tf and g["arm"] == arm}
            keyed = {f"{r['parent']['id']}--{d}": by_suffix[f"{_suffix(r['parent']['id'])}--{d}"]
                     for r in rows if r["parent"] for d in ("entry", "exit")
                     if f"{_suffix(r['parent']['id'])}--{d}" in by_suffix}
            tf_moves = [m for m in moves if m["tf"] == tf]
            out["timeframes"][tf][arm] = {
                "parent_run_id": parent_arm["run_id"], "parent_trades": parent_arm["trades"],
                "rows": [{"status": r["status"], "parent": r["parent"]["id"] if r["parent"] else None,
                          "child": r["child"]["id"] if r["child"] else None,
                          "child_outside": r["child"] if r["child"] and r["child"].get("outside_day") else None}
                         for r in rows],
                "parent_grades": keyed,
                "scorecard": scorecard(rows, keyed, tf_moves, minutes_per_bar),
            }
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--day", required=True, type=dt.date.fromisoformat)
    parser.add_argument("--strategy", default="v001")
    parser.add_argument("--db", type=Path, default=REVIEW.parents[1] / "data" / "axe-trader.sqlite")
    parser.add_argument("--epic", default="US500")
    parser.add_argument("--out", type=Path, default=None,
                        help="defaults to days/<date>.js; days/index.js is rewritten to list every built day")
    parser.add_argument("--compare", help="parent strategy version to show before/after against")
    parser.add_argument("--feedback", type=Path, help="exported review iteration holding the owner's grades and marks")
    args = parser.parse_args()

    version = load_version(REVIEW / "strategies" / f"{args.strategy}.yaml")
    minutes = load_cached_minutes(args.db, args.epic, ENGINE / ".cache")
    spec = load_instruments(ENGINE / "instruments.yaml")[args.epic]
    commit, dirty = engine_state()
    if dirty:
        print("WARNING: research/engine has uncommitted changes; runs are marked engine.dirty")

    timeframes, runs, session = build_version(version, minutes, spec, args, commit, dirty)
    run_ids = [r.record["run_id"] for tf in runs for r in runs[tf].values()]
    compare = None
    if args.compare:
        parent = load_version(REVIEW / "strategies" / f"{args.compare}.yaml")
        parent_tfs, parent_runs, _ = build_version(parent, minutes, spec, args, commit, dirty)
        run_ids += [r.record["run_id"] for tf in parent_runs for r in parent_runs[tf].values()]
        compare = compare_payload(parent, parent_tfs, timeframes, args.feedback, runs)

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
        "compare": compare,
        "run_ids": run_ids,
        "engine": {"commit": commit, "dirty": dirty},
        "built": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
    }
    out = args.out or DAYS / f"{args.day.isoformat()}.js"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(f"(window.DAYS = window.DAYS || {{}})[{json.dumps(args.day.isoformat())}] = "
                   + json.dumps(data, separators=(",", ":"), default=str) + ";\n")
    print(f"wrote {out} ({out.stat().st_size / 1024:.0f} KiB)")
    write_index()


def write_index() -> None:
    """days/index.js: every built day, its strategy and what it is compared with."""
    entries = []
    for path in sorted(DAYS.glob("20??-??-??.js")):
        text = path.read_text()
        payload = json.loads(text[text.index("= ") + 2:].rstrip().rstrip(";"))
        entries.append({"date": path.stem, "strategy": payload["strategy"]["version"],
                        "compare": (payload.get("compare") or {}).get("parent"), "built": payload["built"]})
    (DAYS / "index.js").write_text("window.DAY_INDEX = " + json.dumps(entries) + ";\n")


if __name__ == "__main__":
    main()
