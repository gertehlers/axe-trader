# Self-audit: Brent 5m volatility / efficiency vs Supertrend 7/3 flips

**Auditor:** Claude, the same agent that wrote the experiment. That's why the independent re-implementation and the Codex review exist.

**Audited code:** `research/experiments/2026-10-02-brent-vol-efficiency-swings.py` at commit `88bad42`.

**Audit run:** every number below comes from `outputs/audit_checks_output.txt`, `outputs/original_with_corrected_zigzag.txt` or `outputs/verify_independently_output.txt`. All of them are reproduced by `run_all.sh`.

## Central claim under audit

> ATR7/ATR21, volatility expansion/compression, Kaufman Efficiency Ratio and combinations of those
> features do not reliably distinguish good EARLY Supertrend 7/3 reversals from FALSE flips on Brent 5m
> data in 2024 and 2025.

**Verdict: VERIFIED, with one qualification the original report missed.**
- **The qualification:** 2025 shows several small but statistically significant univariate differences, 14 of 36 tests at p < 0.05. 2024 shows essentially none (0–2 of 36). None replicates in both years with the same direction.
- **Size:** effect sizes stay within |AUC − 0.5| ≤ 0.07.
- **Cross-year models:** models fitted on one year and tested on the other reach only 0.46–0.55 AUC for EARLY vs FALSE.

So "not reliably" holds, but "every feature is a coin flip in both years" is overstated.

## Findings (bugs and problems)

### B1 — Zigzag bug: the new swing's extreme ignores closes before the confirming candle (REAL BUG)

**Original behaviour:**
- When a reversal is confirmed at candle i, the new swing's running extreme starts at i.
- The threshold is 6 × ATR14 of the *candle being tested*, so it varies.
- An earlier candle between the old pivot and i can have had a *more extreme* close that didn't confirm because its own threshold was larger. The original never looks at it again.

**Evidence:** 102 of 1,477 swings at 6 ATR (6.9%) end at a pivot that isn't the swing's most extreme close. In all 102 cases, the true extreme lies before the candle that confirmed the swing's start (`audit_checks_output.txt` §3).
- Example: the down swing from 2024-01-09 13:30. A close of 76.689 at 14:40 is ignored, and the recorded low is 77.26 at 22:00.

**Correction (audit only; the original file is untouched):** after a confirmation, the new extreme = the most extreme close in (pivot, i], and the candles after it are replayed.
- Corrected: 1,593 swings, 0 property violations.
- A first fix attempt, without the replay, still missed 49 earlier confirmations. It was discarded, and that stage is visible in this audit's history.

**Effect on conclusions: none material.**

| (6 ATR, EARLY vs FALSE) | original | corrected |
|---|---|---|
| 2024 EARLY n, net/trade | 484, +0.268 | 477, +0.272 |
| 2024 FALSE n, net/trade | 666, −0.313 | 649, −0.322 |
| 2025 EARLY n, net/trade | 449, +0.315 | 452, +0.312 |
| 2025 FALSE n, net/trade | 652, −0.294 | 638, −0.298 |
| AUC range over 15 features | 0.448 – 0.570 | 0.446 – 0.567 |
| tests p < 0.05 (2024 / 2025, of 36) | 0 / 14 | 2 / 14 |
| cross-year EARLY vs FALSE, all features | 0.523 / 0.530 | 0.534 / 0.538 |
| 7/3 flip lag into big swings | median 10 candles, 35% done | 11 candles, 36% done |
| big turns per year (turn analysis) | 706 / 700 | 766 / 746 |

- The full original report re-run with the corrected zigzag is `outputs/original_with_corrected_zigzag.txt`. Every turn-analysis AUC moves by ≤ 0.03 and keeps the same reading.
- **Also affected, not re-run:** `research/experiments/2026-10-02-brent-swing-personality.py` uses the same zigzag code. Its coin-flip benchmark used the same code too, so that comparison was like-for-like, but its absolute swing counts are slightly off.

### R1 — The report described an EARLY vs FALSE comparison that the script never computed (REPORTING ERROR)

**Original report:** "Hour-matched AUC is 0.44–0.53 for every feature", under "None of the features separate EARLY from FALSE".

**What the script computed:** EARLY vs (LATE + TURNED + FALSE) and FALSE vs (EARLY + LATE + TURNED). There was no direct EARLY vs FALSE.

**Corrected (audit, EARLY vs FALSE, hour-stratified):**
- 2024: 0.474–0.530 (original zigzag), 0.471–0.538 (corrected).
- 2025: 0.448–0.570 (original), 0.446–0.567 (corrected).
- The 2025 extremes are ATR7/ATR21 0.448 (EARLY flips are calmer) and d_ER7 0.570 (EARLY flips have rising short-term efficiency).

### R2 — The cross-year model figure was attributed to the wrong task (REPORTING ERROR)

**Original report:** "The best combination of every feature, fitted on one year and tested on the other, reaches 0.58 / 0.61", in the EARLY vs FALSE paragraph.

**What it was:** EARLY vs **all other classes**. The audit reproduces it exactly: all features 0.577 / 0.600, all + signed ER 0.583 / 0.606.

**EARLY vs FALSE, which the claim is about:** 0.523 / 0.530 (all features) and 0.531 / 0.536 (all + signed ER), on the original zigzag. On the corrected zigzag: 0.534 / 0.538 and 0.538 / 0.542. The lower ends of the day-block 95% CIs are 0.49–0.51. Across all 15 sets the range is 0.46–0.55. So the correction makes the result *weaker*, which supports the central claim.

### C1 — Class P&L is circular (INTERPRETATION ERROR)

The EARLY/LATE/TURNED/FALSE labels are computed from the same future price path the flip-to-flip trade earns on:
- FALSE flips win 1% of the time (−0.31 / −0.29 per trade).
- EARLY flips win 62% / 66% (+0.27 / +0.31).

The original report's sentence "So the whole result is EARLY against FALSE" is arithmetically true but carries **no evidential weight**: the labels guarantee it. The labels remain legitimate *targets* for features frozen at the flip candle, and that is the only use the central claim makes of them.

### S1 — "Replicates in both years" was overstated (STATISTICAL)

- **Circular-shift null, EARLY vs FALSE, 6 ATR:** 0/36 tests at p < 0.05 in 2024 against 14/36 in 2025 (7 at p < 0.01) on the original zigzag; 2/36 against 14/36 on the corrected one.
  - The 2025 effects are consistent across the 4/6/8/10 ATR definitions: lower ATR7/ATR21 and ATR7/ATR100 at EARLY flips; higher ER7, d_ER7 and d_ER14.
  - None reaches p < 0.05 in both years in the same direction.
- **Reading:** a modest 2025-specific property, or a regime effect. It isn't a stable discriminator, and the cross-year models confirm it doesn't transfer.
- **Multiple testing:** 72 tests per zigzag version, with about 3.6 false positives expected at α = 0.05. The 2025 count is far above that, so the 2025 differences are probably real *for 2025*.

### S2 — The "±0.025 noise band" (STATISTICAL)

- **Where it was stated (big turn vs control, about 700 vs 3,500):** VERIFIED.
  - Day-block bootstrap 95% half-width: 0.018–0.024.
  - i.i.d. Hanley–McNeil: 0.022–0.024.
- **For the EARLY vs FALSE flip analysis (about 470 vs 650):** the band is wider.
  - Day-block half-width: 0.032–0.044.
  - The swing-block bootstrap gives the same SD (0.0196).
  - Circular-shift null SD: 0.0186.
- Any flip-level AUC within about 0.04 of 0.5 is noise.

### S3 — Dependence between observations (STATISTICAL)

- Classified flips average 2.83 per big swing (max 21).
- 69% of FALSE flips share a swing with another FALSE flip, so they are not independent.
- The original used no dependence-aware test. The audit adds day-block and swing-block bootstraps and a circular-shift null. The block SDs (≈ 0.020) are close to i.i.d. expectations, so dependence doesn't change any conclusion.

### M1 — The cross-year model is not clean out-of-sample (DISCLOSURE)

- **At the model level the split is honest:** standardisation and weights come only from the training year, L2 and the iteration count are fixed, and there is no feature selection.
- **The research design was not blind to the test year:**
  - The feature list includes ATR7/ATR100 because the 2026-10-01 anatomy study, which used both years, flagged it.
  - The class definitions (1/3 progress, 12 candles) and the feature lookbacks were chosen by the same researcher who had seen both years of earlier Brent results.
- Call it **cross-year replication of a pre-specified model**, not clean out-of-sample validation. It doesn't matter much here: the models fail anyway.

### D1 — Data notes (no defects found)

- **Integrity:**
  - 0 duplicate minutes, 0 OHLC violations (bid and ask), 0 crossed quotes.
  - Timezone UTC throughout. The SHA-256 of the price rows used is in `ENVIRONMENT.json`.
- **Gaps:**
  - 108 weekend gaps.
  - 13 holiday gaps over 3 hours: US holidays and Christmas/New Year, 6.5 h to 1.3 days.
  - 1,137 five-minute gaps over 5 min.
  - 7.6% of 5m candles have fewer than 5 minutes. They are kept.
  - Indicators and the zigzag run straight across gaps.
- **Reserved period:**
  - The report said data from 2026-02-01 is "never loaded". In fact the engine cache reads it into memory and the script filters it out before any computation.
  - No reserved candle reaches any calculation: the max candle used is 2026-01-30 21:55. `verify_independently.py` doesn't read it at all.
- **`price_exclusion`:** its 36 Brent minutes are not in `historical_price`, so the exclusion step is a no-op.

### L1 — Known limitations (not bugs)

- **FALSE lumps together two kinds of flip:** true counter-wiggles, and flips that point the right way for the *next* swing but come more than 12 candles before its turn. Example: 2024-03-20 01:30 in `CLASSIFICATION_WALKTHROUGH.md`.
- **The thresholds are arbitrary:** 1/3, 12 candles and 1/3 of the next swing. Only the swing size (4/6/8/10 ATR) was sensitivity-tested, as requested.
- **"Big" is relative to ATR.** In quiet stretches a 6 ATR swing can be about $0.25.
- **Costs:** spread at both ends plus funding (weekend multiplier 1.0). There is no slippage or commission model.
- **Two years of one instrument on one timeframe.**

## Indicator and implementation checks

| check | result |
|---|---|
| Original re-run output vs committed `.txt` | byte-identical |
| ATR7/14/21/100 re-derived from raw TR (SMA seed 3,000 candles back), 25 random candles | max diff 2.1e-15 |
| ER7/14/21 re-derived by explicit sums, 25 candles | max diff 0 |
| Features and Supertrend recomputed on data truncated at the flip (30 flips) | max diff 2.2e-16, 0 Supertrend mismatches |
| Supertrend: every entry is candle i+1, every exit is (next flip)+1 | true for all 4,078 trades |
| Parametrised re-implementation of the classification at 6 ATR | identical labels for all 3,912 flips |
| **Independent implementation** (raw SQLite, own aggregation, SMA-seeded RMA, Pine `ta.supertrend` transcription, own zigzag/classifier/P&L/pairwise AUC) | **all 32 headline numbers agree** (counts and net exact; AUC within 1e-4) for both zigzag versions |
| Manual calculations (`MANUAL_CALCULATIONS.md`): 1m → 5m candle, TR, ATR7/21/100 recursion, ER7/14/21, for 3 flips | match the implementation to 6 decimals |

Classification hand-checks: `CLASSIFICATION_WALKTHROUGH.md` evaluates the rule in words for 24 sampled flips (3 per class per year). `SAMPLE_CASES.csv` has 56 flips (7 per class per year) with every input a reader needs to recompute the class and the P&L.

## Status of every major conclusion from the original report

| # | conclusion (original report) | status | why |
|---|---|---|---|
| 1 | Central answer: these features can't identify the chop → move transition early enough to make 7/3 entries materially more selective ("no") | **VERIFIED** | The EARLY vs FALSE AUCs are 0.45–0.57 and none replicates across years. Cross-year EARLY vs FALSE ≤ 0.55. The independent implementation agrees. |
| 2 | "Hour-matched AUC 0.44–0.53 for every feature" (EARLY vs FALSE) | **INVALID as stated** | It was computed vs the rest (R1). The true EARLY vs FALSE range is 0.448–0.570. |
| 3 | "Best combination cross-year 0.58 / 0.61" (EARLY vs FALSE) | **INVALID as stated** | The task was EARLY vs rest (R2). EARLY vs FALSE gives 0.52–0.54. |
| 4 | Net per trade flat (≈ −0.05) across feature terciles | **VERIFIED** | The original output is reproduced byte-for-byte; the tercile rows are deterministic. |
| 5 | Volatility features show no information before big turns (AUC ≈ 0.5 at −48 … −12) | **VERIFIED** | Holds with both zigzags. The noise band is valid there (S2). |
| 6 | ATR7/ATR21 rises only at and after the turn (coincident or descriptive) | **VERIFIED** | Same pattern with both zigzags. |
| 7 | ER's high AUC at the turn is mechanical (it measures the leg that is ending) | **WEAK / UNCERTAIN** | Supported by the trajectory (rise into the pivot, collapse after) and by the null result at flips. It wasn't directly tested, for example by conditioning on the prior leg's size. |
| 8 | No compression → release signature | **VERIFIED** | Squeeze AUC changes sign between years (0.55 / 0.48). Release ≈ 0.5 before turns. |
| 9 | Regime-D enrichment comes from ER's built-in effect, not from volatility | **WEAK / UNCERTAIN** | It inherits #7. Volatility alone ≈ 0.5–0.57 is verified. |
| 10 | Flip classes replicate: EARLY 24% at +0.27/+0.31, FALSE 34% at −0.31/−0.29 | numbers **VERIFIED**; "the whole result is EARLY vs FALSE" as a finding **INVALID** | The labels are circular with P&L (C1). |
| 11 | The 7/3 flip enters big swings a median 10 candles late, 35% done | **VERIFIED** | Corrected zigzag: 11 candles, 36%. |
| 12 | Big turns are not concentrated in 12–16 UTC (about 20% everywhere) | **VERIFIED** | Same with both zigzags. |
| 13 | Higher ATR7/ATR100 → fewer EARLY flips (19–22% vs 27%), net flat | **VERIFIED** | Original tercile table. |
| 14 | Hour + ATR7/ATR100 matching leaves AUCs unchanged | **VERIFIED for turns; WEAK for flips** | At flips, matching moves AUCs by up to about 0.03 (2025 d_ER14 0.558). |
| 15 | "Every number very close between 2024 and 2025 … nothing is an edge" | **WEAK / UNCERTAIN** | 2025 has small significant effects that 2024 lacks (S1). "Nothing stable across years" is the defensible version. |
| 16 | ±0.025 noise band | **VERIFIED** for turn analyses; **INVALID** if applied to flips | Flip-level band ±0.032–0.044 (S2). |

## What I would want a sceptic to try hardest to break

1. **The zigzag definition.** Is the corrected version really the "textbook" one? Would a high/low-based zigzag change anything?
2. **The classification rule.** In particular, FALSE swallowing premature-but-right flips (L1). A different defensible rule could move the EARLY vs FALSE AUCs.
3. **The 2025-only effects (S1).** Are they a real regime change, or an artefact of the circular-shift null's calibration in 2025?
