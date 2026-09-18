"""Diagnosis layers 0 and 1 (spec 6.2.3).

Layer 0 (power): how many trades are needed to detect a hypothesised edge at 80% power. Run this
BEFORE looking at a result — a test with too few trades cannot reject anything, and its verdict is
`inconclusive`, never `rejected`.

Layer 1 (signal): exit-free. Forward returns and MFE/MAE after the signal, against random entries
at MATCHED TIMES. Matching matters more than it looks: a signal that only fires at the New York
open would beat all-hours random entries on time-of-day alone, and that is not an edge in the
signal — it is an edge in the clock. Placebo entries are therefore drawn from the same
(weekday, hour) buckets as the real signal, so whatever survives is attributable to the rule.

Prices here are mids. Layer 1 is deliberately cost-free and exit-free: spread and financing are
layer 2's question, and exits are layer 3's. If a signal has no edge before costs, it has none
after them.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

# Two-sided 5% significance, 80% power: the usual z pair.
Z_ALPHA_TWO_SIDED = 1.959963984540054
Z_POWER_80 = 0.8416212335729143


def trades_for_power(effect: float, sd: float, z_alpha: float = Z_ALPHA_TWO_SIDED,
                     z_power: float = Z_POWER_80) -> int:
    """Trades needed to detect a mean effect of `effect` against noise `sd` (one-sample, vs zero)."""
    if effect == 0:
        return np.iinfo(np.int64).max
    return int(np.ceil(((z_alpha + z_power) * sd / abs(effect)) ** 2))


def forward_returns(mid: np.ndarray, entries: np.ndarray, horizon: int) -> np.ndarray:
    """Signed move in points from each entry bar's close to `horizon` bars later."""
    mid = np.asarray(mid, dtype=float)
    entries = np.asarray(entries, dtype=int)
    usable = entries[entries + horizon < len(mid)]
    return mid[usable + horizon] - mid[usable]


def excursions(high: np.ndarray, low: np.ndarray, mid: np.ndarray, entries: np.ndarray,
               horizon: int) -> tuple[np.ndarray, np.ndarray]:
    """Maximum favourable and adverse excursion within `horizon` bars, for a LONG."""
    high = np.asarray(high, dtype=float)
    low = np.asarray(low, dtype=float)
    mid = np.asarray(mid, dtype=float)
    entries = np.asarray(entries, dtype=int)
    usable = entries[entries + horizon < len(high)]
    if len(usable) == 0:
        return np.array([]), np.array([])
    offsets = np.arange(1, horizon + 1)
    window = usable[:, None] + offsets[None, :]
    entry_price = mid[usable]
    mfe = high[window].max(axis=1) - entry_price
    mae = entry_price - low[window].min(axis=1)
    return mfe, mae


def matched_placebo_entries(buckets: np.ndarray, signal_entries: np.ndarray, eligible: np.ndarray,
                            sets: int, seed: int) -> list[np.ndarray]:
    """Draw `sets` placebo entry sets matching the signal's (weekday, hour) composition.

    `buckets` labels every bar; `eligible` is the pool of bars a placebo may be drawn from — the
    same warm-up-excluded, in-range bars the signal could have fired on. A bucket with no eligible
    bars contributes nothing, and the shortfall is reported by the caller as a size mismatch.
    """
    rng = np.random.default_rng(seed)
    eligible = np.asarray(eligible, dtype=int)
    eligible_buckets = buckets[eligible]
    wanted = np.bincount(buckets[signal_entries], minlength=buckets.max() + 1)
    pools = {b: eligible[eligible_buckets == b] for b in np.flatnonzero(wanted)}
    out = []
    for _ in range(sets):
        drawn = [rng.choice(pools[b], size=count, replace=True)
                 for b, count in enumerate(wanted) if count and len(pools.get(b, ()))]
        out.append(np.concatenate(drawn) if drawn else np.array([], dtype=int))
    return out


def percentile_of(value: float, distribution: np.ndarray) -> float:
    """Where `value` sits in `distribution`, as a percentage (100 = beats every placebo)."""
    distribution = np.asarray(distribution, dtype=float)
    if len(distribution) == 0:
        return float("nan")
    return float((distribution < value).mean() * 100.0)


@dataclass
class SignalTest:
    horizon: int
    signal_n: int
    signal_mean: float
    signal_sd: float
    placebo_mean: float
    placebo_sd: float
    percentile: float
    signal_mfe: float
    signal_mae: float
    placebo_mfe: float
    placebo_mae: float

    @property
    def edge(self) -> float:
        return self.signal_mean - self.placebo_mean


def run_layer1(mid: np.ndarray, high: np.ndarray, low: np.ndarray, buckets: np.ndarray,
               signal_entries: np.ndarray, eligible: np.ndarray, horizon: int,
               placebo_sets: int = 200, seed: int = 20260918) -> SignalTest:
    signal_returns = forward_returns(mid, signal_entries, horizon)
    signal_mfe, signal_mae = excursions(high, low, mid, signal_entries, horizon)
    placebos = matched_placebo_entries(buckets, signal_entries, eligible, placebo_sets, seed)

    means, mfes, maes = [], [], []
    for entries in placebos:
        returns = forward_returns(mid, entries, horizon)
        if len(returns) == 0:
            continue
        means.append(returns.mean())
        placebo_mfe, placebo_mae = excursions(high, low, mid, entries, horizon)
        mfes.append(placebo_mfe.mean())
        maes.append(placebo_mae.mean())

    means = np.array(means)
    return SignalTest(
        horizon=horizon,
        signal_n=len(signal_returns),
        signal_mean=float(signal_returns.mean()) if len(signal_returns) else float("nan"),
        signal_sd=float(signal_returns.std(ddof=1)) if len(signal_returns) > 1 else float("nan"),
        placebo_mean=float(means.mean()) if len(means) else float("nan"),
        placebo_sd=float(means.std(ddof=1)) if len(means) > 1 else float("nan"),
        percentile=percentile_of(float(signal_returns.mean()), means) if len(signal_returns) else float("nan"),
        signal_mfe=float(signal_mfe.mean()) if len(signal_mfe) else float("nan"),
        signal_mae=float(signal_mae.mean()) if len(signal_mae) else float("nan"),
        placebo_mfe=float(np.mean(mfes)) if mfes else float("nan"),
        placebo_mae=float(np.mean(maes)) if maes else float("nan"),
    )


def shifted_placebo_entries(times: "np.ndarray", signal_entries: np.ndarray, sets: int, seed: int,
                            period_days: int = 7, max_shifts: int = 52) -> list[np.ndarray]:
    """Placebos that keep the signal's CLUSTERING, by shifting the whole set in time.

    Drawing placebo entries independently understates their variance whenever the real signal
    bunches up — RSI extremes cluster inside a selloff, so 1,600 entries carry far less
    independent information than 1,600 scattered ones, and the percentile against independent
    placebos is anti-conservative.

    Shifting the entire entry set by a whole number of weeks preserves both the clustering and the
    (weekday, hour) composition exactly, and destroys only the alignment with the signal. Entries
    that land outside the series, or on a timestamp with no bar, are dropped.
    """
    rng = np.random.default_rng(seed)
    order = np.argsort(times)
    sorted_times = times[order]
    offsets = rng.choice(np.concatenate([np.arange(-max_shifts, 0), np.arange(1, max_shifts + 1)]),
                         size=sets, replace=True)
    day = np.timedelta64(period_days * 24 * 60, "m")
    out = []
    for weeks in offsets:
        wanted = times[signal_entries] + weeks * day
        found = np.searchsorted(sorted_times, wanted)
        found = np.clip(found, 0, len(sorted_times) - 1)
        exact = sorted_times[found] == wanted
        out.append(order[found[exact]])
    return out
