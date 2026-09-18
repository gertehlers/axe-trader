import pandas as pd
import pytest

from engine.simulator import SimConfig, simulate
from engine.strategy import Enter, MoveStop

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


def run(strategy, minutes, spec=SPEC, config=None):
    return simulate(strategy, minutes, signal_bars_from(minutes), spec, config or SimConfig())


def test_target_needs_trade_through_not_touch():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 102.0, 99.8, 101.0),   # high_bid == target exactly: no fill
        ("2024-01-02T09:02:00Z", 101.0, 103.0, 100.5, 102.5),  # high_bid > target: fills
    ])
    trades, skipped = run(EnterOnFirstBar(stop=98.0, target=102.0), minutes)

    assert len(trades) == 1
    assert trades[0].exit_reason == "TARGET"
    assert trades[0].exit_time == pd.Timestamp("2024-01-02T09:02:00Z")
    assert trades[0].exit_price == 102.0


def test_stop_and_target_in_the_same_minute_resolves_to_the_stop():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 103.0, 97.0, 99.0),    # both touched
    ])
    trades, _ = run(EnterOnFirstBar(stop=98.0, target=102.0), minutes)

    assert trades[0].exit_reason == "STOP"
    assert trades[0].exit_price == 98.0


def test_gap_through_the_stop_fills_at_the_open_not_the_stop():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 95.0, 95.5, 94.0, 94.5),      # opens below the 98 stop
    ])
    trades, _ = run(EnterOnFirstBar(stop=98.0, target=110.0), minutes)

    assert trades[0].exit_reason == "STOP"
    assert trades[0].exit_price == 95.0


def test_long_enters_on_the_next_minute_ask():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:02:00Z", 100.0, 103.0, 99.9, 102.5),
    ])
    trades, _ = run(EnterOnFirstBar(stop=98.0, target=102.0), minutes)

    assert trades[0].entry_price == pytest.approx(100.2)  # ask at 09:01 open
    assert trades[0].entry_time == pd.Timestamp("2024-01-02T09:01:00Z")


def test_short_enters_on_the_bid_and_stops_on_the_ask():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:02:00Z", 100.0, 102.5, 99.9, 102.0),   # high_ask 102.7 >= 102 stop
    ])
    trades, _ = run(EnterOnFirstBar(stop=102.0, target=95.0, side="SHORT"), minutes)

    assert trades[0].entry_price == pytest.approx(100.0)  # bid at 09:01 open
    assert trades[0].exit_reason == "STOP"
    assert trades[0].exit_price == pytest.approx(102.0)


def test_trade_below_min_deal_size_is_skipped_and_counted():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 103.0, 99.0, 102.5),
    ])
    spec = dict(SPEC, min_deal_size=10_000.0)
    trades, skipped = run(EnterOnFirstBar(stop=98.0, target=102.0), minutes, spec=spec)

    assert trades == []
    assert skipped == 1


def test_position_held_through_the_cut_off_pays_financing():
    minutes = minutes_frame([
        ("2024-01-02T20:58:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T20:59:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-03T09:00:00Z", 100.0, 103.0, 99.9, 102.5),
    ])
    trades, _ = run(EnterOnFirstBar(stop=98.0, target=102.0), minutes)

    assert trades[0].financing_usd < 0
    assert trades[0].net_usd < trades[0].gross_usd


def test_r_is_net_over_the_risked_amount():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:02:00Z", 100.0, 103.0, 99.9, 102.5),
    ])
    config = SimConfig(starting_balance_usd=2000.0, risk_pct=1.0)
    trades, _ = run(EnterOnFirstBar(stop=98.0, target=102.0), minutes, config=config)

    assert trades[0].r == pytest.approx(trades[0].net_usd / 20.0, rel=1e-9)


def test_only_one_position_is_open_at_a_time():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.0, 100.0, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 100.2, 99.9, 100.0),
        ("2024-01-02T09:02:00Z", 100.0, 100.2, 99.9, 100.0),
        ("2024-01-02T09:03:00Z", 100.0, 103.0, 99.9, 102.5),
    ])

    class AlwaysEnter:
        timeframe = "1min"

        def on_bar(self, history, position):
            return [] if position else [Enter(side="LONG", stop=98.0, target=102.0)]

    trades, _ = run(AlwaysEnter(), minutes)

    assert len(trades) == 1


def test_position_held_across_several_signal_bars_exits_on_the_right_minute():
    """Exercises the scan cursor: minutes are checked once, and only once, in order."""
    rows = [("2024-01-02T09:00:00Z", 100.0, 100.1, 99.9, 100.0)]
    for m in range(1, 15):
        rows.append((f"2024-01-02T09:{m:02d}:00Z", 100.0, 100.1, 99.9, 100.0))
    rows[12] = ("2024-01-02T09:12:00Z", 100.0, 100.1, 97.0, 97.5)  # stop breached here
    minutes = minutes_frame(rows)
    signal_bars = minutes.resample("5min").agg(
        {"open_bid": "first", "open_ask": "first", "high_bid": "max", "high_ask": "max",
         "low_bid": "min", "low_ask": "min", "close_bid": "last", "close_ask": "last",
         "volume": "sum"})
    signal_bars["minutes_present"] = 5
    signal_bars["complete"] = True

    trades, _ = simulate(EnterOnFirstBar(stop=98.0, target=110.0), minutes, signal_bars,
                         SPEC, SimConfig())

    assert len(trades) == 1
    assert trades[0].exit_reason == "STOP"
    assert trades[0].exit_time == pd.Timestamp("2024-01-02T09:12:00Z")


def test_move_stop_applies_to_later_minutes_only():
    minutes = minutes_frame([
        ("2024-01-02T09:00:00Z", 100.0, 100.1, 99.9, 100.0),
        ("2024-01-02T09:01:00Z", 100.0, 100.1, 99.5, 100.0),   # would not hit a 99.0 stop
        ("2024-01-02T09:02:00Z", 100.0, 100.1, 99.4, 99.6),    # hits the raised 99.5 stop
    ])

    class RaiseStopAfterEntry:
        timeframe = "1min"

        def __init__(self):
            self._bars = 0

        def on_bar(self, history, position):
            self._bars += 1
            if self._bars == 1:
                return [Enter(side="LONG", stop=99.0, target=110.0)]
            if self._bars == 2 and position:
                return [MoveStop(price=99.5)]
            return []

    trades, _ = run(RaiseStopAfterEntry(), minutes)

    assert len(trades) == 1
    assert trades[0].exit_reason == "STOP"
    assert trades[0].stop == 99.5
    assert trades[0].exit_time == pd.Timestamp("2024-01-02T09:02:00Z")
