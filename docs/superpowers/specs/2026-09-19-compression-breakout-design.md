# The volatility-compression breakout — design of record

**Status:** specified, pre-registered, **not yet run.**
**Hypothesis:** `research/ledger/H-0008-compression-breakout.md`
**Parent evidence:** [H-0007](../../../research/ledger/H-0007-run-census-timing.md) (timing),
[H-0006](../../../research/ledger/H-0006-confluence-entry-direction.md) (excursion profile)

## 1. Why this shape, and no other

Two independent measurements arrived at the same wall.

**H-0007** scanned for the runs the owner drew by hand (≥5 ATR within 9 bars, base rate 6.8% on
OIL_CRUDE) and asked what preceded them. Quiet volatility + above-average volume + the 00–09 UTC
session roughly **doubles** the odds of one, and that held from discovery into validation
(×1.66 → ×1.90). But of the filtered bars that ran, **47.9% / 50.8% went LONG** — a coin flip in
every oil cell.

**H-0006** measured the same thing from the trade side: the median OIL_CRUDE entry has a favourable
excursion of +1.67 ATR against an adverse excursion of **+2.08 ATR**. You must survive more than
you stand to make — which is exactly what not knowing the direction costs.

So the project has a signal with **real timing information and zero directional information**. A
breakout is the only structure that consumes that honestly: the filter picks the moment, and the
**market declares the side**. The price of knowing the direction is giving up the part of the move
that happens before the break. Everything below follows from that and adds nothing else.

This is explicitly *not* a return to the confluence entry. That entry is a mean-reversion design
(it fires against a completed ~1.1 ATR move, H-0006) and it is not used here at all.

## 2. The rule, in full

Every number below is either inherited from H-0007, taken from the project's existing config, or
named as an a-priori guard. **There are no free parameters left to tune**, which is what makes §4's
criteria meaningful.

### 2.1 Setup — bar `i` qualifies when all three hold

| condition | value | where it comes from |
|---|---|---|
| quiet | `ATR(14)` percentile over trailing 500 bars **< 0.40** | H-0007, unchanged |
| participation | `volume(i) > 1.2 × SMA(volume, 20)` | H-0007, unchanged |
| session | `hour(i) ∈ {00, 01, 07, 08, 09}` UTC | H-0007, unchanged |

### 2.2 The range

Over the **10 bars ending at `i`** (`swing-lookback-bars: 10`, the project's existing lookback in
`application.yaml`):

```
range_high  = max(high[i-9 .. i])
range_low   = min(low [i-9 .. i])
range_width = range_high - range_low
```

**Guard (a priori, not tuned):** skip the setup if `range_width < 0.5 × ATR(i)`. A range narrower
than half an average bar is not a range, and the broker's `min_step_distance` (0.001 on oil) makes
the resulting stop untradeable. No upper guard: a wide range simply produces a small position.

### 2.3 Entry

Within bars `i+1 .. i+3` (H-0007 measured "a run starts within 2 bars"; one bar of grace):

- **LONG** when `high ≥ range_high` → fill at `range_high + slip`
- **SHORT** when `low ≤ range_low` → fill at `range_low - slip`
- **First touch wins.** If one bar touches both boundaries, **skip the setup** — intrabar order is
  unknowable and assuming either way is the lookahead bug this project has already been bitten by.
- No new position is opened while one is open. No pyramiding.
- If no break occurs by `i+3`, the setup expires unused.

### 2.4 Exit — one configuration, deliberately

Whichever comes first:

1. **Stop** at the opposite range boundary, fixed at entry (LONG → `range_low`, SHORT →
   `range_high`). This is the definition of a failed break: price has retraced the entire range.
2. **Time stop** at **9 bars** after entry, filled at that bar's close. 9 is H-0007's own run
   duration — the move it is built to catch is over by then.

**No target. No trailing stop.** H-0006 showed a tight target throws away a tail-driven
distribution, and every additional exit variant is a new hypothesis, not a knob on this one.

### 2.5 Sizing and costs

- Position sized to risk **1%** of balance on the stop distance, as `SimConfig` already does.
- Spread taken from the bid/ask in the data, per bar — not a constant.
- **Slippage: 10 ticks** (`slippage_ticks=10`, `tick_size=0.001` → 0.010 pts on oil).
  This is the first strategy in the project to model it as non-zero, and it must be: a breakout
  enters on a stop order into a volatility expansion and exits through another stop. The simulator
  charges it on entry, on stop exits and on time exits alike.
- Financing as modelled. Immaterial at a ≤2.25h hold, but left on.

## 3. Data

| slice | window | use |
|---|---|---|
| discovery | 2024-01-01 → 2025-09-30 | **contaminated** — H-0007's filter was chosen here. Reported for completeness only; cannot support the hypothesis. |
| validation | 2025-10-01 → 2026-07-31 | **the primary test.** H-0007's lift was measured here, so the setup is known to fire here — but no breakout economics have ever been computed on it. |
| holdout | 2026-08-01 → | secondary. ~3,000 bars; expected to be underpowered, reported for direction only. |

Instruments: **OIL_CRUDE** (primary), **OIL_BRENT** (secondary, *not independent* — same
underlying), **US500** (secondary, expected to fail: H-0007's session condition inverts there).

`research/EXCLUDED-INSTRUMENTS.md` applies; NATURALGAS is not in any grid.

## 4. Pre-registered criteria

**Primary — OIL_CRUDE, validation slice. Passes only if all four hold:**

1. **n ≥ 100** trades.
2. Net expectancy per trade **> 0** after spread, 10-tick slippage and financing.
3. Bootstrap **95% CI on expectancy entirely above zero** (10,000 resamples, seed 20260919).
4. **Percentile ≥ 95 against its placebo** — the identical breakout mechanics fired at bars matched
   on hour-of-day but **without** the quiet and volume conditions, 200 sets. This is the one that
   matters: it isolates whether the H-0007 setup contributes anything beyond "breakouts on oil."

**Secondary — reported, cannot promote, a contradiction is recorded not fatal:** OIL_CRUDE holdout;
OIL_BRENT validation; discovery.

**Sensitivity — reported, not decisive:** slippage at 0, 5, 10, 20, 40 ticks. If the result depends
on slippage below 10 ticks it is not a real edge, and that will be stated plainly.

**On failure:** this specification is closed. A variant — different range length, a target, a
trailing stop, other session hours — is a **new hypothesis with a new id**, not a re-run of this
one. Iterating here and reporting the best is the failure mode this project restarted to escape.

## 5. What this cannot answer

- **Whether it survives live.** Intrabar fills are modelled from 1-minute bars; a stop order in a
  fast market is not guaranteed at its level. A passing backtest would earn a forward test in
  MONITOR mode, not capital.
- **Whether it transfers.** The session hours are oil's. US500 is included to demonstrate the
  failure, per the per-instrument "personality" goal, not to be fixed.
- **Direction, still.** The breakout does not predict the side; it *waits* for it. If both
  boundaries break in sequence the strategy takes whipsaw losses, and that cost is the whole risk
  of this design.
