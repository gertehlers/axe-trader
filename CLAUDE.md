# axe-trader

Spring Boot 4 / Java 21 trading bot framework. Capital.com integration for live market data; TA4j-powered backtest engine for strategy development. Target instrument: US500 (S&P 500).

## Current Phase

**Pre-MVP.** Immediate goal: get `MONITOR` mode running end-to-end against the Capital.com demo API.
Strategy work follows: build a 5-pillar signal confluence targeting >85% entry/exit accuracy.
See `TODO.md` for the canonical task tracker.

## Trading Goals (North Star)

Read this before touching strategy code — it's the bar every change is judged against.

- **Win rate target: 80%+** (stretch: >85%). This is the whole game — a strategy that trades often but
  wins less than this does not make money once spread/slippage are accounted for. Do not ship a
  "more trades" change that costs win rate without an explicit ask.
- **Cadence: ~5 quality trades/day per instrument, not scalping.** For MOMENTUM this is a natural fit
  (it trades more); for MEAN_REVERSION more trades usually meant looser filters — always check the
  expectancy moved the right way, not just the count.
  Current best (2026-07-04, US500 **15m MOMENTUM**, promoted to `application.yaml`, TODO.md iterations
  14-16): the **first net-positive, OOS-validated profile** — IS +0.31 pts/trade (53% win, 4/5
  quarters positive), OOS Jan-May'26 **+0.10 pts/trade** (51% win, sign held positive) at ~2.1
  trades/day. Positive skew (small stops, winners trail). ⚠️ **Thin & trend-dependent**: it makes
  money in trending months and bleeds in choppy ones (Q1) — positive-expectancy-that-generalizes, not
  yet makes-money-every-month; stabilising the choppy-quarter bleed is the open work.
  **Key lesson: timeframe was the hidden lever.** US500 mean-reverts at 5m (why dip-buying wins ~80%
  but is structurally break-even — wins ≈ losses); at 15m moves clear the spread and breakouts trend,
  so a MOMENTUM entry flips net-positive. The old 5m mean-reversion profile (iterations 1-13) held
  ~80% win but never made money under honest fills — kept as `mode: MEAN_REVERSION` for reference.
- **Reproducibility via 5-pillar confluence.** Every entry/exit must be explainable as a vote count
  across the 5 pillars below (`ConfluenceStrategies` / `PillarVote`) — not a black-box ML signal.
  Confluence threshold, pillar enable flags, and thresholds are the tuning knobs; see
  `backtest.strategy.*` in `application.yaml`.
- **Instrument "personality."** Each instrument gets its own tuned profile — RSI levels, ATR
  multiples, proximity/lookback windows, which pillars are even enabled — because what filters noise
  on US500 may not transfer to another instrument's volatility/session behavior. Today the config is
  a single flat `backtest.strategy` block hardcoded to `US500`; adding a second instrument means
  introducing per-instrument config sections/profiles rather than assuming one tuning fits all.
- **Accuracy before execution.** TRADE mode stays safety-locked (see `AxeTraderRunner`) until backtests
  show the win-rate target holding out-of-sample on the instrument(s) in question.

## Tuning Workflow (Don't Lose Progress)

Strategy tuning runs in this repo tend to be long, iterative loops (tweak a threshold in
`application.yaml`, backtest, read the result, repeat). Sessions and their containers are ephemeral —
work that only exists in conversation memory or an uncommitted working tree is gone the moment a
session ends, whether from a credit cutoff or the container simply being reclaimed. Treat git as the
only durable state:

- **Commit after every iteration, not at the end.** Each config change + its backtest result gets its
  own small commit. Never let a long run sit as one big uncommitted diff.
- **Push after every commit**, not batched — an unpushed local commit is as fragile as an uncommitted one.
- **Log every iteration in `TODO.md`'s tuning log** (config used → trades/win rate → notes), and call
  out the current best result explicitly. This is what lets a brand-new session with zero memory of
  prior conversations pick up cold: read `TODO.md`, see exactly what's been tried and what won, and
  continue — no dependency on any session "remembering" the history.
- `data/axe-trader.sqlite` itself needs no safeguarding — it's disposable and reconstructible from the
  committed `data/axe-trader.sqlite.gz` snapshot (see `DatabaseBootstrap`).

## Build & Run

> **Ephemeral-session setup**: on a fresh container the history DB must be decompressed before any
> test (`gzip -dc data/axe-trader.sqlite.gz > data/axe-trader.sqlite` — `DatabaseBootstrap` only runs
> in `main()`, not under `@SpringBootTest`). See `docs/dev-environment.md` for that and the sweep
> commands.

```bash
./mvnw clean package -DskipTests   # build
./mvnw test                         # run tests
```

Run the jar (or via Spring Boot plugin):

```bash
# Backtest against SQLite history (default)
./mvnw spring-boot:run

# Monitor mode — streams live Capital.com data, stores to SQLite (requires .env)
./mvnw spring-boot:run -Dspring-boot.run.arguments="--axe-trader.mode=monitor"

# Trade mode — NOT IMPLEMENTED (safety lock in AxeTraderRunner)
```

Required `.env` file in project root (Capital.com credentials):

```
CAPITAL_API_USER=...
CAPITAL_API_PASSWORD=...
CAPITAL_API_KEY=...
```

Logs: `logs/axe-trader.log`. Chart output: working directory (PNG, via `BacktestChartExporter`).

## Architecture

```
io.g3tech.axetrader/
├── AxeTraderApplication        # Spring Boot entry point
├── AxeTraderRunner             # Orchestrates MONITOR/TRADE modes; safety-locks TRADE
├── config/AxeTraderMode        # Enum: BACKTEST | MONITOR | TRADE
├── backtest/                   # Core strategy dev subsystem
│   ├── series/BarSeriesFactory       # Load 1m bars from SQLite → aggregate to target TF
│   ├── indicators/IndicatorBundle    # Immutable: RSI, BB, EMA, ATR pre-calculated
│   ├── strategy/StrategyFactory      # Entry/exit rules (ta4j Rule composition)
│   ├── runner/BacktestRunner         # Execute strategy → List<TradeResult>
│   └── chart/BacktestChartExporter   # Render JFreeChart PNG
├── brokers/capital/            # Capital.com REST + WebSocket clients
│   ├── ApiClient               # Price history, account
│   ├── AuthenticationClient    # Session management
│   └── WsClient                # Real-time OHLC subscription
└── strategy/backtest/          # JPA persistence
    ├── data/HistoricalPrice    # Entity: OHLC bars (SQLite)
    └── repositories/           # Spring Data repository
```

**Backtest pipeline:** `BarSeriesFactory` → `IndicatorBundle` → `StrategyFactory` → `BacktestRunner` → `BacktestChartExporter`

## Strategy Vision (5-Pillar Confluence)

Goal: 80%+ (stretch >85%) accurate entry/exit signal by voting across:

| Pillar | Status |
|---|---|
| 1. Technical indicators (RSI extreme + BB touch) | Implemented — LONG + SHORT |
| 2. Candlestick patterns (engulfing / harami / hammer / shooting-star) | Implemented |
| 3. Support & resistance (close near lookback high/low, ATR proximity) | Implemented |
| 4. Chart patterns (break-of-structure proxy vs. prior lookback high/low) | Implemented |
| 5. Volume / trend confirmation (above-average volume aligned with EMA trend) | Implemented |

All 5 pillars vote (see `ConfluenceStrategies` / `PillarVote`); `confluence-threshold` sets how many
must agree before an entry fires. Implemented ≠ tuned — current settings produce too many trades at
too low a win rate (see Trading Goals above and `TODO.md`'s tuning log for the active investigation,
e.g. Structure and Volume/Trend pillars correlating and adding noise rather than independent signal).

## Config Reference (`application.yaml`)

```yaml
axe-trader:
  mode: backtest          # backtest | monitor | trade

backtest:
  epic: US500
  limit: 10000            # 1-min bars to load from SQLite
  timeframe-minutes: 5    # aggregate target (5 → 5m bars)
  strategy:
    rsi-period: 7
    rsi-smooth-period: 7
    bb-period: 20
    bb-multiplier: 2.0
    ema-period: 50
    atr-period: 14
    rsi-oversold: 25       # entry threshold — load-bearing, don't loosen (tuning log iter. 2)
    rsi-overbought: 75     # exit threshold
    stop-atr-multiple: 3.0     # wide stop / tight target = high-win-rate geometry
    target-atr-multiple: 0.75
    max-holding-bars: 0        # optional time stop (0 = off)
    trend-ema-period: 200      # hard gate: long only above / short only below this EMA

    # 5-pillar confluence voting
    confluence-threshold: 3        # votes (pillars) that must agree before entering
    proximity-atr-multiple: 0.5    # "near" a swing level, in ATR units (pillar 3)
    swing-lookback-bars: 10        # backward window for support/resistance & structure (pillars 3 & 4)
    volume-sma-period: 20          # volume baseline (pillar 5)
    enable-candles: true            # pillar 2
    enable-support-resistance: true # pillar 3
    enable-structure: false         # pillar 4 — correlated with Vol+Trend, noise (tuning log iter. 1)
    enable-volume-trend: true       # pillar 5
    enable-long: true
    enable-short: false             # US500 shorts are crash-only lottery (tuning log iter. 7)

brokers.capital.api.url: https://demo-api-capital.backend-capital.com  # demo (not live)
```

All of the above lives under a single flat `backtest.strategy` block today, applied to the one
hardcoded instrument (`US500`). Per the per-instrument "personality" goal above, this will need to
become a per-instrument profile once a second instrument is added — don't assume these numbers
transfer.

## Database

SQLite at `data/axe-trader.sqlite`. Managed by Flyway (`classpath:db/migration`).

Single table: `historical_price` — columns: `epic`, `resolution`, `snapshot_time_utc`, `open/high/low/close_bid/ask`, `last_traded_volume`, `source`, `ingestion_time_utc`.

MONITOR mode populates this table. BACKTEST mode reads from it.

## Open Work (from `TODO.md`)

- [ ] Validate MONITOR mode end-to-end (auth → WebSocket → SQLite writes)
- [ ] Tune 5-pillar confluence toward ~5 quality trades/day at a materially higher win rate than the
      32% baseline (all 5 pillars are implemented — this is now a tuning problem, not a build problem)
- [ ] Design per-instrument config profiles ("personality") ahead of adding a second instrument
- [ ] Add risk controls before any live trading (position sizing, max drawdown, circuit breaker)
- [ ] Implement TRADE mode order execution (blocked until strategy accuracy is proven)
