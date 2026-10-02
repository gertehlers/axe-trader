# Temporal alignment: what each quantity reads, and when it is known

- **t** = the observation candle. For Supertrend analyses this is the flip candle i, known at its close.
- **"Trade time"** = the close of candle t. The order is filled at the open of t+1.
- **Leakage proof (empirical):** for 30 random flips, `audit_checks.py` recomputed every feature and the Supertrend state on the price series truncated at candle t, removing every later candle.
  - The largest difference was 2.2e-16 (floating point), with 0 Supertrend mismatches.

| item | feature or label | earliest data used | latest data used | future information required? | available at trade time? |
|---|---|---|---|---|---|
| TR[t] | input | C[t−1] | candle t (H, L) | no | yes |
| ATR7/14/21/100[t] | input | first candle of the series (decaying weight) | candle t | no | yes |
| r21 = ATR7/ATR21 | feature | series start | t | no | yes |
| slope6 | feature | series start | t | no | yes |
| r100 = ATR7/ATR100 | feature | series start | t | no | yes |
| a21_100 | feature | series start | t | no | yes |
| squeeze | feature | t−36 (plus ATR history) | t−7 | no | yes |
| release | feature | t−36 (plus ATR history) | t | no | yes |
| ER7 / ER14 / ER21 | feature | C[t−N] | C[t] | no | yes |
| d_erN | feature | C[t−2N] | C[t] | no | yes |
| serN (flip direction D) | feature | C[t−N] | C[t]; D = Supertrend direction at t | no | yes |
| UTC hour (stratum) | stratifier | t | t | no | yes |
| ATR7/ATR100 quintile edges (stratum) | stratifier | all classified flips or controls, both years | — | edges use the whole sample (not a predictor; only matching) | the edges no; the value yes |
| Supertrend dir[t], flip at t | event | series start | candle t close | no | yes (at close of t) |
| trade entry price | P&L | open of t+1 | open of t+1 | yes (one candle) | it is the fill |
| trade exit price | P&L | open of next flip + 1 | same | **yes** | no |
| funding | P&L | entry | exit | **yes** | no |
| zigzag pivot (swing start/end) | label | the swing's candles | the confirming candle, a median 30–32 and up to 185 candles after the pivot | **yes** | no |
| swing S containing t, its direction and size | label | S start (can be before t) | S end and its confirmation | **yes** | no |
| progress f (share of S done) | label | S start | S end (size) | **yes** | no |
| class EARLY/LATE/TURNED/FALSE | label | S start | S end, S' end, next flip | **yes** | no |
| big turn / small turn / control (sections 1–4 of the original) | label / sampling | pivots | confirmations | **yes** | no |
| features at offset t+k (k > 0) in trajectories | descriptive | — | t+k | yes, relative to the turn | not at the turn; they describe after-the-fact behaviour on purpose |
| logistic model weights | model | training year | training year | no (for the test year) | yes, if fitted on the past |
| standardisation mean/sd | model | training year only | training year | no | yes |

**Where leakage could hide, and the status of each:**

1. **Features reading candle t+1 or later.** Excluded by the truncation test, and by inspection of every rolling and shift call (all `shift(k ≥ 0)`, all windows ending at t).
2. **Labels feeding features.** None. `F[...]` is built before the zigzag runs, and no feature reads `big`, `fl` or `trades`.
3. **Class labels and P&L share the future path.** This is *circular by design* for the class P&L table. It is **not** leakage into the AUC test, because the features are frozen at t. See `AUDIT_REPORT.md` finding C1.
4. **Turn-anchored analyses (original sections 1–4).**
   - The *selection* of t is itself hindsight (t is a pivot). Features at a pivot are therefore conditioned on "price made an extreme here", which raises ER mechanically.
   - These analyses are descriptive only. The trade-time test is the flip analysis (section 5).
5. **Quintile edges for ATR7/ATR100 matching** use both years. They only define strata for matching and never enter a score.
