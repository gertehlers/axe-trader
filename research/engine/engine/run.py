"""Run a strategy and write the run's evidence (spec 3.5).

The summary leads with net expectancy after costs and its bootstrap CI. Win rate is present
but explicitly tagged informational so no reader mistakes it for a gate (spec 0, D1).
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import subprocess
from pathlib import Path

import numpy as np
import pandas as pd

from engine.simulator import SimConfig, Trade


def _git_commit() -> str:
    try:
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True,
                              check=True).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unknown"


def bootstrap_ci(values: np.ndarray, iterations: int = 10_000, seed: int = 20260917) -> tuple[float, float]:
    values = np.asarray(values, dtype=float)
    if len(values) == 0:
        return (0.0, 0.0)
    rng = np.random.default_rng(seed)
    means = rng.choice(values, size=(iterations, len(values)), replace=True).mean(axis=1)
    return (float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5)))


def _max_drawdown(balances: np.ndarray) -> tuple[float, float]:
    if len(balances) == 0:
        return (0.0, 0.0)
    peak = np.maximum.accumulate(balances)
    drop = peak - balances
    worst = float(drop.max())
    at = int(drop.argmax())
    return (worst, float(worst / peak[at] * 100.0) if peak[at] else 0.0)


def summarise(trades: list[Trade], equity: pd.DataFrame, config: SimConfig, meta: dict) -> dict:
    nets = np.array([t.net_usd for t in trades], dtype=float)
    rs = np.array([t.r for t in trades], dtype=float)
    wins = int((nets > 0).sum()) if len(nets) else 0
    gross = float(sum(abs(t.gross_usd) for t in trades))
    costs = float(sum(abs(t.financing_usd) + abs(t.slippage_usd) for t in trades))
    balances = equity["balance"].to_numpy(dtype=float) if len(equity) else np.array([])
    drawdown_usd, drawdown_pct = _max_drawdown(balances)
    per_period: dict = {}
    if trades:
        frame = pd.DataFrame({"time": [t.exit_time for t in trades], "net": nets}).set_index("time")
        per_period = {
            "per_month": {str(k): float(v) for k, v in frame["net"].resample("MS").sum().items()},
            "per_quarter": {str(k): float(v) for k, v in frame["net"].resample("QS").sum().items()},
        }
    return {
        **meta,
        "git_commit": _git_commit(),
        "config": dataclasses.asdict(config),
        "trades": len(trades),
        "net_usd": float(nets.sum()) if len(nets) else 0.0,
        "expectancy_usd": float(nets.mean()) if len(nets) else 0.0,
        "expectancy_usd_ci95": list(bootstrap_ci(nets)),
        "expectancy_r": float(rs.mean()) if len(rs) else 0.0,
        "expectancy_r_ci95": list(bootstrap_ci(rs)),
        "max_drawdown_usd": drawdown_usd,
        "max_drawdown_pct": drawdown_pct,
        "cost_share_of_gross": float(costs / gross) if gross else 0.0,
        "win_rate": {"value": float(wins / len(trades)) if trades else 0.0, "informational": True},
        **per_period,
    }


def equity_curve(trades: list[Trade], starting_balance: float) -> pd.DataFrame:
    balance = starting_balance
    times, balances = [], []
    for trade in trades:
        balance += trade.net_usd
        times.append(trade.exit_time)
        balances.append(balance)
    return pd.DataFrame({"time": times, "balance": balances})


def write_run(run_dir: Path, trades: list[Trade], equity: pd.DataFrame, summary: dict) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    frame = pd.DataFrame([dataclasses.asdict(t) for t in trades])
    if frame.empty:
        frame = pd.DataFrame(columns=[f.name for f in dataclasses.fields(Trade)])
    frame.to_parquet(run_dir / "trades.parquet")
    equity.to_parquet(run_dir / "equity.parquet")
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2, default=str))


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Run a strategy and write its evidence")
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--epic", required=True)
    parser.add_argument("--timeframe", default="5min")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--balance", type=float, default=2000.0)
    parser.add_argument("--risk-pct", type=float, default=1.0)
    parser.parse_args(argv)
    raise SystemExit("no strategy is wired to the CLI yet; import engine.run from a research script")
