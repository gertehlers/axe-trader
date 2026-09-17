"""Check an instrument's minute history against its trading hours and the spec §2.2 thresholds."""

from __future__ import annotations

import numpy as np
import pandas as pd

from engine.sessions import core_minutes, session_ids

MAX_MISSING_CORE_PCT = 0.5
MAX_GAP_MINUTES = 30
MAX_BAD_TICK_PCT = 0.01
MAX_MISSING_SESSIONS_PER_365D = 15
JUMP_MULTIPLE = 20

PAIRS = [("open_bid", "open_ask"), ("high_bid", "high_ask"), ("low_bid", "low_ask"), ("close_bid", "close_ask")]
ONE_MINUTE = pd.Timedelta(minutes=1)


def _iso(timestamp: pd.Timestamp) -> str:
    return timestamp.strftime("%Y-%m-%dT%H:%M:%SZ")


def _bad_ticks(frame: pd.DataFrame) -> pd.Series:
    crossed = np.zeros(len(frame), dtype=bool)
    for bid, ask in PAIRS:
        crossed |= (frame[bid] > frame[ask]).to_numpy()
    non_positive = (frame[[c for pair in PAIRS for c in pair]] <= 0).any(axis=1).to_numpy()
    mid_close = (frame["close_bid"] + frame["close_ask"]) / 2
    mid_range = ((frame["high_bid"] + frame["high_ask"]) - (frame["low_bid"] + frame["low_ask"])) / 2
    positive_ranges = mid_range[mid_range > 0]
    jumps = np.zeros(len(frame), dtype=bool)
    if len(positive_ranges):
        threshold = JUMP_MULTIPLE * positive_ranges.median()
        jumps = (mid_close.diff().abs() > threshold).to_numpy()
    return pd.Series(crossed | non_positive | jumps, index=frame.index)


def _missing_runs(missing: np.ndarray, minutes: pd.DatetimeIndex, sessions: np.ndarray):
    """Runs of consecutive missing core minutes inside one session: (start positions, end positions, lengths)."""
    positions = np.flatnonzero(missing)
    if len(positions) == 0:
        empty = np.array([], dtype=int)
        return empty, empty, empty
    contiguous = ((np.diff(positions) == 1)
                  & (sessions[positions[1:]] == sessions[positions[:-1]])
                  & np.asarray(minutes[positions[1:]] - minutes[positions[:-1]] == ONE_MINUTE))
    run_starts = np.concatenate([[0], np.flatnonzero(~contiguous) + 1])
    run_ends = np.concatenate([run_starts[1:] - 1, [len(positions) - 1]])
    return positions[run_starts], positions[run_ends], run_ends - run_starts + 1


def verify_instrument(frame: pd.DataFrame, spec: dict, excluded_minutes: int = 0) -> dict:
    hours = spec["opening_hours"]
    first, last = frame.index.min(), frame.index.max()
    core = core_minutes(first.floor("D"), last, hours)
    sessions = session_ids(core)
    present = np.asarray(core.isin(frame.index))

    per_session = pd.DataFrame({"session": sessions, "present": present}).groupby("session")["present"].sum()
    empty_sessions = per_session.index[per_session == 0].to_numpy()
    in_partial = ~np.isin(sessions, empty_sessions)

    partial_minutes = core[in_partial]
    partial_missing = ~present[in_partial]
    starts, ends, lengths = _missing_runs(partial_missing, partial_minutes, sessions[in_partial])
    longest_gap = int(lengths.max()) if len(lengths) else 0
    long_runs = np.argsort(-lengths, kind="stable")[: 20]
    long_gaps = [{"from": _iso(partial_minutes[starts[i]]), "to": _iso(partial_minutes[ends[i]] + ONE_MINUTE),
                  "minutes": int(lengths[i])} for i in long_runs if lengths[i] > MAX_GAP_MINUTES]
    missing_core = int(partial_missing.sum())
    missing_pct = 100.0 * missing_core / len(partial_minutes) if len(partial_minutes) else 100.0

    session_starts = pd.Series(core).groupby(sessions).first()
    days = max((last - first).total_seconds() / 86400, 1.0)
    missing_sessions = len(empty_sessions)
    missing_per_year = missing_sessions / days * 365

    bad = _bad_ticks(frame)
    bad_count = int(bad.sum())
    bad_pct = 100.0 * bad_count / len(frame)

    spread = (frame["close_ask"] - frame["close_bid"]).groupby(frame.index.hour).median()

    failures = []
    if missing_pct >= MAX_MISSING_CORE_PCT:
        failures.append("missing_core_pct")
    if longest_gap > MAX_GAP_MINUTES:
        failures.append("longest_gap")
    if bad_pct >= MAX_BAD_TICK_PCT:
        failures.append("bad_tick_pct")
    if missing_per_year > MAX_MISSING_SESSIONS_PER_365D:
        failures.append("missing_sessions")

    return {
        "epic": spec["epic"],
        "first_minute": _iso(first),
        "last_minute": _iso(last),
        "rows": int(len(frame)),
        "excluded_minutes": int(excluded_minutes),
        "core_minutes_expected": int(len(core)),
        "partial_session_core_minutes": int(len(partial_minutes)),
        "missing_core_minutes": missing_core,
        "missing_core_pct": missing_pct,
        "longest_gap_minutes": longest_gap,
        "long_gaps": long_gaps,
        "missing_sessions": int(missing_sessions),
        "missing_sessions_per_365d": missing_per_year,
        "missing_session_starts": [_iso(session_starts[s]) for s in empty_sessions][:50],
        "bars_outside_core": int((~frame.index.isin(core)).sum()),
        "bad_ticks": bad_count,
        "bad_tick_pct": bad_pct,
        "bad_tick_examples": [_iso(t) for t in bad[bad].index[:20]],
        "spread_median_by_hour": {f"{int(h):02d}": float(v) for h, v in spread.items()},
        "zero_volume_share": float((frame["volume"] == 0).mean()),
        "passed": not failures,
        "failures": failures,
    }


def main(argv: list[str] | None = None) -> None:
    import argparse
    import json
    from datetime import datetime, timezone
    from pathlib import Path

    from engine.data import excluded_minute_count, load_minutes
    from engine.instruments import load_instruments

    parser = argparse.ArgumentParser(description="Verify minute history per instrument")
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--instruments", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--epic", action="append", dest="epics")
    args = parser.parse_args(argv)

    specs = load_instruments(args.instruments)
    epics = args.epics or sorted(specs)
    reports = {}
    for epic in epics:
        frame = load_minutes(args.db, epic)
        if frame.empty:
            reports[epic] = {"epic": epic, "passed": False, "failures": ["no_data"]}
            print(f"{epic:<12} FAIL  no data")
            continue
        report = verify_instrument(frame, specs[epic], excluded_minute_count(args.db, epic))
        reports[epic] = report
        print(f"{epic:<12} {'PASS' if report['passed'] else 'FAIL'}  rows={report['rows']} "
              f"missing_core={report['missing_core_pct']:.3f}% longest_gap={report['longest_gap_minutes']}m "
              f"missing_sessions/yr={report['missing_sessions_per_365d']:.1f} "
              f"bad_ticks={report['bad_tick_pct']:.4f}% {report['failures']}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "db": str(args.db),
        "thresholds": {
            "max_missing_core_pct": MAX_MISSING_CORE_PCT,
            "max_gap_minutes": MAX_GAP_MINUTES,
            "max_bad_tick_pct": MAX_BAD_TICK_PCT,
            "max_missing_sessions_per_365d": MAX_MISSING_SESSIONS_PER_365D,
            "jump_multiple": JUMP_MULTIPLE,
        },
        "instruments": reports,
    }, indent=2))


if __name__ == "__main__":
    main()
