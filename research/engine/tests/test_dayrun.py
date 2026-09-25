import datetime as dt
from pathlib import Path

import pandas as pd
import pytest

from conftest import make_minute_frame
from engine.dayrun import (RESERVED_FROM, ReservedDataError, data_cutoff, data_fingerprint,
                           load_version, review_window, run_day)
from engine.pillars import PillarConfig

VERSION_FILE = Path(__file__).resolve().parents[2] / "review" / "strategies" / "v001.yaml"
SPEC = {
    "currency": "USD", "lot_size": 1, "min_deal_size": 0.01, "size_increment": 0.01,
    "overnight_fee": {"long_rate": -0.0215402, "short_rate": -0.000682,
                      "charge_time_utc": "21:00", "interval_minutes": 1440},
}


def test_v001_matches_the_frozen_engine_defaults():
    version = load_version(VERSION_FILE)
    assert version.pillars == PillarConfig()
    assert set(version.arms) == {"confluence", "symmetric", "trailing", "time", "reversal"}
    assert version.parent is None
    assert len(version.sha256) == 64


def test_review_window_runs_from_the_previous_cash_close_to_this_one():
    start, end = review_window(dt.date(2024, 1, 11))
    assert start == pd.Timestamp("2024-01-10T21:00Z")
    assert end == pd.Timestamp("2024-01-11T21:00Z")


def test_review_window_skips_back_over_a_holiday_weekend():
    start, end = review_window(dt.date(2024, 1, 16))        # Tuesday after MLK day
    assert start == pd.Timestamp("2024-01-12T21:00Z")
    assert end == pd.Timestamp("2024-01-16T21:00Z")


def test_a_non_session_date_is_refused():
    with pytest.raises(ValueError, match="no NYSE session"):
        review_window(dt.date(2024, 1, 15))


def test_the_reserved_period_can_never_be_loaded():
    assert data_cutoff(dt.date(2024, 1, 11)) == pd.Timestamp("2024-01-21T21:00Z")
    assert data_cutoff(dt.date(2026, 7, 29)) < RESERVED_FROM
    with pytest.raises(ReservedDataError):
        data_cutoff(dt.date(2026, 8, 3))


def test_fingerprint_changes_with_any_price():
    minutes = make_minute_frame(n=500)
    before = data_fingerprint(minutes)
    assert before == data_fingerprint(minutes.copy())
    changed = minutes.copy()
    changed.iloc[100, 0] += 0.01
    assert data_fingerprint(changed) != before


@pytest.fixture(scope="module")
def synthetic_day():
    # 20,000 minutes from 2024-01-02 cover the 2024-01-11 session once warmed up on 15m.
    return make_minute_frame(n=20_000)


def test_run_day_is_deterministic_and_identified_by_its_inputs(synthetic_day):
    version = load_version(VERSION_FILE)
    first = run_day(synthetic_day, version, dt.date(2024, 1, 11), "15min", "confluence", SPEC,
                    engine_commit="abc1234")
    second = run_day(synthetic_day, version, dt.date(2024, 1, 11), "15min", "confluence", SPEC,
                     engine_commit="abc1234")
    assert first.record["run_id"] == second.record["run_id"]
    assert first.record["run_id"].startswith("us500-15min-v001-confluence-2024-01-11-")
    other = run_day(synthetic_day, version, dt.date(2024, 1, 11), "15min", "confluence", SPEC,
                    engine_commit="def5678")
    assert other.record["run_id"] != first.record["run_id"]


def test_run_day_records_everything_needed_to_reproduce_it(synthetic_day):
    version = load_version(VERSION_FILE)
    day = run_day(synthetic_day, version, dt.date(2024, 1, 11), "5min", "trailing", SPEC,
                  engine_commit="abc1234")
    record = day.record
    for key in ("strategy", "session", "window", "timeframe", "arm", "arm_parameters", "data",
                "engine", "execution", "calendar"):
        assert key in record, key
    assert record["strategy"]["sha256"] == version.sha256
    assert record["data"]["last_minute"] < RESERVED_FROM.isoformat()


def test_run_day_keeps_trades_that_touch_the_window_and_explains_every_bar(synthetic_day):
    version = load_version(VERSION_FILE)
    day = run_day(synthetic_day, version, dt.date(2024, 1, 11), "15min", "confluence", SPEC,
                  engine_commit="abc1234")
    start, end = review_window(dt.date(2024, 1, 11))
    for trade in day.trades:
        assert pd.Timestamp(trade["exit_time"]) > start
        assert pd.Timestamp(trade["entry_time"]) <= end
    times = [pd.Timestamp(d["time"]) for d in day.decisions]
    assert times == sorted(times)
    assert times[0] >= start - pd.Timedelta("15min")
    assert all(d["action"] in {"NONE", "HOLD", "EXIT"} or d["action"].startswith("ENTER")
               for d in day.decisions)


def test_runs_are_append_only(tmp_path, synthetic_day):
    from engine.dayrun import write_run
    version = load_version(VERSION_FILE)
    day = run_day(synthetic_day, version, dt.date(2024, 1, 11), "15min", "confluence", SPEC,
                  engine_commit="abc1234")
    first = write_run(tmp_path, day)
    assert write_run(tmp_path, day) == first            # identical rerun: accepted, untouched
    day.trades.append({"tampered": True})
    with pytest.raises(FileExistsError, match="append-only"):
        write_run(tmp_path, day)
