import pandas as pd

from engine import cache


def test_cache_is_written_then_reused_without_touching_the_db(db_factory, tmp_path):
    rows = [("US500", "2024-01-01T00:00:00Z", 100.0, 100.2),
            ("US500", "2024-01-01T00:01:00Z", 101.0, 101.2)]
    db = db_factory(rows)
    cache_dir = tmp_path / "cache"

    first = cache.load_cached_minutes(db, "US500", cache_dir)
    assert len(first) == 2
    written = list(cache_dir.glob("*.parquet"))
    assert len(written) == 1

    db.unlink()  # cache must serve without the database present
    second = cache.load_cached_minutes(db, "US500", cache_dir)
    pd.testing.assert_frame_equal(first, second)


def test_excluded_minutes_are_dropped_at_load(db_factory, tmp_path):
    rows = [("US500", "2024-01-01T00:00:00Z", 100.0, 100.2),
            ("US500", "2024-01-01T00:01:00Z", 101.0, 101.2)]
    exclusions = [("US500", "2024-01-01T00:01:00Z", "LOW_BID_ABOVE_ASK")]
    db = db_factory(rows, exclusions)

    frame = cache.load_cached_minutes(db, "US500", tmp_path / "cache")

    assert len(frame) == 1
    assert frame.index[0] == pd.Timestamp("2024-01-01T00:00:00Z")
