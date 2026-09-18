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
