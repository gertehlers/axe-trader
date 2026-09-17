# Research restart — design

**Date:** 2026-09-17 · **Status:** approved in brainstorming, awaiting written-spec review
**Background:** `docs/learnings/2026-09-17-code-and-research-sweep.md` (informational; nothing in it is
treated as evidence).

## 0. Decisions (owner, 2026-09-17)

| # | Decision |
|---|----------|
| D1 | **Primary bar = net expectancy after all costs.** Win rate is reported, never a gate. The point is to make money. Replaces the 80%+ win-rate north star. |
| D2 | **Nothing from before is trusted.** Old results, verdicts and docs are leads at most. |
| D3 | **Keep the SQLite price data and the Java importer; fix the importer first.** Everything else (Java backtest engine, discovery pipeline, TA4j strategy code, `dashboard/`) is removed once the new engine core passes its tests. |
| D4 | **Research engine in Python.** Live-trading language decided later, when something has passed the holdout. |
| D5 | **Holding period: intraday up to a few days.** Overnight financing and gaps must be modelled. |
| D6 | **Simulated account: USD, 1,000–5,000, ~1% risk per trade** (demo account currency is USD). |
| D7 | **Three gates:** walk-forward → one-shot holdout → ≥4-week demo forward test. |
| D8 | **Review tool = claude.ai Artifact page per research question**, comments anchored to data (not screen coordinates), "Send to Claude" reply loop. Owner pain points it must fix: too many steps, fixed flag vocabulary, phone-only single-trade view, no reply loop. |
| D9 | **Capital.com demo API only** (`https://demo-api-capital.backend-capital.com`). |

## 1. Architecture

```
Capital.com demo API
      │  Java importer (kept, fixed — §2)
      ▼
SQLite historical_price: 1-min bid/ask OHLC, per epic, 2024-01 → now
      │  read-only
      ├──► data verification report (§2.2)
      ▼
Python research engine  research/engine  (§3)
  instruments.yaml → cost model
  strategy (closed bars → intents) → 1-min bid/ask simulator
  → trade log (USD + R) → metrics → gates (§4)
      │                                  │
      ▼                                  ▼
research/ledger/ on main (§4)     review Artifact per question (§5)
      ▲                                  │
      └──── hypotheses from comments ◄───┘
```

Rules:
- One branch line: work happens on a fresh branch off `main` and merges back; no parallel research
  lineages. The ledger lives on `main`.
- Each build step below gets its own implementation plan. Steps 3, 5 and 6 get a short design check
  before planning (this spec sets their shape, not every detail).

**Build order:** (1) importer fixes + data verification → (2) engine core → (3) review page (spike
first) → (4) ledger + gates → (5) research program → (6) path to money.

Instruments in scope (imports started 2026-09-17, from 2024-01-01): US500, OIL_BRENT, OIL_CRUDE,
NATURALGAS, GOLD, SILVER, US100, DE40, UK100, J225, EURUSD, GBPUSD, USDJPY, BTCUSD, ETHUSD.

## 2. Step 1 — importer fixes and data verification

### 2.1 Importer fixes (Java, test-first, existing `history` package)

| ID | Problem (see learnings §4) | Required behaviour |
|----|---------------------------|--------------------|
| I1 | Missing minutes inside a page recorded as session closures (learnings D1) | The importer **stops classifying** empty intervals as closures: the provider response cannot tell a market closure from a data hole (real closures, e.g. US500's daily break, sit inside 999-minute windows that contain data). Each empty interval is recorded neutrally with provenance `EMPTY_BASE`, `EMPTY_REFETCH`, `NOT_FOUND_BASE` or `NOT_FOUND_REFETCH` (provider answer × whether the window was a base page or a re-fetch of missing minutes). Closure-vs-gap classification moves to the data verification report (§2.2), which uses Capital.com trading hours. Existing `CAPITAL_EMPTY_OR_404` rows are left as history. *(Revised 2026-09-17 while planning; original wording assumed the response could decide.)* |
| I2 | Every 404 treated as closure (learnings D2) | Unknown epic (market-details lookup fails) → the seed fails with a clear error before staging. Seeding runs a **history-start probe**: weekly Wednesday 12:00 UTC probe windows from the configured start; the seed starts at the Monday 00:00 UTC of the first week that has data and is followed by a week with data (never before the configured start). |
| I3 | Zero-minute update fails (learnings D3) | "Nothing new" (weekend/holiday) exits 0 as `already current` and deletes its staging file. |
| I4 | Late/revised bars never fetched (learnings D4) | Update window ends 2 minutes before now and re-fetches the last 60 stored minutes; a changed bar replaces the stored one and is logged as a revision. |
| I5 | No re-auth on expired session (learnings D5) | On 401/403: create a new session once and retry the request. A failed update resumes its existing staging file on the next run instead of starting a new one. |
| I6 | Unique index not in Flyway; two timestamp formats (learnings D7, D8) | Flyway migration adds the unique index `(source, epic, resolution, snapshot_time_utc)`. One canonical timestamp format `YYYY-MM-DDTHH:MM:SSZ` for every writer. *(Revised during implementation: the migration does **not** create the ledger tables — existing integration tests pin that the ledger is optional for the application and owned by the importer, which creates it on merge.)* |
| I7 | Bar time semantics unverified (learnings E11) | Fetch one raw payload, compare `snapshotTimeUTC` with the candle it describes, document whether it is bar open or close in `docs/local-price-history.md`, and add a test pinning the convention. |

Out of scope for step 1: MONITOR mode, WebSocket client (not needed for research; revisit in step 6).

### 2.2 Data verification report (Python — first piece of the engine)

`python -m engine.verify` reads the DB and writes, per instrument:
- first/last minute; minutes present vs expected per trading session (session calendar derived from
  Capital.com market hours in `instruments.yaml`, not from the data itself);
- in-session gaps (count, total minutes, longest, list);
- bad ticks: bid > ask, zero/negative prices, jumps > 20× median 1-min range;
- spread distribution by hour of day; volume present/zero share.

**Expected minutes ("core session"):** Capital.com returns *current* trading hours in UTC, which
shift by one hour with daylight saving. A minute is expected only if it is open under the fetched
hours **and** under the same hours shifted by +60 minutes, so DST never manufactures gaps. A core
session with **zero** bars is a *missing session* (typically a holiday), counted separately.

**Pass thresholds (per instrument):** missing core minutes < 0.5% (excluding missing sessions); longest
gap inside a partially present session ≤ 30 minutes; bad ticks < 0.01% of minutes; missing sessions
≤ 15 per 365 days. Failing instruments are excluded from research until fixed or re-imported. The report is published as an
Artifact page and its summary stored at `research/data-quality/<date>.json`.

Imports running during this design continue; ranges affected by I1/I2/I4 are re-imported after the
fixes land.

## 3. Step 2 — research engine core (`research/engine`, Python)

### 3.1 Data layer
- Load 1-min bid/ask bars per epic from SQLite into a local parquet cache (gitignored), rebuilt when
  the DB max timestamp changes.
- Resample on the UTC clock grid to any timeframe. Each resampled bar carries `complete: bool` and
  `minutes_present`. Incomplete bars are **never dropped**; strategies see the flag.
- Excluded minutes from the verification report are applied at load.

### 3.2 Instrument specs — `research/engine/instruments.yaml`
Generated by a fetch script from Capital.com `GET /markets/{epic}` (never hand-typed), dated, and
committed: contract size / value per point, min deal size, size step, currency, margin factor,
overnight financing (long/short), trading hours, daily financing cut-off time. Non-USD instruments
convert P&L to USD using stored FX bars at the exit minute (financing at the charge minute).

### 3.3 Strategy interface
```python
class Strategy(Protocol):
    timeframe: str                      # e.g. "15min"
    def on_bar(self, history: BarsView, position: PositionView) -> list[Intent]: ...
```
- `BarsView` exposes closed bars up to and including the current bar only.
- `Intent` = `Enter(side, stop, target | None, risk_r=1.0)` | `Exit()` | `MoveStop(price)`.
- Signals may be precomputed vectorised for speed, but must be expressible through this interface.
- **Lookahead guard (mandatory test):** every strategy is run on the full data and on data truncated at
  20 random cut points (fixed seed); intents up to each cut must be identical, else the strategy is rejected.

### 3.4 Simulator (always 1-min bid/ask)
- **Entry:** market at the next minute's open — ask for long, bid for short.
- **Stop:** long triggers when bid low ≤ stop; fill at stop, or at bid open if the minute opens beyond
  the stop (gap). Short mirrored on ask.
- **Target:** long fills only when bid high > target (trade-through, not touch); short mirrored on ask.
- **Stop and target in the same minute:** stop.
- **Slippage:** per-instrument ticks applied to market entries and stop fills (config, default 0).
- **Financing:** charged per position held through the daily cut-off, using instrument rates;
  weekend/holiday multipliers per Capital.com rules.
- **Sizing:** `size = floor_to_step(account_risk_usd / (stop_distance × usd_value_per_point))`. If
  `size < min_deal_size` the trade is **skipped** and counted in `skipped_min_size`.
- Account equity compounds per run; risk % and starting balance are run parameters (default 2,000 USD, 1%).
- One position per strategy per instrument at a time.
- Exit loop compiled with numba; target: full 2024→now run on one instrument in under 2 minutes.

### 3.5 Outputs
Per run, under `research/runs/<run_id>/` (gitignored except `summary.json`):
- `trades.parquet`: entry/exit time & price, side, size, stop, target, exit reason, gross, spread cost,
  financing, slippage, net USD, R.
- `equity.parquet`; `summary.json`: config, git commit, data range, net USD, expectancy (R and USD)
  with 95% bootstrap CI, trade count, trades/day, win rate (informational), max drawdown (USD and %),
  per-quarter and per-month net, skipped_min_size, cost share of gross.

### 3.6 Tests (pytest)
Hand-computed synthetic scenarios: gap through stop; stop+target same minute; target touch without
trade-through; financing across the cut-off and over a weekend; min-size skip; non-USD conversion;
incomplete-bar flagging; lookahead guard catches a deliberately leaking strategy.

## 4. Step 4 — hypothesis ledger and validation gates

### 4.1 Ledger — `research/ledger/H-####-<slug>.md` on `main`
Front matter: `id, title, source (owner-comment | screen | prior-lead | literature), instruments
(named up front), timeframe, status, created`. Body written **before** any run: plain-language idea,
exact rule, pass/fail criteria. Appended per run: run id, commit, data range, gate, result with CI.

Front matter also records `parent` (for child hypotheses) and `trial_count` (cumulative, §6.2.3).

Status flow: `proposed → in-development → frozen → holdout-passed → demo-passed`, or a diagnosis
status from §6.2.3 (`inconclusive`, `rejected: no signal`, `uneconomic`, `exit-problem`,
`regime-dependent`).
`research/ledger/INDEX.md` lists every hypothesis and the running count of hypotheses and variants
tried.

### 4.2 Gates
| Gate | Data | Pass criteria |
|------|------|---------------|
| G1 walk-forward | 2024-01-01 → 2026-07-31. Train 12 months, test next month, roll monthly; only stitched test months count. | Net expectancy > 0 with CI lower bound > 0; ≥100 test trades; ≥60% of quarters positive; max drawdown < 20% of starting balance; bar raised for variants tried: with k variants tested under one hypothesis, the CI used is (1 − 0.05/k), Bonferroni. |
| G2 holdout | 2026-08-01 → freeze date | Hypothesis must be `frozen` (rule, params, commit recorded). Scored **once**; net expectancy > 0 and within G1's predicted range. The engine refuses a holdout run for a non-frozen hypothesis and records every holdout run in the ledger; a second look reclassifies that period as in-sample for that hypothesis. |
| G3 demo forward | Live demo account | ≥4 weeks and ≥30 trades (whichever later). Net expectancy > 0; live fills, costs and trade count compared against the simulator over the same period — a material mismatch blocks progress until the simulator is corrected. |

Thresholds are starting values and may be changed by the owner; changes are recorded in the ledger
INDEX with date and reason, and never applied retroactively to a hypothesis already past G1.

## 5. Step 3 — review page (claude.ai Artifact)

### 5.1 Spike (throwaway, first)
One Artifact with a pannable/zoomable candle chart of real OIL_BRENT data using the `comments`
capability with `customAnchors`. Must prove: (a) pins anchored to `EPIC|ISO-minute|price` follow pan
and zoom via `placed()`; (b) threads survive a republish with new data; (c) "Send to Claude" reaches a
watching session and a reply lands in the thread. If any fails: fall back to shell-anchored comments
on per-trade chart cards, and return to the owner before building further.

### 5.2 Page (one per research question, republished per run)
- **Header:** hypothesis id + wording, ledger status, run config, commit, data range, gate.
- **KPI strip (all after costs):** net USD, expectancy R with CI, trades/day, max drawdown, worst
  quarter, skipped_min_size.
- **Main chart:** desktop pan/zoom across the run; entry/exit markers, stop/target lines; equity curve
  beneath. Level-of-detail: coarser bars when zoomed out.
- **Trade list:** filters (winners, losers, largest loss, exit reason, cost share); selecting a trade
  centres the chart with 50 bars before entry and after exit.
- **Slices:** by hour, weekday, volatility bucket, quarter — each vs the run baseline.
- **Run comparison:** when a run changes one thing, old vs new side by side with differing trades
  highlighted.
- Run data published as supporting files (`files`) beside the page.

### 5.3 Comment loop
- Free-text comments anchored to data: candle `EPIC|minute|price`, or trade `EPIC|entry-minute|side`.
- "Send to Claude" → Claude restates the comment as a backward-only hypothesis (computable at the
  signal bar), creates a ledger entry with `source: owner-comment`, replies with its id, and later
  replies again with the result and a link to the new run.
- Comments are hypothesis sources, **never labels**; no change is validated by agreement with comments.
- Every session starts by reading open threads on active review pages, so comments left while no
  session was watching are not lost.

## 6. Steps 5–6 — research program and path to money

### 6.1 Research program (order)
1. **Cost reality check:** per instrument × timeframe (5m, 15m, 1h, 4h, 1d), median spread + financing
   vs median bar range and ATR. Output ranks where edges must clear the lowest cost hurdle. Decides
   which instruments/timeframes enter research.
2. **First hypothesis batch** from the known-effects list and the exploration tables (§6.2.1).
3. **Placebo baselines:** random entries with the same exits/sizing (≥200 seeds) and buy-and-hold per
   instrument. Every result page shows them; an edge must beat random by more than its CI.
4. **Prior leads + best batch candidates** — momentum @ 10m (US500), MA 7/14 + RSI(7), and the top
   candidates from step 2 — go through diagnosis (§6.2.3) and the gates (§4.2). Canonical families
   expected in the batch: trend following (4h, daily); opening-range breakout on index sessions;
   London/NY-open behaviour on FX and gold; range mean reversion on FX.
5. **Parameter surfaces** (§6.2.2) per family that shows signal in diagnosis layer 1.
6. Owner comments from review pages feed new hypotheses throughout.

### 6.2 Hypothesis generation, parameter surfaces and diagnosis

**Principle:** certainty is impossible; both error types are controlled and every verdict carries a
reason. Where the data cannot decide, the verdict is `inconclusive`, never `rejected`.

#### 6.2.1 Sources (every hypothesis records one in the ledger `source` field)
1. `known-effect` — documented effects with an economic reason and an identifiable losing side
   (e.g. daily time-series momentum, index overnight/open return concentration, turn-of-month, London
   4pm FX fix, Wednesday EIA inventory reaction in oil, opening-range breakout, Asian-session FX
   ranges). Claude compiles `research/sources/known-effects.md` per instrument with citation and
   rationale before the first batch.
2. `published-strategy` — expert/published strategies, preferring ones with independent replications.
   Treated as candidates only; no privilege.
3. `exploration` — descriptive "what tends to happen after X" tables per instrument on development data
   only: forward returns (mean, dispersion, count) conditioned on hour/weekday/session, prior move
   size, gaps, volatility regime, distance from MA, RSI level, and cross-instrument moves (Brent vs WTI,
   US500 vs US100, gold vs USD pairs, BTC vs ETH). A cell becomes a candidate only if it stands outside
   the distribution of the same table computed on ≥200 shuffled/placebo datasets.
4. `owner-comment` — from review pages (§5.3).
5. `structured-brainstorm` — per-instrument prompts: forced flows (fixes, rebalances, expiries, rolls),
   cross-instrument relations, regime behaviour, scheduled events. Event-based hypotheses need an
   economic-calendar dataset; sourcing it is a small task added to step 5 when the first such hypothesis
   is proposed.

#### 6.2.2 Parameter surfaces (the "big table")
A hypothesis family may be evaluated over a declared grid (instrument × timeframe × holding style ×
indicator lengths/levels × exit params). Rules:
1. **Development data only** (2024-01-01 → 2026-07-31).
2. **Plateau, not peak:** a configuration is a candidate only if ≥70% of its immediate grid neighbours
   are also net-positive after costs (and, where declared, related instruments agree). Isolated peaks
   are recorded as noise.
3. **Placebo grid:** the identical grid is run on ≥200 placebo datasets (random entries with the same
   exits/sizing, and shuffled-return series). The best real configuration must exceed the 95th
   percentile of the best-placebo distribution, else the surface is `rejected: no better than chance`.
4. Survivors become ledger hypotheses with the grid size recorded as their trial count, then pass G1–G3
   as normal.

#### 6.2.3 Diagnosis before any verdict
Each failed or marginal hypothesis is decomposed; the first failing layer sets the status:

| Layer | Test | Status if it fails | Allowed follow-up |
|-------|------|--------------------|-------------------|
| 0 Power | Before testing: trades needed to detect the hypothesised edge at 80% power; available trades counted | `inconclusive` | Park until more data exists |
| 1 Signal | Exit-free: forward-return curve and MFE/MAE after signal vs random entries at matched times, across the declared parameter neighbourhood | `rejected: no signal` | None |
| 2 Economics | Mean favourable move vs spread + financing | `uneconomic` | Child hypothesis on a longer timeframe or lower-cost instrument |
| 3 Exits | Declared exits vs fixed-time, trailing and fixed-R alternatives | `exit-problem` | Child hypothesis changing exits only |
| 4 Stability | Per quarter and per volatility regime | `regime-dependent` | Child hypothesis with a regime filter that must justify itself |

- A **child hypothesis** links to its parent and **inherits the parent's trial count plus one**; the
  Bonferroni adjustment in G1 uses the cumulative count, so repeated knob-turning raises the bar.
- `rejected` and `inconclusive` entries keep their diagnosis and may be reopened with new data, which
  resets nothing about their trial count.

### 6.3 Path to money (after a G2 pass)
- Risk controls: 1% risk per trade; daily loss stop (default 3%); drawdown circuit breaker (default 15%);
  max concurrent positions and a correlated-exposure cap (e.g. not long both OIL_BRENT and OIL_CRUDE);
  manual kill switch.
- Live-execution language chosen in its own short spec; a parity test replays recorded bars through
  live code and the research engine and requires identical orders.
- G3 demo forward test, then a small real account; size increases only after 50 live trades whose
  net expectancy is within the simulator's 95% CI for the same period.

## 7. Decommissioning old code
After step 2's tests pass: delete the Java backtest engine (`backtest/`), discovery pipeline, TA4j
strategy code and `dashboard/` in one commit; keep `history/`, `brokers/capital/` and what they need.
Rewrite `CLAUDE.md`, `TODO.md` and `docs/instrument-personality-playbook.md` to match this spec. The
learnings doc stays as history. The deployed Cloudflare dashboard Worker/D1 are left running until the
owner decides to delete them (outward-facing; needs explicit approval).

## 8. Out of scope
MONITOR mode, live order execution, ML models, instruments beyond the 15 listed, parameter grids that
skip the plateau and placebo-grid rules of §6.2.2.
