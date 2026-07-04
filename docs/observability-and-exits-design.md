# Observability + Exits — Design Spec

Durable output of the 2026-07-04 design session. A fresh session can execute this cold.
Read `CLAUDE.md` (Trading Goals + Tuning Workflow) and `TODO.md` (tuning log) first for context.

## Why this exists

After tuning round 1 we have an 80%-win-rate US500 profile that holds out-of-sample but is
**thin (+0.12 pts/trade net ≈ breakeven-plus)** and rests on an **unverified pnl model**. TODO.md
clusters *outcomes* (win%, quarter breakdown). We want to cluster *causes* — capture the entry
feature vector per trade so losers can be grouped by what produced them — and stop scrolling prose.

Guiding statistical rule: the actionable signal is **win% / expectancy *conditional on* a feature
bucket vs. the overall baseline**, never "distribution over losers" (base-rate trap). E.g. not
"70% of losers were near the EMA" but "trades near the EMA win 52% vs 80% baseline."

## Work items (in order)

### 1. Experiment database + per-trade feature capture

New committed SQLite DB `experiments/experiments.sqlite` (small, git-tracked — unlike the 95 MB
gitignored history DB). Two grains:

**`experiment`** (one row per backtest run):
`id, run_ts, git_commit, git_dirty, config_hash, config_json, instrument, timeframe_min,
window_from, window_to, trades, trades_per_day, win_rate, net_avg_pnl, avg_r, max_drawdown,
worst_quarter_net`. The `worst_quarter_net` scalar (min net expectancy across quarters) makes the
"positive every quarter" gate a WHERE clause:
`SELECT * FROM experiment WHERE win_rate > 0.75 AND worst_quarter_net > 0`.

**`trade`** (one row per trade, FK → experiment). Outcome: `entry_ts, exit_ts, direction,
entry_price, exit_price, pnl, net_pnl, r_multiple, is_win, exit_reason` (STOP/TARGET/TIME/END).
Entry features, all measured at the **signal bar** (backward-only, no look-ahead — same discipline
as `BacktestRunner.reasonsAt`): `rsi_value, dist_to_bb_atr, dist_to_support_atr,
dist_to_resistance_atr, dist_to_trend_ema_atr, trend_slope, atr_value, atr_percentile,
volume_ratio, hour_utc, day_of_week, confluence_score, pillars_fired`.

Because entry features don't change when only stop/target changes, **trades can be pooled across
experiments sharing an entry config** — bigger sample for the conditional analysis (attacks the
thin-sample problem). Query path: no `sqlite3` CLI in the container; commit `experiments/query.py`
(python stdlib `sqlite3`).

`exit_reason` is **not captured today** — needs a small `BacktestRunner` change (classify the exit
bar against stop/target/time). Prerequisite for everything else.

Backfill: re-run all 8 iterations (cheap, ~2s each) to populate trade-level features.

### 2. pnl audit + $ translation (verify the ruler before optimizing against it)

`+0.12` = 0.12 S&P **index points**/trade net of an approximated spread on **mid-price fills** —
NOT dollars (~$30/yr per unit at ~$1/pt). Audit `BacktestRunner` pnl path for: (1) sign per
direction, (2) spread single- vs double-count, (3) fill price = mid vs bid/ask and which bar,
(4) **intrabar problem** — stops/targets trigger on bar *close*, so a trade that hits target
intrabar then closes past the stop is misrecorded (can flip win↔loss; most likely bug/bias site).
Add $/contract translation to the dashboard so "does it make money" is answerable at a glance.

### 3. Phone-first trade-review dashboard (the visual)

Self-contained HTML delivered as a **Claude Artifact** (URL, phone-viewable, shareable). Strict
Artifact CSP blocks external CDNs, so **inline everything / hand-roll SVG** — do NOT reuse the
unpkg lightweight-charts in `BacktestChartExporter`. Contents:
1. KPI tiles: win%, net expectancy (pts **and** $/contract), trades/day, worst-quarter, max DD.
2. Loser-cause slices: conditional win%-per-feature-bucket vs baseline.
3. Equity curve.
4. **Trade gallery** — small zoomed inline-SVG candle cards: **50 bars before entry + 50 after
   exit**, with entry/exit markers, stop + target lines. The "after" window shows "what could have
   been better" (did price hit target right after the stop?). Filterable to losers / losers-by-cause;
   sample winners so it's not thousands.

**Cards are self-describing:** each SVG embeds experiment + trade context as visible text
(config slug, git commit, entry/exit time+price, pnl pts+$, R, exit_reason, entry feature vector)
so a single screenshot is a complete GH-issue bug report. Also emit losers as standalone `.svg`
files. Not a live server — a static artifact regenerated per run (batch backtests gain nothing from
a server; revisit only for live MONITOR-mode phone alerts).

### 4. Tiered (3-tier) exit experiment — the expectancy lever

Current geometry (whole position off at target 0.75 ATR / stop 3.0 ATR) caps every winner and lets
losers run — the direct cause of thin expectancy despite 80% wins. Model a **3-equal-thirds
scale-out with a ratcheting stop** (mirrors the 3 simultaneously-posted stops OCI shows):
- Enter full size; split into thirds.
- Exit ⅓ at T1 → move remaining stop to breakeven.
- Exit ⅓ at T2 → move stop to T1.
- Final ⅓ runs to T3 (or trails).
- Default sweep levels: T1 0.75 / T2 1.5 / T3 3.0 ATR; confirm exact levels with user at kickoff.

Raises average win without necessarily hurting win rate (first tranche still hits ~80%). **Cost:**
ta4j Positions are all-or-nothing; partial exits aren't native — model via split sub-positions or
extend `BacktestRunner` for fractional exits + dynamic stop. **Requires item 2 (trusted pnl) first.**

## Sequencing

1–3 are the "see what's working" observability layer (this round). 4 is the next strategy
experiment, aimed squarely at the make-money goal. Same commit-per-iteration + log-in-TODO.md
workflow as tuning round 1.
