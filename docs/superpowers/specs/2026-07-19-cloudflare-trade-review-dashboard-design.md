# Cloudflare trade-review dashboard — design spec

**Date:** 2026-07-19
**Status:** Design agreed; pending implementation plan.
**Supersedes:** `docs/observability-and-exits-design.md` §3 (which assumed a self-contained Claude
Artifact). An artifact is read-only and sandboxed — it cannot persist phone feedback or sync it back
to the engine, which is the whole point. This spec replaces that delivery mechanism with a Cloudflare
Worker + D1. The *contents* of the dashboard (KPI tiles, slices, equity curve, self-describing trade
cards) carry over from §3 unchanged.

## Why this exists

The strategy work has reached a human-in-the-loop phase (see `docs/instrument-personality-playbook.md`):
our eyes *propose* hypotheses about the trades, the full dataset *disposes* of them out-of-sample.
That loop needs a tool where a human can, from their phone, scroll the backtest's entries/exits, see
each trade in context (50 bars each side, stop/target lines), and **flag trades / leave notes** that
flow back into the engine's hypothesis loop. Read-only viewing is not enough — the feedback
write-back is the requirement that rules out a Claude Artifact and justifies real hosting.

## Scope (what this is / is not)

**In scope:** a phone-first, read-mostly trade-review dashboard hosted on Cloudflare, backed by D1,
fed by the existing Java backtest engine running locally. Feedback captured on the phone syncs back
to the laptop.

**Explicitly out of scope (no change):**
- No rewrite of the Java engine, strategy code, or backtest/tuning harness. The engine stays on the
  laptop and keeps producing `experiments.sqlite`.
- No MONITOR/TRADE mode on Cloudflare. Those stay Java-side and remain future/blocked work.
- Not a live server for streaming data. Trades are a static-per-run dataset pushed after each
  backtest; only feedback is genuinely mutable.

## Goal / success criteria

1. On a phone browser, behind a login, view the latest backtest run: KPI tiles, loser-cause slices,
   equity curve, and a scrollable gallery of trade cards (50 bars before entry + 50 after exit, with
   entry/exit markers, stop + target lines).
2. Flag a trade as a hypothesis and attach a note, from the phone.
3. That feedback survives a re-run of the backtest with different config — it is keyed to the market
   entry, not to a run-scoped id — and can be pulled back to the laptop to feed `query.py` / the
   hypothesis loop.
4. Only the owner can view trades or write feedback (Cloudflare Access).

## Architecture

```
┌─ LAPTOP (unchanged Java) ──────────┐        ┌─ CLOUDFLARE ───────────────────────┐
│                                    │        │                                    │
│  Backtest engine  ──▶ experiments  │  push  │   Worker (single deploy)           │
│  (TA4j sweeps)        .sqlite      │ ─────▶ │   ├─ serves static frontend assets │
│                                    │  sync  │   │   (phone dashboard, TS/React)   │
│  sync script  ◀── feedback ────────┼◀────── │   └─ /api/* ──▶ D1                  │
│  (feeds hypothesis loop)           │  pull  │              (trades + feedback)    │
└────────────────────────────────────┘        └────────────────────────────────────┘
                                                          ▲
                                                    phone (browser, behind Access)
```

Four independent units, each with one responsibility:

### 1. Engine (Java, laptop) — unchanged
No strategy/backtest code changes. It already writes `experiments.sqlite` with per-trade features
(regenerate with `-Dsweep.persist=true`). This spec adds *only* out-of-band sync scripts around it,
not code inside it.

### 2. D1 — single source of truth
Holds both trades and feedback (chosen over a hybrid static-export split: one source of truth,
server-side SQL slicing, room to grow). Schema below.

### 3. Worker — one deployable
A single Cloudflare Worker with a static-assets binding. It serves the frontend assets *and* the
`/api/*` JSON endpoints backed by D1. One deploy, one URL. (Modern Workers-with-static-assets, not
legacy Pages.)

### 4. Frontend (TS, served as the Worker's assets)
React + Vite. Candlesticks hand-rolled in SVG (≈100 bars per card is trivial; avoids a charting-lib
dependency and keeps the phone bundle small). Visual-design pass happens during implementation via
the frontend-design skill. Reuses the §3 card contents: self-describing cards embedding config slug,
git commit, entry/exit time+price, pnl (pts **and** $/contract), R, exit_reason, and the entry
feature vector, so a single screenshot is a complete bug report.

## D1 schema

```sql
runs(
  id            TEXT PRIMARY KEY,   -- e.g. git-commit + timestamp
  created_at    TEXT,
  config_json   TEXT,              -- the strategy config used
  window_start  TEXT,
  window_end    TEXT,
  trades_count  INTEGER,
  win_rate      REAL,
  net_avg_pnl   REAL,
  net_avg_pnl_usd REAL,
  ... other KPI columns (trades/day, worst-quarter, max DD)
)

trades(
  id             TEXT PRIMARY KEY,  -- run-scoped surrogate
  run_id         TEXT REFERENCES runs(id),
  signal_key     TEXT,             -- STABLE identity: epic|entry_timestamp|direction
  entry_time     TEXT,
  exit_time      TEXT,
  direction      TEXT,             -- LONG | SHORT
  entry_price    REAL,
  exit_price     REAL,
  stop_price     REAL,
  target_price   REAL,
  pnl            REAL,
  r_multiple     REAL,
  exit_reason    TEXT,
  is_win         INTEGER,
  -- feature columns carried from experiments.sqlite:
  dist_to_trend_ema_atr REAL,
  atr_percentile        REAL,
  rsi_value             REAL,
  volume_ratio          REAL,
  hour_utc              INTEGER,
  day_of_week           TEXT,
  volatility_regime     TEXT,
  bars_json      TEXT               -- the 50-before + 50-after OHLC window, inlined
)

feedback(
  id           TEXT PRIMARY KEY,
  signal_key   TEXT,               -- keys to the market entry, NOT run_id
  flag         TEXT,               -- e.g. 'hypothesis', 'knife-catch', ...
  note         TEXT,
  created_at   TEXT
)
```

Two deliberate choices:

- **`bars_json` inlined on the trade row** rather than a separate ~30k-row-per-run bars table. The
  access pattern is "open one trade → draw its chart," so one row fetch returns everything needed.
- **`signal_key` is the stable identity of an entry** (`epic|entry_timestamp|direction`). Surrogate
  trade `id`s change every run; `signal_key` does not. **Feedback keys on `signal_key`**, so a flag
  left on an entry re-attaches to that same entry whenever it reappears in a later run — exactly what
  "eyes propose, data disposes across runs" requires.

## API (Worker `/api/*`)

- `GET  /api/runs` — list runs (id, created_at, headline KPIs).
- `GET  /api/runs/:id` — one run's KPIs + config.
- `GET  /api/runs/:id/trades?filter=...` — trades for a run (filterable: losers, losers-by-cause,
  sampled winners). Returns trade rows *without* `bars_json` (list view stays light).
- `GET  /api/trades/:id` — one trade *with* `bars_json` (card/detail view).
- `GET  /api/runs/:id/slices` — conditional win%/expectancy per feature bucket (loser-cause slices).
- `GET  /api/feedback?signal_key=...` — feedback for an entry (or all).
- `POST /api/feedback` — create/update a flag+note for a `signal_key`.

## Sync scripts (laptop ↔ D1)

Kept entirely outside the Java engine — the engine's only job stays "produce `experiments.sqlite`".

- **Push (laptop → D1):** a script reads the latest run from `experiments.sqlite`, transforms rows
  (including building `bars_json` from the bar window and `signal_key` from epic+entry+direction),
  and loads them into D1 via `wrangler d1 execute`. One command after a backtest.
- **Pull (D1 → laptop):** a script pulls the `feedback` table to a local file the hypothesis loop /
  `experiments/query.py` can join against `signal_key`. One command.

## Access / security

**Cloudflare Access** (Zero Trust, free tier) gates the whole Worker with email-OTP login. The owner
logs in once on their phone; nobody else can read trades or POST feedback. No auth code in the app —
Access sits in front. (Considered and rejected for now: shared-secret token — weaker; open — trades
are private and the feedback endpoint accepts writes.)

## Testing

- **Worker/API:** unit-test each `/api/*` handler against a local D1 (Vitest + Miniflare/`wrangler
  dev`), including the `signal_key` feedback re-attachment across two runs.
- **Sync scripts:** test the `experiments.sqlite` → D1 row transform (esp. `signal_key` construction
  and `bars_json` shape) on a fixture DB.
- **Frontend:** component tests for the trade card SVG rendering (entry/exit markers, stop/target
  lines land at the right prices) and the filter/slice views.
- **End-to-end smoke:** push a fixture run → load dashboard → flag a trade → pull feedback → confirm
  it round-trips on `signal_key`.

## Non-goals / deferred

- Live/streaming updates (MONITOR-mode phone alerts) — revisit only if/when MONITOR runs in cloud.
- Multi-user / sharing beyond the single owner.
- Editing strategy config from the phone — the phone proposes hypotheses; the laptop disposes.

## Open questions for implementation planning

- Exact `wrangler`/D1 import mechanism for the push (batched `d1 execute --file` vs D1 HTTP API) —
  decide during planning using the `wrangler` / `cloudflare` skills for current syntax.
- Whether to keep a rolling history of runs in D1 or overwrite to just the latest + a pinned baseline.
