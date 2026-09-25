"""Before/after for one review session: a child strategy version against its parent.

Trades are matched explicitly, never by list position (plan §7):
  * same side and the same entry minute → `unchanged` (same exit) or `exit_changed`;
  * otherwise same side with entries at most `MOVE_BARS` signal bars apart → `moved`;
  * anything left is `removed` (parent only) or `new` (child only).

The owner's grades belong to the parent's trades. They are reported against the match, and they
never become grades of the child's trades: a new or changed trade is ungraded until reviewed.

A marked missed move counts as `caught` when a trade in the move's direction entered between
`CATCH_BEFORE_BARS` bars before the move's start and `CATCH_AFTER_SHARE` of the way through it.
"""

from __future__ import annotations

import pandas as pd

MOVE_BARS = 3
CATCH_BEFORE_BARS = 2
CATCH_AFTER_SHARE = 0.25


def _t(value) -> pd.Timestamp:
    return pd.Timestamp(value)


def match_trades(parent: list[dict], child: list[dict], bar_minutes: int) -> list[dict]:
    rows: list[dict] = []
    left = list(parent)
    right = list(child)
    for c in list(right):
        p = next((p for p in left if p["side"] == c["side"] and _t(p["entry_time"]) == _t(c["entry_time"])), None)
        if p is None:
            continue
        same_exit = _t(p["exit_time"]) == _t(c["exit_time"]) and p["exit_reason"] == c["exit_reason"]
        rows.append({"status": "unchanged" if same_exit else "exit_changed", "parent": p, "child": c})
        left.remove(p)
        right.remove(c)
    window = pd.Timedelta(minutes=MOVE_BARS * bar_minutes)
    for c in list(right):
        near = [p for p in left if p["side"] == c["side"]
                and abs(_t(p["entry_time"]) - _t(c["entry_time"])) <= window]
        if not near:
            continue
        p = min(near, key=lambda p: abs(_t(p["entry_time"]) - _t(c["entry_time"])))
        rows.append({"status": "moved", "parent": p, "child": c})
        left.remove(p)
        right.remove(c)
    rows += [{"status": "removed", "parent": p, "child": None} for p in left]
    rows += [{"status": "new", "parent": None, "child": c} for c in right]
    return sorted(rows, key=lambda r: _t((r["parent"] or r["child"])["entry_time"]))


def move_caught(move: dict, trades: list[dict], bar_minutes: int) -> dict | None:
    """The first trade that caught `move`, or None."""
    start, end = _t(move["from_time"]), _t(move["to_time"])
    lo = start - pd.Timedelta(minutes=CATCH_BEFORE_BARS * bar_minutes)
    hi = start + (end - start) * CATCH_AFTER_SHARE
    hits = [t for t in trades if t["side"] == move["direction"] and lo <= _t(t["entry_time"]) <= hi]
    return min(hits, key=lambda t: _t(t["entry_time"])) if hits else None


GOOD = {"good", "ok"}
BAD = {"poor", "bad"}


def scorecard(rows: list[dict], grades: dict[str, str], moves: list[dict], bar_minutes: int) -> dict:
    """Counts against the owner's grades (keyed '<parent trade id>--entry|exit') and marked moves."""
    def grade(row, decision):
        return grades.get(f"{row['parent']['id']}--{decision}") if row["parent"] else None

    kept = [r for r in rows if r["status"] in ("unchanged", "exit_changed")]
    gone = [r for r in rows if r["status"] == "removed"]
    parent_trades = [r["parent"] for r in rows if r["parent"]]
    child_trades = [r["child"] for r in rows if r["child"]]
    return {
        "bad_entries_removed": sum(grade(r, "entry") in BAD for r in gone),
        "good_entries_lost": sum(grade(r, "entry") in GOOD for r in gone),
        "good_entries_kept": sum(grade(r, "entry") in GOOD for r in kept),
        "bad_entries_kept": sum(grade(r, "entry") in BAD for r in kept),
        "bad_exits_changed": sum(grade(r, "exit") in BAD for r in rows if r["status"] == "exit_changed"),
        "bad_exits_unchanged": sum(grade(r, "exit") in BAD for r in rows if r["status"] == "unchanged"),
        "new_trades_ungraded": sum(r["status"] in ("new", "moved") for r in rows),
        "moves_caught_before": sum(move_caught(m, parent_trades, bar_minutes) is not None for m in moves),
        "moves_caught_after": sum(move_caught(m, child_trades, bar_minutes) is not None for m in moves),
        "moves": len(moves),
        "trades_before": len(parent_trades), "trades_after": len(child_trades),
        "r_before": round(sum(t["r"] for t in parent_trades), 3),
        "r_after": round(sum(t["r"] for t in child_trades), 3),
    }
