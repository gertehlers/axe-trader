"""Read minute bars from the axe-trader SQLite history database."""

from __future__ import annotations

import sqlite3
from pathlib import Path

import pandas as pd

PRICE_COLUMNS = ["open_bid", "open_ask", "high_bid", "high_ask", "low_bid", "low_ask", "close_bid", "close_ask"]


def _connect(db_path: Path) -> sqlite3.Connection:
    return sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)


def load_minutes(db_path: Path, epic: str) -> pd.DataFrame:
    query = f"""
        SELECT snapshot_time_utc AS minute, {", ".join(PRICE_COLUMNS)}, last_traded_volume AS volume
        FROM historical_price WHERE epic = ? AND resolution = 'MINUTE'
    """
    with _connect(db_path) as connection:
        frame = pd.read_sql_query(query, connection, params=(epic,))
    frame["minute"] = pd.to_datetime(frame["minute"], format="%Y-%m-%dT%H:%M:%SZ", utc=True)
    frame[PRICE_COLUMNS] = frame[PRICE_COLUMNS].astype(float)
    frame["volume"] = frame["volume"].astype("int64")
    return frame.set_index("minute").sort_index()


def excluded_minute_count(db_path: Path, epic: str) -> int:
    with _connect(db_path) as connection:
        exists = connection.execute(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='price_exclusion'").fetchone()[0]
        if not exists:
            return 0
        return connection.execute(
            "SELECT COUNT(DISTINCT snapshot_time_utc) FROM price_exclusion WHERE epic = ?", (epic,)).fetchone()[0]
