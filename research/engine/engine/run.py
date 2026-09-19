"""Run a strategy and write the run's evidence (spec 3.5).

The summary leads with net expectancy after costs and its bootstrap CI. Win rate is present
but explicitly tagged informational so no reader mistakes it for a gate (spec 0, D1).
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import subprocess
from collections import Counter
from pathlib import Path

import numpy as np
import pandas as pd

from engine.simulator import SimConfig, Trade
from engine.strategies.confluence import ReversalExit, SymmetricExit, TimeExit, TrailingExit

ARMS = {"trailing": TrailingExit, "time": TimeExit,
        "reversal": ReversalExit, "symmetric": SymmetricExit}

# Which keyword arguments each arm accepts, so an irrelevant flag is a clear error rather than
# a silently ignored one.
ARM_PARAMETERS = {"trailing": {"brake_atr", "trail_atr"}, "time": {"brake_atr", "max_bars"},
                  "reversal": {"brake_atr"}, "symmetric": {"stop_atr"}}


def build_strategy(name: str, frame: pd.DataFrame, **kwargs):
    if name not in ARMS:
        raise ValueError(f"unknown arm {name!r}; expected one of {sorted(ARMS)}")
    allowed = ARM_PARAMETERS[name]
    unknown = set(kwargs) - allowed
    if unknown:
        raise ValueError(f"arm {name!r} does not take {sorted(unknown)}; it takes {sorted(allowed)}")
    return ARMS[name](frame, **kwargs)


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


def _side_summary(trades: list[Trade], starting_balance: float) -> dict:
    nets = np.array([t.net_usd for t in trades], dtype=float)
    rs = np.array([t.r for t in trades], dtype=float)
    if len(trades) == 0:
        return {"trades": 0, "net_usd": 0.0, "net_pct": 0.0, "expectancy_r": 0.0,
                "expectancy_r_ci95": [0.0, 0.0], "win_rate": 0.0, "financing_usd": 0.0}
    return {
        "trades": len(trades),
        "net_usd": float(nets.sum()),
        "net_pct": float(nets.sum() / starting_balance * 100.0),
        "expectancy_r": float(rs.mean()),
        "expectancy_r_ci95": list(bootstrap_ci(rs)),
        "win_rate": float((nets > 0).mean()),
        "financing_usd": float(sum(t.financing_usd for t in trades)),
    }


def risk_measures(trades: list[Trade], equity: pd.DataFrame, starting_balance: float) -> dict:
    """What a circuit breaker would need to be calibrated against (spec 6).

    No breaker is implemented this round: installing one before these numbers exist is a guess,
    and it also distorts the equity path being read.
    """
    if not trades:
        return {"max_drawdown_pct": 0.0, "max_drawdown_at": None, "worst_day_pct": 0.0,
                "longest_losing_run_trades": 0, "longest_losing_run_days": 0.0,
                "financing_share_of_gross": 0.0, "mean_holding_hours": 0.0,
                "max_holding_hours": 0.0}

    nets = np.array([t.net_usd for t in trades], dtype=float)
    balances = equity["balance"].to_numpy(dtype=float)
    peak = np.maximum.accumulate(balances)
    drop = peak - balances
    worst = int(drop.argmax())

    daily = pd.Series(nets, index=pd.DatetimeIndex([t.exit_time for t in trades])).resample("D").sum()
    hours = np.array([(t.exit_time - t.entry_time).total_seconds() / 3600.0 for t in trades])

    longest = run_length = 0
    run_start = run_end = None
    best_span = 0.0
    for trade, net in zip(trades, nets):
        if net < 0:
            run_length += 1
            run_start = run_start or trade.entry_time
            run_end = trade.exit_time
            if run_length > longest:
                longest = run_length
                best_span = (run_end - run_start).total_seconds() / 86400.0
        else:
            run_length = 0
            run_start = run_end = None

    gross = float(sum(abs(t.gross_usd) for t in trades))
    financing = float(sum(abs(t.financing_usd) for t in trades))
    return {
        "max_drawdown_pct": float(drop[worst] / peak[worst] * 100.0) if peak[worst] else 0.0,
        "max_drawdown_at": str(trades[worst].exit_time),
        "worst_day_pct": float(daily.min() / starting_balance * 100.0),
        "longest_losing_run_trades": int(longest),
        "longest_losing_run_days": float(best_span),
        "financing_share_of_gross": float(financing / gross) if gross else 0.0,
        "mean_holding_hours": float(hours.mean()),
        "max_holding_hours": float(hours.max()),
    }


def summarise(trades: list[Trade], equity: pd.DataFrame, config: SimConfig, meta: dict,
              skipped: int = 0, pillar_report: dict | None = None) -> dict:
    """Leads with account-size-invariant units: R and percent. Dollars are a diagnostic.

    With risk_pct = 1, one R is one percent of the account, so R makes every comparison
    independent of the balance the run happened to start with (spec 5.1, D6).
    """
    nets = np.array([t.net_usd for t in trades], dtype=float)
    rs = np.array([t.r for t in trades], dtype=float)
    wins = int((nets > 0).sum()) if len(nets) else 0
    gross = float(sum(abs(t.gross_usd) for t in trades))
    costs = float(sum(abs(t.financing_usd) + abs(t.slippage_usd) for t in trades))
    balances = equity["balance"].to_numpy(dtype=float) if len(equity) else np.array([])
    drawdown_usd, drawdown_pct = _max_drawdown(balances)
    starting = config.starting_balance_usd

    per_period: dict = {}
    if trades:
        stamped = pd.DataFrame({"time": [t.exit_time for t in trades], "net": nets}).set_index("time")
        per_period = {
            "per_month_pct": {str(k): float(v / starting * 100.0)
                              for k, v in stamped["net"].resample("MS").sum().items()},
            "per_quarter_pct": {str(k): float(v / starting * 100.0)
                                for k, v in stamped["net"].resample("QS").sum().items()},
        }

    signals = len(trades) + skipped
    return {
        **meta,
        "git_commit": _git_commit(),
        "config": dataclasses.asdict(config),
        "trades": len(trades),
        # account-size-invariant, reported first
        "expectancy_r": float(rs.mean()) if len(rs) else 0.0,
        "expectancy_r_ci95": list(bootstrap_ci(rs)),
        "net_pct": float(nets.sum() / starting * 100.0) if len(nets) else 0.0,
        "max_drawdown_pct": drawdown_pct,
        "skipped": int(skipped),
        "skipped_share": float(skipped / signals) if signals else 0.0,
        "by_side": {side: _side_summary([t for t in trades if t.side == side], starting)
                    for side in ("LONG", "SHORT")},
        "by_exit_reason": dict(Counter(t.exit_reason for t in trades)),
        "risk": risk_measures(trades, equity, starting),
        "cost_share_of_gross": float(costs / gross) if gross else 0.0,
        "win_rate": {"value": float(wins / len(trades)) if trades else 0.0, "informational": True},
        "pillars": pillar_report,
        # diagnostics, in dollars
        "net_usd": float(nets.sum()) if len(nets) else 0.0,
        "expectancy_usd": float(nets.mean()) if len(nets) else 0.0,
        "expectancy_usd_ci95": list(bootstrap_ci(nets)),
        "max_drawdown_usd": drawdown_usd,
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
    parser = argparse.ArgumentParser(description="Run a confluence exit arm and write its evidence")
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--epic", required=True)
    parser.add_argument("--timeframe", default="15min")
    parser.add_argument("--arm", required=True, choices=sorted(ARMS))
    parser.add_argument("--brake-atr", type=float, default=10.0)
    parser.add_argument("--trail-atr", type=float, default=2.0)
    parser.add_argument("--max-bars", type=int, default=6)
    parser.add_argument("--stop-atr", type=float, default=1.0)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--balance", type=float, default=2000.0)
    parser.add_argument("--risk-pct", type=float, default=1.0)
    args = parser.parse_args(argv)

    from engine.bars import resample
    from engine.cache import load_cached_minutes
    from engine.instruments import load_instruments
    from engine.pillars import PillarConfig, compute_pillars, fire_rates
    from engine.simulator import simulate
    from engine.strategies.confluence import WARMUP_BARS

    engine_dir = Path(__file__).resolve().parents[1]      # research/engine
    minutes = load_cached_minutes(args.db, args.epic, engine_dir / ".cache")
    signal_bars = resample(minutes, args.timeframe)
    spec = load_instruments(engine_dir / "instruments.yaml")[args.epic]

    kwargs = {k: v for k, v in {
        "brake_atr": args.brake_atr, "trail_atr": args.trail_atr,
        "max_bars": args.max_bars, "stop_atr": args.stop_atr,
    }.items() if k in ARM_PARAMETERS[args.arm]}
    strategy = build_strategy(args.arm, signal_bars, **kwargs)

    config = SimConfig(starting_balance_usd=args.balance, risk_pct=args.risk_pct)
    trades, skipped = simulate(strategy, minutes, signal_bars, spec, config)
    equity = equity_curve(trades, args.balance)
    summary = summarise(trades, equity, config,
                        {"epic": args.epic, "arm": args.arm, "timeframe": args.timeframe, **kwargs},
                        skipped=skipped,
                        pillar_report=fire_rates(compute_pillars(signal_bars, PillarConfig()),
                                                 WARMUP_BARS))
    write_run(args.out, trades, equity, summary)
    print(f"{args.epic} {args.arm}: {summary['trades']} trades, {skipped} skipped, "
          f"expectancy {summary['expectancy_r']:+.3f}R, net {summary['net_pct']:+.2f}%, "
          f"max DD {summary['risk']['max_drawdown_pct']:.1f}%")


if __name__ == "__main__":
    main()
