import sqlite3
from pathlib import Path

import pytest

SCHEMA = """
CREATE TABLE historical_price (
  id TEXT PRIMARY KEY, epic TEXT, resolution TEXT, snapshot_time_utc TEXT,
  open_bid REAL, open_ask REAL, high_bid REAL, high_ask REAL, low_bid REAL, low_ask REAL,
  close_bid REAL, close_ask REAL, last_traded_volume INTEGER, source TEXT, ingestion_time_utc TEXT)
"""


def make_db(path: Path, rows, exclusions=()):
    connection = sqlite3.connect(path)
    connection.execute(SCHEMA)
    for index, (epic, minute, close_bid, close_ask) in enumerate(rows):
        connection.execute(
            "INSERT INTO historical_price VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (f"r{index}", epic, "MINUTE", minute,
             close_bid, close_ask, close_bid + 0.1, close_ask + 0.1, close_bid - 0.1, close_ask - 0.1,
             close_bid, close_ask, 10, "capital", minute),
        )
    if exclusions:
        connection.execute("CREATE TABLE price_exclusion (import_run_id TEXT, source TEXT, epic TEXT, "
                           "resolution TEXT, snapshot_time_utc TEXT, reason TEXT, detected_at_utc TEXT)")
        for epic, minute, reason in exclusions:
            connection.execute("INSERT INTO price_exclusion VALUES ('run','capital',?,'MINUTE',?,?,?)",
                               (epic, minute, reason, minute))
    connection.commit()
    connection.close()
    return path


@pytest.fixture
def db_factory(tmp_path):
    def build(rows, exclusions=()):
        return make_db(tmp_path / "history.sqlite", rows, exclusions)
    return build


def make_bar_frame(n=400, seed=7):
    """A synthetic resampled-bar frame with the columns the engine expects.

    Shared by the pillar and strategy tests. It lives here rather than in a test module because
    pytest imports test files as top-level modules, so `from tests.test_pillars import ...`
    does not resolve; conftest is the importable home for shared helpers in this suite.
    """
    import numpy as np
    import pandas as pd

    rng = np.random.default_rng(seed)
    close = 100.0 + np.cumsum(rng.normal(0, 0.5, n))
    high, low = close + 0.6, close - 0.6
    open_ = np.concatenate([[close[0]], close[:-1]])
    spread = 0.02
    return pd.DataFrame({
        "open_bid": open_ - spread / 2, "open_ask": open_ + spread / 2,
        "high_bid": high - spread / 2, "high_ask": high + spread / 2,
        "low_bid": low - spread / 2, "low_ask": low + spread / 2,
        "close_bid": close - spread / 2, "close_ask": close + spread / 2,
        "volume": rng.integers(80, 120, n).astype(float),
        "minutes_present": np.full(n, 240), "complete": np.ones(n, dtype=bool),
    }, index=pd.date_range("2024-01-01", periods=n, freq="4h", tz="UTC"))


@pytest.fixture
def bar_frame():
    return make_bar_frame
