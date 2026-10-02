# Audit package: Brent 5m volatility / efficiency vs Supertrend 7/3 flips (2026-10-02)

## What was tested

**Hypothesis (the owner's):** short-term volatility expansion, a compression → expansion pattern, and Kaufman's efficiency ratio, alone or combined, can spot the transition from chop into a meaningful directional Brent move early enough to make Supertrend 7/3 reversal entries more selective.

**The experiment claims:** no. The audited claim is:

> ATR7/ATR21, volatility expansion/compression, Kaufman Efficiency Ratio and combinations of those
> features do not reliably distinguish good EARLY Supertrend 7/3 reversals from FALSE flips on Brent 5m
> data in 2024 and 2025.

## Files

| file | what it is | produced by |
|---|---|---|
| `../../experiments/2026-10-02-brent-vol-efficiency-swings.py` | **the original experiment** (unchanged) | — |
| `../../experiments/2026-10-02-brent-vol-efficiency-swings.txt` | its committed output | the original |
| `audit_checks.py` | self-audit: re-runs the original via `runpy`, checks the output is byte-identical, audits the data, indicators, zigzag, Supertrend and classification, and runs the statistics (bootstrap, null, sensitivity, cross-year models) | — |
| `rerun_original_with_corrected_zigzag.py` | the original's full report with only its zigzag swapped in memory for the audit's corrected one | — |
| `verify_independently.py` | **a second implementation from raw SQLite**; imports nothing from the engine or the experiment | — |
| `run_all.sh` | runs all three in order | — |
| `AUDIT_REPORT.md` | self-audit findings and the status (VERIFIED / WEAK / INVALID) of every original conclusion | written from the outputs |
| `METHODOLOGY.md` | exact formulas and pseudocode | — |
| `TEMPORAL_ALIGNMENT.md` | per-quantity table of earliest/latest data used and availability at trade time | — |
| `HEADLINE_RESULTS.csv` | class counts and P&L; EARLY vs FALSE AUCs with CIs and null p; vs-rest AUCs; cross-year models. Column `zigzag` = original or corrected | `audit_checks.py` |
| `NULL_TESTS.csv` | circular-shift null summaries per test (observed, null mean/SD/quantiles, p, seed, bootstrap SDs) | `audit_checks.py` |
| `SAMPLE_CASES.csv` | 56 flips (7 per class per year) with OHLC, indicators, swing, classification inputs and P&L | `audit_checks.py` |
| `CLASSIFICATION_WALKTHROUGH.md` | the rule evaluated in words for 24 of those flips | `audit_checks.py` |
| `MANUAL_CALCULATIONS.md` | 1m → 5m, TR, ATR7/21/100 recursion, ER7/14/21 rebuilt from source rows for 3 flips | `audit_checks.py` |
| `expected_from_original.json` | the reference numbers `verify_independently.py` compares against | `audit_checks.py` |
| `ENVIRONMENT.json`, `requirements-lock.txt` | Python and package versions, commit, seeds, data fingerprint | `audit_checks.py`, `pip freeze` |
| `outputs/` | full text logs: `audit_checks_output.txt`, `original_rerun.txt`, `original_with_corrected_zigzag.txt`, `verify_independently_output.txt`, `cross_year_models.csv` | the scripts |
| `CODEX_REVIEW_PROMPT.md` | the prompt for the independent reviewer | — |

## Data

- **Source:** `data/axe-trader.sqlite` at the repository root. It is **gitignored**, 1.5 GB, and lives only in this local checkout.
  - Table `historical_price`, `epic = 'OIL_BRENT'`, `resolution = 'MINUTE'`, `snapshot_time_utc < '2026-02-01T00:00:00Z'`, minus `price_exclusion` (a no-op here).
- **Fingerprint:** 689,021 minute rows, 2024-01-02 01:00 to 2026-01-30 21:58 UTC.
  - The SHA-256 of the price rows used is in `ENVIRONMENT.json` (`data_minutes_sha256`).
  - `audit_checks.py` recomputes it on every run. If yours differs, you are looking at different data.
- **Do not modify it.** Everything opens it read-only (`mode=ro`).
- **Data from 2026-02-01 onward is the owner's reserved final-exam period:**
  - The original filters it out in memory.
  - `verify_independently.py` never reads it.
  - Don't run anything on it.
- **Cache:** the engine keeps a parquet cache in `research/engine/.cache/` (gitignored). It is rebuilt automatically from the DB.

## Reproduce

Environment: Python 3.14.7 with the versions in `requirements-lock.txt`. Any Python ≥ 3.12 with pandas ≥ 2.2, numpy ≥ 2, pyarrow and pyyaml should work. The engine venv is `research/engine/.venv`.

```bash
# from the repository root; deterministic, about 4 minutes
bash research/audits/2026-10-02-brent-vol-efficiency/run_all.sh

# or step by step
cd research/engine
PYTHONPATH=. .venv/bin/python ../experiments/2026-10-02-brent-vol-efficiency-swings.py | diff - ../experiments/2026-10-02-brent-vol-efficiency-swings.txt   # empty diff
PYTHONPATH=. .venv/bin/python ../audits/2026-10-02-brent-vol-efficiency/audit_checks.py
PYTHONPATH=. .venv/bin/python ../audits/2026-10-02-brent-vol-efficiency/rerun_original_with_corrected_zigzag.py
.venv/bin/python ../audits/2026-10-02-brent-vol-efficiency/verify_independently.py          # exit 0 = agrees, 1 = disagreement printed
```

**Seeds:**
- The original samples controls with `default_rng(7)`.
- The audit uses `SEED = 20261002`, with per-test child seeds `[SEED, swing_atr, year, feature_index, zigzag_index]` (listed per row in `NULL_TESTS.csv`).
- Bootstrap and null B: 1,000 at 6 ATR and 500 for sensitivity.

## Expected headline results (6 ATR big swings)

| | 2024 original | 2024 corrected | 2025 original | 2025 corrected |
|---|---|---|---|---|
| EARLY n / net / per trade | 484 / +129.58 / +0.268 | 477 / +129.60 / +0.272 | 449 / +141.26 / +0.315 | 452 / +140.96 / +0.312 |
| FALSE n / net / per trade | 666 / −208.18 / −0.313 | 649 / −209.02 / −0.322 | 652 / −191.90 / −0.294 | 638 / −190.21 / −0.298 |
| AUC ATR7/ATR21 EARLY vs FALSE (hour / raw) | 0.493 / 0.511 | 0.488 / 0.498 | 0.448 / 0.479 | 0.446 / 0.468 |
| AUC ER21 EARLY vs FALSE (hour / raw) | 0.530 / 0.517 | 0.538 / 0.527 | 0.477 / 0.471 | 0.479 / 0.479 |
| features with null p < 0.05 (of 15 at 6 ATR; of 36 incl. sensitivity) | 0 / 0 | 1 / 2 | 7 / 14 | 7 / 14 |
| cross-year logistic, EARLY vs FALSE, all features (tested on this year) | 0.530 | 0.538 | 0.523 | 0.534 |

`verify_independently.py` must print `ALL HEADLINE NUMBERS AGREE`.

## Known limitations

These are detailed in `AUDIT_REPORT.md`:
- **Zigzag bug B1:** the original misplaces 6.9% of pivots. The corrected version is reported alongside it.
- **Circular class P&L (C1):** the labels and the P&L share the future path.
- **FALSE lumps premature-but-right flips** in with counter-wiggles.
- **Arbitrary class thresholds** (1/3, 12 candles). Only the swing size was varied.
- **One instrument, one timeframe, two years.** Bid-based indicators, spread and funding costs, no slippage.
- **The data isn't in git.** Reproducing needs this checkout's SQLite, verified by the SHA-256 fingerprint.
