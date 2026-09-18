"""Position sizing (spec 3.4).

    size = floor_to_step(account_risk_usd / (stop_distance * usd_value_per_point))

Always floored, never rounded up: rounding up risks more than the account allows. A size under
the instrument's minimum deal size returns 0.0, and the caller records a skipped trade rather
than quietly trading a size the broker would reject.
"""

from __future__ import annotations

from decimal import ROUND_FLOOR, Decimal


def floor_to_step(value: float, step: float) -> float:
    if step <= 0:
        raise ValueError(f"step must be positive, got {step}")
    steps = (Decimal(str(value)) / Decimal(str(step))).to_integral_value(rounding=ROUND_FLOOR)
    return float(steps * Decimal(str(step)))


def position_size(risk_usd: float, stop_distance: float, usd_value_per_point: float,
                  min_deal_size: float, size_increment: float) -> float:
    if stop_distance <= 0:
        raise ValueError(f"stop_distance must be positive, got {stop_distance}")
    if usd_value_per_point <= 0:
        raise ValueError(f"usd_value_per_point must be positive, got {usd_value_per_point}")
    raw = risk_usd / (stop_distance * usd_value_per_point)
    size = floor_to_step(raw, size_increment)
    return 0.0 if size < min_deal_size else size
