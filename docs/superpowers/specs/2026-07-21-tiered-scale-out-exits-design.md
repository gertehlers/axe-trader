# Tiered (3-tier) Scale-Out Exits — Design Spec

**Date:** 2026-07-21
**Status:** Approved, not yet implemented
**Implements:** `docs/observability-and-exits-design.md` item 4
**Supersedes:** nothing. Extends the intrabar exit model built in item 2 (2026-07-04).

## Why this exists

The 2026-07-04 pnl audit proved the current all-or-nothing bracket is net-negative under honest
intrabar fills: ~80% of trades win, but the 0.75 ATR target caps every winner while the 3.0 ATR stop
lets losers run four times as far. Entry accuracy is not the problem; exit geometry is.

The Cloudflare dashboard round (merged 2026-07-21) corroborated this independently. On the
`emaCeil_3,0atr` candidate, once `net_pnl` and `max_drawdown` were fixed, the run reads **88% win
rate, net +0.85 pts/trade, max drawdown 83.54** against a total net of ~87 pts — meaning **the
deepest peak-to-trough drawdown is ~96% of everything the strategy made.** A strategy whose worst
drawdown erases its entire profit is not tradeable regardless of win rate.

This spec covers the fix: bank the position in thirds at rising targets, ratcheting the stop as each
tier fills.

## Success criteria (pre-registered — fixed before any results are seen)

This section is the anti-overfitting guard required by `.claude/CLAUDE.md`. It is written **before**
the sweep runs and must not be edited in response to results. If these criteria turn out to be the
wrong ones, that is a finding to record, not a reason to move the goalposts.

**Terminology.** *In-sample (IS)* is the data we tune against: 2024-12-04 → 2026-01-01.
*Out-of-sample (OOS)* is data deliberately held back and never tuned against: Jan–May 2026. Tuning
many configurations against one stretch of history guarantees that the best of them is partly fitted
to that history's noise, and nothing in that same data can tell you how much. The held-out window is
the only honest test: a real edge survives it, a fitted one collapses. This is why the current
profile's OOS result (−0.29 pts/trade) being *worse* than its IS result (−0.14) is diagnostic, not
incidental.

An arm is **promoted** only if it passes all of:

1. **Beats the control on net pts/trade, out-of-sample.** Held-out window Jan–May 2026, which has
   never been tuned against.
2. **Does not worsen max drawdown**, same window. This is the criterion the current profile fails.
3. **At least 30 trades** in the OOS window, so the result is not a small-sample artifact.
4. **Consistency: net-positive in ≥3 of the 4 in-sample quarters.** Guards against an arm carried
   by a single lucky quarter — the failure mode that a single aggregate number hides.

Surviving arms are ranked by **total net ÷ max drawdown** (a MAR-style ratio: most money, least
risk). The top-ranked survivor is the promotion candidate. If **no** arm passes, that is a valid and
reportable outcome — scale-out is not automatically the answer, and reporting "none passed" is
required rather than relaxing the gate.

**Multiple-comparison discipline.** Every arm tested is another chance for one to win in-sample by
luck. Stage 1 is deliberately small (9 arms) for this reason. The OOS window is the arbiter and is
consulted **once**, after the in-sample ranking is fixed. Arms are never re-ranked using OOS data.

**Reporting metrics (not gates).** Both are reported for every arm:
- `hitT1Rate` — fraction of trades whose first tranche reached T1 before the stop. This is the
  metric comparable to today's ~80–88% win rate, because it measures the same event.
- `winRate` (`pnl > 0`) — retained for continuity, but **it is inflated by construction** under
  scale-out: a trade that banks ⅓ at T1 and then stops at breakeven is a tiny net winner. It must
  never be compared against a pre-scale-out run as though it were like-for-like.

## Architecture

### The degenerate case is the safety net

`BacktestRunner.intrabarExit` currently walks bars forward and returns on the **first** stop/target
touch. It is generalised to walk a **ladder** of tiers: bank a fraction at each tier level, ratchet
the stop, and continue until the position is fully closed, stopped out, time-stopped, or the data
ends.

**A ladder of one tier at 100% size with no ratchet reproduces today's behaviour exactly.** This is
load-bearing, not incidental: it means the six existing tests in `BacktestRunnerIntrabarTest` keep
passing **unchanged** and pin the refactor of money-handling code. Any deviation in the single-tier
path is a regression, caught immediately.

### Conservative tie-break, extended

The existing rule: when one bar's high–low range spans both the stop and the target, OHLC cannot
tell which was touched first, so the **stop is assumed** — we never book an intrabar win we cannot
prove.

Extended to tiers: **if a bar spans the stop and any unfilled tier, the stop is assumed first and no
tier banks on that bar.** Deliberately pessimistic and consistent with the existing rule. Where the
ratchet has already moved the stop to breakeven or T1, that ratcheted level is the one used in the
comparison — so a bar spanning the ratcheted stop and T3 books the ratcheted stop.

Within a single bar, multiple tiers **may** fill if the stop is not also touched (a bar that gaps
through T1 and T2 fills both, in order).

### Data model

`TradeResult` keeps its existing shape so current consumers keep working, with these semantics under
a multi-tier ladder:

| Field | Meaning under tiers |
|---|---|
| `pnl` | **size-weighted sum** of the tier fills (⅓ × each fill's pnl) |
| `exitPrice` | size-weighted average of the tier fill prices |
| `exitTime` | exit time of the **final** tranche |
| `exitReason` | reason the **final** tranche closed |
| `isWin` | unchanged, `pnl > 0` — see the inflation warning above |
| `rMultiple` | `pnl / riskPerUnit`, unchanged formula, now over the blended pnl |

Two fields are **added**:
- `tiersFilled` (int, 0–3) — how many tranches banked at a target.
- `hitT1` (boolean) — whether the first tranche reached T1 before the stop.

`ExitReason` gains no new constants; the final tranche's reason is one of the existing
`TARGET`/`STOP`/`TIME`/`END`. A trade that banks T1 and T2 then stops out reports `STOP`, with
`tiersFilled = 2` — the pair carries the full story.

The fractional exits live **entirely in `BacktestRunner`**. ta4j Positions are all-or-nothing and
must not be forced to model sub-positions.

### Consumers

`DashboardExporter`, `ExperimentStore` and `BacktestChartExporter` all read `pnl`/`isWin` and
continue to work unchanged. `DashboardExporter` additionally gains `tiers_filled` and `hit_t1` so the
phone review can distinguish "stopped flat after banking two thirds" from "stopped for a full loss" —
visually a very different trade. The D1 schema change is a new migration; both columns are nullable
so existing rows remain valid.

## Configuration

New optional block under `backtest.strategy`. **Omitting it entirely leaves behaviour exactly as it
is today** — the runner falls back to the existing `target-atr-multiple` as a single 100% tier with
no ratchet. An un-migrated `application.yaml` therefore changes nothing, and the block below is the
*stage-1 experiment* configuration, not a new default:

```yaml
exit:
  tiers:                      # omitted or single-entry => current all-or-nothing behaviour
    - fraction: 0.3333
      target-atr-multiple: 0.75
    - fraction: 0.3333
      target-atr-multiple: 1.5
    - fraction: 0.3334        # absorbs the rounding remainder
      target-atr-multiple: 3.0
  ratchet: BREAKEVEN_AFTER_T1  # NONE | BREAKEVEN_AFTER_T1 | LAGGED
```

Fractions must sum to exactly 1.0; the final tier absorbs any rounding remainder. A config whose
fractions do not sum to 1.0 is a startup error, not a silent renormalisation.

**Ratchet arms:**
- `NONE` — stop stays at the original 3.0 ATR for the life of the trade.
- `BREAKEVEN_AFTER_T1` — after T1 fills, stop moves to entry; after T2 fills, stop moves to the T1
  level. (The design-doc default and the stated hypothesis.)
- `LAGGED` — stop holds at the original level until T2 fills, then moves to breakeven. Gives winners
  room to breathe at the cost of leaving more downside open.

## Sweep plan

**Stage 1 (this round) — 9 arms plus a control.**

- Levels: T1 0.75 / T2 1.5 ATR fixed; **T3 ∈ {2.0, 3.0, 4.0}**.
- Ratchet ∈ {`NONE`, `BREAKEVEN_AFTER_T1`, `LAGGED`}.
- Stop 3.0 ATR, entries **identical** to the current candidate so the scale-out effect is isolated.
- **Control arm:** the current single-target 0.75/3.0 geometry, run in the same sweep on the same
  data. All comparisons are against this control, not against historical numbers from earlier runs —
  which removes any doubt about differing windows, spreads or code versions.

T1 is held at 0.75 ATR because that is where the ~80% hit rate is already demonstrated; moving it
would change the entry edge and the exit geometry at once, confounding the result.

**Stage 2+ (queued, explicitly NOT run this round).** Recorded so they are not silently forgotten
and not quietly promoted into stage 1:
- Wider T1 grid (0.5 / 0.75 / 1.0) and T2 grid (1.5 / 2.0).
- A ½/½ two-tier ladder, to test whether the middle tier earns its place at all.
- A trailing final third (e.g. chandelier stop) instead of a fixed T3.
- Per-instrument re-tuning once a second instrument exists — none of these numbers are assumed to
  transfer (see the instrument-personality playbook).
- **Re-validation against a larger dataset.** Agreed at kickoff: the current windows are what we
  have today, and the promoted arm is to be re-tested against more data as it becomes available.
  A pass here is provisional until that happens.

## Testing

Unit tests on `intrabarExit`, driven by hand-built bar series with known highs/lows — the pattern
already established in `BacktestRunnerIntrabarTest`:

1. **Regression:** the six existing single-tier tests pass unchanged (the degenerate case).
2. Ladder fills all three tiers in order across separate bars; pnl is the size-weighted sum.
3. Multiple tiers fill within one bar when the stop is untouched.
4. Stop and an unfilled tier in the same bar → stop assumed, no tier banks that bar.
5. Ratchet `BREAKEVEN_AFTER_T1`: after T1, a return to entry closes the remainder at breakeven, not
   at the original stop.
6. Ratchet `LAGGED`: after T1 only, the original stop still applies.
7. `tiersFilled` and `hitT1` are correct for: full loss (0, false), stop after T1 (1, true), all
   three tiers (3, true).
8. Time stop and end-of-data with tiers partially filled close the remainder correctly.
9. Fractions not summing to 1.0 is rejected at startup.

Each test asserts a specific pnl figure computed by hand in the test, not a value read back from the
implementation.

## Risks

- **Scale-out is not guaranteed to help.** Banking ⅓ at 0.75 ATR still caps a third of every winner,
  and the breakeven ratchet will scratch out trades that would have run. The pre-registered gate
  exists precisely so a null result is reportable rather than massaged.
- **The win-rate number will look better while meaning less.** Flagged above; `hitT1Rate` is the
  honest comparison. This is the third instance in this project of tooling flattering the strategy,
  so it is called out explicitly rather than trusted to be remembered.
- **Refactoring money-handling code.** Mitigated by the degenerate-case regression tests, which fail
  loudly if single-tier behaviour shifts by even a fraction.
