# Research Engine Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Python research engine core — a minute-accurate bid/ask simulator that turns a strategy into an honest net-USD expectancy, so hypotheses can be tested against the net-expectancy-after-costs bar instead of a win rate.

**Architecture:** A layered pipeline with one responsibility per module. SQLite minute bars are cached to parquet and resampled onto the UTC clock grid; a strategy sees only closed bars through a read-only view and emits `Intent`s; the simulator walks the 1-minute bid/ask series applying entry, stop, target, financing and sizing rules; a run writer emits `trades.parquet`, `equity.parquet` and `summary.json`. Every layer is pure and separately testable — the simulator never reads SQLite, the strategy never sees the future.

**Tech Stack:** Python 3.14, pandas 3.0, numpy 2.5, pyyaml, pytest, pyarrow (new), numba (conditional — see Task 9).

**Spec:** `docs/superpowers/specs/2026-09-17-research-restart-design.md` §3 (Step 2 — research engine core). Decisions D1–D9 in §0 govern; §6.2.3 defines the diagnosis layers this engine must eventually serve.

## Global Constraints

- **Primary bar is net expectancy after costs.** Win rate is informational only and must never gate anything (spec §0 D1).
- **Nothing from the old Java engine is trusted.** No result, config or threshold carries over (spec §0, `docs/learnings/2026-09-17-code-and-research-sweep.md`).
- **Bid/ask discipline:** long entry and target on the **ask** for entry and **bid** for exit; short mirrored. Never use a mid price anywhere.
- **Incomplete bars are never dropped.** They carry `complete: bool` and `minutes_present: int` and strategies see the flags (spec §3.1).
- **`instruments.yaml` is generated, never hand-edited** (spec §3.2). Re-run `python -m engine.instruments` to change it.
- **pandas is 3.x**: datetime integer units are not nanoseconds. Compare `Timedelta`s; never do `asi8` arithmetic.
- Python floor is `>=3.12` (`pyproject.toml`); the working venv is 3.14.7.
- All timestamps are timezone-aware UTC. No naive datetimes cross a module boundary.
- Run everything through the venv: `research/engine/.venv/bin/python`, `research/engine/.venv/bin/pytest`.

## Data available today

`data/axe-trader.sqlite` holds four instruments, all USD-denominated:

| epic | rows | range |
|---|---|---|
| US500 | 958,849 | 2024-01-01 → 2026-09-17 |
| OIL_CRUDE | 944,105 | 2024-01-01 → 2026-09-17 |
| OIL_BRENT | 901,618 | 2024-01-02 → 2026-09-17 |
| NATURALGAS | 801,226 | 2024-01-01 → 2026-09-18 |

US500 is the reference instrument for every performance and end-to-end check.

## File structure

**Created:**
- `engine/cache.py` — parquet cache of minute bars; rebuild keyed on the DB's row count + max timestamp; applies `price_exclusion` minutes at load.
- `engine/bars.py` — resample minutes to a timeframe on the UTC clock grid, carrying `complete` / `minutes_present`.
- `engine/strategy.py` — `Strategy` protocol, `Intent` types (`Enter`/`Exit`/`MoveStop`), `BarsView`, `PositionView`.
- `engine/lookahead.py` — the mandatory truncation guard.
- `engine/sizing.py` — position sizing, step rounding, min-deal-size skip.
- `engine/costs.py` — overnight financing and non-USD → USD conversion.
- `engine/simulator.py` — the 1-minute exit loop: entry, stop, target, gap and same-minute rules.
- `engine/run.py` — orchestrates a run and writes `trades.parquet`, `equity.parquet`, `summary.json`.
- `tests/test_cache.py`, `tests/test_bars.py`, `tests/test_strategy.py`, `tests/test_lookahead.py`, `tests/test_sizing.py`, `tests/test_costs.py`, `tests/test_simulator.py`, `tests/test_run.py`, `tests/test_performance.py`.

**Modified:**
- `research/engine/pyproject.toml` — add `pyarrow`; add `numba` only if Task 9 requires it.
- `.gitignore` — add `research/engine/.cache/` and `research/runs/`.

**Reused unchanged:** `engine/data.py` (`load_minutes`, `excluded_minute_count`), `engine/instruments.py` (`load_instruments`), `engine/sessions.py` (`open_mask`, `core_minutes`), `tests/conftest.py` (`db_factory`).

---

### Task 1: Parquet cache with exclusions applied

**Files:**
- Create: `research/engine/engine/cache.py`
- Test: `research/engine/tests/test_cache.py`
- Modify: `research/engine/pyproject.toml` (add `pyarrow>=17`), `.gitignore`

**Interfaces:**
- Consumes: `engine.data.load_minutes(db_path, epic) -> pd.DataFrame` indexed by `minute`, columns `open_bid, open_ask, high_bid, high_ask, low_bid, low_ask, close_bid, close_ask, volume`.
- Produces:
  - `cache_key(db_path: Path, epic: str) -> str` — `"<rowcount>-<max_snapshot>"`.
  - `load_cached_minutes(db_path: Path, epic: str, cache_dir: Path) -> pd.DataFrame` — same shape as `load_minutes`, with excluded minutes removed.

- [ ] **Step 1: Write the failing test**

```python
# research/engine/tests/test_cache.py
import pandas as pd
import pytest

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && .venv/bin/pytest tests/test_cache.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'engine.cache'`

- [ ] **Step 3: Write minimal implementation**

```python
# research/engine/engine/cache.py
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd research/engine && .venv/bin/pytest tests/test_cache.py -v`
Expected: PASS (2 passed)

- [ ] **Step 5: Add pyarrow and ignore the cache directory**

In `research/engine/pyproject.toml`, change the dependencies line to:

```toml
dependencies = ["pandas>=2.2", "numpy>=2.0", "pyyaml>=6.0", "pyarrow>=17.0"]
```

Then reinstall and append to the repo-root `.gitignore`:

```bash
cd research/engine && .venv/bin/python -m pip install -q -e ".[dev]"
printf 'research/engine/.cache/\nresearch/runs/\n' >> ../../.gitignore
```

- [ ] **Step 6: Run the whole suite**

Run: `cd research/engine && .venv/bin/pytest -q tests`
Expected: PASS — 18 existing + 2 new = 20 passed

- [ ] **Step 7: Commit**

```bash
git add research/engine/engine/cache.py research/engine/tests/test_cache.py research/engine/pyproject.toml .gitignore
git commit -m "feat(engine): cache minute bars to parquet with exclusions applied"
```

---

### Task 2: Resampling to a timeframe on the UTC clock grid

**Files:**
- Create: `research/engine/engine/bars.py`
- Test: `research/engine/tests/test_bars.py`

**Interfaces:**
- Consumes: a minute frame as produced by `cache.load_cached_minutes`.
- Produces: `resample(minutes: pd.DataFrame, timeframe: str) -> pd.DataFrame` — indexed by bucket start, columns `open_bid, open_ask, high_bid, high_ask, low_bid, low_ask, close_bid, close_ask, volume, minutes_present (int64), complete (bool)`.

- [ ] **Step 1: Write the failing test**

```python
# research/engine/tests/test_bars.py
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && .venv/bin/pytest tests/test_bars.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'engine.bars'`

- [ ] **Step 3: Write minimal implementation**

```python
# research/engine/engine/bars.py
"""Resample minute bars onto the UTC clock grid.

A bar is `complete` only when every minute of its bucket is present. Incomplete bars are
kept — a thin bar is information about the market, not a defect — and strategies decide
what to do with the flag.
"""

from __future__ import annotations

import pandas as pd

AGGREGATION = {
    "open_bid": "first", "open_ask": "first",
    "high_bid": "max", "high_ask": "max",
    "low_bid": "min", "low_ask": "min",
    "close_bid": "last", "close_ask": "last",
    "volume": "sum",
}


def timeframe_minutes(timeframe: str) -> int:
    return int(pd.Timedelta(timeframe) / pd.Timedelta(minutes=1))


def resample(minutes: pd.DataFrame, timeframe: str) -> pd.DataFrame:
    grouped = minutes.resample(timeframe, label="left", closed="left")
    out = grouped.agg(AGGREGATION)
    out["minutes_present"] = grouped.size().astype("int64")
    out = out[out["minutes_present"] > 0]
    out["complete"] = out["minutes_present"] == timeframe_minutes(timeframe)
    return out
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd research/engine && .venv/bin/pytest tests/test_bars.py -v`
Expected: PASS (3 passed)

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/bars.py research/engine/tests/test_bars.py
git commit -m "feat(engine): resample minutes to a timeframe, flagging incomplete bars"
```

---

### Task 3: Strategy protocol, intents and views

**Files:**
- Create: `research/engine/engine/strategy.py`
- Test: `research/engine/tests/test_strategy.py`

**Interfaces:**
- Produces:
  - `Enter(side: str, stop: float, target: float | None = None, risk_r: float = 1.0)` — `side` is `"LONG"` or `"SHORT"`.
  - `Exit()`, `MoveStop(price: float)`.
  - `Intent = Enter | Exit | MoveStop`.
  - `BarsView(frame: pd.DataFrame, index: int)` with `.close_bid`, `.close_ask`, `.high_bid`, `.low_bid`, `.complete`, `.minutes_present` (each a numpy array of closed bars up to and including `index`), `.time` (the current bar's `pd.Timestamp`), and `__len__`.
  - `PositionView(side: str | None, entry_price: float, stop: float, target: float | None, size: float)`; `PositionView.flat()` for no position; truthiness is `side is not None`.
  - `Strategy` protocol: attribute `timeframe: str`, method `on_bar(history: BarsView, position: PositionView) -> list[Intent]`.

- [ ] **Step 1: Write the failing test**

```python
# research/engine/tests/test_strategy.py
import numpy as np
import pandas as pd
import pytest

from engine.strategy import BarsView, Enter, Exit, MoveStop, PositionView


def _frame(closes):
    index = pd.to_datetime([f"2024-01-01T00:{i:02d}:00Z" for i in range(len(closes))], utc=True)
    return pd.DataFrame({
        "open_bid": closes, "open_ask": [c + 0.2 for c in closes],
        "high_bid": [c + 1 for c in closes], "high_ask": [c + 1.2 for c in closes],
        "low_bid": [c - 1 for c in closes], "low_ask": [c - 0.8 for c in closes],
        "close_bid": closes, "close_ask": [c + 0.2 for c in closes],
        "volume": [1] * len(closes),
        "minutes_present": [5] * len(closes),
        "complete": [True] * len(closes),
    }, index=index)


def test_bars_view_exposes_only_closed_bars_up_to_the_current_index():
    view = BarsView(_frame([100.0, 101.0, 102.0, 103.0]), index=1)

    assert len(view) == 2
    np.testing.assert_array_equal(view.close_bid, np.array([100.0, 101.0]))
    assert view.time == pd.Timestamp("2024-01-01T00:01:00Z")


def test_bars_view_never_exposes_a_future_bar():
    view = BarsView(_frame([100.0, 101.0, 102.0]), index=0)

    assert len(view) == 1
    assert view.close_bid[-1] == 100.0
    assert 102.0 not in view.close_bid


def test_flat_position_is_falsey_and_open_position_is_truthy():
    assert not PositionView.flat()
    assert PositionView(side="LONG", entry_price=100.0, stop=98.0, target=101.0, size=1.0)


def test_enter_rejects_an_unknown_side():
    with pytest.raises(ValueError):
        Enter(side="SIDEWAYS", stop=98.0)


def test_intents_are_value_objects():
    assert Enter(side="LONG", stop=98.0) == Enter(side="LONG", stop=98.0)
    assert Exit() == Exit()
    assert MoveStop(price=99.0) == MoveStop(price=99.0)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && .venv/bin/pytest tests/test_strategy.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'engine.strategy'`

- [ ] **Step 3: Write minimal implementation**

```python
# research/engine/engine/strategy.py
"""What a strategy is allowed to see, and what it is allowed to ask for.

BarsView is the lookahead barrier: it slices to `index` inclusive, so a strategy physically
cannot read a bar that has not closed. Intents are declarative — a strategy never computes a
fill price; only the simulator does that, from the 1-minute bid/ask series.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, Union

import numpy as np
import pandas as pd

SIDES = ("LONG", "SHORT")


@dataclass(frozen=True)
class Enter:
    side: str
    stop: float
    target: float | None = None
    risk_r: float = 1.0

    def __post_init__(self) -> None:
        if self.side not in SIDES:
            raise ValueError(f"side must be one of {SIDES}, got {self.side!r}")


@dataclass(frozen=True)
class Exit:
    pass


@dataclass(frozen=True)
class MoveStop:
    price: float


Intent = Union[Enter, Exit, MoveStop]


@dataclass(frozen=True)
class PositionView:
    side: str | None
    entry_price: float
    stop: float
    target: float | None
    size: float

    @classmethod
    def flat(cls) -> "PositionView":
        return cls(side=None, entry_price=0.0, stop=0.0, target=None, size=0.0)

    def __bool__(self) -> bool:
        return self.side is not None


class BarsView:
    """Closed bars up to and including `index`. Never further."""

    def __init__(self, frame: pd.DataFrame, index: int) -> None:
        self._frame = frame
        self._index = index

    def __len__(self) -> int:
        return self._index + 1

    def _column(self, name: str) -> np.ndarray:
        return self._frame[name].to_numpy()[: self._index + 1]

    @property
    def time(self) -> pd.Timestamp:
        return self._frame.index[self._index]

    @property
    def open_bid(self) -> np.ndarray: return self._column("open_bid")

    @property
    def open_ask(self) -> np.ndarray: return self._column("open_ask")

    @property
    def high_bid(self) -> np.ndarray: return self._column("high_bid")

    @property
    def high_ask(self) -> np.ndarray: return self._column("high_ask")

    @property
    def low_bid(self) -> np.ndarray: return self._column("low_bid")

    @property
    def low_ask(self) -> np.ndarray: return self._column("low_ask")

    @property
    def close_bid(self) -> np.ndarray: return self._column("close_bid")

    @property
    def close_ask(self) -> np.ndarray: return self._column("close_ask")

    @property
    def volume(self) -> np.ndarray: return self._column("volume")

    @property
    def complete(self) -> np.ndarray: return self._column("complete")

    @property
    def minutes_present(self) -> np.ndarray: return self._column("minutes_present")


class Strategy(Protocol):
    timeframe: str

    def on_bar(self, history: BarsView, position: PositionView) -> list[Intent]: ...
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd research/engine && .venv/bin/pytest tests/test_strategy.py -v`
Expected: PASS (5 passed)

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/strategy.py research/engine/tests/test_strategy.py
git commit -m "feat(engine): add strategy protocol, intents and lookahead-safe views"
```

---

### Task 4: Lookahead guard

**Files:**
- Create: `research/engine/engine/lookahead.py`
- Test: `research/engine/tests/test_lookahead.py`

**Interfaces:**
- Consumes: `engine.strategy.BarsView`, `PositionView`, `Strategy`.
- Produces:
  - `collect_intents(strategy, frame) -> list[tuple[pd.Timestamp, list[Intent]]]` — drives `on_bar` over every bar with a flat position.
  - `check_lookahead(strategy, frame, cuts: int = 20, seed: int = 20260917) -> None` — raises `LookaheadError` on mismatch.
  - `class LookaheadError(AssertionError)`.

- [ ] **Step 1: Write the failing test**

```python
# research/engine/tests/test_lookahead.py
import numpy as np
import pandas as pd
import pytest

from engine.lookahead import LookaheadError, check_lookahead
from engine.strategy import Enter


def _frame(n=200):
    rng = np.random.default_rng(7)
    closes = 100 + np.cumsum(rng.normal(0, 0.5, n))
    index = pd.date_range("2024-01-01", periods=n, freq="5min", tz="UTC")
    return pd.DataFrame({
        "open_bid": closes, "open_ask": closes + 0.2,
        "high_bid": closes + 1, "high_ask": closes + 1.2,
        "low_bid": closes - 1, "low_ask": closes - 0.8,
        "close_bid": closes, "close_ask": closes + 0.2,
        "volume": np.ones(n), "minutes_present": np.full(n, 5), "complete": np.ones(n, dtype=bool),
    }, index=index)


class HonestStrategy:
    timeframe = "5min"

    def on_bar(self, history, position):
        if len(history) < 2:
            return []
        if history.close_bid[-1] > history.close_bid[-2]:
            return [Enter(side="LONG", stop=history.close_bid[-1] - 2)]
        return []


class LeakingStrategy:
    """Reads the whole series instead of the view — the exact bug the guard exists to catch."""
    timeframe = "5min"

    def __init__(self, frame):
        self._all = frame["close_bid"].to_numpy()

    def on_bar(self, history, position):
        i = len(history) - 1
        if i + 1 < len(self._all) and self._all[i + 1] > self._all[i]:
            return [Enter(side="LONG", stop=self._all[i] - 2)]
        return []


def test_guard_passes_an_honest_strategy():
    frame = _frame()
    check_lookahead(HonestStrategy(), frame)


def test_guard_rejects_a_leaking_strategy():
    frame = _frame()
    with pytest.raises(LookaheadError):
        check_lookahead(LeakingStrategy(frame), frame)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && .venv/bin/pytest tests/test_lookahead.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'engine.lookahead'`

- [ ] **Step 3: Write minimal implementation**

```python
# research/engine/engine/lookahead.py
"""The mandatory lookahead guard (spec §3.3).

Run the strategy on the full series and on the series truncated at random cut points. Any
intent that differs before a cut means the strategy read data it could not have had. A
strategy that fails this is rejected outright — no result from it is admissible.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from engine.strategy import BarsView, Intent, PositionView


class LookaheadError(AssertionError):
    pass


def collect_intents(strategy, frame: pd.DataFrame) -> list[tuple[pd.Timestamp, list[Intent]]]:
    flat = PositionView.flat()
    out = []
    for index in range(len(frame)):
        out.append((frame.index[index], list(strategy.on_bar(BarsView(frame, index), flat))))
    return out


def check_lookahead(strategy, frame: pd.DataFrame, cuts: int = 20, seed: int = 20260917) -> None:
    full = collect_intents(strategy, frame)
    rng = np.random.default_rng(seed)
    points = rng.choice(np.arange(2, len(frame)), size=min(cuts, len(frame) - 2), replace=False)
    for cut in sorted(int(p) for p in points):
        truncated = collect_intents(strategy, frame.iloc[:cut])
        for (full_time, full_intents), (cut_time, cut_intents) in zip(full[:cut], truncated):
            if full_time != cut_time or full_intents != cut_intents:
                raise LookaheadError(
                    f"intents differ at {cut_time} when data is truncated at bar {cut}: "
                    f"full={full_intents!r} truncated={cut_intents!r}")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd research/engine && .venv/bin/pytest tests/test_lookahead.py -v`
Expected: PASS (2 passed)

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/lookahead.py research/engine/tests/test_lookahead.py
git commit -m "feat(engine): add the mandatory lookahead truncation guard"
```

---

### Task 5: Position sizing and the min-deal-size skip

**Files:**
- Create: `research/engine/engine/sizing.py`
- Test: `research/engine/tests/test_sizing.py`

**Interfaces:**
- Produces:
  - `floor_to_step(value: float, step: float) -> float`
  - `position_size(risk_usd, stop_distance, usd_value_per_point, min_deal_size, size_increment) -> float` — returns `0.0` when the computed size is below `min_deal_size` (the caller counts the skip).

- [ ] **Step 1: Write the failing test**

```python
# research/engine/tests/test_sizing.py
import pytest

from engine.sizing import floor_to_step, position_size


def test_size_is_risk_divided_by_stop_distance_times_value_per_point():
    # 20 USD of risk, a 4.0-point stop, 1 USD per point -> 5 units
    assert position_size(20.0, 4.0, 1.0, min_deal_size=0.01, size_increment=0.01) == 5.0


def test_size_is_floored_to_the_increment_never_rounded_up():
    # 20 / (3.0 * 1.0) = 6.666... -> 6.66 at a 0.01 step
    assert position_size(20.0, 3.0, 1.0, min_deal_size=0.01, size_increment=0.01) == 6.66


def test_size_below_min_deal_size_returns_zero():
    # 20 / (4.0 * 1.0) = 5, but the instrument needs at least 10
    assert position_size(20.0, 4.0, 1.0, min_deal_size=10.0, size_increment=0.1) == 0.0


def test_value_per_point_scales_the_size():
    # each point is worth 2 USD, so the same risk buys half the size
    assert position_size(20.0, 4.0, 2.0, min_deal_size=0.01, size_increment=0.01) == 2.5


def test_zero_stop_distance_is_rejected():
    with pytest.raises(ValueError):
        position_size(20.0, 0.0, 1.0, min_deal_size=0.01, size_increment=0.01)


def test_floor_to_step_handles_binary_float_error():
    assert floor_to_step(0.29999999999, 0.01) == 0.3
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && .venv/bin/pytest tests/test_sizing.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'engine.sizing'`

- [ ] **Step 3: Write minimal implementation**

```python
# research/engine/engine/sizing.py
"""Position sizing (spec §3.4).

size = floor_to_step(account_risk_usd / (stop_distance * usd_value_per_point))

Always floored, never rounded up: rounding up risks more than the account allows. A size under
the instrument's minimum deal size returns 0.0, and the caller records a skipped trade rather
than quietly trading a size the broker would reject.
"""

from __future__ import annotations

from decimal import Decimal


def floor_to_step(value: float, step: float) -> float:
    if step <= 0:
        raise ValueError(f"step must be positive, got {step}")
    steps = (Decimal(str(value)) / Decimal(str(step))).to_integral_value(rounding="ROUND_FLOOR")
    return float(steps * Decimal(str(step)))


def position_size(risk_usd: float, stop_distance: float, usd_value_per_point: float,
                  min_deal_size: float, size_increment: float) -> float:
    if stop_distance <= 0:
        raise ValueError(f"stop_distance must be positive, got {stop_distance}")
    if usd_value_per_point <= 0:
        raise ValueError(f"usd_value_per_point must be positive, got {usd_value_per_point}")
    raw = risk_usd / (stop_distance * usd_value_per_point)
    size = floor_to_step(raw, size_increment)
    return 0.0 if size < min_deal_size else size
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd research/engine && .venv/bin/pytest tests/test_sizing.py -v`
Expected: PASS (6 passed)

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/sizing.py research/engine/tests/test_sizing.py
git commit -m "feat(engine): add position sizing with min-deal-size skip"
```

---

### Task 6: Financing and currency conversion

**Files:**
- Create: `research/engine/engine/costs.py`
- Test: `research/engine/tests/test_costs.py`

**Interfaces:**
- Consumes: instrument spec dicts from `engine.instruments.load_instruments`.
- Produces:
  - `charge_times(entry: pd.Timestamp, exit_time: pd.Timestamp, charge_time_utc: str) -> pd.DatetimeIndex` — every daily cut-off strictly inside the holding period.
  - `financing_usd(side, size, price, spec, charges, usd_per_unit_rate, weekend_multiplier=1.0) -> float`
  - `usd_per_point(spec: dict, fx_rate: float) -> float` — `lot_size * fx_rate`; `fx_rate` is 1.0 for USD instruments.

**Note on the weekend multiplier:** Capital.com applies a multi-day charge on one weekday to cover the weekend, but that multiplier is **not** in `instruments.yaml` and is not documented anywhere in this repo. It is therefore an explicit parameter defaulting to `1.0`, and the run summary records the value used. Do not guess a value — see Open Questions.

- [ ] **Step 1: Write the failing test**

```python
# research/engine/tests/test_costs.py
import pandas as pd

from engine.costs import charge_times, financing_usd, usd_per_point

SPEC = {
    "currency": "USD", "lot_size": 1, "min_deal_size": 0.01, "size_increment": 0.01,
    "overnight_fee": {"long_rate": -0.0215402, "short_rate": -0.000682,
                      "charge_time_utc": "21:00", "interval_minutes": 1440},
}


def test_no_charge_when_the_position_closes_before_the_cut_off():
    charges = charge_times(pd.Timestamp("2024-01-02T09:00Z"), pd.Timestamp("2024-01-02T15:00Z"), "21:00")
    assert len(charges) == 0


def test_one_charge_when_the_position_is_held_through_the_cut_off():
    charges = charge_times(pd.Timestamp("2024-01-02T20:00Z"), pd.Timestamp("2024-01-03T09:00Z"), "21:00")
    assert list(charges) == [pd.Timestamp("2024-01-02T21:00Z")]


def test_three_charges_across_a_long_hold():
    charges = charge_times(pd.Timestamp("2024-01-02T20:00Z"), pd.Timestamp("2024-01-05T09:00Z"), "21:00")
    assert len(charges) == 3


def test_long_financing_is_negative_and_scales_with_notional():
    # notional = 2 units * 5000 * 1 USD/point = 10,000; rate -0.0215402 % per charge
    fee = financing_usd("LONG", size=2.0, price=5000.0, spec=SPEC,
                        charges=pd.DatetimeIndex([pd.Timestamp("2024-01-02T21:00Z")]),
                        usd_per_unit_rate=1.0)
    assert fee == pytest.approx(-2.15402, rel=1e-6)


def test_two_charges_cost_twice_one():
    one = financing_usd("LONG", 2.0, 5000.0, SPEC,
                        pd.DatetimeIndex([pd.Timestamp("2024-01-02T21:00Z")]), 1.0)
    two = financing_usd("LONG", 2.0, 5000.0, SPEC,
                        pd.DatetimeIndex([pd.Timestamp("2024-01-02T21:00Z"),
                                          pd.Timestamp("2024-01-03T21:00Z")]), 1.0)
    assert two == pytest.approx(one * 2, rel=1e-9)


def test_short_uses_the_short_rate():
    fee = financing_usd("SHORT", 2.0, 5000.0, SPEC,
                        pd.DatetimeIndex([pd.Timestamp("2024-01-02T21:00Z")]), 1.0)
    assert fee == pytest.approx(-0.0682, rel=1e-6)


def test_weekend_multiplier_scales_a_single_charge():
    plain = financing_usd("LONG", 2.0, 5000.0, SPEC,
                          pd.DatetimeIndex([pd.Timestamp("2024-01-05T21:00Z")]), 1.0)
    tripled = financing_usd("LONG", 2.0, 5000.0, SPEC,
                            pd.DatetimeIndex([pd.Timestamp("2024-01-05T21:00Z")]), 1.0,
                            weekend_multiplier=3.0)
    assert tripled == pytest.approx(plain * 3, rel=1e-9)


def test_non_usd_instrument_converts_through_the_fx_rate():
    # a JPY instrument at 0.0067 USD/JPY: one point of one unit is worth 0.0067 USD
    jpy = dict(SPEC, currency="JPY")
    assert usd_per_point(jpy, fx_rate=0.0067) == pytest.approx(0.0067)
    assert usd_per_point(SPEC, fx_rate=1.0) == 1.0
```

Add `import pytest` at the top of the file.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && .venv/bin/pytest tests/test_costs.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'engine.costs'`

- [ ] **Step 3: Write minimal implementation**

```python
# research/engine/engine/costs.py
"""Overnight financing and currency conversion (spec §3.2, §3.4).

Capital.com quotes overnight rates as a percentage of notional per charge interval, charged at
a fixed UTC cut-off. Rates are negative for a cost. A position pays for every cut-off strictly
inside its holding period — entering after today's cut-off and exiting before tomorrow's costs
nothing.
"""

from __future__ import annotations

import pandas as pd


def usd_per_point(spec: dict, fx_rate: float) -> float:
    """Value of one point of one unit, in USD. fx_rate converts the instrument currency to USD."""
    if fx_rate <= 0:
        raise ValueError(f"fx_rate must be positive, got {fx_rate}")
    return float(spec["lot_size"]) * fx_rate


def charge_times(entry: pd.Timestamp, exit_time: pd.Timestamp, charge_time_utc: str) -> pd.DatetimeIndex:
    hour, minute = (int(part) for part in charge_time_utc.split(":"))
    first = entry.normalize() + pd.Timedelta(hours=hour, minutes=minute)
    if first <= entry:
        first = first + pd.Timedelta(days=1)
    if first >= exit_time:
        return pd.DatetimeIndex([], tz="UTC")
    return pd.date_range(first, exit_time - pd.Timedelta(minutes=1), freq="D", tz="UTC")


def financing_usd(side: str, size: float, price: float, spec: dict, charges: pd.DatetimeIndex,
                  usd_per_unit_rate: float, weekend_multiplier: float = 1.0) -> float:
    if len(charges) == 0:
        return 0.0
    fee = spec["overnight_fee"]
    rate_pct = fee["long_rate"] if side == "LONG" else fee["short_rate"]
    notional_usd = size * price * usd_per_point(spec, usd_per_unit_rate)
    return float(notional_usd * (rate_pct / 100.0) * len(charges) * weekend_multiplier)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd research/engine && .venv/bin/pytest tests/test_costs.py -v`
Expected: PASS (8 passed)

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/costs.py research/engine/tests/test_costs.py
git commit -m "feat(engine): add overnight financing and USD conversion"
```

---

### Task 7: The 1-minute simulator

**Files:**
- Create: `research/engine/engine/simulator.py`
- Test: `research/engine/tests/test_simulator.py`

**Interfaces:**
- Consumes: `engine.strategy` (`BarsView`, `PositionView`, `Enter`, `Exit`, `MoveStop`), `engine.sizing.position_size`, `engine.costs`.
- Produces:
  - `@dataclass SimConfig(starting_balance_usd=2000.0, risk_pct=1.0, slippage_ticks=0.0, tick_size=0.0, weekend_multiplier=1.0, fx_rate=1.0)`
  - `@dataclass Trade` with fields `entry_time, entry_price, exit_time, exit_price, side, size, stop, target, exit_reason, gross_usd, spread_cost_usd, financing_usd, slippage_usd, net_usd, r`.
  - `simulate(strategy, minutes: pd.DataFrame, signal_bars: pd.DataFrame, spec: dict, config: SimConfig) -> tuple[list[Trade], int]` — the `int` is `skipped_min_size`.

**Exit rules, exactly (spec §3.4):** entry at the next minute's open, ask for long / bid for short. Long stop triggers when `low_bid <= stop`, filling at `stop`, or at `open_bid` when the minute opens below the stop. Long target fills only when `high_bid > target` — trade-through, not touch. Stop and target in the same minute: **stop**. Short mirrors on the ask.

- [ ] **Step 1: Write the failing test**

```python
# research/engine/tests/test_simulator.py
import pandas as pd
import pytest

from engine.simulator import SimConfig, simulate
from engine.strategy import Enter

SPEC = {
    "currency": "USD", "lot_size": 1, "min_deal_size": 0.01, "size_increment": 0.01,
    "overnight_fee": {"long_rate": -0.0215402, "short_rate": -0.000682,
                      "charge_time_utc": "21:00", "interval_minutes": 1440},
}


def minutes_frame(rows):
    """rows: (minute, open_bid, high_bid, low_bid, close_bid); ask = bid + 0.2 throughout."""
    index = pd.to_datetime([r[0] for r in rows], utc=True)
    frame = pd.DataFrame(index=index)
    frame.index.name = "minute"
    for position, name in enumerate(["open", "high", "low", "close"], start=1):
        frame[f"{name}_bid"] = [r[position] for r in rows]
        frame[f"{name}_ask"] = [r[position] + 0.2 for r in rows]
    frame["volume"] = 1
    return frame


class EnterOnFirstBar:
    timeframe = "1min"

    def __init__(self, stop, target=None, side="LONG"):
        self._intent = Enter(side=side, stop=stop, target=target)
        self._fired = False

    def on_bar(self, history, position):
        if self._fired or position:
            return []
        self._fired = True
        return [self._intent]


def signal_bars_from(minutes):
    out = minutes.copy()
    out["minutes_present"] = 1
    out["complete"] = True
    return out


def test_target_needs_trade_through_not_touch():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 102.0, 99.8, 101.0),   # high_bid == target exactly: no fill
        ("2024-01-02T09:02:00Z", 101.0, 103.0, 100.5, 102.5),  # high_bid > target: fills
    ])
    trades, skipped = simulate(EnterOnFirstBar(stop=98.0, target=102.0),
                               minutes, signal_bars_from(minutes), SPEC, SimConfig())

    assert len(trades) == 1
    assert trades[0].exit_reason == "TARGET"
    assert trades[0].exit_time == pd.Timestamp("2024-01-02T09:02:00Z")
    assert trades[0].exit_price == 102.0


def test_stop_and_target_in_the_same_minute_resolves_to_the_stop():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 103.0, 97.0, 99.0),    # both touched
    ])
    trades, _ = simulate(EnterOnFirstBar(stop=98.0, target=102.0),
                         minutes, signal_bars_from(minutes), SPEC, SimConfig())

    assert trades[0].exit_reason == "STOP"
    assert trades[0].exit_price == 98.0


def test_gap_through_the_stop_fills_at_the_open_not_the_stop():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 95.0, 95.5, 94.0, 94.5),      # opens below the 98 stop
    ])
    trades, _ = simulate(EnterOnFirstBar(stop=98.0, target=110.0),
                         minutes, signal_bars_from(minutes), SPEC, SimConfig())

    assert trades[0].exit_reason == "STOP"
    assert trades[0].exit_price == 95.0


def test_long_enters_on_the_next_minute_ask_and_exits_on_the_bid():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:02:00Z", 100.0, 103.0, 99.9, 102.5),
    ])
    trades, _ = simulate(EnterOnFirstBar(stop=98.0, target=102.0),
                         minutes, signal_bars_from(minutes), SPEC, SimConfig())

    assert trades[0].entry_price == 100.2           # ask at 09:01 open
    assert trades[0].entry_time == pd.Timestamp("2024-01-02T09:01:00Z")
    assert trades[0].spread_cost_usd > 0


def test_trade_below_min_deal_size_is_skipped_and_counted():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 103.0, 99.0, 102.5),
    ])
    spec = dict(SPEC, min_deal_size=10_000.0)
    trades, skipped = simulate(EnterOnFirstBar(stop=98.0, target=102.0),
                               minutes, signal_bars_from(minutes), spec, SimConfig())

    assert trades == []
    assert skipped == 1


def test_position_held_through_the_cut_off_pays_financing():
    minutes = minutes_frame([
        ("2024-01-02T20:58:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T20:59:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-03T09:00:00Z", 100.0, 103.0, 99.9, 102.5),
    ])
    trades, _ = simulate(EnterOnFirstBar(stop=98.0, target=102.0),
                         minutes, signal_bars_from(minutes), SPEC, SimConfig())

    assert trades[0].financing_usd < 0
    assert trades[0].net_usd < trades[0].gross_usd


def test_r_is_net_over_the_risked_amount():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:02:00Z", 100.0, 103.0, 99.9, 102.5),
    ])
    config = SimConfig(starting_balance_usd=2000.0, risk_pct=1.0)
    trades, _ = simulate(EnterOnFirstBar(stop=98.0, target=102.0),
                         minutes, signal_bars_from(minutes), SPEC, config)

    assert trades[0].r == pytest.approx(trades[0].net_usd / 20.0, rel=1e-9)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && .venv/bin/pytest tests/test_simulator.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'engine.simulator'`

- [ ] **Step 3: Write minimal implementation**

```python
# research/engine/engine/simulator.py
"""The 1-minute bid/ask simulator (spec §3.4).

Signals are decided on resampled bars; fills are resolved on the raw minute series, always on
the side of the book the trade actually pays. The pessimistic choices are deliberate: a target
needs trade-through rather than a touch, and a minute that touches both stop and target resolves
to the stop. Optimism here is what made the old engine's results untrustworthy.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import pandas as pd

from engine.costs import charge_times, financing_usd, usd_per_point
from engine.sizing import position_size
from engine.strategy import BarsView, Enter, Exit, MoveStop, PositionView


@dataclass(frozen=True)
class SimConfig:
    starting_balance_usd: float = 2000.0
    risk_pct: float = 1.0
    slippage_ticks: float = 0.0
    tick_size: float = 0.0
    weekend_multiplier: float = 1.0
    fx_rate: float = 1.0


@dataclass
class Trade:
    entry_time: pd.Timestamp
    entry_price: float
    exit_time: pd.Timestamp
    exit_price: float
    side: str
    size: float
    stop: float
    target: float | None
    exit_reason: str
    gross_usd: float
    spread_cost_usd: float
    financing_usd: float
    slippage_usd: float
    net_usd: float
    r: float


def _slippage(config: SimConfig) -> float:
    return config.slippage_ticks * config.tick_size


def simulate(strategy, minutes: pd.DataFrame, signal_bars: pd.DataFrame, spec: dict,
             config: SimConfig) -> tuple[list[Trade], int]:
    value_per_point = usd_per_point(spec, config.fx_rate)
    slip = _slippage(config)
    trades: list[Trade] = []
    skipped = 0
    balance = config.starting_balance_usd

    minute_index = minutes.index
    open_bid = minutes["open_bid"].to_numpy()
    open_ask = minutes["open_ask"].to_numpy()
    high_bid = minutes["high_bid"].to_numpy()
    low_bid = minutes["low_bid"].to_numpy()
    high_ask = minutes["high_ask"].to_numpy()
    low_ask = minutes["low_ask"].to_numpy()

    position: dict | None = None

    for bar_index in range(len(signal_bars)):
        bar_time = signal_bars.index[bar_index]
        view = PositionView.flat() if position is None else PositionView(
            side=position["side"], entry_price=position["entry_price"], stop=position["stop"],
            target=position["target"], size=position["size"])

        for intent in strategy.on_bar(BarsView(signal_bars, bar_index), view):
            if isinstance(intent, Enter) and position is None:
                fills = minute_index.searchsorted(bar_time, side="right")
                if fills >= len(minute_index):
                    continue
                entry_price = (open_ask[fills] + slip) if intent.side == "LONG" else (open_bid[fills] - slip)
                stop_distance = abs(entry_price - intent.stop)
                if stop_distance <= 0:
                    continue
                risk_usd = balance * config.risk_pct / 100.0 * intent.risk_r
                size = position_size(risk_usd, stop_distance, value_per_point,
                                     spec["min_deal_size"], spec["size_increment"])
                if size == 0.0:
                    skipped += 1
                    continue
                position = {"side": intent.side, "entry_price": entry_price, "stop": intent.stop,
                            "target": intent.target, "size": size, "entry_index": fills,
                            "entry_time": minute_index[fills], "risk_usd": risk_usd}
            elif isinstance(intent, MoveStop) and position is not None:
                position["stop"] = intent.price
            elif isinstance(intent, Exit) and position is not None:
                fills = minute_index.searchsorted(bar_time, side="right")
                if fills < len(minute_index):
                    price = (open_bid[fills] - slip) if position["side"] == "LONG" else (open_ask[fills] + slip)
                    trades.append(_close(position, minute_index[fills], price, "SIGNAL",
                                         spec, config, value_per_point))
                    balance += trades[-1].net_usd
                    position = None

        if position is None:
            continue

        next_bar_time = signal_bars.index[bar_index + 1] if bar_index + 1 < len(signal_bars) else None
        stop_at = len(minute_index) if next_bar_time is None else minute_index.searchsorted(
            next_bar_time, side="right")
        for i in range(max(position["entry_index"], 0), stop_at):
            if minute_index[i] < position["entry_time"]:
                continue
            if position["side"] == "LONG":
                hit_stop = low_bid[i] <= position["stop"]
                hit_target = position["target"] is not None and high_bid[i] > position["target"]
                if hit_stop:
                    price = open_bid[i] if open_bid[i] < position["stop"] else position["stop"]
                    price -= slip
                    reason = "STOP"
                elif hit_target:
                    price, reason = position["target"], "TARGET"
                else:
                    continue
            else:
                hit_stop = high_ask[i] >= position["stop"]
                hit_target = position["target"] is not None and low_ask[i] < position["target"]
                if hit_stop:
                    price = open_ask[i] if open_ask[i] > position["stop"] else position["stop"]
                    price += slip
                    reason = "STOP"
                elif hit_target:
                    price, reason = position["target"], "TARGET"
                else:
                    continue
            trades.append(_close(position, minute_index[i], float(price), reason,
                                 spec, config, value_per_point))
            balance += trades[-1].net_usd
            position = None
            break

    return trades, skipped


def _close(position: dict, exit_time: pd.Timestamp, exit_price: float, reason: str,
           spec: dict, config: SimConfig, value_per_point: float) -> Trade:
    side, size = position["side"], position["size"]
    direction = 1.0 if side == "LONG" else -1.0
    gross = (exit_price - position["entry_price"]) * direction * size * value_per_point
    charges = charge_times(position["entry_time"], exit_time, spec["overnight_fee"]["charge_time_utc"])
    financing = financing_usd(side, size, position["entry_price"], spec, charges,
                              config.fx_rate, config.weekend_multiplier)
    slippage_usd = _slippage(config) * size * value_per_point * 2
    spread_cost = 0.0  # already inside gross: entry paid the ask, exit received the bid
    net = gross + financing
    return Trade(
        entry_time=position["entry_time"], entry_price=position["entry_price"],
        exit_time=exit_time, exit_price=exit_price, side=side, size=size,
        stop=position["stop"], target=position["target"], exit_reason=reason,
        gross_usd=gross, spread_cost_usd=spread_cost, financing_usd=financing,
        slippage_usd=slippage_usd, net_usd=net,
        r=net / position["risk_usd"] if position["risk_usd"] else 0.0)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd research/engine && .venv/bin/pytest tests/test_simulator.py -v`
Expected: PASS (7 passed)

**If `test_long_enters_on_the_next_minute_ask_and_exits_on_the_bid` fails on `spread_cost_usd > 0`:** the spread is currently folded into `gross_usd` and reported as `0.0`. Make it explicit instead — compute `spread_cost_usd = (entry_ask - entry_bid + exit_ask - exit_bid) * size * value_per_point` at close, and report `gross_usd` as the mid-to-mid move so that `gross - spread - financing == net`. Adjust the test's other assertions to match and keep the identity tested.

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/simulator.py research/engine/tests/test_simulator.py
git commit -m "feat(engine): add the 1-minute bid/ask simulator"
```

---

### Task 8: Run orchestration and outputs

**Files:**
- Create: `research/engine/engine/run.py`
- Test: `research/engine/tests/test_run.py`

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `bootstrap_ci(values: np.ndarray, iterations=10_000, seed=20260917) -> tuple[float, float]` — 95% percentile CI of the mean.
  - `summarise(trades, equity, config, meta) -> dict`
  - `write_run(run_dir: Path, trades, equity, summary) -> None`
  - `main(argv=None)` — CLI: `--db --epic --timeframe --out --risk-pct --balance`.

- [ ] **Step 1: Write the failing test**

```python
# research/engine/tests/test_run.py
import json

import numpy as np
import pandas as pd
import pytest

from engine.run import bootstrap_ci, summarise, write_run
from engine.simulator import SimConfig, Trade


def _trade(net, r, entry="2024-01-02T09:00:00Z", exit_="2024-01-02T09:05:00Z"):
    return Trade(entry_time=pd.Timestamp(entry), entry_price=100.0, exit_time=pd.Timestamp(exit_),
                 exit_price=101.0, side="LONG", size=1.0, stop=98.0, target=102.0,
                 exit_reason="TARGET", gross_usd=net, spread_cost_usd=0.0, financing_usd=0.0,
                 slippage_usd=0.0, net_usd=net, r=r)


def test_bootstrap_ci_brackets_the_mean():
    values = np.random.default_rng(1).normal(0.5, 1.0, 500)
    low, high = bootstrap_ci(values, iterations=2000)
    assert low < values.mean() < high


def test_summary_reports_net_expectancy_and_marks_win_rate_informational():
    trades = [_trade(10.0, 0.5), _trade(-20.0, -1.0), _trade(30.0, 1.5)]
    equity = pd.DataFrame({"time": [t.exit_time for t in trades],
                           "balance": [2010.0, 1990.0, 2020.0]})

    summary = summarise(trades, equity, SimConfig(), {"epic": "US500", "timeframe": "5min"})

    assert summary["trades"] == 3
    assert summary["net_usd"] == pytest.approx(20.0)
    assert summary["expectancy_usd"] == pytest.approx(20.0 / 3)
    assert summary["win_rate"]["informational"] is True
    assert len(summary["expectancy_usd_ci95"]) == 2
    assert summary["max_drawdown_usd"] == pytest.approx(30.0)


def test_write_run_emits_the_three_expected_files(tmp_path):
    trades = [_trade(10.0, 0.5)]
    equity = pd.DataFrame({"time": [trades[0].exit_time], "balance": [2010.0]})
    summary = summarise(trades, equity, SimConfig(), {"epic": "US500", "timeframe": "5min"})

    write_run(tmp_path / "run1", trades, equity, summary)

    assert (tmp_path / "run1" / "trades.parquet").exists()
    assert (tmp_path / "run1" / "equity.parquet").exists()
    written = json.loads((tmp_path / "run1" / "summary.json").read_text())
    assert written["epic"] == "US500"
    assert "git_commit" in written


def test_summary_of_zero_trades_does_not_divide_by_zero():
    summary = summarise([], pd.DataFrame({"time": [], "balance": []}), SimConfig(),
                        {"epic": "US500", "timeframe": "5min"})
    assert summary["trades"] == 0
    assert summary["expectancy_usd"] == 0.0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && .venv/bin/pytest tests/test_run.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'engine.run'`

- [ ] **Step 3: Write minimal implementation**

```python
# research/engine/engine/run.py
"""Run a strategy and write the run's evidence (spec §3.5).

The summary leads with net expectancy after costs and its bootstrap CI. Win rate is present
but explicitly tagged informational so no reader mistakes it for a gate (spec §0 D1).
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import subprocess
from pathlib import Path

import numpy as np
import pandas as pd

from engine.simulator import SimConfig, Trade


def _git_commit() -> str:
    try:
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True,
                              check=True).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unknown"


def bootstrap_ci(values: np.ndarray, iterations: int = 10_000, seed: int = 20260917) -> tuple[float, float]:
    values = np.asarray(values, dtype=float)
    if len(values) == 0:
        return (0.0, 0.0)
    rng = np.random.default_rng(seed)
    means = rng.choice(values, size=(iterations, len(values)), replace=True).mean(axis=1)
    return (float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5)))


def _max_drawdown(balances: np.ndarray) -> tuple[float, float]:
    if len(balances) == 0:
        return (0.0, 0.0)
    peak = np.maximum.accumulate(balances)
    drop = peak - balances
    worst = float(drop.max())
    at = int(drop.argmax())
    return (worst, float(worst / peak[at] * 100.0) if peak[at] else 0.0)


def summarise(trades: list[Trade], equity: pd.DataFrame, config: SimConfig, meta: dict) -> dict:
    nets = np.array([t.net_usd for t in trades], dtype=float)
    rs = np.array([t.r for t in trades], dtype=float)
    wins = int((nets > 0).sum())
    gross = float(sum(abs(t.gross_usd) for t in trades))
    costs = float(sum(abs(t.financing_usd) + abs(t.spread_cost_usd) + abs(t.slippage_usd) for t in trades))
    balances = equity["balance"].to_numpy(dtype=float) if len(equity) else np.array([])
    drawdown_usd, drawdown_pct = _max_drawdown(balances)
    per_period = {}
    if trades:
        frame = pd.DataFrame({"time": [t.exit_time for t in trades], "net": nets}).set_index("time")
        per_period = {
            "per_month": {str(k): float(v) for k, v in frame["net"].resample("MS").sum().items()},
            "per_quarter": {str(k): float(v) for k, v in frame["net"].resample("QS").sum().items()},
        }
    return {
        **meta,
        "git_commit": _git_commit(),
        "config": dataclasses.asdict(config),
        "trades": len(trades),
        "net_usd": float(nets.sum()) if len(nets) else 0.0,
        "expectancy_usd": float(nets.mean()) if len(nets) else 0.0,
        "expectancy_usd_ci95": list(bootstrap_ci(nets)),
        "expectancy_r": float(rs.mean()) if len(rs) else 0.0,
        "expectancy_r_ci95": list(bootstrap_ci(rs)),
        "max_drawdown_usd": drawdown_usd,
        "max_drawdown_pct": drawdown_pct,
        "cost_share_of_gross": float(costs / gross) if gross else 0.0,
        "win_rate": {"value": float(wins / len(trades)) if trades else 0.0, "informational": True},
        **per_period,
    }


def write_run(run_dir: Path, trades: list[Trade], equity: pd.DataFrame, summary: dict) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    pd.DataFrame([dataclasses.asdict(t) for t in trades]).to_parquet(run_dir / "trades.parquet")
    equity.to_parquet(run_dir / "equity.parquet")
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2, default=str))


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Run a strategy and write its evidence")
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--epic", required=True)
    parser.add_argument("--timeframe", default="5min")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--balance", type=float, default=2000.0)
    parser.add_argument("--risk-pct", type=float, default=1.0)
    args = parser.parse_args(argv)
    raise SystemExit("no strategy is wired to the CLI yet; import engine.run from a research script")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd research/engine && .venv/bin/pytest tests/test_run.py -v`
Expected: PASS (4 passed)

- [ ] **Step 5: Run the whole suite**

Run: `cd research/engine && .venv/bin/pytest -q tests`
Expected: PASS — 45 passed (18 pre-existing + 27 new)

- [ ] **Step 6: Commit**

```bash
git add research/engine/engine/run.py research/engine/tests/test_run.py
git commit -m "feat(engine): summarise runs on net expectancy with bootstrap CI"
```

---

### Task 9: End-to-end performance check on US500

**Files:**
- Create: `research/engine/tests/test_performance.py`
- Modify: `research/engine/pyproject.toml` (only if the target is missed)

**Interfaces:**
- Consumes: everything above.

**Deviation from spec, flagged:** §3.4 says "Exit loop compiled with numba; target: full 2024→now run on one instrument in under 2 minutes." This task treats the **2-minute target as the requirement** and numba as one possible means. Measure first; reach for numba only if pure numpy misses the target. Adding a compiled dependency that buys nothing is a cost with no return. If numba is needed, say so in the commit message.

- [ ] **Step 1: Write the failing test**

```python
# research/engine/tests/test_performance.py
"""End-to-end timing against the real US500 history. Skipped when the DB is absent."""

import time
from pathlib import Path

import pytest

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.instruments import load_instruments
from engine.simulator import SimConfig, simulate
from engine.strategy import Enter

DB = Path(__file__).resolve().parents[3] / "data" / "axe-trader.sqlite"
INSTRUMENTS = Path(__file__).resolve().parents[1] / "instruments.yaml"

pytestmark = pytest.mark.skipif(not DB.exists(), reason="price database not present")


class EveryTenthBar:
    timeframe = "5min"

    def on_bar(self, history, position):
        if position or len(history) % 10 or len(history) < 2:
            return []
        return [Enter(side="LONG", stop=history.close_bid[-1] - 5.0,
                      target=history.close_bid[-1] + 2.0)]


def test_full_us500_run_completes_under_two_minutes(tmp_path):
    minutes = load_cached_minutes(DB, "US500", tmp_path / "cache")
    assert len(minutes) > 900_000

    started = time.perf_counter()
    signal_bars = resample(minutes, "5min")
    spec = load_instruments(INSTRUMENTS)["US500"]
    trades, skipped = simulate(EveryTenthBar(), minutes, signal_bars, spec, SimConfig())
    elapsed = time.perf_counter() - started

    assert len(trades) > 0
    assert elapsed < 120, f"full US500 run took {elapsed:.1f}s, target is under 120s"
```

- [ ] **Step 2: Run it and record the real number**

Run: `cd research/engine && .venv/bin/pytest tests/test_performance.py -v -s`
Expected: either PASS (record the elapsed time in the commit message) or FAIL with the measured time.

- [ ] **Step 3: If and only if it missed 120s, add numba**

Add `"numba>=0.60"` to `dependencies` in `pyproject.toml`, reinstall with
`cd research/engine && .venv/bin/python -m pip install -q -e ".[dev]"`, then extract the
per-minute exit scan from `simulate` into a module-level function decorated with
`@numba.njit(cache=True)` taking plain numpy arrays (`open_bid, high_bid, low_bid, open_ask,
high_ask, low_ask, start_index, stop_index, stop, target, is_long, slip`) and returning
`(exit_index, exit_price, reason_code)` where `reason_code` is `0` none, `1` stop, `2` target.
Keep the pure-Python version beside it and assert in a test that both agree on the Task 7
scenarios.

- [ ] **Step 4: Re-run the full suite**

Run: `cd research/engine && .venv/bin/pytest -q tests`
Expected: PASS, all tests

- [ ] **Step 5: Commit**

```bash
git add research/engine/tests/test_performance.py research/engine/pyproject.toml
git commit -m "test(engine): verify a full US500 run stays inside the 2-minute budget"
```

---

## Open questions for the owner

1. **Weekend financing multiplier.** Capital.com charges a multi-day overnight fee on one weekday to cover the weekend, but the multiplier and the weekday are not in `instruments.yaml` and are not recorded anywhere in this repo. Task 6 leaves it an explicit parameter defaulting to `1.0`, which **understates holding costs for any position carried over a weekend**. Needs either a documented value per asset class or a decision to accept the understatement and note it in every summary.
2. **Spread accounting.** Task 7 currently folds the spread into `gross_usd` (entry pays the ask, exit receives the bid) and reports `spread_cost_usd` as `0.0`. The spec lists them as separate fields. Step 4 of that task gives the alternative — report gross mid-to-mid and the spread separately — which is more informative but makes `gross_usd` a synthetic number. Owner's call on which is the honest presentation.
3. **Non-USD instruments cannot be run end to end yet.** DE40 (EUR), J225 (JPY), UK100 (GBP) and USDJPY (JPY) need FX bars at the exit minute, and neither those instruments nor EURUSD/GBPUSD/USDJPY are in the database. The conversion path is built and unit-tested on synthetic rates in Task 6, but it stays unexercised on real data until those imports happen.

## Self-review

**Spec coverage:** §3.1 data layer → Tasks 1–2. §3.2 instrument specs → already built (`engine/instruments.py`), consumed in Tasks 6–7. §3.3 strategy interface → Task 3; the mandatory lookahead guard → Task 4. §3.4 simulator → Tasks 5–7 (entry, stop, target, gap, same-minute, slippage, financing, sizing, min-size skip, one position at a time), performance → Task 9. §3.5 outputs → Task 8. §3.6 tests → every listed scenario has a named test: gap through stop, stop+target same minute, target touch without trade-through, financing across the cut-off, min-size skip, non-USD conversion, incomplete-bar flagging, lookahead guard catching a leaking strategy. **Gap accepted:** "financing over a weekend" is tested only as a multiplier scaling (Task 6) because the real multiplier is unknown — open question 1.

**Placeholder scan:** no TBDs; every code step carries runnable code; the one conditional step (Task 9 numba) states its exact trigger, the exact signature to extract and the exact acceptance test.

**Type consistency:** `usd_per_point(spec, fx_rate)` is defined in Task 6 and used with that signature in Task 7. `position_size(...)` keyword names match between Tasks 5 and 7. `Trade`'s fields are defined in Task 7 and consumed by name in Task 8. `SimConfig.fx_rate` is passed as `costs.financing_usd`'s `usd_per_unit_rate` — the same quantity under two names, which is a smell but consistent; rename to `fx_rate` throughout if it causes confusion during execution.
