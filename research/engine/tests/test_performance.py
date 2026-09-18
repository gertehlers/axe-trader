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

    print(f"\nUS500 full run: {len(minutes):,} minutes -> {len(signal_bars):,} bars, "
          f"{len(trades):,} trades, {skipped:,} skipped, {elapsed:.1f}s")
    assert len(trades) > 0
    assert elapsed < 120, f"full US500 run took {elapsed:.1f}s, target is under 120s"
