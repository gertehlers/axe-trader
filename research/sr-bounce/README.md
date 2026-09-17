# Support/resistance bounce-vs-random test

**Question:** does US500 bounce off support/resistance levels more often than off arbitrary
nearby prices? If not, S/R is not worth building a strategy on.

Everything below was fixed and committed **before the first run**. Nothing here is tuned on
results; if a definition changes later, it is a new test with a new pre-registration commit.

## Data

- `historical_price`, epic `US500`, resolution `MINUTE`, mid price = (bid + ask) / 2.
- Range 2024-01-01 → 2026-08-06 (917,650 bars).
- **Trading day** = UTC date of (bar time + 2 h). Sessions open 22:00 or 23:00 UTC, so each
  session maps to one date.
- **A** = ATR(14, Wilder) on 5-minute mid bars (wall-clock buckets), taken from the last
  *completed* 5-minute bar. All distances are in A, so thresholds scale with volatility.

## Level families (real)

1. **Prior day** — previous trading day's high, low and close. Active for the whole next
   trading day.
2. **Swing pivots** — a 5m bar whose high (low) is strictly greater (less) than the 6 bars on
   each side. It becomes known only when the 6th right-hand bar completes (no lookahead), and
   stays active until the end of the next trading day.
3. **Round numbers** — multiples of 50 points. `x00` levels are also reported on their own.

## Placebo levels (control)

- Prior day and pivots: the real level shifted by ±2, ±3 and ±4 A (A at the level's creation),
  so there are six placebos per real level, with the same active window.
- Round numbers: 50k + 10, 20, 30 and 40 points.

Placebos go through exactly the same event machinery as real levels. A placebo can sit near a
real level; that contamination pushes the result *toward* no difference, so it is conservative.

## Event and outcome (identical for real and placebo)

For a level L, evaluated on 1-minute bars within its active window:

1. **Arm:** a 1m close ≥ L + 0.5 A arms a support test. A close ≤ L − 0.5 A arms a
   resistance test.
2. **Touch:** the first later bar with low ≤ L (support) or high ≥ L (resistance). Entry
   reference = L.
3. **Outcome**, with R = 1.0 A (A at touch):
   - **Bounce:** price reaches L + R (support) or L − R (resistance) first.
   - **Break:** price reaches L − R (support) or L + R (resistance) first.
   - On the touch bar only the break side is checked. That favours breaks, which is
     conservative for levels.
   - Both reached on the same later bar → **ambiguous**, excluded.
   - Neither within 120 minutes, or the trading day ends → **unresolved**, excluded.
4. After an outcome (or exclusion) the level must re-arm before the next touch.

R = 0.5 A and R = 2.0 A are also reported as robustness checks. R = 1.0 A is primary.

## Statistic and decision rule

- **Bounce rate** = bounces / (bounces + breaks), per family × side, real and placebo.
- **Effect** = real rate − placebo rate. 95% CI from a block bootstrap that resamples whole
  trading days (2,000 resamples, seed 20260917).
- Reported separately on **2024-01-01 → 2025-12-31** and **2026-01-01 → 2026-08-06**.

A family **passes** only if, at R = 1.0 A, on **both** periods, the CI lower bound is above 0
**and** the effect is at least **+3 percentage points**. Anything else is a fail, including a
pass on one period only. No re-definition to rescue a fail.
