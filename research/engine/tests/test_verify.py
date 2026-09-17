import pandas as pd

from engine.verify import verify_instrument

# Weekdays open 10:00-12:00 UTC -> core minutes are 11:00-11:59 (open now and an hour earlier).
HOURS = {day: [[600, 720]] for day in ["mon", "tue", "wed", "thu", "fri"]} | {"sat": [], "sun": []}
SPEC = {"epic": "TEST", "opening_hours": HOURS}


def frame(minutes, crossed=()):
    index = pd.DatetimeIndex(minutes, tz="UTC", name="minute")
    rows = []
    for minute in index:
        bid, ask = 100.0, 100.2
        if minute in crossed:
            bid, ask = 100.3, 100.2
        rows.append({"open_bid": bid, "open_ask": ask, "high_bid": bid + 0.1, "high_ask": ask + 0.1,
                     "low_bid": bid - 0.1, "low_ask": ask - 0.1, "close_bid": bid, "close_ask": ask, "volume": 5})
    return pd.DataFrame(rows, index=index)


def core_hour(day):
    return list(pd.date_range(f"{day} 11:00", f"{day} 11:59", freq="min"))


def test_clean_week_passes():
    minutes = sum((core_hour(d) for d in ["2024-01-01", "2024-01-02", "2024-01-03", "2024-01-04", "2024-01-05"]), [])

    report = verify_instrument(frame(minutes), SPEC)

    assert report["passed"] is True
    assert report["failures"] == []
    assert report["missing_sessions"] == 0
    assert report["missing_core_minutes"] == 0
    assert report["rows"] == 300


def test_dirty_week_fails_on_every_rule():
    tuesday = [m for m in core_hour("2024-01-02")
               if not (pd.Timestamp("2024-01-02 11:10") <= m <= pd.Timestamp("2024-01-02 11:49"))]
    minutes = core_hour("2024-01-01") + tuesday + core_hour("2024-01-04") + core_hour("2024-01-05")
    minutes.append(pd.Timestamp("2024-01-05 10:30"))  # a bar outside the core
    crossed = {pd.Timestamp("2024-01-04 11:30", tz="UTC")}

    report = verify_instrument(frame(sorted(minutes), crossed=crossed), SPEC)

    assert report["missing_sessions"] == 1                       # Wednesday had no bars at all
    assert report["missing_session_starts"] == ["2024-01-03T11:00:00Z"]
    assert report["longest_gap_minutes"] == 40
    assert report["holes_over_30m"] == [{"from": "2024-01-02T11:10:00Z", "to": "2024-01-02T11:50:00Z", "minutes": 40}]
    assert report["missing_core_minutes"] == 40
    assert round(report["missing_core_pct"], 2) == round(40 / 240 * 100, 2)
    assert report["bad_ticks"] == 1
    assert report["bars_outside_core"] == 1
    assert set(report["failures"]) == {"missing_core_pct", "bad_tick_pct", "missing_sessions"}
    assert report["passed"] is False


def test_holes_are_listed_but_do_not_fail_on_their_own():
    days = weekdays("2024-01-01", 2)
    minutes = []
    for index, day in enumerate(days):
        hour = core_hour(day)
        minutes += [m for m in hour if not (index == 1 and pd.Timestamp(f"{day} 11:10") <= m < pd.Timestamp(f"{day} 11:12"))]
    long_hole_day = days[2]
    minutes = [m for m in minutes if not (pd.Timestamp(f"{long_hole_day} 11:05") <= m < pd.Timestamp(f"{long_hole_day} 11:45"))]

    report = verify_instrument(frame(minutes), SPEC)

    assert len(report["holes_over_30m"]) == 1
    assert "longest_gap" not in report["failures"]


def test_short_sessions_do_not_count_toward_missing_sessions():
    # Open 10:00-12:00 and 12:05-12:45 -> core sessions 11:00-11:59 (60 min) and 12:05-12:44 (40 min).
    hours = {day: [[600, 720], [725, 765]] for day in ["mon", "tue", "wed", "thu", "fri"]} | {"sat": [], "sun": []}
    spec = {"epic": "TEST", "opening_hours": hours}
    minutes = sum((core_hour(d) for d in weekdays("2024-01-01", 1)), [])  # the 40-minute block never trades

    report = verify_instrument(frame(minutes), spec)

    assert report["missing_sessions"] == 0
    assert report["short_missing_sessions"] == 4  # Mon-Thu; Friday's short block is after the last bar
    assert "missing_sessions" not in report["failures"]


def weekdays(start, weeks):
    days = pd.date_range(start, periods=weeks * 7, freq="D")
    return [d.strftime("%Y-%m-%d") for d in days if d.dayofweek < 5]


# Weekdays open 10:00-14:00 UTC -> core 11:00-13:59, long enough for early closes and drift to differ.
LONG_SPEC = {"epic": "TEST", "opening_hours": {day: [[600, 840]] for day in ["mon", "tue", "wed", "thu", "fri"]}
             | {"sat": [], "sun": []}}


def core_block(day):
    return list(pd.date_range(f"{day} 11:00", f"{day} 13:59", freq="min"))


def test_an_early_close_is_an_edge_gap_not_an_interior_gap():
    days = weekdays("2024-01-01", 2)
    minutes = (sum((core_block(d) for d in days[:4]), []) + core_block(days[4])[:30]  # Friday closes at 11:30
               + core_block(days[5]))

    report = verify_instrument(frame(minutes), LONG_SPEC)

    assert report["edge_gap_sessions"] == 1
    assert report["longest_gap_minutes"] == 0
    assert report["missing_core_minutes"] == 0


def test_a_boundary_up_to_an_hour_off_is_drift_not_an_early_close():
    days = weekdays("2024-01-01", 2)
    minutes = sum((core_block(d)[45:] for d in days[:6]), [])  # every session opens 45 minutes late

    report = verify_instrument(frame(minutes), LONG_SPEC)

    assert report["edge_gap_sessions"] == 0
    assert report["boundary_drift_minutes"] == 45 * 6  # the calendar starts at midnight of the first bar's day
    assert report["missing_core_minutes"] == 0


def test_a_persistent_news_move_is_not_a_bad_tick():
    minutes = core_hour("2024-01-01")
    data = frame(minutes)
    after = data.index >= pd.Timestamp("2024-01-01 11:30", tz="UTC")
    data.loc[after, ["open_bid", "close_bid", "high_bid", "low_bid"]] += 50
    data.loc[after, ["open_ask", "close_ask", "high_ask", "low_ask"]] += 50

    report = verify_instrument(data, SPEC)

    assert report["bad_ticks"] == 0


def test_price_jump_counts_as_a_bad_tick():
    minutes = core_hour("2024-01-01")
    data = frame(minutes)
    spike = pd.Timestamp("2024-01-01 11:30", tz="UTC")
    data.loc[spike, ["close_bid", "close_ask"]] = [150.0, 150.2]
    data.loc[spike, ["high_bid", "high_ask"]] = [150.1, 150.3]

    report = verify_instrument(data, SPEC)

    assert report["bad_ticks"] >= 1
    assert "2024-01-01T11:30:00Z" in report["bad_tick_examples"]
