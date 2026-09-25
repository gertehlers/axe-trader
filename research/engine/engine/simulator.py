"""The 1-minute bid/ask simulator (spec 3.4).

Signals are decided on resampled bars; fills are resolved on the raw minute series, always on
the side of the book the trade actually pays. The pessimistic choices are deliberate: a target
needs trade-through rather than a touch, and a minute that touches both stop and target resolves
to the stop. Optimism here is what made the old engine's results untrustworthy.

Spread is not a separate line item: a long pays the ask on entry and receives the bid on exit,
so the cost is already inside gross_usd. spread_cost_usd reports it for visibility, computed
from the quoted spread at entry and exit, and is NOT subtracted again.
"""

from __future__ import annotations

from dataclasses import dataclass

import pandas as pd

from engine.costs import charge_times, financing_usd, usd_per_point
from engine.sizing import position_size
from engine.strategy import BarsView, Enter, Exit, MoveStop, PositionView


@dataclass(frozen=True)
class SimConfig:
    starting_balance_usd: float = 2000.0
    risk_pct: float = 1.0
    slippage_ticks: float = 0.0
    tick_size: float = 0.0
    weekend_multiplier: float = 1.0
    fx_rate: float = 1.0


@dataclass
class Trade:
    entry_time: pd.Timestamp
    entry_price: float
    exit_time: pd.Timestamp
    exit_price: float
    side: str
    size: float
    stop: float
    target: float | None
    exit_reason: str
    gross_usd: float
    spread_cost_usd: float
    financing_usd: float
    slippage_usd: float
    net_usd: float
    r: float


def simulate(strategy, minutes: pd.DataFrame, signal_bars: pd.DataFrame, spec: dict,
             config: SimConfig, trace: dict | None = None,
             bar_width: pd.Timedelta | None = None) -> tuple[list[Trade], int]:
    """Run `strategy` over `signal_bars`, filling on `minutes`.

    `trace`, when given, is filled with the decision record the review page explains from:
    `{bar_index: {"time", "position", "intents", "events"}}` for every bar that held a position,
    produced an intent or had an event. A bar absent from it was flat and asked for nothing. The
    trace only observes; a traced run produces exactly the untraced trades.

    Timing, per signal bar: (1) stops and targets are resolved on the bar's own minutes; (2) the
    strategy decides on the closed bar; (3) its intents fill at the first minute at or after the
    bar's END (label + `bar_width`), never inside the bar whose close produced them.
    `bar_width` defaults to the smallest spacing between signal bars.
    """
    value_per_point = usd_per_point(spec, config.fx_rate)
    slip = config.slippage_ticks * config.tick_size
    trades: list[Trade] = []
    skipped = 0
    balance = config.starting_balance_usd

    minute_index = minutes.index
    open_bid = minutes["open_bid"].to_numpy()
    open_ask = minutes["open_ask"].to_numpy()
    high_bid = minutes["high_bid"].to_numpy()
    low_bid = minutes["low_bid"].to_numpy()
    high_ask = minutes["high_ask"].to_numpy()
    low_ask = minutes["low_ask"].to_numpy()

    position: dict | None = None

    def record(bar_index: int) -> dict | None:
        if trace is None:
            return None
        return trace.setdefault(bar_index, {"time": signal_bars.index[bar_index].isoformat(),
                                            "position": None, "intents": [], "events": []})

    def holding_bar(minute: int) -> int:
        """The signal bar whose period contains `minute`: where a stop or target hit belongs."""
        return int(signal_bars.index.searchsorted(minute_index[minute], side="right")) - 1

    def note(bar_index: int, event: dict) -> None:
        if trace is not None:
            record(bar_index)["events"].append(event)

    def close(exit_index: int, exit_price: float, reason: str, bar_index: int, why: str = "") -> None:
        nonlocal position, balance
        trade = _close(position, minute_index[exit_index], float(exit_price), reason, spec, config,
                       value_per_point, slip,
                       entry_spread=open_ask[position["entry_index"]] - open_bid[position["entry_index"]],
                       exit_spread=open_ask[exit_index] - open_bid[exit_index])
        trades.append(trade)
        balance += trade.net_usd
        note(bar_index, {"kind": "exit", "reason": reason, "why": why,
                         "time": trade.exit_time.isoformat(), "price": trade.exit_price,
                         "stop": trade.stop, "target": trade.target,
                         "net_usd": trade.net_usd, "r": trade.r})
        position = None

    width = bar_width if bar_width is not None else _infer_width(signal_bars.index)

    def resolve(scan_end: int) -> None:
        """Check stops and targets on every unchecked minute before `scan_end`."""
        if position is None:
            return
        # Advance the scan cursor: minutes already checked cannot trigger later, and re-scanning
        # them makes a long hold quadratic in the number of bars it spans.
        scan_start = position["scan_from"]
        position["scan_from"] = max(scan_start, scan_end)
        for i in range(scan_start, scan_end):
            if position["side"] == "LONG":
                if low_bid[i] <= position["stop"]:
                    price = open_bid[i] if open_bid[i] < position["stop"] else position["stop"]
                    close(i, price - slip, "STOP", holding_bar(i), _stop_why(position))
                    return
                if position["target"] is not None and high_bid[i] > position["target"]:
                    close(i, position["target"], "TARGET", holding_bar(i), "target traded through")
                    return
            else:
                if high_ask[i] >= position["stop"]:
                    price = open_ask[i] if open_ask[i] > position["stop"] else position["stop"]
                    close(i, price + slip, "STOP", holding_bar(i), _stop_why(position))
                    return
                if position["target"] is not None and low_ask[i] < position["target"]:
                    close(i, position["target"], "TARGET", holding_bar(i), "target traded through")
                    return

    for bar_index in range(len(signal_bars)):
        bar_end = signal_bars.index[bar_index] + width
        fills = int(minute_index.searchsorted(bar_end, side="left"))
        resolve(fills)                 # the bar's own minutes, before its close is acted on

        view = PositionView.flat() if position is None else PositionView(
            side=position["side"], entry_price=position["entry_price"], stop=position["stop"],
            target=position["target"], size=position["size"])

        intents = strategy.on_bar(BarsView(signal_bars, bar_index), view)
        if trace is not None and (view or intents):
            entry = record(bar_index)
            if view:
                entry["position"] = {"side": view.side, "entry_price": view.entry_price,
                                     "stop": view.stop, "target": view.target,
                                     "entry_time": position["entry_time"].isoformat(),
                                     "initial_stop": position["initial_stop"]}
            entry["intents"] = [_describe(intent) for intent in intents]

        for intent in intents:
            if isinstance(intent, Enter) and position is None:
                if fills >= len(minute_index):
                    note(bar_index, {"kind": "entry_skipped", "why": "no minute left to fill on"})
                    continue
                entry_price = (open_ask[fills] + slip) if intent.side == "LONG" else (open_bid[fills] - slip)
                stop_distance = abs(entry_price - intent.stop)
                if stop_distance <= 0:
                    note(bar_index, {"kind": "entry_skipped", "why": "stop is not beyond the fill"})
                    continue
                risk_usd = balance * config.risk_pct / 100.0 * intent.risk_r
                size = position_size(risk_usd, stop_distance, value_per_point,
                                     spec["min_deal_size"], spec["size_increment"])
                if size == 0.0:
                    skipped += 1
                    note(bar_index, {"kind": "entry_skipped", "why": "position size rounds to zero"})
                    continue
                position = {"side": intent.side, "entry_price": float(entry_price),
                            "stop": intent.stop, "target": intent.target, "size": size,
                            "entry_index": int(fills), "entry_time": minute_index[fills],
                            "scan_from": int(fills), "risk_usd": risk_usd,
                            "initial_stop": intent.stop}
                note(bar_index, {"kind": "entry_fill", "side": intent.side,
                                 "time": minute_index[fills].isoformat(),
                                 "price": float(entry_price), "stop": intent.stop,
                                 "target": intent.target, "size": size})
            elif isinstance(intent, MoveStop) and position is not None:
                note(bar_index, {"kind": "stop_move", "from": position["stop"], "to": intent.price})
                position["stop"] = intent.price
            elif isinstance(intent, Exit) and position is not None and fills < len(minute_index):
                price = (open_bid[fills] - slip) if position["side"] == "LONG" else (open_ask[fills] + slip)
                close(int(fills), price, "SIGNAL", bar_index, intent.why)

    resolve(len(minute_index))         # a position still open after the last bar

    return trades, skipped


def _infer_width(index: pd.DatetimeIndex) -> pd.Timedelta:
    if len(index) < 2:
        return pd.Timedelta(minutes=1)
    return pd.Series(index[1:] - index[:-1]).min()


def _describe(intent) -> dict:
    if isinstance(intent, Enter):
        return {"kind": "enter", "side": intent.side, "stop": intent.stop, "target": intent.target}
    if isinstance(intent, MoveStop):
        return {"kind": "move_stop", "to": intent.price}
    return {"kind": "exit", "why": intent.why}


def _stop_why(position: dict) -> str:
    if position["stop"] == position["initial_stop"]:
        return "initial stop hit"
    return "moved stop hit"


def _close(position: dict, exit_time: pd.Timestamp, exit_price: float, reason: str,
           spec: dict, config: SimConfig, value_per_point: float, slip: float,
           entry_spread: float, exit_spread: float) -> Trade:
    side, size = position["side"], position["size"]
    direction = 1.0 if side == "LONG" else -1.0
    gross = (exit_price - position["entry_price"]) * direction * size * value_per_point
    charges = charge_times(position["entry_time"], exit_time, spec["overnight_fee"]["charge_time_utc"])
    financing = financing_usd(side, size, position["entry_price"], spec, charges,
                              config.fx_rate, config.weekend_multiplier)
    # Reported for visibility only: the spread is already inside `gross`, since entry paid the
    # ask and exit received the bid. Subtracting it again would double-count.
    spread_cost = float((entry_spread + exit_spread) * size * value_per_point)
    slippage_usd = float(slip * size * value_per_point * 2)
    net = gross + financing
    return Trade(
        entry_time=position["entry_time"], entry_price=position["entry_price"],
        exit_time=exit_time, exit_price=exit_price, side=side, size=size,
        stop=position["stop"], target=position["target"], exit_reason=reason,
        gross_usd=float(gross), spread_cost_usd=spread_cost, financing_usd=float(financing),
        slippage_usd=slippage_usd, net_usd=float(net),
        r=float(net / position["risk_usd"]) if position["risk_usd"] else 0.0)
