import pandas as pd

from engine.compare import CATCH_AFTER_SHARE, match_trades, move_caught, scorecard


def trade(entry, side="LONG", exit_="2024-01-11T16:00:00+00:00", r=0.5, tid=None):
    return {"id": tid or f"t-{entry}-{side}", "side": side, "entry_time": entry, "exit_time": exit_, "r": r,
            "exit_reason": "SIGNAL", "exit_price": 1.0, "entry_price": 1.0}


def test_same_entry_same_exit_is_unchanged_and_same_entry_new_exit_is_exit_changed():
    parent = [trade("2024-01-11T14:30:00+00:00"), trade("2024-01-11T18:00:00+00:00")]
    child = [trade("2024-01-11T14:30:00+00:00"),
             trade("2024-01-11T18:00:00+00:00", exit_="2024-01-11T19:00:00+00:00", r=1.0)]
    rows = match_trades(parent, child, bar_minutes=5)
    assert [r["status"] for r in rows] == ["unchanged", "exit_changed"]


def test_nearby_same_side_entry_is_moved_and_distant_ones_are_removed_and_new():
    parent = [trade("2024-01-11T14:30:00+00:00"), trade("2024-01-11T18:00:00+00:00")]
    child = [trade("2024-01-11T14:40:00+00:00"), trade("2024-01-11T20:00:00+00:00")]
    rows = match_trades(parent, child, bar_minutes=5)
    assert sorted(r["status"] for r in rows) == ["moved", "new", "removed"]


def test_opposite_side_at_the_same_time_is_not_a_match():
    rows = match_trades([trade("2024-01-11T14:30:00+00:00")],
                        [trade("2024-01-11T14:30:00+00:00", side="SHORT")], bar_minutes=5)
    assert sorted(r["status"] for r in rows) == ["new", "removed"]


def test_a_move_is_caught_by_a_same_direction_entry_near_its_start():
    move = {"direction": "LONG", "from_time": "2024-01-11T17:00:00Z", "to_time": "2024-01-11T19:00:00Z"}
    assert move_caught(move, [trade("2024-01-11T17:10:00+00:00")], bar_minutes=5)
    # later than CATCH_AFTER_SHARE of the move's duration: too late to count
    late = pd.Timestamp("2024-01-11T17:00Z") + pd.Timedelta(minutes=120 * CATCH_AFTER_SHARE + 10)
    assert not move_caught(move, [trade(late.isoformat())], bar_minutes=5)
    assert not move_caught(move, [trade("2024-01-11T17:10:00+00:00", side="SHORT")], bar_minutes=5)


def test_scorecard_counts_grades_only_where_the_owner_graded():
    parent = [trade("2024-01-11T14:30:00+00:00", tid="p1"), trade("2024-01-11T18:00:00+00:00", tid="p2")]
    child = [trade("2024-01-11T14:30:00+00:00", tid="c1")]
    rows = match_trades(parent, child, bar_minutes=5)
    grades = {"p1--entry": "good", "p2--entry": "bad", "p2--exit": "bad"}
    card = scorecard(rows, grades, moves=[], bar_minutes=5)
    assert card["bad_entries_removed"] == 1 and card["good_entries_kept"] == 1
    assert card["good_entries_lost"] == 0 and card["new_trades_ungraded"] == 0
