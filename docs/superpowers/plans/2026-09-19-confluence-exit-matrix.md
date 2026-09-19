# Confluence Exit Matrix — Implementation Plan (Part 1: engine)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the frozen confluence entry and four exit arms as runnable `Strategy` implementations in the Python research engine, with account-size-invariant reporting — so the 51-configuration matrix can be run and its trades plotted.

**Architecture:** Pure-numpy indicators computed once over the whole bar frame, wrapped in `Strategy` classes that are constructed **per truncation by a factory** (the lookahead guard requires this). Strategies emit declarative intents (`Enter`/`MoveStop`/`Exit`); the existing 1-minute bid/ask simulator resolves all fills. No simulator change is required by this plan.

**Tech Stack:** Python 3, numpy, pandas, pytest. Existing modules: `engine/{cache,bars,indicators,strategy,sizing,costs,simulator,run,diagnose}.py`.

**Spec:** `docs/superpowers/specs/2026-09-19-confluence-exit-matrix-design.md`

**Scope note — this is Part 1 of 2.** Part 1 (Tasks 1–12) delivers working, testable software: a confluence strategy that runs end-to-end through the simulator and reports in R and %. Part 2 (the 51-config matrix run, H-0006, and the trade-review page) is written *after* Part 1 lands, when the real APIs exist rather than being guessed at here.

## Global Constraints

- **Environment:** all commands run from `research/engine/` with `PYTHONPATH=.` and the venv at `.venv`. Rebuild if missing: `python3 -m venv .venv && .venv/bin/python -m pip install -e ".[dev]"`.
- **NEVER `git add -u` or `git add .` in this repo.** `data/axe-trader.sqlite.gz` is a tracked 253 MB binary that is permanently modified in the working tree. Stage explicit paths only, and run `git diff --cached --name-only` before every commit. (`mvn`/`./mvnw` also rewrite `output/charts/*.html`.)
- **Prices are mid:** every pillar, indicator and candle pattern computes on `(bid + ask) / 2`, consistent with all existing experiments. Only the simulator touches bid/ask separately.
- **Lookahead:** every strategy is passed to `check_lookahead` **as a factory (a callable taking the frame), never an instance.** A precomputed array captured in `__init__` is never truncated, so an instance-based check silently passes a leak.
- **Faithful port:** pillar definitions replicate ta4j 0.22.6 exactly, including quirks. Do not "fix" them — the entry is frozen by spec §3.
- **Development data only:** 2024-01-01 → 2026-07-31. The 2026-08-01 holdout is never touched.
- **Instruments:** US500, OIL_CRUDE, OIL_BRENT. NATURALGAS is excluded (`research/EXCLUDED-INSTRUMENTS.md`).
- **Test command:** `.venv/bin/pytest -q tests`. Full suite must stay green (85 passing at plan time).

---

## File Structure

| file | responsibility |
|---|---|
| `engine/indicators.py` (modify) | add `wilder_mma`, `true_range`, `atr`, `highest`, `lowest`, `plus_di`, `minus_di`, `adx`, `up_trend`, `down_trend` |
| `engine/candles.py` (create) | the six ta4j candle patterns as boolean arrays |
| `engine/pillars.py` (create) | the four pillar votes, the confluence score, and fire-rate reporting |
| `engine/strategies/__init__.py` (create) | package marker |
| `engine/strategies/confluence.py` (create) | frozen entry + the four exit arms |
| `engine/run.py` (modify) | R/% reporting, skipped, per-side split, risk measures, CLI |
| `tests/test_indicators.py` (modify) | new indicator tests |
| `tests/test_candles.py` (create) | pattern tests on hand-built bars |
| `tests/test_pillars.py` (create) | pillar and score tests |
| `tests/test_confluence_strategies.py` (create) | entry + each exit arm's intents |
| `tests/test_run.py` (modify) | reporting tests |
| `research/experiments/2026-09-20-confluence-layer1.py` (create) | H-0005 |
| `research/ledger/H-0005-confluence-entry-signal.md` (create) | H-0005 write-up |

---

### Task 1: Wilder smoothing, true range, ATR and rolling extremes

**Files:**
- Modify: `research/engine/engine/indicators.py`
- Test: `research/engine/tests/test_indicators.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `wilder_mma(values: np.ndarray, period: int) -> np.ndarray`, `true_range(high, low, close) -> np.ndarray`, `atr(high, low, close, period: int) -> np.ndarray`, `highest(values, period) -> np.ndarray`, `lowest(values, period) -> np.ndarray`. All return float arrays the same length as the input.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_indicators.py`:

```python
import numpy as np
from engine.indicators import wilder_mma, true_range, atr, highest, lowest


def test_wilder_mma_seeds_on_the_first_value_like_ta4j():
    # ta4j's MMAIndicator seeds with the first value, then smooths recursively.
    values = np.array([10.0, 20.0, 30.0])
    out = wilder_mma(values, 2)
    assert out[0] == 10.0
    assert out[1] == (10.0 * 1 + 20.0) / 2       # 15.0
    assert out[2] == (15.0 * 1 + 30.0) / 2       # 22.5


def test_true_range_takes_the_largest_of_the_three_ranges():
    high = np.array([10.0, 12.0])
    low = np.array([9.0, 11.0])
    close = np.array([9.5, 11.5])
    out = true_range(high, low, close)
    assert out[0] == 1.0                  # first bar: high - low
    assert out[1] == 12.0 - 9.5           # gap up: high - previous close


def test_atr_is_wilder_smoothed_true_range():
    high = np.array([10.0, 12.0, 13.0])
    low = np.array([9.0, 11.0, 12.0])
    close = np.array([9.5, 11.5, 12.5])
    assert np.allclose(atr(high, low, close, 2), wilder_mma(true_range(high, low, close), 2))


def test_highest_and_lowest_include_the_current_bar():
    # This is ta4j's behaviour and pillar 3 depends on it: at a new extreme the
    # distance to the level is zero, so "near support" is trivially true.
    values = np.array([5.0, 3.0, 4.0, 2.0])
    assert list(highest(values, 2)) == [5.0, 5.0, 4.0, 4.0]
    assert list(lowest(values, 2)) == [5.0, 3.0, 3.0, 2.0]
    assert lowest(values, 3)[3] == 2.0
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_indicators.py -q -k "wilder or true_range or atr or highest"`
Expected: FAIL with `ImportError: cannot import name 'wilder_mma'`

- [ ] **Step 3: Implement**

Append to `engine/indicators.py`:

```python
def wilder_mma(values: np.ndarray, period: int) -> np.ndarray:
    """Wilder's modified moving average, seeded on the first value.

    This is ta4j's MMAIndicator, not a simple mean of the first `period` values. The seeding
    difference washes out after a few dozen bars but is replicated so the port matches.
    """
    values = np.asarray(values, dtype=float)
    out = np.full(len(values), np.nan)
    if len(values) == 0:
        return out
    out[0] = values[0]
    for i in range(1, len(values)):
        out[i] = (out[i - 1] * (period - 1) + values[i]) / period
    return out


def true_range(high: np.ndarray, low: np.ndarray, close: np.ndarray) -> np.ndarray:
    high = np.asarray(high, dtype=float)
    low = np.asarray(low, dtype=float)
    close = np.asarray(close, dtype=float)
    previous = np.empty_like(close)
    previous[0] = close[0]
    previous[1:] = close[:-1]
    return np.maximum(high - low, np.maximum(np.abs(high - previous), np.abs(low - previous)))


def atr(high: np.ndarray, low: np.ndarray, close: np.ndarray, period: int) -> np.ndarray:
    return wilder_mma(true_range(high, low, close), period)


def _rolling(values: np.ndarray, period: int, reducer) -> np.ndarray:
    values = np.asarray(values, dtype=float)
    out = np.full(len(values), np.nan)
    for i in range(len(values)):
        out[i] = reducer(values[max(0, i - period + 1): i + 1])
    return out


def highest(values: np.ndarray, period: int) -> np.ndarray:
    """Rolling maximum INCLUDING the current bar, as ta4j's HighestValueIndicator does."""
    return _rolling(values, period, np.max)


def lowest(values: np.ndarray, period: int) -> np.ndarray:
    """Rolling minimum INCLUDING the current bar, as ta4j's LowestValueIndicator does."""
    return _rolling(values, period, np.min)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_indicators.py -q`
Expected: PASS, all tests in the file

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/indicators.py research/engine/tests/test_indicators.py
git diff --cached --name-only   # MUST show exactly these two files
git commit -m "feat(engine): add Wilder smoothing, ATR and inclusive rolling extremes"
```

---

### Task 2: Directional movement, ADX and the trend flags

**Files:**
- Modify: `research/engine/engine/indicators.py`
- Test: `research/engine/tests/test_indicators.py`

**Interfaces:**
- Consumes: `wilder_mma`, `atr` (Task 1).
- Produces: `plus_di(high, low, close, period) -> np.ndarray`, `minus_di(high, low, close, period) -> np.ndarray`, `adx(high, low, close, period) -> np.ndarray`, `up_trend(high, low, close, period=5, threshold=25.0) -> np.ndarray` (bool), `down_trend(high, low, close, period=5, threshold=25.0) -> np.ndarray` (bool).

**Why this exists:** ta4j's `HammerIndicator` and `ShootingStarIndicator` are not shape-only — each requires an ADX trend filter. Without this task, pillar 2 would be a different strategy (spec §3.2).

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_indicators.py`:

```python
from engine.indicators import plus_di, minus_di, adx, up_trend, down_trend


def _ramp(n: int, step: float):
    """A clean one-directional series: rising for step > 0, falling for step < 0."""
    base = 100.0 + step * np.arange(n, dtype=float)
    return base + 1.0, base - 1.0, base      # high, low, close


def test_plus_di_dominates_minus_di_in_a_rising_market():
    high, low, close = _ramp(60, 1.0)
    assert plus_di(high, low, close, 5)[-1] > minus_di(high, low, close, 5)[-1]


def test_minus_di_dominates_plus_di_in_a_falling_market():
    high, low, close = _ramp(60, -1.0)
    assert minus_di(high, low, close, 5)[-1] > plus_di(high, low, close, 5)[-1]


def test_adx_is_high_in_a_clean_trend():
    high, low, close = _ramp(60, 1.0)
    assert adx(high, low, close, 5)[-1] > 25.0


def test_trend_flags_pick_the_right_direction():
    up_h, up_l, up_c = _ramp(60, 1.0)
    down_h, down_l, down_c = _ramp(60, -1.0)
    assert up_trend(up_h, up_l, up_c)[-1]
    assert not down_trend(up_h, up_l, up_c)[-1]
    assert down_trend(down_h, down_l, down_c)[-1]
    assert not up_trend(down_h, down_l, down_c)[-1]


def test_trend_flags_are_false_during_warmup():
    high, low, close = _ramp(60, 1.0)
    assert not up_trend(high, low, close)[:20].any()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_indicators.py -q -k "di or adx or trend"`
Expected: FAIL with `ImportError: cannot import name 'plus_di'`

- [ ] **Step 3: Implement**

Append to `engine/indicators.py`:

```python
def _directional_movement(high: np.ndarray, low: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    high = np.asarray(high, dtype=float)
    low = np.asarray(low, dtype=float)
    up = np.zeros(len(high))
    down = np.zeros(len(high))
    up_move = high[1:] - high[:-1]
    down_move = low[:-1] - low[1:]
    up[1:] = np.where((up_move > down_move) & (up_move > 0), up_move, 0.0)
    down[1:] = np.where((down_move > up_move) & (down_move > 0), down_move, 0.0)
    return up, down


def plus_di(high, low, close, period: int) -> np.ndarray:
    up, _ = _directional_movement(high, low)
    with np.errstate(divide="ignore", invalid="ignore"):
        return 100.0 * wilder_mma(up, period) / atr(high, low, close, period)


def minus_di(high, low, close, period: int) -> np.ndarray:
    _, down = _directional_movement(high, low)
    with np.errstate(divide="ignore", invalid="ignore"):
        return 100.0 * wilder_mma(down, period) / atr(high, low, close, period)


def adx(high, low, close, period: int) -> np.ndarray:
    plus = plus_di(high, low, close, period)
    minus = minus_di(high, low, close, period)
    with np.errstate(divide="ignore", invalid="ignore"):
        dx = 100.0 * np.abs(plus - minus) / (plus + minus)
    return wilder_mma(np.nan_to_num(dx, nan=0.0, posinf=0.0, neginf=0.0), period)


def _trend(high, low, close, period: int, threshold: float, rising: bool) -> np.ndarray:
    """ta4j's Up/DownTrendIndicator: strong ADX now, direction decided at the PREVIOUS bar.

    Warm-up is 4 * period bars (DI smoothing then ADX smoothing). The exact ta4j unstable-bar
    count differs by a few bars; over a ~5,900-bar 4h series that is immaterial, and the
    strategy's own 600-bar warm-up dominates it either way.
    """
    strength = adx(high, low, close, period)
    plus = plus_di(high, low, close, period)
    minus = minus_di(high, low, close, period)
    out = np.zeros(len(strength), dtype=bool)
    leader, follower = (plus, minus) if rising else (minus, plus)
    warmup = 4 * period
    for i in range(max(warmup, 1), len(out)):
        out[i] = bool(strength[i] > threshold and leader[i - 1] > follower[i - 1])
    return out


def up_trend(high, low, close, period: int = 5, threshold: float = 25.0) -> np.ndarray:
    return _trend(high, low, close, period, threshold, rising=True)


def down_trend(high, low, close, period: int = 5, threshold: float = 25.0) -> np.ndarray:
    return _trend(high, low, close, period, threshold, rising=False)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_indicators.py -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/indicators.py research/engine/tests/test_indicators.py
git diff --cached --name-only
git commit -m "feat(engine): add directional movement, ADX and the trend flags hammer depends on"
```

---

### Task 3: The six candle patterns

**Files:**
- Create: `research/engine/engine/candles.py`
- Test: `research/engine/tests/test_candles.py`

**Interfaces:**
- Consumes: `up_trend`, `down_trend` (Task 2).
- Produces: `bullish_engulfing(o, h, l, c)`, `bearish_engulfing(o, h, l, c)`, `bullish_harami(o, h, l, c)`, `bearish_harami(o, h, l, c)`, `hammer(o, h, l, c)`, `shooting_star(o, h, l, c)` — each `-> np.ndarray` of bool, same length as input. Argument order is always `(open, high, low, close)`.

- [ ] **Step 1: Write the failing test**

Create `tests/test_candles.py`:

```python
import numpy as np
from engine.candles import (bullish_engulfing, bearish_engulfing, bullish_harami,
                            bearish_harami, hammer, shooting_star)


def bars(rows):
    """rows are (open, high, low, close) tuples."""
    a = np.array(rows, dtype=float)
    return a[:, 0], a[:, 1], a[:, 2], a[:, 3]


def test_bullish_engulfing_needs_the_current_body_outside_the_previous_one():
    o, h, l, c = bars([(10.0, 10.5, 8.5, 9.0),    # bearish
                       (8.5, 11.5, 8.4, 11.0)])   # bullish, opens below and closes above
    assert not bullish_engulfing(o, h, l, c)[0]   # first bar can never be a 2-candle pattern
    assert bullish_engulfing(o, h, l, c)[1]


def test_bullish_engulfing_rejects_a_body_that_does_not_clear_the_previous_open():
    o, h, l, c = bars([(10.0, 10.5, 8.5, 9.0),
                       (8.5, 10.0, 8.4, 9.8)])    # closes below the previous open
    assert not bullish_engulfing(o, h, l, c)[1]


def test_bearish_engulfing_mirrors_it():
    o, h, l, c = bars([(9.0, 10.5, 8.5, 10.0),    # bullish
                       (10.5, 10.6, 8.0, 8.5)])   # bearish, opens above and closes below
    assert bearish_engulfing(o, h, l, c)[1]


def test_bullish_harami_needs_the_current_body_inside_the_previous_one():
    o, h, l, c = bars([(11.0, 11.2, 8.8, 9.0),    # bearish, wide
                       (9.5, 10.6, 9.4, 10.5)])   # bullish, contained
    assert bullish_harami(o, h, l, c)[1]


def test_bearish_harami_mirrors_it():
    o, h, l, c = bars([(9.0, 11.2, 8.8, 11.0),    # bullish, wide
                       (10.5, 10.6, 9.4, 9.5)])   # bearish, contained
    assert bearish_harami(o, h, l, c)[1]


def test_hammer_requires_a_long_lower_wick_and_a_downtrend():
    # 40 falling bars establish the ADX downtrend, then the hammer shape.
    base = [(100.0 - i, 101.0 - i, 99.0 - i, 99.5 - i) for i in range(40)]
    base.append((60.0, 60.2, 54.0, 60.1))   # body 0.1, lower wick 6.0, upper wick 0.1
    o, h, l, c = bars(base)
    assert hammer(o, h, l, c)[-1]


def test_hammer_is_false_without_the_trend_even_with_the_shape():
    flat = [(100.0, 100.5, 99.5, 100.2) for _ in range(40)]
    flat.append((100.0, 100.2, 94.0, 100.1))
    o, h, l, c = bars(flat)
    assert not hammer(o, h, l, c)[-1]


def test_shooting_star_requires_a_long_upper_wick_and_an_uptrend():
    base = [(100.0 + i, 101.0 + i, 99.0 + i, 100.5 + i) for i in range(40)]
    base.append((140.0, 146.0, 139.9, 140.1))   # body 0.1, upper wick 5.9, lower wick 0.1
    o, h, l, c = bars(base)
    assert shooting_star(o, h, l, c)[-1]


def test_a_doji_is_never_a_hammer_or_a_shooting_star():
    # Zero body height would divide by zero in the wick ratios.
    base = [(100.0 - i, 101.0 - i, 99.0 - i, 99.5 - i) for i in range(40)]
    base.append((60.0, 60.2, 54.0, 60.0))   # open == close
    o, h, l, c = bars(base)
    assert not hammer(o, h, l, c)[-1]
    assert not shooting_star(o, h, l, c)[-1]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_candles.py -q`
Expected: FAIL with `ModuleNotFoundError: No module named 'engine.candles'`

- [ ] **Step 3: Implement**

Create `engine/candles.py`:

```python
"""The six ta4j 0.22.6 candle patterns pillar 2 votes on, ported exactly.

Ported from the ta4j sources rather than from the config, because two of them are not what
their names suggest: `hammer` and `shooting_star` each require an ADX-based trend filter on top
of the wick-ratio test. A shape-only port would be a different strategy (spec 3.2).

All six take (open, high, low, close) as mid prices and return boolean arrays.
"""

from __future__ import annotations

import numpy as np

from engine.indicators import down_trend, up_trend

HAMMER_BODY_TO_BOTTOM_WICK = 2.0
HAMMER_BODY_TO_UPPER_WICK = 1.0
STAR_BODY_TO_BOTTOM_WICK = 1.0
STAR_BODY_TO_UPPER_WICK = 2.0


def _previous(values: np.ndarray) -> np.ndarray:
    out = np.empty_like(values)
    out[0] = values[0]
    out[1:] = values[:-1]
    return out


def _two_bar(open_, close, bullish_first: bool, inside: bool) -> np.ndarray:
    """Engulfing and harami share a shape: previous body one way, current the other.

    `inside` picks harami (current body within the previous one) over engulfing (outside it).
    """
    open_ = np.asarray(open_, dtype=float)
    close = np.asarray(close, dtype=float)
    prev_open, prev_close = _previous(open_), _previous(close)

    if bullish_first:
        direction_ok = (prev_close > prev_open) & (close < open_)     # prev bullish, curr bearish
        if inside:      # bearish harami
            body_ok = (open_ > prev_open) & (open_ < prev_close) & (close > prev_open) & (close < prev_close)
        else:           # bearish engulfing
            body_ok = (open_ > prev_open) & (open_ > prev_close) & (close < prev_open) & (close < prev_close)
    else:
        direction_ok = (prev_close < prev_open) & (close > open_)     # prev bearish, curr bullish
        if inside:      # bullish harami
            body_ok = (open_ < prev_open) & (open_ > prev_close) & (close < prev_open) & (close > prev_close)
        else:           # bullish engulfing
            body_ok = (open_ < prev_open) & (open_ < prev_close) & (close > prev_open) & (close > prev_close)

    out = direction_ok & body_ok
    out[0] = False      # a two-candle pattern cannot exist on the first bar
    return out


def bullish_engulfing(open_, high, low, close) -> np.ndarray:
    return _two_bar(open_, close, bullish_first=False, inside=False)


def bearish_engulfing(open_, high, low, close) -> np.ndarray:
    return _two_bar(open_, close, bullish_first=True, inside=False)


def bullish_harami(open_, high, low, close) -> np.ndarray:
    return _two_bar(open_, close, bullish_first=False, inside=True)


def bearish_harami(open_, high, low, close) -> np.ndarray:
    return _two_bar(open_, close, bullish_first=True, inside=True)


def _wick_ratios(open_, high, low, close):
    open_ = np.asarray(open_, dtype=float)
    high = np.asarray(high, dtype=float)
    low = np.asarray(low, dtype=float)
    close = np.asarray(close, dtype=float)
    body = np.abs(close - open_)
    upper_boundary = np.maximum(open_, close)
    lower_boundary = np.minimum(open_, close)
    with np.errstate(divide="ignore", invalid="ignore"):
        bottom = (lower_boundary - low) / body
        upper = (high - upper_boundary) / body
    # A doji has zero body: the ratios are non-finite and the pattern is defined as false.
    finite = body > 0
    return bottom, upper, finite


def hammer(open_, high, low, close) -> np.ndarray:
    bottom, upper, finite = _wick_ratios(open_, high, low, close)
    shape = finite & (bottom > HAMMER_BODY_TO_BOTTOM_WICK) & (upper <= HAMMER_BODY_TO_UPPER_WICK)
    return shape & down_trend(high, low, close)


def shooting_star(open_, high, low, close) -> np.ndarray:
    bottom, upper, finite = _wick_ratios(open_, high, low, close)
    shape = finite & (upper > STAR_BODY_TO_UPPER_WICK) & (bottom <= STAR_BODY_TO_BOTTOM_WICK)
    return shape & up_trend(high, low, close)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_candles.py -q`
Expected: PASS, 9 tests

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/candles.py research/engine/tests/test_candles.py
git diff --cached --name-only
git commit -m "feat(engine): port the six ta4j candle patterns, ADX trend filter included"
```

---

### Task 4: The four pillars and the confluence score

**Files:**
- Create: `research/engine/engine/pillars.py`
- Test: `research/engine/tests/test_pillars.py`

**Interfaces:**
- Consumes: `rsi`, `bollinger`, `ema`, `sma`, `atr`, `highest`, `lowest` (indicators); all six patterns (Task 3).
- Produces:
  - `PillarConfig` dataclass with fields `rsi_period=7, rsi_oversold=25.0, rsi_overbought=75.0, bb_period=20, bb_multiplier=2.0, ema_period=50, trend_ema_period=200, atr_period=14, proximity_atr_multiple=0.5, swing_lookback_bars=10, volume_sma_period=20, confluence_threshold=3`.
  - `PillarVotes` dataclass with fields `names: list[str]`, `bullish: dict[str, np.ndarray]`, `bearish: dict[str, np.ndarray]`, `bullish_score: np.ndarray`, `bearish_score: np.ndarray`, `long_gate: np.ndarray`, `short_gate: np.ndarray`.
  - `compute_pillars(frame: pd.DataFrame, config: PillarConfig) -> PillarVotes`.
  - `fire_rates(votes: PillarVotes, warmup: int) -> dict` → `{"bullish": {name: rate}, "bearish": {...}, "bullish_score_histogram": {...}, "bearish_score_histogram": {...}}`.

- [ ] **Step 1: Write the failing test**

Create `tests/test_pillars.py`:

```python
import numpy as np
import pandas as pd
from engine.pillars import PillarConfig, compute_pillars, fire_rates


def frame(n=400, seed=7):
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


def test_four_pillars_are_present_and_structure_is_absent():
    votes = compute_pillars(frame(), PillarConfig())
    assert votes.names == ["RSI+BB", "Candle", "S/R", "Vol+Trend"]


def test_scores_are_the_count_of_voting_pillars():
    votes = compute_pillars(frame(), PillarConfig())
    stacked = np.vstack([votes.bullish[name] for name in votes.names]).sum(axis=0)
    assert np.array_equal(votes.bullish_score, stacked)


def test_scores_never_exceed_the_pillar_count():
    votes = compute_pillars(frame(), PillarConfig())
    assert votes.bullish_score.max() <= 4
    assert votes.bearish_score.min() >= 0


def test_support_resistance_fires_at_every_new_extreme():
    # ta4j's lowest() includes the current bar, so at a new low the distance is zero and
    # "near support" is trivially true. Replicated deliberately (spec 3.2) and pinned here.
    falling = frame()
    close = 200.0 - np.arange(len(falling), dtype=float)
    for column, offset in (("close_bid", -0.01), ("close_ask", 0.01),
                           ("low_bid", -0.61), ("low_ask", -0.59),
                           ("high_bid", 0.59), ("high_ask", 0.61)):
        falling[column] = close + offset
    votes = compute_pillars(falling, PillarConfig())
    assert votes.bullish["S/R"][300:].all()


def test_fire_rates_report_each_pillar_and_the_score_histogram():
    votes = compute_pillars(frame(), PillarConfig())
    rates = fire_rates(votes, warmup=250)
    assert set(rates["bullish"]) == set(votes.names)
    assert all(0.0 <= v <= 1.0 for v in rates["bullish"].values())
    assert sum(rates["bullish_score_histogram"].values()) == len(votes.bullish_score) - 250
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_pillars.py -q`
Expected: FAIL with `ModuleNotFoundError: No module named 'engine.pillars'`

- [ ] **Step 3: Implement**

Create `engine/pillars.py`:

```python
"""The 4-pillar confluence vote, ported from the archived Java StrategyFactory.

FROZEN. Do not re-tune and do not "fix" the quirks — the entry is held fixed so that every
difference between exit arms is attributable to the exit (spec 1, 3).

Two replicated quirks, both deliberate (spec 3.2):
  * pillar 3's support/resistance levels come from `lowest`/`highest` INCLUDING the current
    bar, so it votes at every new extreme;
  * pillar 3 uses the extremes of CLOSE, not of high/low.
`fire_rates` exists so an always-on pillar is visible rather than assumed to contribute.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from engine.candles import (bearish_engulfing, bearish_harami, bullish_engulfing,
                            bullish_harami, hammer, shooting_star)
from engine.indicators import atr, bollinger, ema, highest, lowest, rsi, sma

PILLAR_NAMES = ["RSI+BB", "Candle", "S/R", "Vol+Trend"]


@dataclass(frozen=True)
class PillarConfig:
    rsi_period: int = 7
    rsi_oversold: float = 25.0
    rsi_overbought: float = 75.0
    bb_period: int = 20
    bb_multiplier: float = 2.0
    ema_period: int = 50
    trend_ema_period: int = 200
    atr_period: int = 14
    proximity_atr_multiple: float = 0.5
    swing_lookback_bars: int = 10
    volume_sma_period: int = 20
    confluence_threshold: int = 3


@dataclass(frozen=True)
class PillarVotes:
    names: list[str]
    bullish: dict[str, np.ndarray]
    bearish: dict[str, np.ndarray]
    bullish_score: np.ndarray
    bearish_score: np.ndarray
    long_gate: np.ndarray
    short_gate: np.ndarray
    atr: np.ndarray = field(default_factory=lambda: np.array([]))


def compute_pillars(frame: pd.DataFrame, config: PillarConfig) -> PillarVotes:
    mid_open = ((frame["open_bid"] + frame["open_ask"]) / 2.0).to_numpy()
    mid_high = ((frame["high_bid"] + frame["high_ask"]) / 2.0).to_numpy()
    mid_low = ((frame["low_bid"] + frame["low_ask"]) / 2.0).to_numpy()
    mid_close = ((frame["close_bid"] + frame["close_ask"]) / 2.0).to_numpy()
    volume = frame["volume"].to_numpy(dtype=float)

    rsi_values = rsi(mid_close, config.rsi_period)
    _, band_upper, band_lower = bollinger(mid_close, config.bb_period, config.bb_multiplier)
    fast_ema = ema(mid_close, config.ema_period)
    trend_ema = ema(mid_close, config.trend_ema_period)
    atr_values = atr(mid_high, mid_low, mid_close, config.atr_period)
    volume_baseline = sma(volume, config.volume_sma_period)

    proximity = atr_values * config.proximity_atr_multiple
    support = lowest(mid_close, config.swing_lookback_bars)
    resistance = highest(mid_close, config.swing_lookback_bars)
    high_volume = volume > volume_baseline

    bullish = {
        "RSI+BB": (rsi_values < config.rsi_oversold) & (mid_close <= band_lower),
        "Candle": (bullish_engulfing(mid_open, mid_high, mid_low, mid_close)
                   | bullish_harami(mid_open, mid_high, mid_low, mid_close)
                   | hammer(mid_open, mid_high, mid_low, mid_close)),
        "S/R": np.abs(mid_close - support) < proximity,
        "Vol+Trend": high_volume & (mid_close > fast_ema),
    }
    bearish = {
        "RSI+BB": (rsi_values > config.rsi_overbought) & (mid_close >= band_upper),
        "Candle": (bearish_engulfing(mid_open, mid_high, mid_low, mid_close)
                   | bearish_harami(mid_open, mid_high, mid_low, mid_close)
                   | shooting_star(mid_open, mid_high, mid_low, mid_close)),
        "S/R": np.abs(mid_close - resistance) < proximity,
        "Vol+Trend": high_volume & (mid_close < fast_ema),
    }
    for votes in (bullish, bearish):
        for name, value in votes.items():
            votes[name] = np.nan_to_num(value, nan=0.0).astype(bool)

    return PillarVotes(
        names=list(PILLAR_NAMES),
        bullish=bullish,
        bearish=bearish,
        bullish_score=np.vstack([bullish[n] for n in PILLAR_NAMES]).sum(axis=0),
        bearish_score=np.vstack([bearish[n] for n in PILLAR_NAMES]).sum(axis=0),
        long_gate=np.nan_to_num(mid_close > trend_ema, nan=0.0).astype(bool),
        short_gate=np.nan_to_num(mid_close < trend_ema, nan=0.0).astype(bool),
        atr=atr_values,
    )


def fire_rates(votes: PillarVotes, warmup: int) -> dict:
    """How often each pillar votes, and the distribution of the confluence score.

    A pillar firing on nearly every bar contributes nothing to a threshold; this is what makes
    that visible instead of assumed.
    """
    out: dict = {"bullish": {}, "bearish": {}}
    for side, table in (("bullish", votes.bullish), ("bearish", votes.bearish)):
        for name in votes.names:
            out[side][name] = float(table[name][warmup:].mean())
    for side, score in (("bullish", votes.bullish_score), ("bearish", votes.bearish_score)):
        out[f"{side}_score_histogram"] = {int(k): int(v) for k, v in
                                          sorted(Counter(score[warmup:].tolist()).items())}
    return out
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_pillars.py -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/pillars.py research/engine/tests/test_pillars.py
git diff --cached --name-only
git commit -m "feat(engine): port the 4-pillar confluence vote with fire-rate instrumentation"
```

---

### Task 5: H-0005 — layer-1 signal test of the confluence entry

**Files:**
- Create: `research/experiments/2026-09-20-confluence-layer1.py`
- Create: `research/ledger/H-0005-confluence-entry-signal.md`
- Modify: `research/ledger/INDEX.md`

**Interfaces:**
- Consumes: `compute_pillars`, `PillarConfig`, `fire_rates` (Task 4); `engine.diagnose.forward_returns`, `engine.diagnose.percentile_of`, `engine.diagnose.trades_for_power`; `engine.cache.load_cached_minutes`; `engine.bars.resample`.
- Produces: `research/experiments/2026-09-20-confluence-layer1.json` — used by Task 13 (Part 2) for the review page.

**This task has no unit test.** It is a research run, not library code; its correctness is the pre-registered criteria in the ledger entry and the placebo comparison inside it. The library code it calls is tested in Tasks 1–4.

- [ ] **Step 1: Write the ledger entry BEFORE the run**

Create `research/ledger/H-0005-confluence-entry-signal.md`:

```markdown
---
id: H-0005
title: The 4-pillar confluence entry predicts forward returns
source: prior-lead
instruments: [US500, OIL_CRUDE, OIL_BRENT]
timeframe: 4h
status: proposed
created: 2026-09-20
parent: null
trial_count: 15
---

## The idea, in plain language

The archived engine's 4-pillar confluence at threshold 3 has never been tested as a whole.
[[H-0001-rsi-bb-mean-reversion]] tested pillar 1 alone and found it *anti-predictive*, but
3-of-4 means pillar 1 need not fire, so the confluence's entry set is not a subset of H-0001's.

This is a layer-1 test: exit-free, cost-free, forward returns from confluence entry bars against
matched random entries. Spread is layer 2's question and exits are layer 3's.

## Exact rule

Entry bars are those where the confluence score for a side is >= 3 of 4 enabled pillars AND the
EMA(200) trend gate permits that side. Pillars and parameters exactly as
`docs/superpowers/specs/2026-09-19-confluence-exit-matrix-design.md` 3.1.

Horizons in 4h bars: 1, 2, 3, 6, 12. Both sides, three instruments = 15 cells, the trial count.
Development data only, 2024-01-01 -> 2026-07-31. Placebo: `shifted_placebo_entries`, 200
whole-week shifts, preserving entry clustering and weekday/hour.

## Pass/fail criteria, set before the run

**SIGNAL PRESENT** requires, for at least one instrument and side:
1. >= 200 entry bars (below that the cell is `inconclusive`, layer 0).
2. Mean forward return in the trade's direction is positive at >= 3 of the 5 horizons.
3. The percentile against the placebo distribution is >= 95 at the best of those horizons.

**FAIL** -> `rejected: no signal`.

**This gate does not stop the build** (spec D4). Its purpose is to fix how the exit matrix is
read: with signal, arm differences are about exits; without it, every arm loses and the chart
shows what a dead entry looks like under four exit regimes.

## Runs

_(appended after the run)_
```

- [ ] **Step 2: Write the experiment script**

Create `research/experiments/2026-09-20-confluence-layer1.py`:

```python
"""H-0005: does the 4-pillar confluence entry predict anything, before any exit exists?

Criteria are pre-registered in research/ledger/H-0005-confluence-entry-signal.md. Layer 1 is
deliberately cost-free and exit-free: a signal with no edge before costs has none after them.

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-20-confluence-layer1.py
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.diagnose import forward_returns, percentile_of
from engine.pillars import PillarConfig, compute_pillars, fire_rates

ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data" / "axe-trader.sqlite"
CACHE = ROOT / "research" / "engine" / ".cache"
OUT = Path(__file__).with_suffix(".json")

EPICS = ["US500", "OIL_CRUDE", "OIL_BRENT"]
TIMEFRAME = "4h"
DEV_START = pd.Timestamp("2024-01-01T00:00:00Z")
DEV_END = pd.Timestamp("2026-07-31T23:59:00Z")
HORIZONS = [1, 2, 3, 6, 12]
PLACEBO_SETS = 200
SHIFT_WEEKS = 26
MIN_ENTRIES = 200
WARMUP = 600


def main() -> None:
    rng = np.random.default_rng(20260920)
    shifts = rng.choice(np.concatenate([np.arange(-SHIFT_WEEKS, 0), np.arange(1, SHIFT_WEEKS + 1)]),
                        size=PLACEBO_SETS, replace=True)
    week = np.timedelta64(7 * 24 * 60, "m")
    results = {}

    for epic in EPICS:
        bars = resample(load_cached_minutes(DB, epic, CACHE), TIMEFRAME)
        bars = bars[(bars.index >= DEV_START) & (bars.index <= DEV_END)]
        votes = compute_pillars(bars, PillarConfig())
        mid_close = ((bars["close_bid"] + bars["close_ask"]) / 2.0).to_numpy()

        warm = np.zeros(len(bars), dtype=bool)
        warm[WARMUP:] = True
        times = bars.index.to_numpy().astype("datetime64[m]")
        order = np.argsort(times)
        sorted_times = times[order]

        entry = {"LONG": warm & (votes.bullish_score >= 3) & votes.long_gate,
                 "SHORT": warm & (votes.bearish_score >= 3) & votes.short_gate}

        results[epic] = {"bars": int(len(bars)), "fire_rates": fire_rates(votes, WARMUP), "sides": {}}
        print(f"\n=== {epic} {TIMEFRAME} {bars.index[0].date()} -> {bars.index[-1].date()} "
              f"({len(bars):,} bars) ===")
        for name, rate in results[epic]["fire_rates"]["bullish"].items():
            print(f"  pillar {name:<10} bullish fires {rate:6.1%}  "
                  f"bearish {results[epic]['fire_rates']['bearish'][name]:6.1%}")

        for side, mask in entry.items():
            entries = np.flatnonzero(mask)
            record = {"entries": int(len(entries)), "horizons": {}}
            if len(entries) < MIN_ENTRIES:
                record["status"] = "inconclusive"
                record["reason"] = f"under {MIN_ENTRIES} entries"
                results[epic]["sides"][side] = record
                print(f"  {side}: {len(entries)} entries -> inconclusive")
                continue

            sign = 1.0 if side == "LONG" else -1.0
            for horizon in HORIZONS:
                real = sign * forward_returns(mid_close, entries, horizon)
                placebo = np.full(PLACEBO_SETS, np.nan)
                for i, weeks in enumerate(shifts):
                    wanted = times[entries] + weeks * week
                    found = np.clip(np.searchsorted(sorted_times, wanted), 0, len(sorted_times) - 1)
                    exact = sorted_times[found] == wanted
                    shifted = order[found[exact]]
                    if len(shifted) < 0.8 * len(entries):
                        continue
                    placebo[i] = (sign * forward_returns(mid_close, shifted, horizon)).mean()
                usable = ~np.isnan(placebo)
                record["horizons"][f"{horizon}bar"] = {
                    "mean": float(real.mean()),
                    "placebo_mean": float(placebo[usable].mean()),
                    "edge": float(real.mean() - placebo[usable].mean()),
                    "percentile": percentile_of(float(real.mean()), placebo[usable]),
                }
            means = [h["mean"] for h in record["horizons"].values()]
            best = max(record["horizons"].values(), key=lambda h: h["percentile"])
            record["positive_horizons"] = int(sum(m > 0 for m in means))
            record["best_percentile"] = best["percentile"]
            record["signal"] = bool(record["positive_horizons"] >= 3 and best["percentile"] >= 95.0)
            results[epic]["sides"][side] = record

            print(f"  {side}: {len(entries)} entries, "
                  f"{record['positive_horizons']}/5 horizons positive, "
                  f"best percentile {best['percentile']:.1f} -> "
                  f"{'SIGNAL' if record['signal'] else 'no signal'}")
            for label, h in record["horizons"].items():
                print(f"      {label:>6}  mean {h['mean']:+8.4f}  placebo {h['placebo_mean']:+8.4f}"
                      f"  edge {h['edge']:+8.4f}  pct {h['percentile']:5.1f}")

    any_signal = any(s.get("signal") for e in results.values() for s in e["sides"].values())
    verdict = "signal present" if any_signal else "rejected: no signal"
    print(f"\n==> {verdict}")

    OUT.write_text(json.dumps({
        "hypothesis": "H-0005", "timeframe": TIMEFRAME,
        "window": [str(DEV_START.date()), str(DEV_END.date())],
        "holdout": "2026-08-01 onward is held out and untouched",
        "horizons_bars": HORIZONS, "placebo_sets": PLACEBO_SETS,
        "trial_count": len(EPICS) * 2 * 1,
        "instruments": results, "verdict": verdict,
    }, indent=2, default=str))
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: Run it**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-20-confluence-layer1.py`
Expected: prints per-instrument pillar fire rates and per-side horizon tables, writes the JSON, exits 0.

**Read the pillar fire rates before anything else.** If `S/R` fires on a large majority of bars, the confluence is effectively 3 pillars at a threshold of 3 — i.e. unanimous — and that changes how every later result is read. Record the observation in the ledger either way.

- [ ] **Step 4: Append the result to the ledger**

Edit `research/ledger/H-0005-confluence-entry-signal.md`: replace `status: proposed` with the verdict (quoted if it contains a colon, e.g. `status: "rejected: no signal"`), and replace the `## Runs` placeholder with the observed table, the pillar fire rates, and which of the three pre-registered criteria passed. Do not move a threshold.

Then edit `research/ledger/INDEX.md`: bump the header counts, add the H-0005 row, and add a note explaining what the result means for the exit matrix.

- [ ] **Step 5: Commit**

```bash
git add research/experiments/2026-09-20-confluence-layer1.py \
        research/experiments/2026-09-20-confluence-layer1.json \
        research/ledger/H-0005-confluence-entry-signal.md \
        research/ledger/INDEX.md
git diff --cached --name-only
git commit -m "research: H-0005 layer-1 signal test of the 4-pillar confluence entry"
```

---

### Task 6: The frozen confluence entry as a Strategy base

**Files:**
- Create: `research/engine/engine/strategies/__init__.py`
- Create: `research/engine/engine/strategies/confluence.py`
- Test: `research/engine/tests/test_confluence_strategies.py`

**Interfaces:**
- Consumes: `PillarConfig`, `PillarVotes`, `compute_pillars` (Task 4); `Enter`, `Exit`, `MoveStop`, `BarsView`, `PositionView` from `engine.strategy`.
- Produces: `ConfluenceBase` with `timeframe: str`, `__init__(self, frame, config=PillarConfig(), brake_atr=10.0, timeframe="4h")`, `on_bar(history, position) -> list[Intent]`, and the hook `exit_intents(self, index, position) -> list[Intent]` that subclasses override. Also `entry_side(self, index) -> str | None`.

**Design note for the implementer:** indicators are precomputed over the whole frame in `__init__`, which is why every strategy **must** be handed to `check_lookahead` as a factory. `on_bar` finds its bar with `index = len(history) - 1`.

- [ ] **Step 1: Write the failing test**

Create `tests/test_confluence_strategies.py`:

```python
import numpy as np
import pandas as pd
import pytest
from engine.lookahead import check_lookahead
from engine.strategy import Enter, Exit, MoveStop, BarsView, PositionView
from engine.strategies.confluence import ConfluenceBase, SymmetricExit
from tests.test_pillars import frame   # reuse the synthetic frame builder


def run(strategy, frame_):
    """Feed every bar to the strategy while flat, collecting entry intents."""
    out = []
    for i in range(len(frame_)):
        out.append(strategy.on_bar(BarsView(frame_, i), PositionView.flat()))
    return out


def test_entry_requires_three_pillars_and_the_trend_gate():
    f = frame()
    strategy = ConfluenceBase(f)
    for i in range(len(f)):
        intents = strategy.on_bar(BarsView(f, i), PositionView.flat())
        if intents:
            assert isinstance(intents[0], Enter)
            if intents[0].side == "LONG":
                assert strategy.votes.bullish_score[i] >= 3
                assert strategy.votes.long_gate[i]
            else:
                assert strategy.votes.bearish_score[i] >= 3
                assert strategy.votes.short_gate[i]


def test_no_entry_during_warmup():
    f = frame()
    strategy = ConfluenceBase(f)
    for i in range(strategy.warmup):
        assert strategy.on_bar(BarsView(f, i), PositionView.flat()) == []


def test_brake_is_placed_at_the_configured_atr_multiple():
    f = frame()
    strategy = ConfluenceBase(f, brake_atr=10.0)
    mid = ((f["close_bid"] + f["close_ask"]) / 2.0).to_numpy()
    for i in range(len(f)):
        intents = strategy.on_bar(BarsView(f, i), PositionView.flat())
        if intents and isinstance(intents[0], Enter):
            distance = 10.0 * strategy.votes.atr[i]
            expected = mid[i] - distance if intents[0].side == "LONG" else mid[i] + distance
            assert intents[0].stop == pytest.approx(expected)
            return
    pytest.skip("no entry fired on the synthetic frame")


def test_base_emits_no_exit_of_its_own():
    f = frame()
    strategy = ConfluenceBase(f)
    held = PositionView(side="LONG", entry_price=100.0, stop=90.0, target=None, size=1.0)
    assert all(strategy.on_bar(BarsView(f, i), held) == [] for i in range(len(f)))


def test_strategy_passes_the_lookahead_guard_as_a_factory():
    f = frame()
    # An INSTANCE would pass this check even if it leaked, because its precomputed arrays are
    # never truncated. The factory form is the only one that actually tests anything.
    check_lookahead(lambda frame_: SymmetricExit(frame_, stop_atr=1.0), f)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_confluence_strategies.py -q`
Expected: FAIL with `ModuleNotFoundError: No module named 'engine.strategies'`

- [ ] **Step 3: Implement**

Create `engine/strategies/__init__.py`:

```python
"""Strategy implementations for the research engine."""
```

Create `engine/strategies/confluence.py`:

```python
"""The frozen 4-pillar confluence entry, and the four exit arms built on it (spec 4).

Every arm shares one entry so that differences between them are attributable to the exit.
Indicators are precomputed over the whole frame in __init__, which is why these classes MUST be
handed to `check_lookahead` as a factory: an instance's captured arrays are never truncated, so
an instance-based check silently passes a leak.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from engine.pillars import PillarConfig, compute_pillars
from engine.strategy import BarsView, Enter, Exit, Intent, MoveStop, PositionView

WARMUP_BARS = 600


class ConfluenceBase:
    """Entry only. Subclasses add an exit by overriding `exit_intents`."""

    def __init__(self, frame: pd.DataFrame, config: PillarConfig | None = None,
                 brake_atr: float = 10.0, timeframe: str = "4h") -> None:
        self.timeframe = timeframe
        self.config = config or PillarConfig()
        self.brake_atr = brake_atr
        self.votes = compute_pillars(frame, self.config)
        self.mid_close = ((frame["close_bid"] + frame["close_ask"]) / 2.0).to_numpy()
        self.mid_high = ((frame["high_bid"] + frame["high_ask"]) / 2.0).to_numpy()
        self.mid_low = ((frame["low_bid"] + frame["low_ask"]) / 2.0).to_numpy()
        self.warmup = WARMUP_BARS
        self.entry_index: int | None = None
        self.high_water: float = 0.0
        self.low_water: float = 0.0

    # -- entry ------------------------------------------------------------------
    def entry_side(self, index: int) -> str | None:
        if index < self.warmup or not np.isfinite(self.votes.atr[index]):
            return None
        threshold = self.config.confluence_threshold
        if self.votes.bullish_score[index] >= threshold and self.votes.long_gate[index]:
            return "LONG"
        if self.votes.bearish_score[index] >= threshold and self.votes.short_gate[index]:
            return "SHORT"
        return None

    def brake_price(self, index: int, side: str) -> float:
        distance = self.brake_atr * float(self.votes.atr[index])
        price = float(self.mid_close[index])
        return price - distance if side == "LONG" else price + distance

    def enter(self, index: int, side: str) -> list[Intent]:
        """Arms with a target override this; the default is a brake and no target."""
        return [Enter(side=side, stop=self.brake_price(index, side), target=None)]

    # -- exit -------------------------------------------------------------------
    def exit_intents(self, index: int, position: PositionView) -> list[Intent]:
        return []

    # -- driver -----------------------------------------------------------------
    def on_bar(self, history: BarsView, position: PositionView) -> list[Intent]:
        index = len(history) - 1
        if position:
            self.high_water = max(self.high_water, float(self.mid_high[index]))
            self.low_water = min(self.low_water, float(self.mid_low[index]))
            return self.exit_intents(index, position)

        self.entry_index = None
        side = self.entry_side(index)
        if side is None:
            return []
        self.entry_index = index
        self.high_water = float(self.mid_high[index])
        self.low_water = float(self.mid_low[index])
        return self.enter(index, side)


class SymmetricExit(ConfluenceBase):
    """E4: a tight stop with a target at the same distance. 1:1 by construction."""

    def __init__(self, frame: pd.DataFrame, stop_atr: float = 1.0, **kwargs) -> None:
        super().__init__(frame, brake_atr=stop_atr, **kwargs)
        self.stop_atr = stop_atr

    def enter(self, index: int, side: str) -> list[Intent]:
        distance = self.stop_atr * float(self.votes.atr[index])
        price = float(self.mid_close[index])
        if side == "LONG":
            return [Enter(side=side, stop=price - distance, target=price + distance)]
        return [Enter(side=side, stop=price + distance, target=price - distance)]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_confluence_strategies.py -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/strategies/ research/engine/tests/test_confluence_strategies.py
git diff --cached --name-only
git commit -m "feat(engine): add the frozen confluence entry and the symmetric 1:1 exit arm"
```

---

### Task 7: E2 — the time stop

**Files:**
- Modify: `research/engine/engine/strategies/confluence.py`
- Test: `research/engine/tests/test_confluence_strategies.py`

**Interfaces:**
- Consumes: `ConfluenceBase` (Task 6).
- Produces: `TimeExit(frame, brake_atr=10.0, max_bars=6, **kwargs)`.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_confluence_strategies.py`:

```python
from engine.strategies.confluence import TimeExit


def test_time_exit_fires_exactly_n_bars_after_entry():
    f = frame()
    strategy = TimeExit(f, max_bars=6)
    held = PositionView(side="LONG", entry_price=100.0, stop=90.0, target=None, size=1.0)
    entered = None
    for i in range(len(f)):
        if entered is None:
            if strategy.on_bar(BarsView(f, i), PositionView.flat()):
                entered = i
            continue
        intents = strategy.on_bar(BarsView(f, i), held)
        if i - entered < 6:
            assert intents == [], f"exited early at bar {i - entered}"
        else:
            assert intents == [Exit()]
            return
    pytest.skip("no entry fired on the synthetic frame")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_confluence_strategies.py -q -k time_exit`
Expected: FAIL with `ImportError: cannot import name 'TimeExit'`

- [ ] **Step 3: Implement**

Append to `engine/strategies/confluence.py`:

```python
class TimeExit(ConfluenceBase):
    """E2: out after `max_bars` signal bars, win or lose. The brake still applies throughout."""

    def __init__(self, frame: pd.DataFrame, max_bars: int = 6, **kwargs) -> None:
        super().__init__(frame, **kwargs)
        self.max_bars = max_bars

    def exit_intents(self, index: int, position: PositionView) -> list[Intent]:
        if self.entry_index is None:
            return []
        return [Exit()] if index - self.entry_index >= self.max_bars else []
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_confluence_strategies.py -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/strategies/confluence.py research/engine/tests/test_confluence_strategies.py
git diff --cached --name-only
git commit -m "feat(engine): add the time-stop exit arm"
```

---

### Task 8: E1 — the trailing stop

**Files:**
- Modify: `research/engine/engine/strategies/confluence.py`
- Test: `research/engine/tests/test_confluence_strategies.py`

**Interfaces:**
- Consumes: `ConfluenceBase` (Task 6).
- Produces: `TrailingExit(frame, brake_atr=10.0, trail_atr=2.0, **kwargs)`.

**Mechanics (spec §4.1), stated exactly:** for a long, on each closed bar
`new_stop = max(current_stop, high_water_mark − trail_atr × ATR(index))`, where `high_water_mark`
is the highest mid high since entry. `MoveStop` is emitted **only** when `new_stop > current_stop`.
Mirrored for shorts. The one-way ratchet is what makes using the current bar's ATR safe — a
volatility spike can never widen the stop.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_confluence_strategies.py`:

```python
from engine.strategies.confluence import TrailingExit


def test_trailing_stop_ratchets_up_and_never_down_for_a_long():
    f = frame()
    strategy = TrailingExit(f, trail_atr=2.0)
    # Drive the high-water mark up by hand, then back down, and check the stop only rises.
    strategy.entry_index = 700
    strategy.high_water = float(strategy.mid_high[700])
    held = PositionView(side="LONG", entry_price=100.0, stop=50.0, target=None, size=1.0)

    seen = [50.0]
    for i in range(701, 760):
        intents = strategy.on_bar(BarsView(f, i), held)
        for intent in intents:
            assert isinstance(intent, MoveStop)
            assert intent.price > seen[-1], "stop moved away from price"
            seen.append(intent.price)
            held = PositionView(side="LONG", entry_price=100.0, stop=intent.price,
                                target=None, size=1.0)
    assert seen == sorted(seen)


def test_trailing_stop_emits_nothing_when_the_level_would_not_improve():
    f = frame()
    strategy = TrailingExit(f, trail_atr=2.0)
    strategy.entry_index = 700
    strategy.high_water = float(strategy.mid_high[700])
    # A stop already far above anything the trail would produce must produce no intent.
    held = PositionView(side="LONG", entry_price=100.0, stop=1e9, target=None, size=1.0)
    assert strategy.on_bar(BarsView(f, 701), held) == []
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_confluence_strategies.py -q -k trailing`
Expected: FAIL with `ImportError: cannot import name 'TrailingExit'`

- [ ] **Step 3: Implement**

Append to `engine/strategies/confluence.py`:

```python
class TrailingExit(ConfluenceBase):
    """E1: the stop ratchets behind price and never away from it.

    Uses the CURRENT bar's ATR. That is only safe because of the one-way ratchet below: a
    volatility spike raises the trail distance but can never widen an existing stop.
    """

    def __init__(self, frame: pd.DataFrame, trail_atr: float = 2.0, **kwargs) -> None:
        super().__init__(frame, **kwargs)
        self.trail_atr = trail_atr

    def exit_intents(self, index: int, position: PositionView) -> list[Intent]:
        distance = self.trail_atr * float(self.votes.atr[index])
        if not np.isfinite(distance):
            return []
        if position.side == "LONG":
            candidate = self.high_water - distance
            return [MoveStop(price=candidate)] if candidate > position.stop else []
        candidate = self.low_water + distance
        return [MoveStop(price=candidate)] if candidate < position.stop else []
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_confluence_strategies.py -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/strategies/confluence.py research/engine/tests/test_confluence_strategies.py
git diff --cached --name-only
git commit -m "feat(engine): add the trailing-stop exit arm with a one-way ratchet"
```

---

### Task 9: E3 — the signal-reversal exit

**Files:**
- Modify: `research/engine/engine/strategies/confluence.py`
- Test: `research/engine/tests/test_confluence_strategies.py`

**Interfaces:**
- Consumes: `ConfluenceBase` (Task 6).
- Produces: `ReversalExit(frame, brake_atr=10.0, **kwargs)`.

**Mechanics (spec §4.1):** exit when the confluence score **for the side actually held** falls
below the threshold. It does not require the opposite side to vote.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_confluence_strategies.py`:

```python
from engine.strategies.confluence import ReversalExit


def test_reversal_exit_fires_when_the_held_side_stops_voting():
    f = frame()
    strategy = ReversalExit(f)
    held = PositionView(side="LONG", entry_price=100.0, stop=90.0, target=None, size=1.0)
    for i in range(strategy.warmup, len(f)):
        intents = strategy.on_bar(BarsView(f, i), held)
        if strategy.votes.bullish_score[i] >= strategy.config.confluence_threshold:
            assert intents == []
        else:
            assert intents == [Exit()]


def test_reversal_exit_reads_the_held_side_not_the_other_one():
    f = frame()
    strategy = ReversalExit(f)
    short = PositionView(side="SHORT", entry_price=100.0, stop=110.0, target=None, size=1.0)
    for i in range(strategy.warmup, len(f)):
        expected = [] if strategy.votes.bearish_score[i] >= 3 else [Exit()]
        assert strategy.on_bar(BarsView(f, i), short) == expected
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_confluence_strategies.py -q -k reversal`
Expected: FAIL with `ImportError: cannot import name 'ReversalExit'`

- [ ] **Step 3: Implement**

Append to `engine/strategies/confluence.py`:

```python
class ReversalExit(ConfluenceBase):
    """E3: out when the confluence score for the side actually held falls below the threshold."""

    def exit_intents(self, index: int, position: PositionView) -> list[Intent]:
        score = (self.votes.bullish_score if position.side == "LONG"
                 else self.votes.bearish_score)
        return [] if score[index] >= self.config.confluence_threshold else [Exit()]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_confluence_strategies.py -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/strategies/confluence.py research/engine/tests/test_confluence_strategies.py
git diff --cached --name-only
git commit -m "feat(engine): add the signal-reversal exit arm"
```

---

### Task 10: Report in R and percent, split by side

**Files:**
- Modify: `research/engine/engine/run.py`
- Test: `research/engine/tests/test_run.py`

**Interfaces:**
- Consumes: `Trade`, `SimConfig` from `engine.simulator`.
- Produces: `summarise(trades, equity, config, meta, skipped=0, pillar_report=None) -> dict` — the existing signature gains two optional keyword arguments. New keys: `net_pct`, `expectancy_r`, `expectancy_r_ci95`, `skipped`, `skipped_share`, `by_side` (`{"LONG": {...}, "SHORT": {...}}`), `by_exit_reason`, `risk` (see Task 11), `pillars`.

**Rationale (spec §5.1, D6):** the account balance is arbitrary; the percentage is not. With
`risk_pct = 1`, one R **is** one percent of account, so R makes every comparison size-invariant.
Dollars stay as a diagnostic.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_run.py`:

```python
import pandas as pd
from engine.run import summarise, equity_curve
from engine.simulator import SimConfig, Trade


def a_trade(side="LONG", net=10.0, reason="TARGET", risk=20.0):
    stamp = pd.Timestamp("2024-03-01T12:00:00Z")
    return Trade(entry_time=stamp, entry_price=100.0, exit_time=stamp + pd.Timedelta(hours=4),
                 exit_price=101.0, side=side, size=1.0, stop=90.0, target=110.0,
                 exit_reason=reason, gross_usd=net, spread_cost_usd=0.5, financing_usd=0.0,
                 slippage_usd=0.0, net_usd=net, r=net / risk)


def test_summary_reports_percent_of_account_and_r():
    config = SimConfig(starting_balance_usd=2000.0, risk_pct=1.0)
    trades = [a_trade(net=20.0), a_trade(net=-20.0)]
    summary = summarise(trades, equity_curve(trades, 2000.0), config, {})
    assert summary["net_usd"] == 0.0
    assert summary["net_pct"] == 0.0
    assert summary["expectancy_r"] == 0.0
    assert "expectancy_r_ci95" in summary


def test_summary_splits_long_and_short():
    config = SimConfig(starting_balance_usd=2000.0, risk_pct=1.0)
    trades = [a_trade(side="LONG", net=40.0), a_trade(side="SHORT", net=-20.0)]
    summary = summarise(trades, equity_curve(trades, 2000.0), config, {})
    assert summary["by_side"]["LONG"]["trades"] == 1
    assert summary["by_side"]["LONG"]["net_usd"] == 40.0
    assert summary["by_side"]["SHORT"]["net_usd"] == -20.0
    assert summary["by_side"]["SHORT"]["expectancy_r"] < 0


def test_summary_records_skipped_trades_as_a_share():
    config = SimConfig(starting_balance_usd=2000.0, risk_pct=1.0)
    trades = [a_trade()]
    summary = summarise(trades, equity_curve(trades, 2000.0), config, {}, skipped=3)
    assert summary["skipped"] == 3
    assert summary["skipped_share"] == 0.75      # 3 skipped of 4 signals


def test_summary_breaks_down_exit_reasons():
    config = SimConfig(starting_balance_usd=2000.0, risk_pct=1.0)
    trades = [a_trade(reason="STOP"), a_trade(reason="TARGET"), a_trade(reason="SIGNAL")]
    summary = summarise(trades, equity_curve(trades, 2000.0), config, {})
    assert summary["by_exit_reason"] == {"STOP": 1, "TARGET": 1, "SIGNAL": 1}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_run.py -q`
Expected: FAIL with `KeyError: 'net_pct'`

- [ ] **Step 3: Implement**

In `engine/run.py`, replace the body of `summarise` with:

```python
def _side_summary(trades: list[Trade], starting_balance: float) -> dict:
    nets = np.array([t.net_usd for t in trades], dtype=float)
    rs = np.array([t.r for t in trades], dtype=float)
    if len(trades) == 0:
        return {"trades": 0, "net_usd": 0.0, "net_pct": 0.0, "expectancy_r": 0.0,
                "win_rate": 0.0, "financing_usd": 0.0}
    return {
        "trades": len(trades),
        "net_usd": float(nets.sum()),
        "net_pct": float(nets.sum() / starting_balance * 100.0),
        "expectancy_r": float(rs.mean()),
        "expectancy_r_ci95": list(bootstrap_ci(rs)),
        "win_rate": float((nets > 0).mean()),
        "financing_usd": float(sum(t.financing_usd for t in trades)),
    }


def summarise(trades: list[Trade], equity: pd.DataFrame, config: SimConfig, meta: dict,
              skipped: int = 0, pillar_report: dict | None = None) -> dict:
    """Leads with account-size-invariant units: R and percent. Dollars are a diagnostic.

    With risk_pct = 1, one R is one percent of the account, so R makes every comparison
    independent of the balance the run happened to start with (spec 5.1).
    """
    nets = np.array([t.net_usd for t in trades], dtype=float)
    rs = np.array([t.r for t in trades], dtype=float)
    wins = int((nets > 0).sum()) if len(nets) else 0
    gross = float(sum(abs(t.gross_usd) for t in trades))
    costs = float(sum(abs(t.financing_usd) + abs(t.slippage_usd) for t in trades))
    balances = equity["balance"].to_numpy(dtype=float) if len(equity) else np.array([])
    drawdown_usd, drawdown_pct = _max_drawdown(balances)
    starting = config.starting_balance_usd

    per_period: dict = {}
    if trades:
        stamped = pd.DataFrame({"time": [t.exit_time for t in trades], "net": nets}).set_index("time")
        per_period = {
            "per_month_pct": {str(k): float(v / starting * 100.0)
                              for k, v in stamped["net"].resample("MS").sum().items()},
            "per_quarter_pct": {str(k): float(v / starting * 100.0)
                                for k, v in stamped["net"].resample("QS").sum().items()},
        }

    signals = len(trades) + skipped
    return {
        **meta,
        "git_commit": _git_commit(),
        "config": dataclasses.asdict(config),
        "trades": len(trades),
        # account-size-invariant, reported first
        "expectancy_r": float(rs.mean()) if len(rs) else 0.0,
        "expectancy_r_ci95": list(bootstrap_ci(rs)),
        "net_pct": float(nets.sum() / starting * 100.0) if len(nets) else 0.0,
        "max_drawdown_pct": drawdown_pct,
        "skipped": int(skipped),
        "skipped_share": float(skipped / signals) if signals else 0.0,
        "by_side": {side: _side_summary([t for t in trades if t.side == side], starting)
                    for side in ("LONG", "SHORT")},
        "by_exit_reason": dict(Counter(t.exit_reason for t in trades)),
        "cost_share_of_gross": float(costs / gross) if gross else 0.0,
        "win_rate": {"value": float(wins / len(trades)) if trades else 0.0, "informational": True},
        "pillars": pillar_report,
        # diagnostics, in dollars
        "net_usd": float(nets.sum()) if len(nets) else 0.0,
        "expectancy_usd": float(nets.mean()) if len(nets) else 0.0,
        "expectancy_usd_ci95": list(bootstrap_ci(nets)),
        "max_drawdown_usd": drawdown_usd,
        **per_period,
    }
```

Add `from collections import Counter` to the imports at the top of `run.py`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_run.py -q`
Expected: PASS. Then run the full suite: `.venv/bin/pytest -q tests` — **any existing test asserting on `per_month`/`per_quarter` must be updated to the `_pct` names, not deleted.**

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/run.py research/engine/tests/test_run.py
git diff --cached --name-only
git commit -m "feat(engine): report runs in R and percent, split by side and exit reason"
```

---

### Task 11: Risk measurements

**Files:**
- Modify: `research/engine/engine/run.py`
- Test: `research/engine/tests/test_run.py`

**Interfaces:**
- Consumes: `Trade` (simulator), `summarise` (Task 10).
- Produces: `risk_measures(trades, equity, starting_balance) -> dict` with keys `max_drawdown_pct`, `max_drawdown_at`, `worst_day_pct`, `longest_losing_run_trades`, `longest_losing_run_days`, `financing_share_of_gross`, `mean_holding_hours`, `max_holding_hours`. Wired into `summarise` under the `risk` key.

**Rationale (spec §6):** the strategy cannot wipe the account in the modelled world — risk-based
sizing caps each trade at 1%, one position is held at a time, and gaps through the stop already
fill at the gapped open. So risk control is a **measurement** this round; a breaker's thresholds
get set from these numbers before live, not guessed now.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_run.py`:

```python
from engine.run import risk_measures


def test_risk_measures_find_the_longest_losing_run():
    base = pd.Timestamp("2024-03-01T00:00:00Z")
    trades = []
    for i, net in enumerate([10.0, -5.0, -5.0, -5.0, 10.0, -5.0]):
        t = a_trade(net=net)
        trades.append(Trade(**{**t.__dict__,
                              "entry_time": base + pd.Timedelta(days=i),
                              "exit_time": base + pd.Timedelta(days=i, hours=4)}))
    measures = risk_measures(trades, equity_curve(trades, 2000.0), 2000.0)
    assert measures["longest_losing_run_trades"] == 3
    assert measures["longest_losing_run_days"] >= 2


def test_risk_measures_report_holding_time_in_hours():
    trades = [a_trade()]
    measures = risk_measures(trades, equity_curve(trades, 2000.0), 2000.0)
    assert measures["mean_holding_hours"] == 4.0
    assert measures["max_holding_hours"] == 4.0


def test_risk_measures_are_attached_to_the_summary():
    config = SimConfig(starting_balance_usd=2000.0, risk_pct=1.0)
    trades = [a_trade()]
    summary = summarise(trades, equity_curve(trades, 2000.0), config, {})
    assert "risk" in summary
    assert "max_drawdown_pct" in summary["risk"]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_run.py -q -k risk`
Expected: FAIL with `ImportError: cannot import name 'risk_measures'`

- [ ] **Step 3: Implement**

Add to `engine/run.py`:

```python
def risk_measures(trades: list[Trade], equity: pd.DataFrame, starting_balance: float) -> dict:
    """What a circuit breaker would need to be calibrated against (spec 6).

    No breaker is implemented this round: installing one before these numbers exist is a guess,
    and it also distorts the equity path being read.
    """
    if not trades:
        return {"max_drawdown_pct": 0.0, "max_drawdown_at": None, "worst_day_pct": 0.0,
                "longest_losing_run_trades": 0, "longest_losing_run_days": 0.0,
                "financing_share_of_gross": 0.0, "mean_holding_hours": 0.0,
                "max_holding_hours": 0.0}

    nets = np.array([t.net_usd for t in trades], dtype=float)
    balances = equity["balance"].to_numpy(dtype=float)
    peak = np.maximum.accumulate(balances)
    drop = peak - balances
    worst = int(drop.argmax())

    daily = pd.Series(nets, index=pd.DatetimeIndex([t.exit_time for t in trades])).resample("D").sum()
    hours = np.array([(t.exit_time - t.entry_time).total_seconds() / 3600.0 for t in trades])

    longest = run_length = 0
    run_start = run_end = None
    best_span = 0.0
    for trade, net in zip(trades, nets):
        if net < 0:
            run_length += 1
            run_start = run_start or trade.entry_time
            run_end = trade.exit_time
            if run_length > longest:
                longest = run_length
                best_span = (run_end - run_start).total_seconds() / 86400.0
        else:
            run_length = 0
            run_start = run_end = None

    gross = float(sum(abs(t.gross_usd) for t in trades))
    financing = float(sum(abs(t.financing_usd) for t in trades))
    return {
        "max_drawdown_pct": float(drop[worst] / peak[worst] * 100.0) if peak[worst] else 0.0,
        "max_drawdown_at": str(trades[worst].exit_time),
        "worst_day_pct": float(daily.min() / starting_balance * 100.0),
        "longest_losing_run_trades": int(longest),
        "longest_losing_run_days": float(best_span),
        "financing_share_of_gross": float(financing / gross) if gross else 0.0,
        "mean_holding_hours": float(hours.mean()),
        "max_holding_hours": float(hours.max()),
    }
```

Then add `"risk": risk_measures(trades, equity, config.starting_balance_usd),` to the dict returned by `summarise`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest -q tests`
Expected: PASS, full suite green

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/run.py research/engine/tests/test_run.py
git diff --cached --name-only
git commit -m "feat(engine): measure drawdown, losing runs and holding time per run"
```

---

### Task 12: Wire the run CLI

**Files:**
- Modify: `research/engine/engine/run.py`
- Test: `research/engine/tests/test_run.py`

**Interfaces:**
- Consumes: everything above; `engine.cache.load_cached_minutes(db, epic, cache_dir)`, `engine.bars.resample(minutes, timeframe)`, `engine.simulator.simulate(strategy, minutes, signal_bars, spec, config) -> (trades, skipped)`, `engine.instruments.load_instruments(path) -> dict[str, dict]`.
- Produces: `build_strategy(name: str, frame, **kwargs)` returning a strategy instance, and a working `main(argv)` accepting `--db --epic --timeframe --arm --brake-atr --trail-atr --max-bars --stop-atr --out --balance --risk-pct`. `ARMS` maps `{"trailing": TrailingExit, "time": TimeExit, "reversal": ReversalExit, "symmetric": SymmetricExit}`.

**Current state:** `main` ends with `raise SystemExit("no strategy is wired to the CLI yet; import engine.run from a research script")`. That line goes.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_run.py`:

```python
import pytest
from engine.run import ARMS, build_strategy
from engine.strategies.confluence import TrailingExit, TimeExit, ReversalExit, SymmetricExit
from tests.test_pillars import frame as pillar_frame


def test_every_arm_in_the_spec_is_reachable_by_name():
    assert set(ARMS) == {"trailing", "time", "reversal", "symmetric"}
    assert ARMS["trailing"] is TrailingExit
    assert ARMS["time"] is TimeExit
    assert ARMS["reversal"] is ReversalExit
    assert ARMS["symmetric"] is SymmetricExit


def test_build_strategy_passes_arm_parameters_through():
    strategy = build_strategy("trailing", pillar_frame(), brake_atr=14.0, trail_atr=3.0)
    assert isinstance(strategy, TrailingExit)
    assert strategy.brake_atr == 14.0
    assert strategy.trail_atr == 3.0


def test_unknown_arm_is_rejected_by_name():
    with pytest.raises(ValueError, match="unknown arm"):
        build_strategy("nonsense", pillar_frame())
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest tests/test_run.py -q -k arm`
Expected: FAIL with `ImportError: cannot import name 'ARMS'`

- [ ] **Step 3: Implement**

In `engine/run.py`, add near the imports:

```python
from engine.strategies.confluence import ReversalExit, SymmetricExit, TimeExit, TrailingExit

ARMS = {"trailing": TrailingExit, "time": TimeExit,
        "reversal": ReversalExit, "symmetric": SymmetricExit}

# Which keyword arguments each arm accepts, so an irrelevant flag is a clear error rather than
# a silently ignored one.
ARM_PARAMETERS = {"trailing": {"brake_atr", "trail_atr"}, "time": {"brake_atr", "max_bars"},
                  "reversal": {"brake_atr"}, "symmetric": {"stop_atr"}}


def build_strategy(name: str, frame: pd.DataFrame, **kwargs):
    if name not in ARMS:
        raise ValueError(f"unknown arm {name!r}; expected one of {sorted(ARMS)}")
    allowed = ARM_PARAMETERS[name]
    unknown = set(kwargs) - allowed
    if unknown:
        raise ValueError(f"arm {name!r} does not take {sorted(unknown)}; it takes {sorted(allowed)}")
    return ARMS[name](frame, **kwargs)
```

Then replace `main` entirely:

```python
def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Run a confluence exit arm and write its evidence")
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--epic", required=True)
    parser.add_argument("--timeframe", default="4h")
    parser.add_argument("--arm", required=True, choices=sorted(ARMS))
    parser.add_argument("--brake-atr", type=float, default=10.0)
    parser.add_argument("--trail-atr", type=float, default=2.0)
    parser.add_argument("--max-bars", type=int, default=6)
    parser.add_argument("--stop-atr", type=float, default=1.0)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--balance", type=float, default=2000.0)
    parser.add_argument("--risk-pct", type=float, default=1.0)
    args = parser.parse_args(argv)

    from engine.bars import resample
    from engine.cache import load_cached_minutes
    from engine.instruments import load_instruments
    from engine.pillars import PillarConfig, compute_pillars, fire_rates
    from engine.simulator import simulate
    from engine.strategies.confluence import WARMUP_BARS

    engine_dir = Path(__file__).resolve().parents[1]      # research/engine
    minutes = load_cached_minutes(args.db, args.epic, engine_dir / ".cache")
    signal_bars = resample(minutes, args.timeframe)
    spec = load_instruments(engine_dir / "instruments.yaml")[args.epic]

    kwargs = {k: v for k, v in {
        "brake_atr": args.brake_atr, "trail_atr": args.trail_atr,
        "max_bars": args.max_bars, "stop_atr": args.stop_atr,
    }.items() if k in ARM_PARAMETERS[args.arm]}
    strategy = build_strategy(args.arm, signal_bars, **kwargs)

    config = SimConfig(starting_balance_usd=args.balance, risk_pct=args.risk_pct)
    trades, skipped = simulate(strategy, minutes, signal_bars, spec, config)
    equity = equity_curve(trades, args.balance)
    summary = summarise(trades, equity, config, {"epic": args.epic, "arm": args.arm,
                                                 "timeframe": args.timeframe, **kwargs},
                        skipped=skipped,
                        pillar_report=fire_rates(compute_pillars(signal_bars, PillarConfig()),
                                                 WARMUP_BARS))
    write_run(args.out, trades, equity, summary)
    print(f"{args.epic} {args.arm}: {summary['trades']} trades, {skipped} skipped, "
          f"expectancy {summary['expectancy_r']:+.3f}R, net {summary['net_pct']:+.2f}%, "
          f"max DD {summary['risk']['max_drawdown_pct']:.1f}%")
```

- [ ] **Step 4: Run tests and a real end-to-end run**

Run: `cd research/engine && PYTHONPATH=. .venv/bin/pytest -q tests`
Expected: PASS, full suite green

Then a real run (the first trade this engine has ever produced):

```bash
cd research/engine && PYTHONPATH=. .venv/bin/python -m engine.run \
  --db ../../data/axe-trader.sqlite --epic US500 --timeframe 4h \
  --arm symmetric --stop-atr 1.0 --out /tmp/us500-symmetric
```
Expected: a line reporting trades, skipped, expectancy in R, net %, max drawdown — and three files in `/tmp/us500-symmetric`.

- [ ] **Step 5: Commit**

```bash
git add research/engine/engine/run.py research/engine/tests/test_run.py
git diff --cached --name-only
git commit -m "feat(engine): wire the run CLI to the confluence exit arms"
```

---

## Part 2 (written after Part 1 lands)

Two tasks, deliberately deferred so they are written against real APIs rather than guessed:

- **H-0006 — the 51-configuration matrix.** Ledger entry with pre-registered criteria first, then the sweep across 3 instruments × 17 configurations, the placebo grid, and the per-arm / per-instrument comparison tables (spec §7.2).
- **The trade-review page.** Adapter from `Trade` to the review contract plus per-trade candle windows, republished to `https://claude.ai/artifact/YTkPaQr27cT4njL7uEnjx9` by passing that URL as `url` (spec §7.3). This is the deliverable.

---

## Self-Review

**Spec coverage.** §2 layer-1 test → Task 5. §3.1 pillars → Task 4 (with Tasks 1–3 supplying ATR, ADX and candles). §3.2 port-fidelity quirks → Task 1 (`highest`/`lowest` inclusive test), Task 3 (doji test), Task 4 (S/R always-fires test, `fire_rates`). §3.3 both sides + per-side reporting → Task 10 `by_side`. §4 exit arms → Tasks 6–9. §4.1 exact mechanics → Tasks 6–9, each pinned by a test. §4.2 placebo → Part 2. §5.1 R and % → Task 10. §5.2 skipped share → Task 10. §6 risk measurement → Task 11. §7.1 outputs → Tasks 10–11. §7.2 comparison and §7.3 review page → Part 2, declared.

**Gap accepted deliberately:** spec §4.2's placebo grid is Part 2, because it is a property of the matrix run, not of the strategy classes.

**Placeholder scan:** no TBDs; every code step carries runnable code. Every cross-module call was checked against the real source: `load_instruments(path)` (not `load_specs()`), `simulate(...) -> (trades, skipped)`, `write_run(run_dir, trades, equity, summary)`.

**Type consistency:** `PillarConfig`/`PillarVotes` field names match between Tasks 4, 6 and 12. `brake_atr` is the constructor argument in `ConfluenceBase` and every subclass; `SymmetricExit` maps `stop_atr` onto it. `exit_intents(index, position)` has one signature across Tasks 6–9. `summarise`'s new keyword arguments (`skipped`, `pillar_report`) are introduced in Task 10 and used in Task 12 with the same names.
