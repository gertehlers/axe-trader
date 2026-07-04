# axe-trader — Backlog

## Status
Phase 4 complete. Pre-MVP: priority is a stable monitor pipeline, then strategy accuracy.

North star (see `CLAUDE.md` → Trading Goals): 80%+ win rate, ~5 quality trades/day per instrument
(not scalping), reproducible via 5-pillar confluence, with each instrument tuned as its own
"personality" rather than one shared config.

---

## In Progress

- [ ] Validate MONITOR mode end-to-end (auth → WebSocket → SQLite writes confirmed)
- [ ] Strategy fine-tuning — iterating toward ~5 quality trades/day with a win rate that shows real edge

---

## Strategy

- [ ] Establish RSI-only baseline — measure win rate before adding complexity
- [ ] Fix entry/exit rules — see pillar analysis below; current win rate ~32%
- [x] Implement SHORT entries — confluence runs bullish votes LONG, bearish votes SHORT
- [x] Layer in 5-pillar confluence toward >85% accuracy target (scoring/voting, threshold-tunable):
  - [x] Pillar 1: Technical indicators — RSI extreme + Bollinger Band touch (bull/bear votes)
  - [x] Pillar 2: Candlestick patterns — engulfing / harami / hammer / shooting-star
  - [x] Pillar 3: Support & resistance levels — close near lookback low/high (ATR proximity)
  - [x] Pillar 4: Chart patterns — break-of-structure proxy (break prior lookback high/low)
  - [x] Pillar 5: Volume / trend confirmation — above-average volume aligned with EMA trend

### Confluence tuning — where we left off (2026-06-14)

Current `application.yaml` settings: threshold 2, RSI 25/75, proximity-atr 0.3, swing-lookback 20.
Dataset: 2078 5m bars ≈ 26.6 trading days.

Pillar combination analysis at these settings (168 trades, 32% win rate, ~6.3/day):

| Combo                           | Count | Win% | Notes                                    |
|---------------------------------|-------|------|------------------------------------------|
| Structure + Vol+Trend           |  55   |  29% | **Noise** — both describe trending bars  |
| RSI+BB + S/R                    |  47   |  34% | Clean mean-reversion at extremes         |
| Candle + Vol+Trend              |  24   |  21% | Weak                                     |
| Candle + S/R                    |  22   |  41% | Good — reversal candle at real level     |
| Candle + Structure + Vol+Trend  |   7   |  43% | Better quality when 3 align              |
| Candle + RSI+BB + S/R           |   1   | 100% | Golden combo, but very rare              |

**Key finding:** Structure (close > prior lookback high) and Vol+Trend (close > EMA + high volume)
are correlated — both fire in trending bars. They generate 55 trades at only 29% win rate.
Removing this pair would cut trades from 168 → 113 and lift overall win rate.

**Next action options:**
- Disable `enable-structure: false` → drops the noisy Structure+Vol+Trend pair entirely
- Keep Structure but redesign it as something less correlated with Vol+Trend (e.g. require
  price to have been *below* the level recently before breaking it)
- Goal: ~5 quality trades/day at materially higher win rate than 32%

### Tuning iteration 1 — full-year sweep (2026-07-03)

Harness: `ConfluenceSweepTest` (`-Dtest=ConfluenceSweepTest -Dsweep=true`), grid of 21 configs in one
JVM. **In-sample window: 2024-12-04 → 2026-01-01** (76,796 5m bars, 336 trading days, avg spread
0.48 pts). **Jan–May 2026 is held out for out-of-sample validation — do not tune against it.**
"Net" = after one full spread per round trip. Modeling gaps (noted in harness javadoc): mid-price
fills, stops/targets trigger on bar close not intrabar wicks.

Key rows (full table in the harness output; base config = yaml except where named):

| Config                        | Trades | /day | Win% | netAvgPnl (pts) |
|-------------------------------|--------|------|------|-----------------|
| th2_struct (old baseline)     | 6510   | 19.4 | 37%  | **−0.40**       |
| th2_noStruct_stop3.0_tgt1.0   | 5408   | 16.1 | 71%  | **−0.34**       |
| th3_noStruct (yaml geometry)  | 335    | 1.0  | 40%  | +0.67           |
| th3_noStruct_stop2.0_tgt1.0   | 335    | 1.0  | 66%  | +0.73           |
| **th3_noStruct_stop3.0_tgt1.0** | 334  | 1.0  | **70%** | +0.58        |
| th3_noStruct_stop1.5_tgt1.5   | 337    | 1.0  | 57%  | +1.15 (best expectancy) |
| th4_* (any)                   | 3      | 0.0  | —    | dead            |

**Findings:**
1. The 26-day Dec-2024 window behind the old 32% baseline was unrepresentative — threshold 2 actually
   fires 15–19 trades/day over the year and **loses money after spread at every geometry tested**.
   Threshold 2 is a dead end, full stop.
2. Threshold 3 + structure off ≈ 1 trade/day, profitable after spread at every geometry — this is the
   quality zone. Threshold 4 ≈ 3 trades/year — dead.
3. **Stop/target geometry is the dominant win-rate lever** (as predicted: target > stop structurally
   caps win rate). Same entries went 40% → 70% by moving from stop 1.5/target 3.0 to stop 3.0/target 1.0.
   Win rate and expectancy trade off: 1.5/1.5 gives 57% win but the best netAvgPnl (+1.15).
4. Win rate ≈ net win rate everywhere — trade sizes (ATR-scale) dwarf the 0.48-pt spread; spread
   mainly erodes expectancy, which is what kills threshold 2.

**Gaps vs. north star:** 70% < 80% win rate; 1.0 < ~5 trades/day.

**Next (iteration 2):** anchored on th3_noStruct — (a) push geometry further (stop 3.5–4.0, target
0.5–0.75) watching that netAvgPnl stays clearly positive; (b) raise cadence by loosening pillar gates
(RSI 30/70, proximity-atr 0.4–0.5, shorter swing-lookback) so 3-of-4 votes fire more often; (c) later,
time-of-day/volatility-regime filters as win-rate polish.

### Tuning iteration 2 — 80% crossed in-sample (2026-07-03)

Same window/harness as iteration 1. All configs: threshold 3, structure off, RSI 25/75 unless noted.

| Config                          | Trades | /day | Win% | netAvgPnl (pts) |
|---------------------------------|--------|------|------|-----------------|
| **geo stop4.0/tgt0.5**          | 333    | 1.0  | **81%** | +0.41        |
| geo stop3.5/tgt0.5              | 335    | 1.0  | 80%  | +0.33           |
| geo stop4.0/tgt0.75             | 332    | 1.0  | 79%  | +0.52           |
| geo stop3.0/tgt0.75             | 335    | 1.0  | 76%  | +0.78 (best expectancy) |
| gates prox0.5/look10 (geo 3.0/1.0) | 720 | 2.1  | 72%  | +0.34           |
| gates rsi30-70 or 35-65 (any)   | 555–940 | 1.7–2.8 | 69–71% | **negative to +0.34** |

**Findings:**
1. **The 80% win-rate bar is reachable in-sample**: stop 4.0 ATR / target 0.5 ATR → 81% on 333
   trades, still positive after spread. Win rate climbs smoothly along the geometry axis
   (70→76→79→81%), so this is a real response surface, not a lucky cell.
2. Expectancy thins as win rate climbs (+0.78 at 76% vs +0.41 at 81%). Classic high-win-rate shape:
   wins ~0.5 ATR, losses ~4 ATR — one stop-out eats ~8 wins. **Position sizing / max-drawdown
   controls (Infrastructure backlog) are mandatory before live.**
3. **Cadence lever found**: S/R proximity 0.5 ATR + swing-lookback 10 doubles trades to 2.1/day and
   *raises* win rate to 72% (at geo 3.0/1.0). The S/R pillar was over-strict, not under-strict.
4. **RSI 25/75 is load-bearing**: widening to 30/70 or 35/65 adds trades that lose money after
   spread. Do not loosen pillar 1.

**Next (iteration 3):** compose the two wins — gates (prox 0.4–0.6 × look 8–14) × geometry
(stop 3.0–4.0 × tgt 0.5–0.75), all at th3/noStruct/RSI 25/75. If effects stack: ~2/day at ~80%.
Then pick 2–3 candidates and run the held-out Jan–May 2026 window ONCE as the out-of-sample referee.

### Tuning iteration 3 — compose holds: 84% at 2.4/day in-sample (2026-07-03)

Same window/harness. 45 configs: prox {0.4,0.5,0.6} × look {8,10,14} × geometry
{3.0/0.75, 3.5/0.5, 3.5/0.75, 4.0/0.5, 4.0/0.75}, all th3/noStruct/RSI 25/75.

The cadence and geometry effects stack. The entire look 8–10 × prox 0.4–0.6 × stop4.0/tgt0.5
region sits at **83–84% win** on 620–950 trades — a plateau, not a spike. look 14 degrades toward
zero net expectancy everywhere (stale swing levels). Best rows per profile:

| Profile               | Config                          | Trades | /day | Win% | netAvgPnl |
|-----------------------|---------------------------------|--------|------|------|-----------|
| Win-rate champion     | prox0.5 look8 stop4.0 tgt0.5    | 812    | 2.4  | 84%  | +0.52     |
| Cadence variant       | prox0.6 look8 stop4.0 tgt0.5    | 941    | 2.8  | 84%  | +0.44     |
| Balanced              | prox0.5 look10 stop4.0 tgt0.75  | 710    | 2.1  | 81%  | +0.69     |
| Expectancy champion   | prox0.5 look10 stop3.0 tgt0.75  | 722    | 2.1  | 78%  | +0.86     |

**Out-of-sample validation (next, one shot):** run exactly these candidates on the held-out
2026-01-01 → 2026-05-01 window (~85 trading days, expect ~200 trades each). Pass = win rate holds
within a few points of in-sample and net expectancy stays clearly positive. No retuning against
this window — if candidates crater, back to in-sample with walk-forward splits instead.

### Out-of-sample validation — FAILED on expectancy (2026-07-04)

Window 2026-01-01 → 2026-05-02: 24,126 5m bars, 104 trading days, avg spread 0.55 pts (vs 0.48 IS).

| Config                          | IS win% / net | OOS win% / net |
|---------------------------------|---------------|----------------|
| prox0.5 look8 stop4.0 tgt0.5    | 84% / +0.52   | 79% / **−1.68** |
| prox0.6 look8 stop4.0 tgt0.5    | 84% / +0.44   | 79% / **−1.64** |
| prox0.5 look10 stop4.0 tgt0.75  | 81% / +0.69   | 78% / **−1.29** |
| prox0.5 look10 stop3.0 tgt0.75  | 78% / +0.86   | 75% / **−0.79** |

**Read:** win rate survived (−3 to −5 pts, normal shrinkage — the entry logic generalizes), but
**net expectancy flipped hard negative**. Jan–May 2026 is a different volatility regime (higher
spread, cadence 2.4→3.0/day), and the wide-stop/tight-target geometry pays for its high win rate
with catastrophic-sized rare losses — in a hotter regime those swamp the small wins. Also the
close-triggered stop understates real losses most in fast markets (close can land far beyond the
stop level), so live would be worse still.

**Decisions:**
1. The 2026 window is now *burned* for candidate selection (we looked at it). Future config
   selection must come from walk-forward splits inside Dec'24–Dec'25; 2026 gets one final look
   only for the single end-stage config.
2. Win rate alone is officially a misleading objective for this strategy shape — every future
   iteration gates on **net expectancy ≥ 0 in every regime/quarter**, then maximizes win rate.

**Next (iteration 4):** find where the losses live. `TradeResult` already carries a per-trade
`VolatilityRegime` (LOW/NORMAL/HIGH, rolling ATR percentile — no look-ahead). Extend the harness to
break down win%/net expectancy per regime and per quarter, run on IS. If losses concentrate in the
HIGH regime, add a regime gate (skip entries when ATR is in the top rolling percentile band) — an
explainable filter consistent with the 5-pillar reproducibility ethos — then re-check stability
across 2025 quarters via walk-forward before ever touching 2026 again.

### Tuning iteration 4 — regime hypothesis dead; quarter instability is the disease (2026-07-04)

Per-regime / per-quarter breakdown of the four candidates on IS:

1. **Regime gate is the wrong fix**: HIGH-vol is the *best* regime (+0.96 to +1.18 net across
   candidates); LOW-vol is the drag (−0.29 to +0.23). A skip-high-vol filter would hurt. Hypothesis
   falsified before writing any filter code — that's the breakdown doing its job.
2. **The real disease: expectancy whipsaws by quarter even in-sample.** E.g. win-rate champion:
   2024Q4 −1.86, 2025Q1 −0.39, Q2 +2.19, Q3 −0.41, Q4 +1.42. The aggregate +0.52 was two great
   quarters subsidizing three negative ones. OOS didn't reveal a regime shift; it revealed that
   "profitable on average" was never "profitable consistently".
3. Win rate is stable everywhere (77–89% per quarter) — the entries are fine. With wins ~0.5 ATR vs
   losses ~4 ATR, the quarter's sign hinges on 2–3 extra stop-outs — the geometry is knife-edge.
4. Most robust candidate: **stop3.0/tgt0.75** (all regime rows positive, only 2024Q4 clearly
   negative, least-bad OOS). The extreme 4.0/0.5 geometry is the fragile one.

**Next (iteration 5): time-based exit.** With target 0.5–0.75 ATR vs stop 3–4 ATR there's a hole in
the exit logic: trades that hit neither level drift for hours carrying full stop-size tail risk.
Add a `max-holding-bars` knob (exit after N bars if neither stop nor target hit — ta4j
`OpenedPositionMinimumBarCountRule` OR'd into the exit), sweep N × the two sane geometries, and
gate candidates on **every quarter net ≥ ~0**, not the aggregate.

### Tuning iteration 5 — time stop falsified (2026-07-04)

`max-holding-bars` knob implemented (kept — useful risk control), but it's not the stabilizer:
hold 24–48 bars ≈ no change vs hold 0; hold 6–12 cuts win rate to 63–77% while the bad quarters
stay negative (hold12 on 4.0/0.5: Q4'24 −1.35, Q1'25 −0.10, Q3'25 −0.02). **The bad quarters lose
via genuine stop-outs, not drifting trades.** Quarter pattern is consistent across all geometries:
Q4'24 always worst (−1.4 to −2.7 — note: Dec 2024 is the window the original 32% baseline came
from), Q1'25/Q3'25 mildly negative, Q2'25/Q4'25 strongly positive.

**Next (iteration 6): trend-direction diagnosis.** All entries are mean-reversion (RSI extreme +
BB touch + near S/R); clustered stop-outs are the signature of fighting a strong trend. Add a
LONG/SHORT × quarter breakdown to the harness — if bad quarters bleed on one side only, the fix is
a hard higher-timeframe trend gate (only long when trend up/flat, only short when down/flat),
which is explainable and pillar-consistent. No strategy code until the data confirms.

### Tuning iteration 6 — directional signature confirmed (2026-07-04)

LONG/SHORT × quarter split on the robust profile (th3/noStruct/prox0.5/look10/stop3.0/tgt0.75):

| Quarter | LONG net | SHORT net |
|---------|----------|-----------|
| 2024Q4  | **−2.58** | −0.57    |
| 2025Q1  | +1.08    | −0.45     |
| 2025Q2  | +0.94    | **+4.98** |
| 2025Q3  | +0.49    | −1.26     |
| 2025Q4  | +1.37    | +1.20     |

1. **Longs are consistently profitable in every 2025 quarter**; the only long disaster is Dec 2024 —
   a selloff month where mean-reversion longs caught falling knives all the way down.
2. **Shorts are a lottery ticket**: bleed in 3 of 5 quarters, jackpot (+4.98/trade) in the Q2'25
   selloff. Classic S&P profile — shorting mean-reversion fights upward drift except in corrections.
3. Both facts point at one fix: **hard higher-timeframe trend gate per direction** — longs only when
   price is above a long EMA (kills Dec-24-style knife-catching), shorts only when below it (keeps
   the crash jackpot, cuts the drift bleed).

**Next (iteration 7):** implement `trend-ema-period` (hard directional gate, 0 = disabled), sweep
{100, 200, 400} × the two lead profiles. Pass bar unchanged: every quarter net ≥ ~0 in-sample, then
walk-forward check, and only then the final one-shot on 2026.

### Tuning iteration 7 — trend gate works; shorts unmasked as a crash lottery (2026-07-04)

Trend gate {0,100,200,400} × two profiles. EMA 200 on stop3.0/tgt0.75 fixes Dec'24 exactly as
designed (−1.90 → +0.30) and Q1'25; EMA 400 over-filters (net negative aggregate). But the
per-direction view is the real finding:

- **LONGS (3.0/0.75 + trend200): 4/5 quarters positive** (+0.39, +0.81, −0.38, +0.61, +0.88),
  ~80% win, ~0.9/day, net +0.46/trade. The residual Q2'25 −0.38 is the gate blocking recovery
  dip-buys — a far smaller leak than the Dec'24 one it closed.
- **SHORTS: the entire profit is one quarter** (+5.54/trade in the Q2'25 selloff); −1.3 to −3.2
  everywhere else, gated or ungated. On an upward-drifting index this is not an edge — it's crash
  insurance that occasionally pays. Cut them from the base profile.

**Final candidate (pre-committed before the 2026 one-shot):** LONG-only, th3, structure off,
prox 0.5, look 10, stop 3.0, tgt 0.75, trend-EMA 200, RSI 25/75. Expected IS: ~80% win, ~0.9/day,
net ≈ +0.46/trade, worst quarter −0.38.

**Next (iteration 8):** add `enable-long`/`enable-short` knobs; confirm candidate in-sample with a
monthly breakdown; then ONE shot on 2026 with exactly this config — no retuning on the result. If
it holds (net ≥ 0, win ≥ ~75%): promote to `application.yaml` as the US500 profile and surface the
cadence question (0.9/day vs ~5 target) to the user. If it fails: stop and reassess with the user.

---

## Infrastructure

- [ ] Implement TRADE mode order execution (blocked until strategy accuracy is proven)
- [ ] Add risk controls before any live trading (position sizing, max drawdown, circuit breaker)
- [ ] Design per-instrument config profiles ("personality") — today `backtest.strategy` is one flat
      block hardcoded to `US500`; each new instrument needs its own tuned thresholds/pillar weights
      rather than inheriting US500's settings

---

## Completed

- [x] Phase 1–4: Backtest engine, Capital.com REST + WebSocket integration, historical price storage
- [x] TA4j strategy pipeline (BarSeriesFactory → IndicatorBundle → StrategyFactory → BacktestRunner)
- [x] JFreeChart visualisation of backtest results
- [x] SQLite persistence with Flyway migrations
