"""The NYSE cash session for a date, in UTC: 09:30-16:00 America/New_York, holidays and early closes.

`engine.sessions` answers a different question: when Capital.com quotes the CFD, which is nearly
around the clock. This module is the exchange session a US500 trader means by "the day". It takes
the NYSE calendar from `pandas_market_calendars` rather than a hand-kept list, and converts by date,
so the UTC open moves with US daylight saving (which does not switch on Europe's dates).
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass
from functools import lru_cache

import pandas as pd

REGULAR_HOURS = pd.Timedelta(hours=6, minutes=30)


@dataclass(frozen=True)
class CashSession:
    date: dt.date
    open_utc: pd.Timestamp
    close_utc: pd.Timestamp

    @property
    def early_close(self) -> bool:
        return self.close_utc - self.open_utc < REGULAR_HOURS


@lru_cache(maxsize=1)
def _calendar():
    import pandas_market_calendars as calendars
    return calendars.get_calendar("NYSE")


def cash_sessions(start: dt.date, end: dt.date) -> list[CashSession]:
    """Every NYSE session from `start` to `end` inclusive, in date order."""
    schedule = _calendar().schedule(start_date=str(start), end_date=str(end))
    return [CashSession(date=day.date(), open_utc=pd.Timestamp(row.market_open).tz_convert("UTC"),
                        close_utc=pd.Timestamp(row.market_close).tz_convert("UTC"))
            for day, row in schedule.iterrows()]


def cash_session(day: dt.date) -> CashSession | None:
    """The session on `day`, or None on a weekend or exchange holiday."""
    sessions = cash_sessions(day, day)
    return sessions[0] if sessions else None
