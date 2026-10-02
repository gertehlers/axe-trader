# Independent review: Brent 5m volatility / efficiency vs Supertrend 7/3 flips

You are an independent, sceptical reviewer of a quantitative research result. Another AI agent (Claude) wrote the experiment, audited itself, and wrote a second implementation. **Do not trust Claude's conclusions, its self-audit, or its claim that the two implementations agree.** Your job is to try to prove the work wrong, and to say clearly if you can't.

Work from the repository root. Everything you need is in `research/audits/2026-10-02-brent-vol-efficiency/` (start with `README.md`), the original experiment `research/experiments/2026-10-02-brent-vol-efficiency-swings.py`, the engine `research/engine/engine/` (`indicators.py`, `flips.py`, `bars.py`, `cache.py`, `data.py`, `costs.py`), and the raw data `data/axe-trader.sqlite`.

## The claim under review

> ATR7/ATR21, volatility expansion/compression, Kaufman Efficiency Ratio and combinations of those
> features do not reliably distinguish good EARLY SuperTrend 7/3 reversals from FALSE flips on Brent 5m
> data in 2024 and 2025.

## Hard rules

1. **Execute, don't just read.** Run the reproduction and inspect the outputs yourself. A static code review alone is not acceptable.
   - Commands are below. They take about 4 minutes and are deterministic.
2. **Reproduce the headline numbers yourself.** Write your own small script (for example `research/audits/2026-10-02-brent-vol-efficiency/codex_check.py`) that computes at least the EARLY/FALSE counts, their net P&L, and the ATR7/ATR21 and ER21 AUCs for 2024 and 2025 from the raw SQLite rows.
   - Don't copy the original's or the verifier's functions: re-derive them from `METHODOLOGY.md` and your own reading.
3. **Do not modify** the original experiment, the engine, `audit_checks.py` or `verify_independently.py` to make numbers agree. If something disagrees, report the disagreement with both values and your explanation.
4. **Do not modify the data.** Open `data/axe-trader.sqlite` read-only.
   - **Never read or compute on rows at or after `2026-02-01T00:00:00Z`.** That is the owner's reserved final-exam period.
5. **Do not optimise anything or propose a trading strategy.** This is an audit.
6. Put anything you create under `research/audits/2026-10-02-brent-vol-efficiency/codex/`.

## Commands to run

```bash
bash research/audits/2026-10-02-brent-vol-efficiency/run_all.sh
# expect: "original output reproduced byte-for-byte" and "ALL HEADLINE NUMBERS AGREE"

cd research/engine
.venv/bin/python ../audits/2026-10-02-brent-vol-efficiency/verify_independently.py; echo "exit $?"
PYTHONPATH=. .venv/bin/python ../experiments/2026-10-02-brent-vol-efficiency-swings.py | diff - ../experiments/2026-10-02-brent-vol-efficiency-swings.txt
```

Also confirm the data fingerprint: compare the `data_minutes_sha256` in `ENVIRONMENT.json` with what `audit_checks.py` prints in `outputs/audit_checks_output.txt` §1.

## What to look for specifically

1. **Lookahead leakage.**
   - Does any feature at the flip candle t read candle t+1 or later? Check every `shift`, `rolling` and window bound in the original and in `audit_checks.py`'s truncation test.
   - Is the truncation test itself sound?
   - Is the Supertrend flip at candle i really known only at the close of i, with the entry at the open of i+1?
2. **Alignment errors.**
   - The 1m → 5m aggregation: label and closed side, and incomplete candles.
   - The year assignment, hour stratification, and the flip ↔ trade join (`signal_time`).
   - Off-by-one errors in `squeeze`, `release`, `slope6`, `d_erN`.
3. **Indicator correctness.**
   - TR, Wilder ATR (seeding, current-candle inclusion) and ER.
   - Supertrend 7/3 against TradingView `ta.supertrend` semantics.
   - Spot-check `MANUAL_CALCULATIONS.md` and `SAMPLE_CASES.csv` rows against the raw SQLite yourself.
4. **Zigzag and classification logic.**
   - Is finding B1 (the zigzag ignores closes before the confirming candle) real, and is the "corrected" zigzag actually correct? Look for remaining defects such as tie handling, the first swing, the replay loop, and the time-varying threshold.
   - Is the EARLY/LATE/TURNED/FALSE rule implemented as documented? Check at least 10 rows of `SAMPLE_CASES.csv` by hand against raw prices.
   - Does the rule bias the comparison? For example, FALSE swallowing "premature but right" flips, or the 1/3 and 12-candle thresholds.
5. **Statistical dependence.**
   - Flips cluster within swings and days.
   - Are the day-block and swing-block bootstraps and the circular-shift null implemented correctly and appropriate?
   - Is the null miscalibrated? Check why 2025 shows 14/36 tests at p < 0.05 while 2024 shows 0–2/36.
6. **Misleading AUC interpretation.**
   - Hour-stratified vs raw AUC, and the direction of effects (AUC < 0.5 is also information).
   - Multiple testing across features, thresholds, years and zigzag versions.
   - Whether "not reliably" is the right reading of small effects in one year.
7. **Label / P&L circularity.**
   - The class labels use the same future path as the trade P&L (the self-audit's finding C1). Does any part of the *central* claim rely on that circular P&L?
   - Is there any other place where outcome information leaks into a feature or a stratifier? The ATR7/ATR100 quintile edges use both years; is that harmless?
8. **The cross-year model** (`audit_checks.py` §8 and `outputs/cross_year_models.csv`).
   - Is training really confined to one year: standardisation, weights, any NaN handling?
   - Is calling it "cross-year replication, not clean out-of-sample" (finding M1) fair?
9. **Self-audit honesty.**
   - Do `AUDIT_REPORT.md`'s VERIFIED / WEAK / INVALID labels match the evidence in `outputs/`?
   - Did the self-audit miss anything, or bury anything?

## What to deliver

Write `research/audits/2026-10-02-brent-vol-efficiency/codex/CODEX_REVIEW.md` with:

1. **Commands you ran**, with exit codes and the key lines of output.
2. **Your independently computed headline numbers**, next to Claude's (the README table), noting any difference.
3. **Findings**, each with severity (critical / major / minor), evidence (file:line, data rows, numbers), and whether it changes the central conclusion.
4. **Disagreements** with Claude's implementations or self-audit, unresolved and stated plainly.
5. **Verdict:** does the evidence support the central claim? Answer one of:
   - SUPPORTED
   - SUPPORTED WITH QUALIFICATIONS (say which)
   - NOT SUPPORTED
   - CANNOT DETERMINE (say what's missing)

   Give a short justification.

Be concrete. "Looks fine" without an executed check behind it doesn't count.
