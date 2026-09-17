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
