"""H-0004: does the US500 overnight premium survive CFD financing?

The most replicated equity-index anomaly is that essentially the entire long-run return accrues
overnight (cash close -> next cash open) while the intraday session contributes nothing. The
economic reason has a named losing side: intraday liquidity providers flatten into the close
because their risk limits forbid carrying inventory across a closed book, and they pay whoever does.

The question here is not whether the premium exists. It is whether a retail CFD account can be paid
it, because holding US500 overnight costs -0.0215%/day -- about 1.30 pts a night against a 66.8-pt
median daily range, which is precisely what the premium compensates.

Criteria are pre-registered in research/ledger/H-0004-us500-overnight-premium.md and restated in
CRITERIA below so script and ledger cannot drift apart.

Two definitions do all the work and are the easiest things to get wrong:

  1. Sessions are America/New_York, NOT UTC. The effect is about the US cash session, which moves
     with US daylight saving. instruments.yaml records hours in one DST state only and is useless
     for this.
  2. The overnight leg is a HELD POSITION, not an untradeable gap, because the US500 CFD trades
     ~23h. That is the only reason this is testable at all -- and it is also why financing applies.

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-us500-overnight-premium.py
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
import yaml

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.costs import charge_times
from engine.diagnose import trades_for_power

ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data" / "axe-trader.sqlite"
CACHE = ROOT / "research" / "engine" / ".cache"
SPECS = ROOT / "research" / "engine" / "instruments.yaml"
OUT = Path(__file__).with_suffix(".json")

EPIC = "US500"
DEV_START = pd.Timestamp("2024-01-01T00:00:00Z")
DEV_END = pd.Timestamp("2026-07-31T23:59:00Z")
HOLDOUT_NOTE = "2026-08-01 onward is held out and untouched"

EXCHANGE_TZ = "America/New_York"
CASH_OPEN = (9, 30)
CASH_CLOSE = (16, 0)
BOOTSTRAP = 10_000
SEED = 20260919

MIN_OBSERVATIONS = 100          # criterion 2
MIN_POSITIVE_QUARTER_SHARE = 0.60   # criterion 3


def bootstrap_ci(values: np.ndarray, iterations: int = BOOTSTRAP, seed: int = SEED):
    values = np.asarray(values, dtype=float)
    if len(values) == 0:
        return (0.0, 0.0)
    rng = np.random.default_rng(seed)
    means = rng.choice(values, size=(iterations, len(values)), replace=True).mean(axis=1)
    return (float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5)))


def session_marks(minutes: pd.DataFrame) -> pd.DataFrame:
    """One row per US trading day: the cash open and cash close bar, in UTC, with mid prices.

    Built on the exchange clock so that US daylight saving is handled by the calendar rather than
    by a hardcoded UTC hour, which would silently shift the session by an hour twice a year.
    """
    local = minutes.tz_convert(EXCHANGE_TZ)
    minute_of_day = local.index.hour * 60 + local.index.minute
    open_minute = CASH_OPEN[0] * 60 + CASH_OPEN[1]
    close_minute = CASH_CLOSE[0] * 60 + CASH_CLOSE[1]

    in_session = (minute_of_day >= open_minute) & (minute_of_day <= close_minute)
    session = local[in_session].copy()
    session["trading_day"] = session.index.normalize()

    mid = (session["close_bid"] + session["close_ask"]) / 2.0
    grouped = pd.DataFrame({"mid": mid, "trading_day": session["trading_day"]},
                           index=session.index)
    first = grouped.groupby("trading_day").head(1)
    last = grouped.groupby("trading_day").tail(1)

    marks = pd.DataFrame({
        "open_time_utc": first.index.tz_convert("UTC"),
        "open_mid": first["mid"].to_numpy(),
    }, index=first["trading_day"].to_numpy())
    marks["close_time_utc"] = last.index.tz_convert("UTC")
    marks["close_mid"] = last["mid"].to_numpy()
    marks["bars_in_session"] = grouped.groupby("trading_day").size().to_numpy()
    return marks


def financing_points(entry: pd.Timestamp, exit_time: pd.Timestamp, price: float,
                     rate_pct: float, charge_time_utc: str) -> tuple[float, int]:
    """Financing for one leg, in price points, charged per 21:00 UTC cut-off actually crossed.

    A Friday-close -> Monday-open hold crosses three cut-offs and is charged for three. This
    deliberately avoids the weekend_multiplier default of 1.0, which understates weekend carry
    (open question from the 2026-09-18 handover).
    """
    charges = charge_times(entry, exit_time, charge_time_utc)
    return float(price * (abs(rate_pct) / 100.0) * len(charges)), int(len(charges))


def main() -> None:
    specs = yaml.safe_load(SPECS.read_text())["instruments"][EPIC]
    fee = specs["overnight_fee"]

    minutes = load_cached_minutes(DB, EPIC, CACHE)
    minutes = minutes[(minutes.index >= DEV_START) & (minutes.index <= DEV_END)]
    spread = float((minutes["close_ask"] - minutes["close_bid"]).mean())

    marks = session_marks(minutes)
    # A day needs a real session on both sides of a leg. Drop days with a stub session, which
    # would otherwise contribute a fake open or close price (half-days, data gaps).
    typical_bars = float(marks["bars_in_session"].median())
    marks = marks[marks["bars_in_session"] >= 0.5 * typical_bars]

    print(f"{EPIC} {marks.index[0].date()} -> {marks.index[-1].date()}  "
          f"{len(marks)} sessions, mean spread {spread:.4f} pts")
    print(f"{HOLDOUT_NOTE}")
    print(f"sessions defined on {EXCHANGE_TZ}: "
          f"{CASH_OPEN[0]:02d}:{CASH_OPEN[1]:02d} -> {CASH_CLOSE[0]:02d}:{CASH_CLOSE[1]:02d}, "
          f"median {typical_bars:.0f} minute bars per session\n")

    rows = []
    previous = None
    for day, row in marks.iterrows():
        if previous is not None:
            gross = float(row["open_mid"] - previous["close_mid"])
            fin, charges = financing_points(previous["close_time_utc"], row["open_time_utc"],
                                            float(previous["close_mid"]), fee["long_rate"],
                                            fee["charge_time_utc"])
            rows.append({"day": day, "leg": "overnight", "gross": gross, "spread": spread,
                         "financing": fin, "charges": charges,
                         "hours": (row["open_time_utc"] - previous["close_time_utc"]).total_seconds() / 3600.0})
        gross = float(row["close_mid"] - row["open_mid"])
        fin, charges = financing_points(row["open_time_utc"], row["close_time_utc"],
                                        float(row["open_mid"]), fee["long_rate"],
                                        fee["charge_time_utc"])
        rows.append({"day": day, "leg": "intraday", "gross": gross, "spread": spread,
                     "financing": fin, "charges": charges,
                     "hours": (row["close_time_utc"] - row["open_time_utc"]).total_seconds() / 3600.0})
        previous = row

    frame = pd.DataFrame(rows)
    frame["day"] = pd.to_datetime(frame["day"])

    results = {}
    print(f"{'leg':>10} {'side':>6} {'n':>5} {'gross':>8} {'spread':>7} {'finan':>7} "
          f"{'net':>8} {'CI low':>8} {'CI high':>8} {'q+':>7}")
    print("-" * 82)
    for leg in ("overnight", "intraday"):
        part = frame[frame["leg"] == leg]
        for side, sign in (("long", 1.0), ("short", -1.0)):
            gross = sign * part["gross"].to_numpy()
            net = gross - part["spread"].to_numpy() - sign_financing(sign, part, fee)
            low, high = bootstrap_ci(net)
            quarters = pd.Series(net, index=part["day"].to_numpy()).resample("QS").sum()
            positive_share = float((quarters > 0).mean())
            results[f"{leg}_{side}"] = {
                "n": int(len(net)),
                "net_sd": float(net.std(ddof=1)),
                "sessions_for_power": trades_for_power(effect=float(net.mean()),
                                                       sd=float(net.std(ddof=1))),
                "gross_mean": float(gross.mean()),
                "spread_mean": float(part["spread"].mean()),
                "financing_mean": float(sign_financing(sign, part, fee).mean()),
                "mean_charges": float(part["charges"].mean()),
                "mean_hours": float(part["hours"].mean()),
                "net_mean": float(net.mean()),
                "net_ci95": [low, high],
                "net_total": float(net.sum()),
                "quarters": int(len(quarters)),
                "quarters_positive": int((quarters > 0).sum()),
                "quarters_positive_share": positive_share,
                "per_quarter": {str(k.date()): float(v) for k, v in quarters.items()},
            }
            r = results[f"{leg}_{side}"]
            print(f"{leg:>10} {side:>6} {r['n']:>5} {r['gross_mean']:>8.3f} "
                  f"{r['spread_mean']:>7.3f} {r['financing_mean']:>7.3f} {r['net_mean']:>8.3f} "
                  f"{low:>8.3f} {high:>8.3f} "
                  f"{r['quarters_positive']:>3}/{r['quarters']:<3}")

    overnight = results["overnight_long"]
    intraday = results["intraday_long"]
    criteria = {
        "1_net_expectancy": {
            "required": "overnight-long net mean > 0 AND bootstrap CI lower bound > 0",
            "observed_net_mean": overnight["net_mean"],
            "observed_ci_low": overnight["net_ci95"][0],
            "pass": bool(overnight["net_mean"] > 0 and overnight["net_ci95"][0] > 0),
        },
        "2_power": {
            "required": f">= {MIN_OBSERVATIONS} observations, and enough to resolve the observed "
                        f"net effect at 80% power",
            "observed": overnight["n"],
            "sessions_needed_for_observed_effect": overnight["sessions_for_power"],
            "years_needed_at_252_sessions": overnight["sessions_for_power"] / 252.0,
            "pass": bool(overnight["n"] >= MIN_OBSERVATIONS
                         and overnight["n"] >= overnight["sessions_for_power"]),
        },
        "3_stability": {
            "required": f">= {MIN_POSITIVE_QUARTER_SHARE:.0%} of quarters net-positive",
            "observed": overnight["quarters_positive_share"],
            "pass": bool(overnight["quarters_positive_share"] >= MIN_POSITIVE_QUARTER_SHARE),
        },
        "4_effect_not_drift": {
            "required": "overnight gross mean > intraday gross mean",
            "observed_overnight": overnight["gross_mean"],
            "observed_intraday": intraday["gross_mean"],
            "pass": bool(overnight["gross_mean"] > intraday["gross_mean"]),
        },
    }

    criteria["1b_costs_covered"] = {
        "required": "layer 2 on its own: overnight gross mean > spread + financing",
        "observed_gross": overnight["gross_mean"],
        "observed_costs": overnight["spread_mean"] + overnight["financing_mean"],
        "pass": bool(overnight["gross_mean"]
                     > overnight["spread_mean"] + overnight["financing_mean"]),
    }

    # Spec 6.2.3 assigns the status to the FIRST FAILING LAYER, and layer 0 comes before layer 2.
    # An edge that covers its costs but cannot be resolved by the available sample is underpowered,
    # not uneconomic, and `inconclusive` is the status that says "park until more data exists".
    if not criteria["2_power"]["pass"]:
        verdict = "inconclusive"
    elif not criteria["4_effect_not_drift"]["pass"]:
        verdict = "rejected: no signal"
    elif not criteria["1b_costs_covered"]["pass"]:
        verdict = "uneconomic"
    elif not criteria["1_net_expectancy"]["pass"]:
        verdict = "inconclusive"
    elif not criteria["3_stability"]["pass"]:
        verdict = "regime-dependent"
    else:
        verdict = "pass: promote to G1 candidate"

    print()
    for name, check in criteria.items():
        print(f"  {'PASS' if check['pass'] else 'FAIL'}  {name}: {check['required']}")
    print(f"  --> {verdict}\n")

    print(f"layer 0: {overnight['n']} sessions available, "
          f"{overnight['sessions_for_power']:,} needed to resolve a {overnight['net_mean']:+.3f} pt "
          f"net edge against sd {overnight['net_sd']:.1f} "
          f"({overnight['sessions_for_power'] / 252.0:.0f} years)")
    print(f"layer 2: gross {overnight['gross_mean']:+.3f} vs costs "
          f"{overnight['spread_mean'] + overnight['financing_mean']:.3f} "
          f"(spread {overnight['spread_mean']:.3f} + financing {overnight['financing_mean']:.3f}) "
          f"-> financing takes {overnight['financing_mean'] / overnight['gross_mean']:.0%} of the premium\n")

    print("the split, gross, in points per session:")
    print(f"  overnight  {overnight['gross_mean']:+.3f}   "
          f"({overnight['mean_hours']:.1f}h held, {overnight['mean_charges']:.2f} financing charges)")
    print(f"  intraday   {intraday['gross_mean']:+.3f}   "
          f"({intraday['mean_hours']:.1f}h held, {intraday['mean_charges']:.2f} financing charges)")
    print(f"  total day  {overnight['gross_mean'] + intraday['gross_mean']:+.3f}")

    OUT.write_text(json.dumps({
        "hypothesis": "H-0004",
        "epic": EPIC,
        "window": [str(marks.index[0].date()), str(marks.index[-1].date())],
        "holdout": HOLDOUT_NOTE,
        "sessions": int(len(marks)),
        "exchange_tz": EXCHANGE_TZ,
        "cash_session": f"{CASH_OPEN[0]:02d}:{CASH_OPEN[1]:02d}-{CASH_CLOSE[0]:02d}:{CASH_CLOSE[1]:02d}",
        "trial_count": 4,
        "mean_spread": spread,
        "financing_rates": {"long": fee["long_rate"], "short": fee["short_rate"],
                            "charge_time_utc": fee["charge_time_utc"]},
        "weekend_handling": "every 21:00 UTC cut-off crossed is charged; no weekend_multiplier applied",
        "legs": results,
        "criteria": criteria,
        "verdict": verdict,
    }, indent=2, default=str))
    print(f"wrote {OUT}")


def sign_financing(sign: float, part: pd.DataFrame, fee: dict) -> np.ndarray:
    """Financing is a cost either way, but the rate differs by side.

    Long pays long_rate, short pays short_rate, and on US500 they are wildly different
    (-0.0215 vs -0.00068 %/day). The `financing` column was computed at the long rate, so the
    short side is rescaled rather than recomputed.
    """
    financing = part["financing"].to_numpy()
    if sign > 0:
        return financing
    ratio = abs(fee["short_rate"]) / abs(fee["long_rate"])
    return financing * ratio


if __name__ == "__main__":
    main()
