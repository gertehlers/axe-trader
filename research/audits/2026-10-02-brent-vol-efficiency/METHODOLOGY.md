# Methodology: Brent 5m volatility and efficiency vs Supertrend 7/3 flip quality

This file documents exactly what the original experiment
`research/experiments/2026-10-02-brent-vol-efficiency-swings.py` computes, plus the audit's additions
(marked **AUDIT**). Where the description and the code could differ, the code is authoritative and the
difference is a finding.

## 1. Data

- **Source:** the local SQLite database `data/axe-trader.sqlite` (gitignored), table `historical_price`,
  rows where `epic = 'OIL_BRENT'` and `resolution = 'MINUTE'`.
  - Columns: bid and ask OHLC, plus `last_traded_volume`.
  - Timestamps are UTC strings `YYYY-MM-DDTHH:MM:SSZ`.
- **Exclusions:** minutes listed in `price_exclusion` for OIL_BRENT are dropped (`engine/cache.py`).
  - There are 36 exclusion rows before 2026-02-01, and none of them is present in `historical_price`. Rejects are never written, so the drop removes nothing.
- **Reserved period:**
  - Rows from `2026-02-01T00:00Z` (`engine.dayrun.RESERVED_FROM`) are excluded. The engine cache loads the epic's full history into memory, and the experiment filters `m.index < RESERVED_FROM` before any computation.
  - `verify_independently.py` never reads those rows at all (SQL `WHERE snapshot_time_utc < '2026-02-01T00:00:00Z'`).
- **Range:** 689,021 minutes, 2024-01-02 01:00 to 2026-01-30 21:58 UTC.
  - Fingerprint: the SHA-256 of the price rows is in `ENVIRONMENT.json`.
- **5-minute candles:** `engine/bars.py` uses `resample("5min", label="left", closed="left")`.
  - open = first, high = max, low = min, close = last.
  - A candle exists if at least 1 minute is present; candles with fewer than 5 minutes are kept (7.6% of candles in scope).
  - All indicators use the **bid** OHLC.
- **Scope:** observations are candles from 2024-01-11 00:00 UTC to 2025-12-31 23:55 UTC, and never among the first 300 candles of the series.
  - Year = the UTC calendar year of the observation candle.
  - 2026 candles (to 2026-01-30) are used only as forward context for labels and trade exits.

## 2. True range and ATR

```
prevC[0] = C[0];  prevC[t] = C[t-1]
TR[t]    = max(H[t]-L[t], |H[t]-prevC[t]|, |L[t]-prevC[t]|)
ATR_n[0] = TR[0]                                        # engine: seeded on the first value (ta4j MMA)
ATR_n[t] = (ATR_n[t-1] * (n-1) + TR[t]) / n             # Wilder; includes the current candle t
```

- n = 7, 14, 21, 100.
- The seed has a weight of (1−1/n)^t. By the first observation (more than 2,000 candles in) that weight is below 1e-9 even for n = 100.
- `verify_independently.py` instead uses TradingView's `ta.rma`, seeded with the SMA of the first n TR values. The two agree on every audited number.
- TR, ATR and the zigzag run straight across market gaps, including weekends. A gap's open shows up in the TR of the first candle after it.

## 3. Features at candle t (all use candles ≤ t only)

| name | formula |
|---|---|
| r21 | ATR7[t] / ATR21[t] |
| slope6 | r21[t] − r21[t−6] |
| r100 | ATR7[t] / ATR100[t] |
| a21_100 | ATR21[t] / ATR100[t] |
| squeeze | mean(r21[t−36 … t−7]) (30 values) |
| release | r21[t] − min(r21[t−36 … t−1]) |
| erN (N = 7, 14, 21) | \|C[t] − C[t−N]\| / Σ_{k=t−N+1..t} \|C[k] − C[k−1]\| |
| d_erN | erN[t] − erN[t−N] |
| serN (flips only) | (C[t] − C[t−N]) / Σ\|ΔC\| × D, where D = trade direction (+1 long, −1 short) |

## 4. Supertrend 7/3 (`engine/indicators.py::supertrend`, TradingView `ta.supertrend` semantics)

```
mid = (H+L)/2;  up = mid + 3*ATR7;  lo = mid - 3*ATR7          # ATR7 as in section 2
lo[t] = lo[t]   if lo[t] > lo[t-1] or C[t-1] < lo[t-1] else lo[t-1]
up[t] = up[t]   if up[t] < up[t-1] or C[t-1] > up[t-1] else up[t-1]
dir[0] = -1
dir[t] = (+1 if C[t] > up[t] else -1) if dir[t-1] == -1 else (-1 if C[t] < lo[t] else +1)
```

- **Flip candle:** i is a flip candle if `dir[i] != dir[i−1]`. It is known at the close of candle i.
- **Matching evidence:** `research/engine/tests/test_supertrend.py`, and the handover's Capital.com screenshot checks (Brent 5m 2026-10-01 06:30Z = 96.557).
- **AUDIT:** `verify_independently.py` transcribes Pine v5 `ta.supertrend` literally, with an SMA-seeded RMA. It reproduces every classified flip, and therefore the identical counts and P&L.

## 5. Trades (`engine/flips.py::flip_trades`)

- **One trade per flip.** It enters at the **open of candle i+1** and exits at the **open of candle j+1**, where j is the next flip candle. The trade is always in the market, reversing at each flip. A flip with no next flip makes no trade.
- **Fills:**
  - Long: buy `open_ask`, sell `open_bid`.
  - Short: sell `open_bid`, buy `open_ask`.
  - So the real spread is paid at both ends. There is no extra slippage and no commission.
- **Funding:** `entry_price × rate/100` per 21:00 UTC cut-off strictly inside (entry, exit).
  - The rate is −0.01096% (long and short) from `instruments.yaml`.
  - The weekend multiplier is 1.0, which understates weekend holds.
- `net_pts = gross + funding`, for 1 unit.

## 6. Zigzag (labels only)

Threshold: `thr[t] = 6 × ATR14[t]`, the ATR of the **candle being tested**. It changes from candle to candle within a swing. Prices are bid **closes** only.

```
original (as coded):
  find the first t with |C[t]-C[0]| >= thr[t]; trend = sign; ext = t
  for each next candle i:
    if C[i] is strictly beyond C[ext] in the trend direction: ext = i
    elif |C[ext] - C[i]| >= thr[i]:                      # reversal confirmed at candle i
        emit swing (turn, ext, trend); known(next swing start) = i
        turn = ext; ext = i; trend = -trend              # <-- tracking of the new swing starts at i
  drop the first swing
```

**AUDIT finding B1:**
- Because the new swing's extreme starts at i, a more extreme close between the old pivot and i is never considered. Such a close can exist when that candle's own threshold was larger.
- This happens in 102 of 1,477 6 ATR swings (6.9%).
- The audit's **corrected** zigzag sets the new extreme to the most extreme close in (pivot, i], then re-plays the candles after it. With that change, 0 of 1,593 swings violate any property check.
- Both versions are reported throughout.

**Timing:**
- A swing **start** (pivot) is a past candle. It only becomes known at its confirmation candle: a median 30 candles later (original) or 32 (corrected), p90 64–66, max 185.
- Every zigzag-derived quantity is an outcome label, never a feature (see `TEMPORAL_ALIGNMENT.md`).

## 7. Flip classification (labels)

Inputs:
- flip candle i with direction D
- the ST exit candle x = (next flip) + 1
- S = the big swing with `start ≤ i < end`, with direction d, size |C[end] − C[start]|
- S' = the swing after S

Flips with no containing swing, or no S', are dropped.

```
if d == D:
    f = D*(C[i]-C[start]) / size          # share of S already done at the flip close
    EARLY  if f <= 1/3
    TURNED if f > 1/3 and end(S) < x      # S ended (the next big swing, against us, began) before our exit
    LATE   otherwise
else:
    EARLY if end(S) - i <= 12 and end(S) < x and D*(C[i]-C[end(S)]) <= size(S')/3
    FALSE otherwise                        # the flip points against a big swing that does not turn soon
```

**Mechanical consequences (AUDIT):**
- **Labels use future prices, and so does trade P&L.** FALSE flips win 1% of the time, EARLY flips 62–66%.
  - Class P&L is a property of the labelling, not evidence of an edge.
  - The legitimate use of the labels is as targets for features measured at candle i.
- **FALSE also contains flips that pointed the right way for the *next* swing** but came more than 12 candles before its turn. The walkthrough shows one at 2024-03-20 01:30.
- **"Big" is relative to ATR.** In quiet periods a 6 ATR swing can be only $0.25.

## 8. Statistics

- **AUC:** the Mann–Whitney probability that a positive scores higher than a negative, with ties counted as half.
  - The original uses pandas ranks.
  - The audit uses sorted-array counts, and the verifier uses all pairwise comparisons. All three agree.
- **Hour-stratified AUC:**
  - Compute the AUC within each UTC hour and average, weighted by n_pos × n_neg of that hour.
  - "hour + r100q" uses strata of hour × ATR7/ATR100 quintile. For turns, the quintile edges come from controls; for flips, from all classified flips pooled over both years.
- **Original significance claim:** "±0.025" came from the i.i.d. Hanley–McNeil SE for about 600 vs about 3,000 observations.
- **AUDIT uncertainty, per year, for EARLY vs FALSE:**
  - Day-block bootstrap (resample UTC days with replacement) and swing-block bootstrap (resample the big swing containing each flip).
  - B = 1,000 at 6 ATR and 500 for the sensitivity runs. Reported as percentile 95% CIs.
- **AUDIT null:**
  - Circular shift of the year's time-ordered class-label sequence against the feature sequence, by a random 50…n−50 flips, B as above.
  - Two-sided p = (1 + #{|null − 0.5| ≥ |obs − 0.5|}) / (B + 1).
  - This keeps the labels' autocorrelation (runs of FALSE inside one swing) and the features' autocorrelation, and breaks only their alignment.
- **Cross-year model:**
  - Logistic regression by Newton–Raphson, 50 iterations, with an intercept.
  - L2 = 0.01 on the non-intercept weights. Features are standardised with the training year's mean and SD. Rows with any NaN are dropped.
  - No feature selection: 15 feature sets were fixed in the code before the run.
  - It is fitted on one year and scored on the other.
  - The original task was **EARLY vs all other classes**. The audit adds **EARLY vs FALSE**.
- **Sensitivity (AUDIT):** big swings at 4, 6, 8 and 10 ATR. These are not used to choose a threshold.
