"""Trading-session calendar from Capital.com opening hours, robust to daylight-saving shifts.

Capital.com reports *current* hours in UTC. Markets tied to New York, London or Frankfurt move by one
hour in UTC twice a year, so a minute only counts as expected ("core") when it is open under the fetched
hours and under the same hours shifted by +60 minutes. That never manufactures gaps from DST.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
MINUTES_PER_WEEK = 7 * 1440


def _week_table(hours: dict) -> np.ndarray:
    table = np.zeros(MINUTES_PER_WEEK, dtype=bool)
    for day_index, day in enumerate(DAYS):
        for start, end in hours.get(day, []):
            table[day_index * 1440 + start: day_index * 1440 + end] = True
    return table


def _minute_of_week(minutes: pd.DatetimeIndex) -> np.ndarray:
    return (minutes.dayofweek.to_numpy() * 1440 + minutes.hour.to_numpy() * 60 + minutes.minute.to_numpy())


def open_mask(minutes: pd.DatetimeIndex, hours: dict) -> np.ndarray:
    return _week_table(hours)[_minute_of_week(minutes)]


def core_minutes(start: pd.Timestamp, end: pd.Timestamp, hours: dict) -> pd.DatetimeIndex:
    minutes = pd.date_range(start.floor("min"), end.floor("min"), freq="min")
    table = _week_table(hours)
    now = _minute_of_week(minutes)
    earlier = (now - 60) % MINUTES_PER_WEEK
    return minutes[table[now] & table[earlier]]


def session_ids(core: pd.DatetimeIndex) -> np.ndarray:
    if len(core) == 0:
        return np.array([], dtype=int)
    steps = np.asarray(core[1:] - core[:-1] != pd.Timedelta(minutes=1))
    return np.concatenate([[0], np.cumsum(steps)]).astype(int)
