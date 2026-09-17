import pandas as pd

from engine.data import excluded_minute_count, load_minutes


def test_loads_one_epic_sorted_with_utc_open_minutes(db_factory):
    db = db_factory([
        ("GOLD", "2024-01-02T10:01:00Z", 2000.0, 2000.5),
        ("GOLD", "2024-01-02T10:00:00Z", 1999.0, 1999.5),
        ("US500", "2024-01-02T10:00:00Z", 4700.0, 4700.4),
    ])

    frame = load_minutes(db, "GOLD")

    assert list(frame.index) == [pd.Timestamp("2024-01-02T10:00:00Z"), pd.Timestamp("2024-01-02T10:01:00Z")]
    assert frame.index.name == "minute"
    assert frame.loc[pd.Timestamp("2024-01-02T10:01:00Z"), "close_ask"] == 2000.5
    assert frame["volume"].dtype.kind == "i"


def test_counts_distinct_excluded_minutes(db_factory):
    db = db_factory([("GOLD", "2024-01-02T10:00:00Z", 1.0, 1.1)], exclusions=[
        ("GOLD", "2024-01-02T10:05:00Z", "CLOSE_BID_ABOVE_ASK"),
        ("GOLD", "2024-01-02T10:05:00Z", "HIGH_BELOW_LOW"),
        ("US500", "2024-01-02T10:06:00Z", "CLOSE_BID_ABOVE_ASK"),
    ])

    assert excluded_minute_count(db, "GOLD") == 1
