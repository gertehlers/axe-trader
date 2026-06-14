# axe-trader

Spring Boot 4 / Java 26 trading bot framework. Capital.com integration for live market data; TA4j-powered backtest engine for strategy development. Target instrument: US500 (S&P 500).

## Current Phase

**Pre-MVP.** Immediate goal: get `MONITOR` mode running end-to-end against the Capital.com demo API.
Strategy work follows: build a 5-pillar signal confluence targeting >85% entry/exit accuracy.
See `todo.md` for the canonical task tracker.

## Build & Run

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

Goal: >85% accurate entry/exit signal by combining:

| Pillar | Status |
|---|---|
| Technical indicators (RSI, BB, EMA, ATR) | Partially done — longs only; entries/exits need tuning |
| Candlestick patterns | Not started |
| Support & resistance | Not started |
| Chart patterns | Not started |
| Volume / trend confirmation | Not started |

Current strategy is RSI mean-reversion + Bollinger Band touch + EMA trend filter. Per `todo.md`, entries/exits are "whack" — consider RSI-only baseline first, then layer pillars.

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
    rsi-oversold: 30       # entry threshold
    rsi-overbought: 70     # exit threshold
    stop-atr-multiple: 1.5
    target-atr-multiple: 3.0

brokers.capital.api.url: https://demo-api-capital.backend-capital.com  # demo (not live)
```

## Database

SQLite at `data/axe-trader.sqlite`. Managed by Flyway (`classpath:db/migration`).

Single table: `historical_price` — columns: `epic`, `resolution`, `snapshot_time_utc`, `open/high/low/close_bid/ask`, `last_traded_volume`, `source`, `ingestion_time_utc`.

MONITOR mode populates this table. BACKTEST mode reads from it.

## Open Work (from `todo.md`)

- [ ] Validate MONITOR mode end-to-end (auth → WebSocket → SQLite writes)
- [ ] Fix entry/exit strategy (simplify to RSI baseline, then iterate)
- [ ] Implement SHORT entries (currently only LONG)
- [ ] Add candlestick pattern detection (pillar 2)
- [ ] Add support & resistance levels (pillar 3)
- [ ] Add chart pattern recognition (pillar 4)
- [ ] Add volume/trend confirmation (pillar 5)
- [ ] Implement TRADE mode order execution (blocked until strategy accuracy is proven)
