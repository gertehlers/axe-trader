#!/usr/bin/env python3
"""Query the experiment results DB (regenerate it first with:
   ./mvnw test -Dmaven.compiler.release=21 -Dtest=ConfluenceSweepTest -Dsweep=true -Dsweep.persist=true).

Usage:
  python3 experiments/query.py                      # list experiments (headline metrics)
  python3 experiments/query.py winners              # experiments >75% win and positive every quarter
  python3 experiments/query.py losers <experiment>  # losing trades for an experiment (by label or id)
  python3 experiments/query.py slice <experiment> <feature> [buckets]
                                                    # win% / net expectancy per feature bucket vs baseline
  python3 experiments/query.py sql "<raw SQL>"      # arbitrary read-only SQL

`slice` is the point of the whole thing: it shows win rate CONDITIONAL on a feature bucket vs. the
overall baseline, so you cluster causes (e.g. "near the EMA -> 52% vs 80% baseline"), not outcomes.
"""
import sqlite3
import sys
from pathlib import Path

DB = Path(__file__).with_name("experiments.sqlite")


def conn():
    if not DB.exists():
        sys.exit(f"{DB} not found — run the sweep with -Dsweep.persist=true first.")
    c = sqlite3.connect(DB)
    c.row_factory = sqlite3.Row
    return c


def _exp_id(c, ref):
    row = c.execute(
        "SELECT id FROM experiment WHERE CAST(id AS TEXT)=? OR label=? ORDER BY id DESC LIMIT 1",
        (str(ref), str(ref)),
    ).fetchone()
    if not row:
        sys.exit(f"no experiment matching '{ref}'")
    return row["id"]


def list_experiments(c):
    print(f"{'id':>3}  {'label':<40} {'trades':>6} {'/day':>5} {'win%':>5} {'netPnl':>7} {'worstQ':>7} {'maxDD':>7}")
    for r in c.execute("SELECT * FROM experiment ORDER BY id"):
        print(f"{r['id']:>3}  {r['label']:<40} {r['trades']:>6} {r['trades_per_day']:>5.1f} "
              f"{r['win_rate']*100:>4.0f}% {r['net_avg_pnl']:>7.2f} {r['worst_quarter_net']:>7.2f} {r['max_drawdown']:>7.1f}")


def winners(c):
    rows = c.execute("SELECT * FROM experiment WHERE win_rate > 0.75 AND worst_quarter_net > 0 "
                     "AND net_avg_pnl > 0 ORDER BY net_avg_pnl DESC").fetchall()
    if not rows:
        print("no experiment with >75% win AND positive net expectancy in every quarter.")
        return
    for r in rows:
        print(f"#{r['id']} {r['label']}: {r['win_rate']*100:.0f}% win, net {r['net_avg_pnl']:+.2f}/trade, "
              f"worstQ {r['worst_quarter_net']:+.2f}, {r['trades_per_day']:.1f}/day")


def losers(c, ref):
    eid = _exp_id(c, ref)
    rows = c.execute("SELECT * FROM trade WHERE experiment_id=? AND is_win=0 ORDER BY net_pnl", (eid,)).fetchall()
    print(f"{len(rows)} losing trades for experiment #{eid}")
    for r in rows[:40]:
        ema = "" if r["dist_to_trend_ema_atr"] is None else f"emaDist {r['dist_to_trend_ema_atr']:+.2f} "
        print(f"  {r['entry_ts'][:16]} {r['direction']:<5} pnl {r['pnl']:+6.2f} {r['exit_reason']:<6} "
              f"rsi {r['rsi_value']:5.1f} {ema}atr%ile {r['atr_percentile']:.2f} h{r['hour_utc']:02d}")


def slice_feature(c, ref, feature, buckets=5):
    eid = _exp_id(c, ref)
    vals = c.execute(f"SELECT {feature} AS v, is_win, net_pnl FROM trade "
                     f"WHERE experiment_id=? AND {feature} IS NOT NULL ORDER BY v", (eid,)).fetchall()
    if not vals:
        sys.exit(f"no non-null values for feature '{feature}' in experiment #{eid}")
    n = len(vals)
    base_win = 100.0 * sum(v["is_win"] for v in vals) / n
    print(f"experiment #{eid} — baseline win {base_win:.0f}% over {n} trades; win% by {feature} bucket:")
    per = max(1, n // int(buckets))
    for b in range(0, n, per):
        chunk = vals[b:b + per]
        lo, hi = chunk[0]["v"], chunk[-1]["v"]
        win = 100.0 * sum(x["is_win"] for x in chunk) / len(chunk)
        net = sum(x["net_pnl"] for x in chunk) / len(chunk)
        flag = "  <-- drag" if win < base_win - 10 else ""
        print(f"  {feature} [{lo:+8.3f} .. {hi:+8.3f}]  {len(chunk):4d} trades  win {win:3.0f}%  net {net:+.2f}{flag}")


def main():
    args = sys.argv[1:]
    with conn() as c:
        if not args:
            list_experiments(c)
        elif args[0] == "winners":
            winners(c)
        elif args[0] == "losers" and len(args) >= 2:
            losers(c, args[1])
        elif args[0] == "slice" and len(args) >= 3:
            slice_feature(c, args[1], args[2], args[3] if len(args) > 3 else 5)
        elif args[0] == "sql" and len(args) >= 2:
            for r in c.execute(args[1]):
                print(dict(r))
        else:
            print(__doc__)


if __name__ == "__main__":
    main()
