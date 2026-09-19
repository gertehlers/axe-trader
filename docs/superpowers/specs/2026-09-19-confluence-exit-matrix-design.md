# Confluence exit matrix — design

Date: 2026-09-19
Status: proposed, awaiting owner review
Supersedes: nothing. Extends `2026-09-17-research-restart-design.md` (the plan of record).

## 0. Decisions (owner, 2026-09-19)

| # | decision |
|---|---|
| D1 | Strategy path is the **confluence**, not a new signal family. |
| D2 | The wide stop is a **disaster brake only**; something else performs the exit. |
| D3 | Test exit mechanisms **1, 2 and 3 separately**, and compare across instruments — the owner expects volatile and non-volatile instruments to want different stops. |
| D4 | **Layer-1 test the entry first**, then build the matrix regardless of that result. |
| D5 | Account balance **$2,000**, 1% risk. |
| D6 | Report in **percentage of account and R**, not dollars. The amount does not matter; the percentage does. |
| D7 | Also test the **symmetric 1:1** target/stop variant — narrow target with a wide stop has not worked, the wins are too small. |
| D8 | **NATURALGAS is excluded** from all research (`research/EXCLUDED-INSTRUMENTS.md`). |
| D9 | **Shorts stay enabled on every instrument, US500 included.** At this stage the project is not in a position to exclude an angle that large. |

## 1. Purpose, and what this is not

The owner needs to **see entries and exits on a chart and comment on them**. That is the
deliverable. Four hypotheses have been closed on summary statistics alone, and the next step in the
project's own method (`docs/instrument-personality-playbook.md`: *eyes propose, data disposes*)
requires trades to look at. No strategy has ever been run through the Python simulator, so no trade
has ever existed to plot.

**This is a hypothesis-generation run, not a G1 submission.** Its output is a set of observations
and owner comments, not a validated edge. Concretely:

- The matrix is one ledger entry, **H-0006**, with its 51 configurations recorded as its trial
  count. The layer-1 entry test (§2) is a separate entry, **H-0005**, and runs first.
- **No configuration from this round may be promoted to G1 without being re-specified as its own
  hypothesis carrying the cumulative trial count.** This is what stops 51 configurations of
  exploration quietly becoming a result.
- A config that looks good here is a *candidate to re-test*, never a finding.

### Why the exits, and not the entries

H-0001 established that the archived engine's 88.2% win rate was **geometry**: a 3.0-ATR stop
against a 0.75-ATR target wins ~80% of the time on a driftless walk. The wins were small by
construction. D7 is the owner's reading of the same fact, and it is the right one — but it has
never been tested, because the entry and the exit were always changed together.

Freezing the entry and varying only the exit is the experiment that separates them.

## 2. Prerequisite — layer-1 test of the confluence entry (D4)

**Run before any exit work.** The 4-pillar confluence at threshold 3 is *formally untested*:
H-0001 tested pillar 1 alone and found it anti-predictive, but 3-of-4 means pillar 1 need not fire,
so the confluence's entry set is not a subset of H-0001's.

Method: identical to `research/experiments/2026-09-18-us500-layer1.py`. Forward returns from
confluence entry bars against `diagnose.shifted_placebo_entries` (200 whole-week shifts), on all
three instruments at 4h. Exit-free and cost-free — spread is layer 2's question and exits are
layer 3's.

**Horizons are counted in 4h bars, not minutes**: 1, 2, 3, 6 and 12 bars (4h, 8h, 12h, 24h, 48h).
The earlier layer-1 runs used 60/120/240-minute horizons because their signal bars were 5-minute;
carrying those numbers over to a 4h series would measure a fraction of a single bar.

**This gate does not stop the build.** Per D4 the matrix is built either way. Its purpose is to fix
how everything downstream is read:

- **Signal present** → differences between exit arms are about exits.
- **No signal** → every arm loses, and the chart shows what a dead entry looks like under four
  exit regimes. Still worth seeing; must not be misread as an exit finding.

The result is recorded in the ledger as its own hypothesis (H-0005) before the matrix runs.

## 3. The entry — frozen, ported faithfully

Ported from `src/main/java/.../strategy/StrategyFactory.java`. **Not re-tuned.** Parameters from
the archived `application.yaml` profile.

### 3.1 Pillars

Enter when **≥ 3 of 4** enabled pillars vote the same way (the Java rule is
`score > threshold − 0.5`, i.e. `score >= 3`), and the trend gate permits that side.

| # | pillar | bullish vote | bearish vote |
|---|---|---|---|
| 1 | RSI+BB | `RSI(7) < 25` **and** `close <= bbLower(20, 2.0)` | `RSI(7) > 75` **and** `close >= bbUpper(20, 2.0)` |
| 2 | Candle | bullish engulfing **or** bullish harami **or** hammer | bearish engulfing **or** bearish harami **or** shooting star |
| 3 | S/R | `\|close − lowest(close, 10)\| < 0.5 × ATR(14)` | `\|close − highest(close, 10)\| < 0.5 × ATR(14)` |
| 4 | Structure | **disabled** (correlated with pillar 5) | **disabled** |
| 5 | Vol+Trend | `volume > SMA(volume, 20)` **and** `close > EMA(50)` | `volume > SMA(volume, 20)` **and** `close < EMA(50)` |

Trend gate: `close > EMA(200)` for longs, `close < EMA(200)` for shorts. `trend-ema-max-atr` is 0
(disabled) and stays disabled.

### 3.2 Two port-fidelity notes

**Pillar 3's levels include the current bar.** ta4j's `lowest(n)` / `highest(n)` are inclusive, so
when price makes a new 10-bar low, `|close − support| = 0` and "near support" is **trivially true**.
Pillar 3 therefore fires on every new extreme. This is replicated exactly — the entry is frozen and
a "fix" would make this a different strategy — but **every pillar's fire rate and the distribution
of the confluence score must be reported**, so that a pillar which is effectively always-on is
visible rather than assumed to be contributing.

**Pillar 3 uses the extremes of `close`, not of `high`/`low`.** Also replicated as-is.

### 3.3 Both sides enabled (D9)

The archived config sets `enable-short: false`. That is overridden here for two independent
reasons, and the second is the load-bearing one:

- The long-only call was a **US500 5-minute** finding ("crash-only lottery") from the untrusted
  era, and oil has no drift argument for long-only in any case.
- **The project cannot afford to exclude a whole direction right now (D9).** Half the opportunity
  space would be removed on the strength of one instrument's tuning, at a point where four
  hypotheses have been closed and nothing has replaced them. Excluding shorts is a decision to make
  *from* evidence gathered here, not one to import.

**Pre-registered prediction, not a pre-condemnation.** US500 shorts are expected to underperform
US500 longs, because H-0004 measured short-overnight carry at −3.774 pts/session with a CI entirely
below zero, and 4h positions behind a wide brake will be held overnight. This exists as a
**calibration check on the run**: if it does not appear, suspect the harness before the prediction.
It is explicitly *not* grounds for dropping shorts afterwards — a short book that merely
underperforms longs may still be positive, and the carry penalty applies to US500 only, not to oil,
whose long and short financing rates are identical (−0.01096%/day both sides).

**Consequence for the output (§7.1).** Because both sides run inside every configuration, results
mixing them would confound the cross-instrument comparison D3 asks for — an instrument whose
signals happen to skew short would look like an instrument where the *exit* behaves differently.
Every configuration therefore reports **long and short separately as well as combined**, including
trade count, expectancy in R, win rate and financing per side.

## 4. The exit arms

**4h only, all three instruments.** Oil daily caps at ~10 ATR (§5.2), which would make the stop
axis ragged and the instrument comparison invalid. US500 1d is feasible and is a follow-up.

| arm | initial stop (brake) | what exits | parameter | configs |
|---|---|---|---|---|
| **E1 trailing** | {6, 10, 14} ATR | stop ratchets behind price, never away | trail *k* ∈ {2, 3} ATR | 6 |
| **E2 time** | {6, 10, 14} ATR | forced exit after *N* bars | *N* ∈ {6, 12} (24h, 48h) | 6 |
| **E3 reversal** | {6, 10, 14} ATR | confluence score **for the held side** falls below 3 | — | 3 |
| **E4 symmetric** | {1, 2} ATR | fixed target at 1×stop distance | — | 2 |

**17 per instrument × 3 instruments = 51 configurations.** Deliberately small: H-0002 used 96 and
that was enough to manufacture a peak out of noise. Long and short are not separate configurations
— each strategy takes whichever side fires.

All four are expressible in the existing intent vocabulary (`Enter(side, stop, target, risk_r)`,
`MoveStop(price)`, `Exit()`). **No simulator change is required.**

E4 is the only arm with a `target`; E1–E3 pass `target=None` and exit via `Exit()` or by the brake
being hit.

### 4.1 Exact mechanics

Stated precisely so no part is left to the implementer's judgement:

- **The brake is fixed at entry.** `stop = entry ∓ brake_multiple × ATR(14) at the entry bar`. It is
  not recomputed as ATR changes; only E1 ever moves it.
- **E1's trail uses the current bar's ATR** and ratchets one way only. For a long, on each closed
  bar: `new_stop = max(current_stop, high_water_mark − k × ATR(14) of that bar)`, where
  `high_water_mark` is the highest bar high since entry. `MoveStop` is emitted only when
  `new_stop > current_stop`. Mirrored for shorts. The ratchet guard means a volatility spike can
  never widen the stop, so using current ATR is safe.
- **E2 counts bars of the signal timeframe from the entry bar**, exclusive: an entry on bar *i*
  with `N = 6` emits `Exit()` on bar *i+6*. The brake still applies throughout.
- **E3 re-evaluates the confluence each closed bar** and emits `Exit()` when the score for the side
  actually held falls below 3. It does not require the opposite side to vote.
- **E4's stop is fixed at entry** at `{1, 2} × ATR(14)`, with `target` set to the same distance on
  the other side — 1:1 by construction, so the break-even win rate is 50% plus costs.

### 4.2 Placebo baseline

Per spec §6.1.3, every arm is also run on **random entries at matched times with identical exits**
(200 seeds, `shifted_placebo_entries`). An arm's result means nothing except relative to its own
placebo — an exit rule that "works" on random entries is measuring the exit's interaction with
price autocorrelation, not the entry.

## 5. Sizing, units and feasibility

### 5.1 Everything is a percentage (D6)

Already true of sizing: `risk_usd = balance × risk_pct/100 × risk_r`, and `balance` compounds as
trades close, so risk is 1% of *current* account value.

**Reporting changes.** `run.py` currently leads with `net_usd` / `expectancy_usd`. It must lead
with account-size-invariant units and demote dollars to a diagnostic:

| unit | definition |
|---|---|
| **R** | `net / risk_usd`. With `risk_pct = 1`, 1R = 1% of account. Already on `Trade`. |
| **% of account** | net return as a percentage of starting balance; per-month and per-quarter likewise. |
| max drawdown % | already present as `max_drawdown_pct`. |
| dollars | retained, reported last, diagnostic only. |

### 5.2 The one thing that cannot be a percentage

`min_deal_size` is an absolute broker constraint: **1 unit for both oils, 0.01 for US500**, whatever
the account holds. Below it the trade is **rejected outright, not taken small**. At $2,000 / 1%:

| instrument | tf | ATR(14) | widest usable stop | 10-ATR size | margin |
|---|---|---|---|---|---|
| US500 | 4h | 25.98 | **77.0 ATR** | 0.077 | $23.28 |
| US500 | 1d | 74.91 | 26.7 ATR | 0.027 | $8.06 |
| OIL_BRENT | 4h | 0.7320 | **27.3 ATR** | 2.732 | $20.53 |
| OIL_CRUDE | 4h | 0.7332 | **27.3 ATR** | 2.728 | $19.53 |
| OIL_BRENT | 1d | 1.8832 | 10.6 ATR | 1.062 | $7.99 |
| OIL_CRUDE | 1d | 1.9879 | 10.1 ATR | 1.006 | $7.19 |

The strategy is percentage-invariant; **the set of configurations the broker will accept is not.**
The 14-ATR brake in §4 sits inside every 4h instrument's ceiling, which is why the matrix is 4h.

**Requirement:** every configuration reports its **skipped-trade count and skipped share** as a
first-class number, and any configuration skipping **> 5%** of its signals is flagged in the output
and excluded from cross-instrument comparison. Without this, a config that the account cannot afford
looks like a config that does not work.

**Margin is not a constraint** at these sizes ($7–23 against $2,000) and is not modelled. Recorded
so that a later reader does not assume it was overlooked.

## 6. Risk controls — measure, do not guard

Given risk-based sizing and **one position at a time** (the simulator holds a single position, so
correlated Brent/WTI exposure is structurally impossible), the strategy cannot wipe the account in
the modelled world: per-trade loss is capped at 1% by construction, and a gap through the brake is
the only way to exceed it. Gaps are already modelled honestly —
`price = open_bid[i] if open_bid[i] < stop else stop` fills at the gapped open, not the stop price.

What remains is **slow bleed**: long holds paying financing, and runs of 1% losses.

Therefore no circuit breaker is implemented in this round. Instead each configuration reports:

- max drawdown (% of account) and the date it occurred
- worst single day (%)
- longest losing run (trades, and calendar days)
- total financing paid as a share of gross
- mean and maximum holding time

A breaker's thresholds are then set from these numbers as a **pre-live** task per spec §6.3, not
guessed now. A breaker installed before the numbers exist also distorts the equity path being read.

Rejected alternative: strategy-level risk control. `PositionView` exposes no balance, so a strategy
cannot see drawdown. It would require a simulator change and is not justified yet.

## 7. Outputs

### 7.1 Per run

`run.py` already writes `trades.parquet`, `equity.parquet`, `summary.json`. Additions required:

- R and % as the leading expectancy figures (§5.1)
- `skipped` and skipped share (§5.2)
- the §6 risk measurements
- per-pillar fire rates and the confluence-score distribution (§3.2)
- exit-reason breakdown (`STOP` / `TARGET` / `SIGNAL`), which is how E1–E4 are told apart
- **every figure split long / short as well as combined** (§3.3), so the D3 instrument comparison
  cannot be confounded by a side skew

### 7.2 Comparison

One table per instrument — arms as rows, the §5.1 and §6 columns — plus a cross-instrument table
answering D3 directly: **does the same ATR-normalised exit behave differently on US500 than on
oil?** ATR-normalisation is the null; a residual difference is the finding.

### 7.3 The review page — the actual deliverable

Republish `research/review/research-state.html` (artifact
`https://claude.ai/artifact/YTkPaQr27cT4njL7uEnjx9`, passing its URL as `url`) with a trade-review
surface: per-trade candle window, entry and exit markers, stop and brake lines, the trailing stop's
path where E1 applies, exit reason, R, and which pillars voted. Comment anchors per trade, so a
comment becomes a `source: owner-comment` hypothesis per spec §5.3.

`research/review-spike/` already proves the candle-plus-comment mechanism against a hand-exported
Java run; this reuses that and feeds it from `Trade`. The adapter needs the per-trade candle window
extracted from bars — `Trade` already carries entry/exit time and price, side, size, stop, target,
exit reason and R.

## 8. Build order

| # | step | note |
|---|---|---|
| 1 | Layer-1 test of the confluence entry (§2), logged as H-0005 | ~1h; gates interpretation, not the build |
| 2 | Port the 4 pillars to Python, with fire-rate instrumentation (§3) | the frozen entry |
| 3 | Exit arms E1–E4 as `Strategy` implementations (§4) | no simulator change |
| 4 | Reporting in R and % + skipped/risk measurements (§5, §6) | changes `run.py` |
| 5 | Wire `run.py`'s CLI | currently a stub that raises `SystemExit` |
| 6 | Run the 51-config matrix + placebo grid | cheap once 2–5 exist |
| 7 | Trade-review page (§7.3) | the deliverable |

Steps 2–5 are where the work is. Test-first throughout: the pillars get unit tests against
hand-checked bars, and each exit arm gets a test asserting its intents on a constructed series.

**Lookahead:** every strategy must be passed to `check_lookahead` **as a factory, not an instance** —
a precomputed indicator array captured in `__init__` is never truncated, so an instance-based check
silently passes a leak.

## 9. Out of scope

- Re-tuning the entry or the confluence threshold — it is frozen by design (§1).
- Partial exits / scale-out. `Exit()` is all-or-nothing; the 2026-07 scale-out round was already
  falsified in-sample and is not being revisited.
- A circuit breaker (§6), per-instrument profile config, TRADE mode, or any live execution.
- US500 1d and oil 1d arms — a follow-up once 4h is read (§4).
- NATURALGAS (D8).

## 10. Open questions

- **Slippage stays 0**, as in every run so far, so all costs are a floor. Unchanged from the
  standing open question; the wide-brake arms are the most exposed to it, since a stop fill in a
  fast market is exactly where slippage bites.
- **Weekend financing multiplier defaults to 1.0**, understating weekend carry. E1–E3 hold over
  weekends by design, so this arm set is the most affected of anything run so far. A documented
  value would improve every number in §6; absent one, financing is understated and must be labelled
  as such in the output.
