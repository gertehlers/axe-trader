"""One strategy version on one review session: the run record the day-review page is built from.

Owner's plan (docs/superpowers/plans/2026-09-25-us500-day-review-loop.md §3): a *run* is one
immutable strategy version applied to one session and timeframe (and here one exit arm), on a
specific data snapshot and engine version. Its record carries everything needed to reproduce it,
and its id is derived from those inputs, so the same inputs always name the same run and any
changed input names a new one. Nothing is ever overwritten.

The review "day" is the Capital.com trading day that ends at the NYSE cash close: from the previous
session's close to this session's close, so overnight entries (allowed by the baseline) belong to
the day they lead into. The cash session itself is shaded on the page.

Data after `RESERVED_FROM` is the untouched evaluation period and is never loaded here.
"""

from __future__ import annotations

import dataclasses
import datetime as dt
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

import pandas as pd
import yaml

from engine.bars import resample
from engine.cash_session import cash_session, cash_sessions
from engine.explain import explain_bar
from engine.pillars import PillarConfig
from engine.simulator import SimConfig, simulate

RESERVED_FROM = pd.Timestamp("2026-08-01T00:00Z")
# Enough forward data for a trade entered on the review day to close (weekends, holidays).
FORWARD_DAYS = 10


class ReservedDataError(ValueError):
    """Raised when a review would need data from the untouched evaluation period."""


@dataclass(frozen=True)
class StrategyVersion:
    version: str
    parent: str | None
    hypothesis: str
    pillars: PillarConfig
    arms: dict
    execution: dict
    sha256: str
    path: str


def load_version(path: Path) -> StrategyVersion:
    raw = Path(path).read_bytes()
    spec = yaml.safe_load(raw)
    return StrategyVersion(
        version=spec["version"], parent=spec.get("parent"), hypothesis=spec["hypothesis"].strip(),
        pillars=PillarConfig(**spec["pillars"]), arms=spec["arms"], execution=spec["execution"],
        sha256=hashlib.sha256(raw).hexdigest(), path=str(path))


def review_window(day: dt.date) -> tuple[pd.Timestamp, pd.Timestamp]:
    """(previous cash close, this cash close) in UTC."""
    session = cash_session(day)
    if session is None:
        raise ValueError(f"{day} has no NYSE session")
    earlier = cash_sessions(day - dt.timedelta(days=10), day - dt.timedelta(days=1))
    return earlier[-1].close_utc, session.close_utc


def data_cutoff(day: dt.date) -> pd.Timestamp:
    """The last minute a run for `day` may load. Refuses any day inside the reserved period."""
    _, close = review_window(day)
    if close >= RESERVED_FROM:
        raise ReservedDataError(f"{day} is inside the reserved evaluation period "
                                f"(from {RESERVED_FROM.date()}); it is not reviewed")
    return min(close + pd.Timedelta(days=FORWARD_DAYS), RESERVED_FROM - pd.Timedelta(minutes=1))


def data_fingerprint(minutes: pd.DataFrame) -> str:
    """sha256 over the index and every price and volume value, in order."""
    digest = hashlib.sha256()
    digest.update(pd.util.hash_pandas_object(minutes, index=True).to_numpy().tobytes())
    return digest.hexdigest()


@dataclass
class DayRun:
    record: dict
    trades: list[dict]
    decisions: list[dict]
    strategy: object
    bars: pd.DataFrame
    display: tuple[int, int]          # [first, last] signal-bar indexes shown on the page
    all_trades: list[dict] = dataclasses.field(default_factory=list)   # the full run, for matching


def _trade_dict(trade) -> dict:
    out = dataclasses.asdict(trade)
    for key in ("entry_time", "exit_time"):
        out[key] = pd.Timestamp(out[key]).isoformat()
    return out


def run_day(minutes: pd.DataFrame, version: StrategyVersion, day: dt.date, timeframe: str,
            arm: str, spec: dict, engine_commit: str, epic: str = "US500",
            engine_dirty: bool = False) -> DayRun:
    from engine.run import build_strategy     # engine.run imports the strategies; keep this lazy

    start, end = review_window(day)
    cutoff = data_cutoff(day)
    data = minutes[minutes.index <= cutoff]
    bars = resample(data, timeframe)
    arm_parameters = dict(version.arms[arm])
    strategy = build_strategy(arm, bars, config=version.pillars, **arm_parameters)
    execution = version.execution
    config = SimConfig(starting_balance_usd=float(execution["starting_balance_usd"]),
                       risk_pct=float(execution["risk_pct"]),
                       slippage_ticks=float(execution["slippage_ticks"]))
    trace: dict = {}
    trades, skipped = simulate(strategy, data, bars, spec, config, trace=trace,
                               bar_width=pd.Timedelta(timeframe))

    kept = [t for t in trades if t.exit_time > start and t.entry_time <= end]
    last = max([end] + [t.exit_time for t in kept])
    # A trade carried in from before the window is shown from its entry signal bar.
    begin = min([start] + [t.entry_time for t in kept])
    first_bar = int(bars.index.searchsorted(begin, side="left"))
    if begin < start:
        first_bar = max(0, first_bar - 1)
    last_bar = int(bars.index.searchsorted(last, side="right")) - 1
    decisions = []
    for bar in range(first_bar, last_bar + 1):
        story = explain_bar(strategy, trace, bar)
        decisions.append({key: story[key] for key in ("bar", "time", "action", "blocked_by",
                                                      "position", "events")})

    fingerprint = data_fingerprint(data)
    identity = {
        "epic": epic, "session": day.isoformat(), "timeframe": timeframe, "arm": arm,
        "strategy_sha256": version.sha256, "arm_parameters": arm_parameters,
        "engine_commit": engine_commit, "engine_dirty": engine_dirty, "data": fingerprint,
        "config": dataclasses.asdict(config),
    }
    digest = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()[:10]
    run_id = f"{epic.lower()}-{timeframe}-{version.version}-{arm}-{day.isoformat()}-{digest}"
    session = cash_session(day)
    record = {
        "run_id": run_id,
        "strategy": {"version": version.version, "parent": version.parent,
                     "sha256": version.sha256, "path": version.path},
        "session": {"date": day.isoformat(), "cash_open": session.open_utc.isoformat(),
                    "cash_close": session.close_utc.isoformat(), "early_close": session.early_close},
        "window": {"start": start.isoformat(), "end": end.isoformat(),
                   "display_start": bars.index[first_bar].isoformat(), "display_end": last.isoformat()},
        "timeframe": timeframe, "arm": arm, "arm_parameters": arm_parameters,
        "data": {"epic": epic, "fingerprint_sha256": fingerprint, "minutes": int(len(data)),
                 "first_minute": data.index[0].isoformat(), "last_minute": data.index[-1].isoformat(),
                 "cutoff": cutoff.isoformat(), "reserved_from": RESERVED_FROM.isoformat()},
        "engine": {"commit": engine_commit, "dirty": engine_dirty},
        "execution": {**execution, "sim_config": dataclasses.asdict(config),
                      "fills": "1-minute bid/ask; stop wins a minute that touches stop and target",
                      "costs": {"spread": "paid via bid/ask", "financing": spec["overnight_fee"]}},
        "calendar": "NYSE via pandas_market_calendars; review day = previous cash close to this close",
        "summary": {"trades_in_window": len(kept), "skipped_in_full_run": int(skipped)},
    }
    return DayRun(record=record, trades=[_trade_dict(t) for t in kept], decisions=decisions,
                  strategy=strategy, bars=bars, display=(first_bar, last_bar),
                  all_trades=[_trade_dict(t) for t in trades])


def write_run(run_dir: Path, day_run: DayRun) -> Path:
    """Write a run's record append-only. An existing run with the same id must be identical."""
    target = Path(run_dir) / day_run.record["run_id"]
    record = json.dumps(day_run.record, indent=2, default=str) + "\n"
    trades = json.dumps(day_run.trades, indent=2, default=str) + "\n"
    if target.exists():
        if (target / "run.json").read_text() != record or (target / "trades.json").read_text() != trades:
            raise FileExistsError(f"{target} exists with different content; runs are append-only")
        return target
    target.mkdir(parents=True)
    (target / "run.json").write_text(record)
    (target / "trades.json").write_text(trades)
    return target
