"""Why the strategy did what it did on one bar, quoted from the engine's own record.

Two sources, and nothing else:
  * the strategy's precomputed votes and their readings (`PillarVotes.readings`) — the same arrays
    the entry rule was decided from, so quoting them is quoting the decision;
  * the simulator's decision trace (`simulate(..., trace=...)`) — position state, fills, stop moves
    and exit triggers as they happened.

Nothing here looks past the bar being explained, and nothing is inferred from how the trade
turned out. If the record does not say why, the explanation does not either.
"""

from __future__ import annotations

import math

import numpy as np

SETUP = ("confluence entry: 4 pillars vote per side; enter when at least {threshold} agree on "
         "one side and the trend gate allows that side (LONG first if both qualify)")

RULES = {
    "LONG": {
        "RSI+BB": "RSI({rsi_period}) < {rsi_oversold} and close <= lower Bollinger({bb_period}, {bb_multiplier})",
        "Candle": "bullish engulfing, bullish harami or hammer",
        "S/R": "close within {proximity_atr_multiple} ATR({atr_period}) of the lowest close of the last {swing_lookback_bars} bars",
        "Vol+Trend": "tick volume > SMA({volume_sma_period}) of tick volume and close > EMA({ema_period})",
    },
    "SHORT": {
        "RSI+BB": "RSI({rsi_period}) > {rsi_overbought} and close >= upper Bollinger({bb_period}, {bb_multiplier})",
        "Candle": "bearish engulfing, bearish harami or shooting star",
        "S/R": "close within {proximity_atr_multiple} ATR({atr_period}) of the highest close of the last {swing_lookback_bars} bars",
        "Vol+Trend": "tick volume > SMA({volume_sma_period}) of tick volume and close < EMA({ema_period})",
    },
}
GATE_RULES = {"LONG": "close > EMA({trend_ema_period})", "SHORT": "close < EMA({trend_ema_period})"}

# Which readings each pillar's rule is decided from, per side.
PILLAR_READINGS = {
    "LONG": {
        "RSI+BB": ("rsi", "close", "bb_lower"),
        "Candle": ("bullish_engulfing", "bullish_harami", "hammer"),
        "S/R": ("close", "support", "proximity", "atr"),
        "Vol+Trend": ("volume", "volume_baseline", "close", "ema_fast"),
    },
    "SHORT": {
        "RSI+BB": ("rsi", "close", "bb_upper"),
        "Candle": ("bearish_engulfing", "bearish_harami", "shooting_star"),
        "S/R": ("close", "resistance", "proximity", "atr"),
        "Vol+Trend": ("volume", "volume_baseline", "close", "ema_fast"),
    },
}


def _value(array: np.ndarray, bar: int):
    value = array[bar]
    if isinstance(value, (bool, np.bool_)):
        return bool(value)
    value = float(value)
    return None if math.isnan(value) else round(value, 4)


def _side(strategy, bar: int, side: str) -> dict:
    votes, config = strategy.votes, strategy.config
    params = vars(config)
    table = votes.bullish if side == "LONG" else votes.bearish
    score = votes.bullish_score if side == "LONG" else votes.bearish_score
    gate = votes.long_gate if side == "LONG" else votes.short_gate
    pillars = [{
        "name": name,
        "vote": bool(table[name][bar]),
        "rule": RULES[side][name].format(**params),
        "readings": {key: _value(votes.readings[key], bar) for key in PILLAR_READINGS[side][name]},
    } for name in votes.names]
    return {
        "pillars": pillars,
        "score": int(score[bar]),
        "threshold": int(config.confluence_threshold),
        "gate": bool(gate[bar]),
        "gate_rule": GATE_RULES[side].format(**params),
        "gate_readings": {"close": _value(votes.readings["close"], bar),
                          "ema_trend": _value(votes.readings["ema_trend"], bar)},
    }


def _shortfall(side: str, detail: dict) -> str | None:
    if detail["score"] < detail["threshold"]:
        voted = [p["name"] for p in detail["pillars"] if p["vote"]]
        return (f"{side}: {detail['score']} of {detail['threshold']} votes needed"
                f" ({'+'.join(voted) if voted else 'no pillar voted'})")
    if not detail["gate"]:
        return f"{side}: {detail['score']} votes, but the trend gate is closed ({detail['gate_rule']} is false)"
    return None


def explain_bar(strategy, trace: dict, bar: int) -> dict:
    """The full decision at signal bar `bar`: votes, readings, gate, position and what happened.

    `action` is ENTER <side>, EXIT, HOLD (in a position, nothing happened) or NONE. `blocked_by`
    lists every recorded reason the entry rule did not fire here.
    """
    record = trace.get(bar, {})
    events = list(record.get("events", []))
    position = record.get("position")
    sides = {side: _side(strategy, bar, side) for side in ("LONG", "SHORT")}
    warm = bar >= strategy.warmup and bool(np.isfinite(strategy.votes.atr[bar]))

    blocked: list[str] = []
    if not warm:
        blocked.append(f"indicator warm-up: entries start after bar {strategy.warmup}")
    qualifying = [side for side, detail in sides.items()
                  if detail["score"] >= detail["threshold"] and detail["gate"]]
    if position is not None:
        for side in qualifying:
            blocked.append(f"{side} signal qualified, but a {position['side']} position was already open")
    else:
        for side, detail in sides.items():
            reason = _shortfall(side, detail)
            if reason and side not in qualifying:
                blocked.append(reason)
        blocked.extend(f"entry skipped: {e['why']}" for e in events if e["kind"] == "entry_skipped")

    kinds = [e["kind"] for e in events]
    if "entry_fill" in kinds:
        action = f"ENTER {next(e['side'] for e in events if e['kind'] == 'entry_fill')}"
    elif "exit" in kinds:
        action = "EXIT"
    elif position is not None:
        action = "HOLD"
    else:
        action = "NONE"

    return {
        "bar": int(bar),
        "time": strategy_time(strategy, trace, bar),
        "setup": SETUP.format(threshold=strategy.config.confluence_threshold),
        "exit_rule": type(strategy).__doc__.strip().splitlines()[0] if type(strategy).__doc__ else "",
        "warm": warm,
        "sides": sides,
        "position": position,
        "events": events,
        "action": action,
        "blocked_by": blocked,
    }


def strategy_time(strategy, trace: dict, bar: int) -> str:
    if bar in trace:
        return trace[bar]["time"]
    return strategy.bar_times[bar].isoformat()
