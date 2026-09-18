"""Parquet cache of minute bars, rebuilt when the database changes.

The cache key is the row count plus the newest snapshot timestamp for the epic: a top-up
import changes both, and nothing else does. Excluded minutes are applied here, once, so no
consumer can forget to drop them.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

import pandas as pd

from engine.data import load_minutes


def _connect(db_path: Path) -> sqlite3.Connection:
    return sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)


def cache_key(db_path: Path, epic: str) -> str:
    with _connect(db_path) as connection:
        count, newest = connection.execute(
            "SELECT COUNT(*), MAX(snapshot_time_utc) FROM historical_price "
            "WHERE epic = ? AND resolution = 'MINUTE'", (epic,)).fetchone()
    return f"{count}-{newest}"


def _excluded_minutes(db_path: Path, epic: str) -> set[str]:
    with _connect(db_path) as connection:
        exists = connection.execute(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='price_exclusion'").fetchone()[0]
        if not exists:
            return set()
        rows = connection.execute(
            "SELECT DISTINCT snapshot_time_utc FROM price_exclusion WHERE epic = ?", (epic,)).fetchall()
    return {row[0] for row in rows}


def _cache_path(cache_dir: Path, epic: str, key: str) -> Path:
    return cache_dir / f"{epic}-MINUTE-{key}.parquet"


def load_cached_minutes(db_path: Path, epic: str, cache_dir: Path) -> pd.DataFrame:
    cache_dir.mkdir(parents=True, exist_ok=True)
    existing = sorted(cache_dir.glob(f"{epic}-MINUTE-*.parquet"))
    if db_path.exists():
        path = _cache_path(cache_dir, epic, cache_key(db_path, epic))
        if not path.exists():
            frame = load_minutes(db_path, epic)
            excluded = _excluded_minutes(db_path, epic)
            if excluded:
                drop = pd.to_datetime(sorted(excluded), format="%Y-%m-%dT%H:%M:%SZ", utc=True)
                frame = frame.drop(index=drop, errors="ignore")
            for stale in existing:
                stale.unlink()
            frame.to_parquet(path)
        return pd.read_parquet(path)
    if not existing:
        raise FileNotFoundError(f"no database at {db_path} and no cache for {epic} in {cache_dir}")
    return pd.read_parquet(existing[-1])
