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
