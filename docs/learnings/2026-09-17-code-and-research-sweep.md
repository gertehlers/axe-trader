# Learnings — code & research sweep (2026-09-17)

> **Status: informational.** Nothing here has been acted on. It is a record of what a read-only
> sweep found, so future sessions can check whether we missed something or did something wrong.
> Each item says how sure we are: **confirmed** (read in code / data) or **suspected** (needs a check).
> Code paths are relative to `src/main/java/io/g3tech/axetrader/` on `feature/delta-price-import`
> (the most advanced code line) unless stated otherwise.

Sources: three parallel read-only reviews (backtest engine; data & broker layer; research record
across `main`, `feature/delta-price-import`, `worktree-ma-cross-spike`, `worktree-sr-bounce-test`,
`origin/claude/credits-strategy-experiments-5h32ys`), plus a live import session.

---

## 1. The big picture

1. **Win rate has been measuring stop/target geometry, not edge.** Every 80%+ profile used tiny
   targets against wide stops and was break-even or negative under honest intrabar fills
   (IS −0.14 / OOS −0.29 pts/trade; best variant −0.02..0.00). Its max drawdown ≈ 96% of total profit.
   The only results that stayed positive out-of-sample won 38–53% of the time. **The 80% win-rate
   north star conflicts with making money** — the credits branch said so explicitly.
   A better primary objective: net expectancy after costs, positive in most quarters, surviving a
   genuinely unseen holdout; win rate is a secondary diagnostic.
2. **The only positive out-of-sample lead was lost in an unmerged branch.** Momentum on 10m bars
   (IS +0.52, OOS +0.45, 53% win, ~3/day, every quarter positive) lives on
   `origin/claude/credits-strategy-experiments-5h32ys`. `main` later wrote "next lever open" without
   knowing about it. It has **not** been re-verified on the clean 2024+ dataset or the fixed engine.
3. **The out-of-sample window is spent.** Jan–May 2026 was declared "burned" after the first OOS run,
   then consulted again in iterations 8, 9, 10, twice on the credits branch, six times by MA spikes,
   and by the S/R test. Any result that "passed OOS" on it is really in-sample. Data after
   **2026-08-06** has not been used by any test and is the only clean holdout we have.
4. **The tooling flattered results repeatedly** (close-based exits; `net_pnl` holding gross pnl;
   drawdown/worst quarter not written) and the engine still has lookahead and fill issues (§3). Any
   headline number from before these are fixed should be treated as optimistic.

## 2. What is falsified (don't re-try blindly)

- Confluence threshold 2 or 4; loosening RSI beyond 25/75.
- Skipping high-volatility regimes (high vol was the *best* regime); time stops.
- Shorts in the mean-reversion strategy (all profit came from one quarter).
- Distance-from-trend-EMA cap, any cap 1.0–3.0 ATR (IS +1.43 → OOS −2.02).
- Mean reversion on 15m; momentum on 5m / 8m / 25m / 30m.
- 3-tier scale-out exits (lost to do-nothing control on MAR; note it was tested on entries that were
  already falsified OOS).
- MA cross on 1m, MA 50/200, RSI-reset direction pairing.
- Discrete support/resistance levels (prior day, pivots, round 50s) as a bounce edge — 48–50%
  bounce, within ±0.6pp of random placebo levels (`research/sr-bounce/RESULTS.md`). This undercuts
  the reason for Pillar 3.

Still alive but unproven: momentum 10m (above); MA 7/14 + RSI(7) filter (IS +0.19 / OOS +0.56 at
~40% win, but on close-based exits and the legacy 500k DB with 573 bad ticks).

## 3. Backtest engine — correctness issues

| # | Sev | Issue | Where | Conf. |
|---|-----|-------|-------|-------|
| E1 | High | **Entry ATR looks ahead**: stop/target/R use ATR of the *fill* bar, whose high/low/close aren't known at its open. | `backtest/runner/BacktestRunner.java:86,137` | confirmed |
| E2 | High | **Fill bar's own range never checked**: exit walk starts at `entryIndex+1`, so a stop hit inside the entry bar is ignored. | `backtest/runner/TieredExitEngine.java:58` | confirmed |
| E3 | High | **ta4j and the runner disagree on when a trade ends**: ta4j (close-based ATR exit) decides re-entry, runner computes P&L with intrabar tiers → overlapping or skipped trades; open positions at series end dropped. | `BacktestRunner.java:66,70`, `backtest/strategy/StrategyFactory.java:142-150` | confirmed |
| E4 | High | **Stops fill at the stop level even when price gaps through it**; no forced flat at session end; weekend holds with no financing. | `TieredExitEngine.java:67-69` | confirmed |
| E5 | High | **5m buckets missing any minute are silently dropped** and the series stitched; indicators span holes and exits can't see dropped price action (gap ~every 12 bars on US500). | `backtest/series/BarSeriesFactory.java:66` | confirmed |
| E6 | Med-high | **Exclusion ledger ignored** by sweeps and discovery (bad ticks fed in); only `build()` applies it. | `ConfluenceSweepTest.java:97`, `backtest/discovery/DiscoveryRunService.java:86` | confirmed |
| E7 | Med | **Mid-price only**: brackets trigger on mid; spread subtracted afterwards as one window-wide average (incl. overnight); `TradeResult.win` is gross; no commission/financing/slippage. | `BacktestRunner.java:138`, `ConfluenceSweepTest.java:93` | confirmed |
| E8 | Med | **No statistical honesty checks**: single IS/OOS split, OOS reused, no confidence intervals (~250 trades at 80% ≈ ±5pp), no correction for number of configs tried. | sweep tooling | confirmed |
| E9 | Med | **Discovery gate always passes**: "oracle net" is `mfeAtr` (never negative); report mislabels it `net_240m_atr`; promotion picks top `totalNet` (winner's curse). | `backtest/discovery/DiscoveryPipeline.java:216-235,375` | confirmed |
| E10 | Med | **5m bars hardcoded** in exit policy (`minutesToTradingClose / 5`). | `backtest/discovery/exit/ExitPolicyEvaluator.java:33` | confirmed |
| E11 | Med-low | **Bar timestamp may be off by one minute**: code treats `snapshotTimeUtc` as minute *end*; Capital usually stamps bar *open*. | `BarSeriesFactory.java:146-151` | suspected — check a raw payload |
| E12 | Low | Discovery session calendar fitted on the whole window; no purge between folds. | `DiscoveryRunService.java:109`, `DiscoveryPipeline.java:125-130` | confirmed |
| E13 | Low | S/R pillar partly circular (lowest close includes current close). | `StrategyFactory.java:70-76` | confirmed |

Done correctly (don't re-audit): clock-grid 1m→5m aggregation; signal on closed bar, fill next bar;
pillar reasons/features read at the signal bar; Structure pillar uses `previous(1)`; brackets fill at
level with stop-wins-ties; discovery is side-aware (ask in, bid out); expanding walk-forward folds;
discovery OOS use is persisted and enforced.

## 4. Data & broker layer

| # | Sev | Issue | Where | Conf. |
|---|-----|-------|-------|-------|
| D1 | High | **Intraday data holes are recorded as "session closures"**: missing minutes inside a page are re-requested; an empty/404 answer is logged as `CAPITAL_EMPTY_OR_404` closure. 12,016 of 13,216 US500 closures are ≤5 min. "Zero gaps" only proves every minute was asked for. | `history/HistoryStagingStore.java:215-216,499-515` | confirmed (DB) |
| D2 | High | **Any 404 counts as a closure** — bad epic, date before provider history, delisted market all look like "closed". Probe history depth per new instrument. | `history/CapitalHistoricalPricePageSource.java:122` | confirmed |
| D3 | Med | **Weekend/holiday top-up fails** (zero accepted minutes rejected) and leaves a staging file. | `history/HistoryImportService.java:73`, `HistoryDatabasePromoter.java:115` | confirmed |
| D4 | Med | **Revised or late bars never picked up**: window ends at the current minute, `INSERT OR IGNORE`, cursor only moves forward. | `history/HistoryUpdateWindow.java:23`, `HistoryDeltaMerger.java:105` | suspected |
| D5 | Med | **No re-auth on 401/403** during long runs; update runs abandon partial staging. | `CapitalHistoricalPricePageSource.java:130-131,151-157` | confirmed |
| D6 | Med | **MONITOR mode doesn't work**: `AxeTraderRunner` never invoked; `WsClient` only logs; no keepalive ping. CLAUDE.md claims otherwise. | `AxeTraderRunner`, `brokers/capital/WsClient.java:139-150` | confirmed |
| D7 | Med | **Two timestamp formats** (`...T23:20Z` via JPA vs `...T23:20:00Z` via importer) would defeat the unique index and break the cursor parser if MONITOR ever writes. | `strategy/backtest/repositories/InstantConverter.java:16`, `HistoryCursorReader.java:74-86` | confirmed (latent) |
| D8 | Med | **Unique index isn't a Flyway migration** (created on the fly); docs claim two migrations exist. | `HistoryDeltaMerger.java:133-149` | confirmed |
| D9 | Low | Volume is CFD **tick count**, not traded volume; not comparable across instruments or demo vs live. | — | confirmed |
| D10 | Low | History comes from the **demo** API; demo spreads/ticks may differ from live. | `application.yaml:30` | suspected |

Done well: stage → audit → merge in one transaction; failed runs keep evidence; cursor derived from
data (no drift); half-open minute windows; bad candles to `price_exclusion` with reasons; 429
`Retry-After` respected; page hashes; timestamped backups; UTC handling correct (`snapshotTimeUTC`).

## 5. Multi-instrument readiness (data now being imported)

As of 2026-09-17 the clean DB (`.worktrees/delta-price-import/data/axe-trader.sqlite`) is being
seeded from 2024-01-01 with OIL_BRENT, OIL_CRUDE, NATURALGAS, GOLD, SILVER, US100, DE40, UK100,
J225, EURUSD, GBPUSD, USDJPY, BTCUSD, ETHUSD. What will break or mislead:

- One global `value-per-point` (1.0) — dollar figures meaningless for FX, oil, gold.
- No per-instrument spread, financing, trading hours, weekend rules.
- `/5` hardcoded bar size (E10); global OOS dates regardless of each instrument's history.
- 24/7 crypto may find no session boundary in `HistoricalSessionCalendar` (20-min gap rule) and
  exclude every observation (suspected).
- D1/D2 apply to every instrument; a date before an instrument's history start looks like closures.
- Config is a single US500 profile (`application.yaml:58,62`).

## 6. Process lessons

- **Lineage fork**: iterations 11–18 never merged; two branches reused the same iteration numbers.
  A single hypothesis ledger on `main` (planned as "Section 4", unbuilt) would have prevented this.
- **Different datasets across research lines** (legacy 500k DB vs clean 917k DB) make results
  incomparable.
- **Small-sample traps**: 4-trade "passes" (playbook's own `volume_ratio > 2.5` example), 62-trade
  winners, 26-trade OOS verdicts.
- **Gitignored artefacts ≠ missing work**: the DB lives per checkout; check the right checkout first.
- **The visual review loop fell out of use.** `dashboard/` (Cloudflare Worker + D1 phone UI) was
  built in July, but later research (Python S/R test, discovery pipeline) never fed it. The owner's
  verdict (2026-09-17): commenting is cumbersome — too many steps (export → push → review → pull),
  fixed flag vocabulary, phone-only single-trade view, and no reply loop showing what happened to a
  comment. Wanted: free-form comments like Claude Design, anchored to the thing on the chart
  (trade / candle time / price level), **not** screen coordinates, because the chart is panned and
  zoomed.

## 7. Stale or contradictory docs

- `CLAUDE.md`: "next lever is exits (3-tier scale-out)" (falsified 2026-07-22); `spring-boot:run`
  runs a backtest (it doesn't; runner dormant); MONITOR "populates the table" (it doesn't); "32%
  baseline" obsolete; the 80% north star.
- `docs/instrument-personality-playbook.md`: "scale-out is the active money lever"; `volume_ratio`
  example "passes" on 4 trades.
- `TODO.md`: "tasks 5–12 not started" (merged); iteration 8 still headed "PASS"; claims OOS kept clean.
- `application.yaml`: `trend-ema-max-atr` comment says "~2 is the lead, pending OOS" (falsified).
- Memory `feedback_strategy_tuning.md` says ">33% win rate", contradicting the 80% target.
- `worst_quarter_net` is a sum in `DashboardExporter` but a mean in `ExperimentStore`; dashboard
  win-rate tile shows gross wins.

## 8. Capital.com facts (learned the hard way)

- The REST API works only with **Capital.com platform accounts** (web/app), **not MT4/MT5**
  accounts. An MT5 demo login gives `401 {"errorCode":"error.null.accountId"}`.
- `CAPITAL_API_USER` = account email; `CAPITAL_API_PASSWORD` = the **custom password set on the API
  key**, not the account password; 2FA must be on to generate a key.
- Demo base URL `https://demo-api-capital.backend-capital.com`; owner's rule: **demo only**.
- Epics: UKOIL = `OIL_BRENT`, WTI = `OIL_CRUDE`, natural gas = `NATURALGAS`, others as named
  (`GOLD`, `SILVER`, `US100`, `DE40`, `UK100`, `J225`, `EURUSD`, `GBPUSD`, `USDJPY`, `BTCUSD`, `ETHUSD`).
- Page size max 1,000 bars; importer paces at 5 req/s; sessions expire after ~10 min idle.
