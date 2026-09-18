import pandas as pd

from engine import bars


def _minutes(times, closes):
    index = pd.to_datetime(times, utc=True)
    frame = pd.DataFrame(index=index)
    frame.index.name = "minute"
    for column, offset in [("open", 0.0), ("high", 0.5), ("low", -0.5), ("close", 0.0)]:
        frame[f"{column}_bid"] = [c + offset for c in closes]
        frame[f"{column}_ask"] = [c + offset + 0.2 for c in closes]
    frame["volume"] = 1
    return frame


def test_five_minute_bar_aggregates_ohlc_on_the_clock_grid():
    frame = _minutes(
        ["2024-01-01T00:00:00Z", "2024-01-01T00:01:00Z", "2024-01-01T00:02:00Z",
         "2024-01-01T00:03:00Z", "2024-01-01T00:04:00Z"],
        [100.0, 103.0, 99.0, 101.0, 102.0])

    out = bars.resample(frame, "5min")

    assert len(out) == 1
    row = out.iloc[0]
    assert out.index[0] == pd.Timestamp("2024-01-01T00:00:00Z")
    assert row["open_bid"] == 100.0
    assert row["close_bid"] == 102.0
    assert row["high_bid"] == 103.5
    assert row["low_bid"] == 98.5
    assert row["minutes_present"] == 5
    assert bool(row["complete"]) is True


def test_incomplete_bar_is_kept_and_flagged():
    frame = _minutes(["2024-01-01T00:00:00Z", "2024-01-01T00:03:00Z"], [100.0, 101.0])

    out = bars.resample(frame, "5min")

    assert len(out) == 1
    assert out.iloc[0]["minutes_present"] == 2
    assert bool(out.iloc[0]["complete"]) is False


def test_empty_buckets_produce_no_rows():
    frame = _minutes(["2024-01-01T00:00:00Z", "2024-01-01T00:20:00Z"], [100.0, 101.0])

    out = bars.resample(frame, "5min")

    assert list(out.index) == [pd.Timestamp("2024-01-01T00:00:00Z"),
                               pd.Timestamp("2024-01-01T00:20:00Z")]
