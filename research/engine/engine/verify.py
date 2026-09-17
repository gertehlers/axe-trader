"""Check an instrument's minute history against its trading hours and the spec §2.2 thresholds."""

from __future__ import annotations

import numpy as np
import pandas as pd

from engine.sessions import core_minutes, session_ids

# Owner decision 2026-09-17: Capital.com's own minute history misses ~2% of trading minutes in every hour
# (confirmed "prices not found" at the source), so the bar is set to what the data really is and every
# hole is listed rather than failing the instrument.
MAX_MISSING_CORE_PCT = 3.0
HOLE_MINUTES = 30
MAX_BAD_TICK_PCT = 0.01
MAX_MISSING_SESSIONS_PER_365D = 15
MAX_EDGE_GAP_SESSIONS_PER_365D = 20
# Sessions shorter than this (e.g. US500 21:05-21:59 UTC, a seasonal futures break Capital.com's published
# hours do not show) are reported but do not count toward the session rules.
MIN_COUNTED_SESSION_MINUTES = 60
# Published hours ignore daylight saving and some breaks, so real session boundaries drift by up to an hour.
# An edge gap longer than this is a genuine early close / late open (holidays); shorter is boundary drift.
BOUNDARY_DRIFT_MINUTES = 60
JUMP_MULTIPLE = 20
# Spike threshold uses the median mid range of the preceding hour; neighbours must be this close in time.
LOCAL_RANGE_WINDOW = 60
MAX_NEIGHBOUR_MINUTES = 5

PAIRS = [("open_bid", "open_ask"), ("high_bid", "high_ask"), ("low_bid", "low_ask"), ("close_bid", "close_ask")]
ONE_MINUTE = pd.Timedelta(minutes=1)


def _iso(timestamp: pd.Timestamp) -> str:
    return timestamp.strftime("%Y-%m-%dT%H:%M:%SZ")


def _bad_ticks(frame: pd.DataFrame) -> pd.Series:
    crossed = np.zeros(len(frame), dtype=bool)
    for bid, ask in PAIRS:
        crossed |= (frame[bid] > frame[ask]).to_numpy()
    non_positive = (frame[[c for pair in PAIRS for c in pair]] <= 0).any(axis=1).to_numpy()
    return pd.Series(crossed | non_positive | _reverting_spikes(frame), index=frame.index)


def _reverting_spikes(frame: pd.DataFrame) -> np.ndarray:
    """A bar whose mid close jumps away from both neighbours and snaps back.

    A persistent move (news, a weekend reopening) is real price action, not a bad tick, so it never counts.
    """
    mid = (frame["close_bid"] + frame["close_ask"]) / 2
    mid_range = ((frame["high_bid"] + frame["high_ask"]) - (frame["low_bid"] + frame["low_ask"])) / 2
    positive = mid_range.where(mid_range > 0)
    local = positive.rolling(LOCAL_RANGE_WINDOW, min_periods=10).median().shift(1)
    threshold = JUMP_MULTIPLE * local.fillna(positive.median())
    previous, following = mid.shift(1), mid.shift(-1)
    away, back = mid - previous, following - mid
    times = frame.index.to_series()
    near = pd.Timedelta(minutes=MAX_NEIGHBOUR_MINUTES)
    neighbours_close = ((times - times.shift(1)) <= near) & ((times.shift(-1) - times) <= near)
    spike = ((away.abs() > threshold) & (back.abs() > threshold) & (np.sign(away) != np.sign(back))
             & ((following - previous).abs() < 0.5 * away.abs()) & neighbours_close.to_numpy())
    return spike.fillna(False).to_numpy(dtype=bool)


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
    present = np.asarray(core.isin(frame.index))
    sessions = session_ids(core)

    per_session = pd.DataFrame({"session": sessions, "present": present}).groupby("session")["present"].agg(["sum", "size"])
    empty_sessions = per_session.index[per_session["sum"] == 0].to_numpy()
    long_sessions = set(per_session.index[per_session["size"] >= MIN_COUNTED_SESSION_MINUTES].tolist())
    counted_empty = [s for s in empty_sessions if s in long_sessions]
    in_partial = ~np.isin(sessions, empty_sessions)

    partial_minutes = core[in_partial]
    partial_missing = ~present[in_partial]
    partial_sessions = sessions[in_partial]
    starts, ends, lengths = _missing_runs(partial_missing, partial_minutes, partial_sessions)

    # A run touching its session's first or last minute is a late open or early close (holidays), not a hole.
    boundary = partial_sessions[1:] != partial_sessions[:-1]
    session_first = np.concatenate([[True], boundary])
    session_last = np.concatenate([boundary, [True]])
    at_edge = session_first[starts] | session_last[ends] if len(starts) else np.array([], dtype=bool)
    interior = ~at_edge

    interior_lengths = lengths[interior]
    longest_gap = int(interior_lengths.max()) if len(interior_lengths) else 0
    holes = [{"from": _iso(partial_minutes[starts[i]]), "to": _iso(partial_minutes[ends[i]] + ONE_MINUTE),
              "minutes": int(lengths[i])} for i in np.flatnonzero(interior & (lengths > HOLE_MINUTES))]
    edge_positions = np.flatnonzero(at_edge)
    drift_minutes = int(sum(lengths[i] for i in edge_positions if lengths[i] <= BOUNDARY_DRIFT_MINUTES))
    edge_indexes = [i for i in edge_positions
                    if lengths[i] > BOUNDARY_DRIFT_MINUTES and int(partial_sessions[starts[i]]) in long_sessions]
    edge_gaps = [{"from": _iso(partial_minutes[starts[i]]), "to": _iso(partial_minutes[ends[i]] + ONE_MINUTE),
                  "minutes": int(lengths[i])} for i in edge_indexes]
    edge_gap_sessions = len({int(partial_sessions[starts[i]]) for i in edge_indexes})
    missing_core = int(interior_lengths.sum())
    missing_pct = 100.0 * missing_core / len(partial_minutes) if len(partial_minutes) else 100.0

    session_starts = pd.Series(core).groupby(sessions).first()
    days = max((last - first).total_seconds() / 86400, 1.0)
    missing_sessions = len(counted_empty)
    missing_per_year = missing_sessions / days * 365
    edge_per_year = edge_gap_sessions / days * 365

    bad = _bad_ticks(frame)
    bad_count = int(bad.sum())
    bad_pct = 100.0 * bad_count / len(frame)

    spread = (frame["close_ask"] - frame["close_bid"]).groupby(frame.index.hour).median()

    failures = []
    if missing_pct >= MAX_MISSING_CORE_PCT:
        failures.append("missing_core_pct")
    if bad_pct >= MAX_BAD_TICK_PCT:
        failures.append("bad_tick_pct")
    if missing_per_year > MAX_MISSING_SESSIONS_PER_365D:
        failures.append("missing_sessions")
    if edge_per_year > MAX_EDGE_GAP_SESSIONS_PER_365D:
        failures.append("edge_gap_sessions")

    return {
        "epic": spec["epic"],
        "first_minute": _iso(first),
        "last_minute": _iso(last),
        "rows": int(len(frame)),
        "excluded_minutes": int(excluded_minutes),
        "core_minutes_expected": int(len(core)),
        "edge_gap_sessions": int(edge_gap_sessions),
        "edge_gap_sessions_per_365d": edge_per_year,
        "edge_gap_minutes": int(sum(lengths[i] for i in edge_indexes)),
        "boundary_drift_minutes": drift_minutes,
        "edge_gaps": edge_gaps[:50],
        "partial_session_core_minutes": int(len(partial_minutes)),
        "missing_core_minutes": missing_core,
        "missing_core_pct": missing_pct,
        "longest_gap_minutes": longest_gap,
        "holes_over_30m": holes,
        "holes_over_30m_per_365d": len(holes) / days * 365,
        "missing_sessions": int(missing_sessions),
        "missing_sessions_per_365d": missing_per_year,
        "missing_session_starts": [_iso(session_starts[s]) for s in counted_empty][:50],
        "short_missing_sessions": int(len(empty_sessions) - len(counted_empty)),
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
              f"holes>30m/yr={report['holes_over_30m_per_365d']:.1f} "
              f"edge_sessions/yr={report['edge_gap_sessions_per_365d']:.1f} "
              f"missing_sessions/yr={report['missing_sessions_per_365d']:.1f} "
              f"bad_ticks={report['bad_tick_pct']:.4f}% {report['failures']}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "db": str(args.db),
        "thresholds": {
            "max_missing_core_pct": MAX_MISSING_CORE_PCT,
            "hole_minutes": HOLE_MINUTES,
            "max_edge_gap_sessions_per_365d": MAX_EDGE_GAP_SESSIONS_PER_365D,
            "min_counted_session_minutes": MIN_COUNTED_SESSION_MINUTES,
            "boundary_drift_minutes": BOUNDARY_DRIFT_MINUTES,
            "max_bad_tick_pct": MAX_BAD_TICK_PCT,
            "max_missing_sessions_per_365d": MAX_MISSING_SESSIONS_PER_365D,
            "jump_multiple": JUMP_MULTIPLE,
        },
        "instruments": reports,
    }, indent=2))


if __name__ == "__main__":
    main()
