import pandas as pd

from engine.sessions import core_minutes, open_mask, session_ids

# US500-like: weekdays 00:00-21:00 and 21:05-24:00, Friday closes 21:00, Sunday opens 22:00.
HOURS = {
    "mon": [[0, 1260], [1265, 1440]], "tue": [[0, 1260], [1265, 1440]], "wed": [[0, 1260], [1265, 1440]],
    "thu": [[0, 1260], [1265, 1440]], "fri": [[0, 1260]], "sat": [], "sun": [[1320, 1440]],
}


def ts(text):
    return pd.Timestamp(text, tz="UTC")


def test_open_mask_follows_the_weekday_hours():
    minutes = pd.DatetimeIndex([ts("2024-01-01 20:59"), ts("2024-01-01 21:02"), ts("2024-01-06 12:00"),
                                ts("2024-01-07 22:00")])  # Mon, Mon break, Sat, Sun open
    assert open_mask(minutes, HOURS).tolist() == [True, False, False, True]


def test_core_minutes_require_open_now_and_one_hour_earlier():
    core = core_minutes(ts("2024-01-07 21:00"), ts("2024-01-08 00:30"), HOURS)  # Sunday evening into Monday

    assert ts("2024-01-07 22:30") not in core   # open, but 21:30 Sunday was closed
    assert ts("2024-01-07 23:00") in core       # open, and 22:00 was open
    assert ts("2024-01-08 00:10") in core       # Monday, and Sunday 23:10 was open
    assert core[0] == ts("2024-01-07 23:00")


def test_the_daily_break_removes_itself_and_the_same_minutes_an_hour_later():
    core = core_minutes(ts("2024-01-08 20:00"), ts("2024-01-08 23:00"), HOURS)

    assert ts("2024-01-08 20:59") in core       # open now and one hour earlier
    assert ts("2024-01-08 21:02") not in core   # closed now
    assert ts("2024-01-08 21:30") in core       # open now (21:05-24:00) and at 20:30
    assert ts("2024-01-08 22:02") not in core   # open now, but 21:02 was closed
    assert ts("2024-01-08 22:10") in core


def test_sessions_split_on_breaks():
    core = pd.DatetimeIndex([ts("2024-01-08 10:00"), ts("2024-01-08 10:01"), ts("2024-01-08 12:00")])
    assert session_ids(core).tolist() == [0, 0, 1]
