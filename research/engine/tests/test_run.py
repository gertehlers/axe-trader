import json

import numpy as np
import pandas as pd
import pytest

from engine.run import bootstrap_ci, summarise, write_run
from engine.simulator import SimConfig, Trade


def _trade(net, r, entry="2024-01-02T09:00:00Z", exit_="2024-01-02T09:05:00Z"):
    return Trade(entry_time=pd.Timestamp(entry), entry_price=100.0, exit_time=pd.Timestamp(exit_),
                 exit_price=101.0, side="LONG", size=1.0, stop=98.0, target=102.0,
                 exit_reason="TARGET", gross_usd=net, spread_cost_usd=0.0, financing_usd=0.0,
                 slippage_usd=0.0, net_usd=net, r=r)


def test_bootstrap_ci_brackets_the_mean():
    values = np.random.default_rng(1).normal(0.5, 1.0, 500)
    low, high = bootstrap_ci(values, iterations=2000)
    assert low < values.mean() < high


def test_summary_reports_net_expectancy_and_marks_win_rate_informational():
    trades = [_trade(10.0, 0.5), _trade(-20.0, -1.0), _trade(30.0, 1.5)]
    equity = pd.DataFrame({"time": [t.exit_time for t in trades],
                           "balance": [2010.0, 1990.0, 2020.0]})

    summary = summarise(trades, equity, SimConfig(), {"epic": "US500", "timeframe": "5min"})

    assert summary["trades"] == 3
    assert summary["net_usd"] == pytest.approx(20.0)
    assert summary["expectancy_usd"] == pytest.approx(20.0 / 3)
    assert summary["win_rate"]["informational"] is True
    assert len(summary["expectancy_usd_ci95"]) == 2
    assert summary["max_drawdown_usd"] == pytest.approx(20.0)


def test_write_run_emits_the_three_expected_files(tmp_path):
    trades = [_trade(10.0, 0.5)]
    equity = pd.DataFrame({"time": [trades[0].exit_time], "balance": [2010.0]})
    summary = summarise(trades, equity, SimConfig(), {"epic": "US500", "timeframe": "5min"})

    write_run(tmp_path / "run1", trades, equity, summary)

    assert (tmp_path / "run1" / "trades.parquet").exists()
    assert (tmp_path / "run1" / "equity.parquet").exists()
    written = json.loads((tmp_path / "run1" / "summary.json").read_text())
    assert written["epic"] == "US500"
    assert "git_commit" in written


def test_summary_of_zero_trades_does_not_divide_by_zero():
    summary = summarise([], pd.DataFrame({"time": [], "balance": []}), SimConfig(),
                        {"epic": "US500", "timeframe": "5min"})
    assert summary["trades"] == 0
    assert summary["expectancy_usd"] == 0.0


def test_summary_records_the_weekend_multiplier_actually_used():
    """The default understates weekend cost, so a reader must be able to see what was assumed."""
    summary = summarise([_trade(10.0, 0.5)],
                        pd.DataFrame({"time": [pd.Timestamp("2024-01-02T09:05:00Z")],
                                      "balance": [2010.0]}),
                        SimConfig(weekend_multiplier=3.0), {"epic": "US500", "timeframe": "5min"})
    assert summary["config"]["weekend_multiplier"] == 3.0
