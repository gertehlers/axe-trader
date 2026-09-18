"""Overnight financing and currency conversion (spec 3.2, 3.4).

Capital.com quotes overnight rates as a percentage of notional per charge interval, charged at
a fixed UTC cut-off. Rates are negative for a cost. A position pays for every cut-off strictly
inside its holding period — entering after today's cut-off and exiting before tomorrow's costs
nothing.

The weekend multiplier is NOT derived from instruments.yaml, because Capital.com does not
publish it there. It defaults to 1.0, which understates the cost of anything carried over a
weekend. Every run records the value used so no result can silently depend on the default.
"""

from __future__ import annotations

import pandas as pd


def usd_per_point(spec: dict, fx_rate: float) -> float:
    """Value of one point of one unit, in USD. fx_rate converts the instrument currency to USD."""
    if fx_rate <= 0:
        raise ValueError(f"fx_rate must be positive, got {fx_rate}")
    return float(spec["lot_size"]) * fx_rate


def charge_times(entry: pd.Timestamp, exit_time: pd.Timestamp, charge_time_utc: str) -> pd.DatetimeIndex:
    hour, minute = (int(part) for part in charge_time_utc.split(":"))
    first = entry.normalize() + pd.Timedelta(hours=hour, minutes=minute)
    if first <= entry:
        first = first + pd.Timedelta(days=1)
    if first >= exit_time:
        return pd.DatetimeIndex([], tz="UTC")
    return pd.date_range(first, exit_time - pd.Timedelta(minutes=1), freq="D", tz="UTC")


def financing_usd(side: str, size: float, price: float, spec: dict, charges: pd.DatetimeIndex,
                  usd_per_unit_rate: float, weekend_multiplier: float = 1.0) -> float:
    if len(charges) == 0:
        return 0.0
    fee = spec["overnight_fee"]
    rate_pct = fee["long_rate"] if side == "LONG" else fee["short_rate"]
    notional_usd = size * price * usd_per_point(spec, usd_per_unit_rate)
    return float(notional_usd * (rate_pct / 100.0) * len(charges) * weekend_multiplier)
