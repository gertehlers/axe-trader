# axe-trader — Backlog

## Status
Phase 4 complete. Pre-MVP: priority is a stable monitor pipeline, then strategy accuracy.

---

## In Progress

- [ ] Validate MONITOR mode end-to-end (auth → WebSocket → SQLite writes confirmed)

---

## Strategy

- [ ] Establish RSI-only baseline — measure win rate before adding complexity
- [ ] Fix entry/exit rules — current signals produce poor results (too many false positives)
- [x] Implement SHORT entries — confluence runs bullish votes LONG, bearish votes SHORT
- [x] Layer in 5-pillar confluence toward >85% accuracy target (scoring/voting, threshold-tunable):
  - [x] Pillar 1: Technical indicators — RSI extreme + Bollinger Band touch (bull/bear votes)
  - [x] Pillar 2: Candlestick patterns — engulfing / harami / hammer / shooting-star
  - [x] Pillar 3: Support & resistance levels — close near lookback low/high (ATR proximity)
  - [x] Pillar 4: Chart patterns — break-of-structure proxy (break prior lookback high/low)
  - [x] Pillar 5: Volume / trend confirmation — above-average volume aligned with EMA trend

---

## Infrastructure

- [ ] Implement TRADE mode order execution (blocked until strategy accuracy is proven)
- [ ] Add risk controls before any live trading (position sizing, max drawdown, circuit breaker)

---

## Completed

- [x] Phase 1–4: Backtest engine, Capital.com REST + WebSocket integration, historical price storage
- [x] TA4j strategy pipeline (BarSeriesFactory → IndicatorBundle → StrategyFactory → BacktestRunner)
- [x] JFreeChart visualisation of backtest results
- [x] SQLite persistence with Flyway migrations
