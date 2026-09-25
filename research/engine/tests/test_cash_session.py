import datetime as dt

import pandas as pd

from engine.cash_session import cash_session, cash_sessions


def test_winter_session_is_14_30_to_21_00_utc():
    session = cash_session(dt.date(2024, 1, 11))
    assert session.open_utc == pd.Timestamp("2024-01-11T14:30Z")
    assert session.close_utc == pd.Timestamp("2024-01-11T21:00Z")
    assert not session.early_close


def test_summer_session_moves_an_hour_earlier_in_utc():
    session = cash_session(dt.date(2024, 7, 10))
    assert session.open_utc == pd.Timestamp("2024-07-10T13:30Z")
    assert session.close_utc == pd.Timestamp("2024-07-10T20:00Z")


def test_first_session_after_the_dst_switch_uses_summer_hours():
    # US clocks moved on Sunday 2024-03-10; Europe's did not until 03-31, which is the gap a
    # hard-coded UTC offset gets wrong.
    assert cash_session(dt.date(2024, 3, 8)).open_utc == pd.Timestamp("2024-03-08T14:30Z")
    assert cash_session(dt.date(2024, 3, 11)).open_utc == pd.Timestamp("2024-03-11T13:30Z")


def test_holiday_has_no_session():
    assert cash_session(dt.date(2024, 1, 15)) is None      # Martin Luther King Jr. Day
    assert cash_session(dt.date(2024, 1, 13)) is None      # Saturday


def test_day_after_thanksgiving_closes_early():
    session = cash_session(dt.date(2024, 11, 29))
    assert session.early_close
    assert session.close_utc == pd.Timestamp("2024-11-29T18:00Z")


def test_sessions_are_listed_in_order_without_holidays():
    days = [s.date for s in cash_sessions(dt.date(2024, 1, 11), dt.date(2024, 1, 17))]
    assert days == [dt.date(2024, 1, 11), dt.date(2024, 1, 12),
                    dt.date(2024, 1, 16), dt.date(2024, 1, 17)]
