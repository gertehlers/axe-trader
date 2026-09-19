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


# --- account-size-invariant reporting (tasks 10 and 11) ------------------------------------

import pandas as pd

from engine.run import equity_curve, risk_measures, summarise
from engine.simulator import SimConfig, Trade


def a_trade(side="LONG", net=10.0, reason="TARGET", risk=20.0,
            entry=None, exit_=None):
    stamp = entry or pd.Timestamp("2024-03-01T12:00:00Z")
    return Trade(entry_time=stamp, entry_price=100.0,
                 exit_time=exit_ or (stamp + pd.Timedelta(hours=4)),
                 exit_price=101.0, side=side, size=1.0, stop=90.0, target=110.0,
                 exit_reason=reason, gross_usd=net, spread_cost_usd=0.5, financing_usd=0.0,
                 slippage_usd=0.0, net_usd=net, r=net / risk)


def test_summary_reports_percent_of_account_and_r():
    config = SimConfig(starting_balance_usd=2000.0, risk_pct=1.0)
    trades = [a_trade(net=20.0), a_trade(net=-20.0)]
    summary = summarise(trades, equity_curve(trades, 2000.0), config, {})
    assert summary["net_usd"] == 0.0
    assert summary["net_pct"] == 0.0
    assert summary["expectancy_r"] == 0.0
    assert "expectancy_r_ci95" in summary


def test_summary_splits_long_and_short():
    config = SimConfig(starting_balance_usd=2000.0, risk_pct=1.0)
    trades = [a_trade(side="LONG", net=40.0), a_trade(side="SHORT", net=-20.0)]
    summary = summarise(trades, equity_curve(trades, 2000.0), config, {})
    assert summary["by_side"]["LONG"]["trades"] == 1
    assert summary["by_side"]["LONG"]["net_usd"] == 40.0
    assert summary["by_side"]["SHORT"]["net_usd"] == -20.0
    assert summary["by_side"]["SHORT"]["expectancy_r"] < 0


def test_summary_records_skipped_trades_as_a_share():
    config = SimConfig(starting_balance_usd=2000.0, risk_pct=1.0)
    trades = [a_trade()]
    summary = summarise(trades, equity_curve(trades, 2000.0), config, {}, skipped=3)
    assert summary["skipped"] == 3
    assert summary["skipped_share"] == 0.75      # 3 skipped of 4 signals


def test_summary_breaks_down_exit_reasons():
    config = SimConfig(starting_balance_usd=2000.0, risk_pct=1.0)
    trades = [a_trade(reason="STOP"), a_trade(reason="TARGET"), a_trade(reason="SIGNAL")]
    summary = summarise(trades, equity_curve(trades, 2000.0), config, {})
    assert summary["by_exit_reason"] == {"STOP": 1, "TARGET": 1, "SIGNAL": 1}


def test_risk_measures_find_the_longest_losing_run():
    base = pd.Timestamp("2024-03-01T00:00:00Z")
    trades = [a_trade(net=net, entry=base + pd.Timedelta(days=i))
              for i, net in enumerate([10.0, -5.0, -5.0, -5.0, 10.0, -5.0])]
    measures = risk_measures(trades, equity_curve(trades, 2000.0), 2000.0)
    assert measures["longest_losing_run_trades"] == 3
    assert measures["longest_losing_run_days"] >= 2


def test_risk_measures_report_holding_time_in_hours():
    trades = [a_trade()]
    measures = risk_measures(trades, equity_curve(trades, 2000.0), 2000.0)
    assert measures["mean_holding_hours"] == 4.0
    assert measures["max_holding_hours"] == 4.0


def test_risk_measures_are_attached_to_the_summary():
    config = SimConfig(starting_balance_usd=2000.0, risk_pct=1.0)
    trades = [a_trade()]
    summary = summarise(trades, equity_curve(trades, 2000.0), config, {})
    assert "risk" in summary
    assert "max_drawdown_pct" in summary["risk"]
