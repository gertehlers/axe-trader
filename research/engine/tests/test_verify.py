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
    assert report["missing_core_minutes"] == 40
    assert round(report["missing_core_pct"], 2) == round(40 / 240 * 100, 2)
    assert report["bad_ticks"] == 1
    assert report["bars_outside_core"] == 1
    assert set(report["failures"]) == {"missing_core_pct", "longest_gap", "bad_tick_pct", "missing_sessions"}
    assert report["passed"] is False


def test_price_jump_counts_as_a_bad_tick():
    minutes = core_hour("2024-01-01")
    data = frame(minutes)
    spike = pd.Timestamp("2024-01-01 11:30", tz="UTC")
    data.loc[spike, ["close_bid", "close_ask"]] = [150.0, 150.2]
    data.loc[spike, ["high_bid", "high_ask"]] = [150.1, 150.3]

    report = verify_instrument(data, SPEC)

    assert report["bad_ticks"] >= 1
    assert "2024-01-01T11:30:00Z" in report["bad_tick_examples"]
