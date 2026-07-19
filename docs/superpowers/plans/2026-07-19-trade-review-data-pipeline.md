# Trade-Review Data Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Cloudflare data pipeline behind the phone trade-review dashboard — a Java exporter emits an enriched `run.json`, a push script loads it into D1, a Worker serves it over `/api/*` behind Cloudflare Access, and feedback written to D1 syncs back to the laptop. (No UI — that is Plan 2.)

**Architecture:** The Java backtest engine gains one read-only exporter that reuses `BarSeriesFactory`/`TradeResult` data already in the sweep loop to write `run.json` (KPIs + trades + derived stop/target + 50-bar-each-side windows + stable `signal_key`). A Node/wrangler push script uploads that file into a Cloudflare D1 database. A single Cloudflare Worker (Hono) serves `/api/*` JSON from D1. Feedback is keyed on `signal_key` so it survives re-runs; a pull script brings it back to the laptop. Cloudflare Access gates the whole Worker.

**Tech Stack:** Java 21 + Jackson (exporter, existing deps); TypeScript + Hono (Worker); Cloudflare D1 (SQLite); Wrangler CLI; Vitest + `@cloudflare/vitest-pool-workers` (tests); Cloudflare Access (auth).

## Global Constraints

- **Java build/test:** `./mvnw test` (JDK 21). On a fresh container decompress the history DB first: `gzip -dc data/axe-trader.sqlite.gz > data/axe-trader.sqlite`.
- **The engine's strategy/backtest logic is not modified.** The only Java addition is a new read-only exporter class plus one gated call site in `ConfluenceSweepTest`.
- **All Cloudflare code lives under `dashboard/`** at the repo root. Never commit `dashboard/node_modules`, `dashboard/.wrangler`, or any `.dev.vars`.
- **Load the `wrangler` and `cloudflare` skills before any `wrangler`/deploy step or before finalizing `wrangler.jsonc` / the vitest-pool-workers config** — confirm current syntax against Cloudflare docs. The config shown in tasks is the design intent; the skills are the authority on exact current keys.
- **`signal_key` format is exactly `instrument|entryTsIso|direction`** (e.g. `US500|2025-03-04T14:30:00Z|LONG`), used identically by the exporter, the API, and feedback. It is the join key across runs.
- **D1 binding name is `DB`; database name is `axe-trader-dashboard`.**
- **Money/geometry note:** stop/target derivation must match `BacktestRunner`: `stopDist = stopAtrMultiple × atrAtEntry`, `targetDist = targetAtrMultiple × atrAtEntry`; LONG → stop `entry−stopDist`, target `entry+targetDist`; SHORT reversed. Use the per-trade entry ATR (`TradeFeatures.atr()`).

---

### Task 1: Scaffold the `dashboard/` Worker project

**Files:**
- Create: `dashboard/package.json`
- Create: `dashboard/tsconfig.json`
- Create: `dashboard/wrangler.jsonc`
- Create: `dashboard/vitest.config.ts`
- Create: `dashboard/src/index.ts`
- Create: `dashboard/test/health.test.ts`
- Create: `dashboard/.gitignore`
- Modify: `.gitignore` (repo root) — add `dashboard/node_modules/`, `dashboard/.wrangler/`

**Interfaces:**
- Produces: a Hono `app` exported as default from `dashboard/src/index.ts`; `GET /api/health` → `{ "ok": true }`.

- [ ] **Step 1: Create `dashboard/.gitignore`**

```
node_modules/
.wrangler/
dist/
*.dev.vars
.dev.vars
```

- [ ] **Step 2: Create `dashboard/package.json`**

```json
{
  "name": "axe-trader-dashboard",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "wrangler dev",
    "deploy": "wrangler deploy",
    "test": "vitest run",
    "migrate:local": "wrangler d1 migrations apply axe-trader-dashboard --local",
    "migrate:remote": "wrangler d1 migrations apply axe-trader-dashboard --remote"
  },
  "dependencies": {
    "hono": "^4"
  },
  "devDependencies": {
    "@cloudflare/vitest-pool-workers": "^0.5",
    "typescript": "^5",
    "vitest": "^2",
    "wrangler": "^3"
  }
}
```

- [ ] **Step 3: Create `dashboard/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "es2022",
    "module": "es2022",
    "moduleResolution": "bundler",
    "lib": ["es2022"],
    "types": ["@cloudflare/workers-types"],
    "strict": true,
    "skipLibCheck": true,
    "noEmit": true
  },
  "include": ["src", "test", "scripts"]
}
```

- [ ] **Step 4: Create `dashboard/wrangler.jsonc`** (confirm keys with the `wrangler` skill)

```jsonc
{
  "name": "axe-trader-dashboard",
  "main": "src/index.ts",
  "compatibility_date": "2026-07-01",
  "d1_databases": [
    { "binding": "DB", "database_name": "axe-trader-dashboard", "database_id": "PLACEHOLDER_SET_IN_TASK_11", "migrations_dir": "migrations" }
  ],
  "assets": { "directory": "./public", "binding": "ASSETS" }
}
```

Note: `public/` is populated by Plan 2 (the frontend build). Create an empty `dashboard/public/.gitkeep` so the assets dir exists.

- [ ] **Step 5: Create `dashboard/src/index.ts`**

```ts
import { Hono } from "hono";

export type Env = { DB: D1Database };

const app = new Hono<{ Bindings: Env }>();

app.get("/api/health", (c) => c.json({ ok: true }));

export default app;
```

- [ ] **Step 6: Create `dashboard/vitest.config.ts`** (confirm with the `wrangler`/`cloudflare` skill)

```ts
import { defineWorkersConfig } from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig({
  test: {
    poolOptions: {
      workers: {
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          d1Databases: { DB: "axe-trader-dashboard" },
        },
      },
    },
  },
});
```

- [ ] **Step 7: Create `dashboard/test/health.test.ts`**

```ts
import { env } from "cloudflare:test";
import { describe, it, expect } from "vitest";
import app from "../src/index";

describe("health", () => {
  it("returns ok", async () => {
    const res = await app.request("/api/health", {}, env);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
  });
});
```

- [ ] **Step 8: Install and run the test to verify it passes**

Run: `cd dashboard && npm install && npm test`
Expected: PASS (1 test). If the pool config errors, load the `wrangler` skill and reconcile `vitest.config.ts` / `wrangler.jsonc` with current docs before proceeding.

- [ ] **Step 9: Add `dashboard/` ignores to the repo-root `.gitignore` and commit**

```bash
printf 'dashboard/node_modules/\ndashboard/.wrangler/\n' >> .gitignore
git add dashboard/.gitignore dashboard/package.json dashboard/tsconfig.json dashboard/wrangler.jsonc dashboard/vitest.config.ts dashboard/src/index.ts dashboard/test/health.test.ts dashboard/public/.gitkeep .gitignore
git commit -m "feat(dashboard): scaffold Cloudflare Worker + vitest harness"
```

---

### Task 2: D1 schema migration

**Files:**
- Create: `dashboard/migrations/0001_init.sql`
- Create: `dashboard/test/schema.test.ts`

**Interfaces:**
- Produces: tables `runs`, `trades`, `feedback` (columns exactly as below). Later tasks read/write these.

- [ ] **Step 1: Write the failing test**

```ts
import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";

beforeAll(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

describe("schema", () => {
  it("has runs, trades, feedback tables", async () => {
    const { results } = await env.DB.prepare(
      "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
    ).all();
    const names = results.map((r) => r.name);
    expect(names).toContain("runs");
    expect(names).toContain("trades");
    expect(names).toContain("feedback");
  });

  it("enforces one feedback row per signal_key", async () => {
    await env.DB.prepare(
      "INSERT INTO feedback (id, signal_key, flag, note, created_at) VALUES (?,?,?,?,?)"
    ).bind("f1", "US500|2025-01-01T00:00:00Z|LONG", "hypothesis", "n", "2026-07-19T00:00:00Z").run();
    await expect(
      env.DB.prepare(
        "INSERT INTO feedback (id, signal_key, flag, note, created_at) VALUES (?,?,?,?,?)"
      ).bind("f2", "US500|2025-01-01T00:00:00Z|LONG", "x", "y", "2026-07-19T00:00:01Z").run()
    ).rejects.toThrow();
  });
});
```

Add the migrations binding to `dashboard/vitest.config.ts` `miniflare` block (confirm exact key with the `wrangler` skill):

```ts
          bindings: { TEST_MIGRATIONS: [] }, // populated by readD1Migrations, see below
```

At the top of `vitest.config.ts`:

```ts
import { readD1Migrations } from "@cloudflare/vitest-pool-workers/config";
const migrations = await readD1Migrations("./migrations");
// then in miniflare: bindings: { TEST_MIGRATIONS: migrations }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd dashboard && npm test -- schema`
Expected: FAIL (tables do not exist / migrations empty).

- [ ] **Step 3: Write `dashboard/migrations/0001_init.sql`**

```sql
CREATE TABLE runs (
  id              TEXT PRIMARY KEY,
  created_at      TEXT NOT NULL,
  label           TEXT,
  config_json     TEXT,
  instrument      TEXT,
  timeframe_min   INTEGER,
  window_start    TEXT,
  window_end      TEXT,
  trades_count    INTEGER,
  trades_per_day  REAL,
  win_rate        REAL,
  net_avg_pnl     REAL,
  net_avg_pnl_usd REAL,
  avg_r           REAL,
  max_drawdown    REAL,
  worst_quarter_net REAL
);

CREATE TABLE trades (
  id             TEXT PRIMARY KEY,
  run_id         TEXT NOT NULL REFERENCES runs(id),
  signal_key     TEXT NOT NULL,
  entry_time     TEXT,
  exit_time      TEXT,
  direction      TEXT,
  entry_price    REAL,
  exit_price     REAL,
  stop_price     REAL,
  target_price   REAL,
  pnl            REAL,
  net_pnl        REAL,
  r_multiple     REAL,
  exit_reason    TEXT,
  is_win         INTEGER,
  rsi_value      REAL,
  dist_to_trend_ema_atr REAL,
  atr_value      REAL,
  atr_percentile REAL,
  volume_ratio   REAL,
  hour_utc       INTEGER,
  day_of_week    INTEGER,
  volatility_regime TEXT,
  confluence_score  INTEGER,
  pillars_fired  TEXT,
  bars_json      TEXT
);
CREATE INDEX idx_trades_run ON trades(run_id);
CREATE INDEX idx_trades_signal ON trades(signal_key);

CREATE TABLE feedback (
  id           TEXT PRIMARY KEY,
  signal_key   TEXT NOT NULL UNIQUE,
  flag         TEXT,
  note         TEXT,
  created_at   TEXT NOT NULL
);
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd dashboard && npm test -- schema`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add dashboard/migrations/0001_init.sql dashboard/test/schema.test.ts dashboard/vitest.config.ts
git commit -m "feat(dashboard): D1 schema — runs, trades, feedback"
```

---

### Task 3: TS row types and a seed helper for tests

**Files:**
- Create: `dashboard/src/schema.ts`
- Create: `dashboard/test/seed.ts`

**Interfaces:**
- Produces: `RunRow`, `TradeRow`, `FeedbackRow` types; `seedRun(db, overrides?)` and `seedTrade(db, runId, overrides?)` test helpers returning the inserted row's id.

- [ ] **Step 1: Create `dashboard/src/schema.ts`**

```ts
export interface RunRow {
  id: string; created_at: string; label: string | null; config_json: string | null;
  instrument: string | null; timeframe_min: number | null;
  window_start: string | null; window_end: string | null;
  trades_count: number | null; trades_per_day: number | null;
  win_rate: number | null; net_avg_pnl: number | null; net_avg_pnl_usd: number | null;
  avg_r: number | null; max_drawdown: number | null; worst_quarter_net: number | null;
}

export interface TradeRow {
  id: string; run_id: string; signal_key: string;
  entry_time: string | null; exit_time: string | null; direction: string | null;
  entry_price: number | null; exit_price: number | null;
  stop_price: number | null; target_price: number | null;
  pnl: number | null; net_pnl: number | null; r_multiple: number | null;
  exit_reason: string | null; is_win: number | null;
  rsi_value: number | null; dist_to_trend_ema_atr: number | null;
  atr_value: number | null; atr_percentile: number | null; volume_ratio: number | null;
  hour_utc: number | null; day_of_week: number | null; volatility_regime: string | null;
  confluence_score: number | null; pillars_fired: string | null; bars_json: string | null;
}

export interface FeedbackRow {
  id: string; signal_key: string; flag: string | null; note: string | null; created_at: string;
}
```

- [ ] **Step 2: Create `dashboard/test/seed.ts`**

```ts
import type { TradeRow } from "../src/schema";

export async function seedRun(db: D1Database, overrides: Record<string, unknown> = {}): Promise<string> {
  const id = (overrides.id as string) ?? `run-${crypto.randomUUID()}`;
  await db.prepare(
    `INSERT INTO runs (id, created_at, label, config_json, instrument, timeframe_min,
      window_start, window_end, trades_count, trades_per_day, win_rate, net_avg_pnl,
      net_avg_pnl_usd, avg_r, max_drawdown, worst_quarter_net)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
  ).bind(
    id, (overrides.created_at as string) ?? "2026-07-19T00:00:00Z",
    (overrides.label as string) ?? "test-run", (overrides.config_json as string) ?? "{}",
    "US500", 5, "2025-01-01T00:00:00Z", "2025-12-31T00:00:00Z",
    (overrides.trades_count as number) ?? 0, 0.9, (overrides.win_rate as number) ?? 0.8,
    (overrides.net_avg_pnl as number) ?? -0.14, -0.13, 0.1, 5.0, -1.5
  ).run();
  return id;
}

export async function seedTrade(
  db: D1Database, runId: string, overrides: Partial<TradeRow> = {}
): Promise<string> {
  const id = overrides.id ?? `trade-${crypto.randomUUID()}`;
  const signal = overrides.signal_key ?? `US500|2025-03-04T14:30:00Z|LONG`;
  await db.prepare(
    `INSERT INTO trades (id, run_id, signal_key, entry_time, exit_time, direction,
      entry_price, exit_price, stop_price, target_price, pnl, net_pnl, r_multiple,
      exit_reason, is_win, rsi_value, dist_to_trend_ema_atr, atr_value, atr_percentile,
      volume_ratio, hour_utc, day_of_week, volatility_regime, confluence_score,
      pillars_fired, bars_json)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
  ).bind(
    id, runId, signal,
    overrides.entry_time ?? "2025-03-04T14:30:00Z", overrides.exit_time ?? "2025-03-04T15:30:00Z",
    overrides.direction ?? "LONG", overrides.entry_price ?? 5000, overrides.exit_price ?? 5004,
    overrides.stop_price ?? 4988, overrides.target_price ?? 5003,
    overrides.pnl ?? 4, overrides.net_pnl ?? 3.5, overrides.r_multiple ?? 0.3,
    overrides.exit_reason ?? "TARGET", overrides.is_win ?? 1, overrides.rsi_value ?? 28,
    overrides.dist_to_trend_ema_atr ?? 1.2, overrides.atr_value ?? 4, overrides.atr_percentile ?? 0.6,
    overrides.volume_ratio ?? 1.0, overrides.hour_utc ?? 14, overrides.day_of_week ?? 2,
    overrides.volatility_regime ?? "NORMAL", overrides.confluence_score ?? 3,
    overrides.pillars_fired ?? "RSI+BB+S/R", overrides.bars_json ?? "[]"
  ).run();
  return id;
}
```

- [ ] **Step 3: Verify it compiles (no test yet — helpers are exercised by later tasks)**

Run: `cd dashboard && npx tsc --noEmit`
Expected: no type errors.

- [ ] **Step 4: Commit**

```bash
git add dashboard/src/schema.ts dashboard/test/seed.ts
git commit -m "feat(dashboard): row types + test seed helpers"
```

---

### Task 4: `GET /api/runs` and `GET /api/runs/:id`

**Files:**
- Create: `dashboard/src/routes/runs.ts`
- Modify: `dashboard/src/index.ts`
- Create: `dashboard/test/runs.test.ts`

**Interfaces:**
- Consumes: `Env`, `RunRow`, `seedRun`.
- Produces: mounts `runsRoutes` at `/api`; `GET /api/runs` → `RunRow[]` (newest first); `GET /api/runs/:id` → `RunRow` or 404.

- [ ] **Step 1: Write the failing test**

```ts
import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";
import app from "../src/index";
import { seedRun } from "./seed";

beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

describe("runs api", () => {
  it("lists runs newest first", async () => {
    await seedRun(env.DB, { id: "r-old", created_at: "2026-07-01T00:00:00Z" });
    await seedRun(env.DB, { id: "r-new", created_at: "2026-07-19T00:00:00Z" });
    const res = await app.request("/api/runs", {}, env);
    expect(res.status).toBe(200);
    const rows = await res.json<{ id: string }[]>();
    expect(rows[0].id).toBe("r-new");
  });

  it("gets one run, 404 when missing", async () => {
    await seedRun(env.DB, { id: "r-1" });
    expect((await app.request("/api/runs/r-1", {}, env)).status).toBe(200);
    expect((await app.request("/api/runs/nope", {}, env)).status).toBe(404);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd dashboard && npm test -- runs`
Expected: FAIL (route not found → 404 on the list route).

- [ ] **Step 3: Create `dashboard/src/routes/runs.ts`**

```ts
import { Hono } from "hono";
import type { Env } from "../index";
import type { RunRow } from "../schema";

export const runsRoutes = new Hono<{ Bindings: Env }>();

runsRoutes.get("/runs", async (c) => {
  const { results } = await c.env.DB.prepare(
    "SELECT * FROM runs ORDER BY created_at DESC"
  ).all<RunRow>();
  return c.json(results);
});

runsRoutes.get("/runs/:id", async (c) => {
  const row = await c.env.DB.prepare("SELECT * FROM runs WHERE id = ?")
    .bind(c.req.param("id")).first<RunRow>();
  if (!row) return c.json({ error: "not found" }, 404);
  return c.json(row);
});
```

- [ ] **Step 4: Mount it in `dashboard/src/index.ts`**

```ts
import { Hono } from "hono";
import { runsRoutes } from "./routes/runs";

export type Env = { DB: D1Database };

const app = new Hono<{ Bindings: Env }>();
app.get("/api/health", (c) => c.json({ ok: true }));
app.route("/api", runsRoutes);

export default app;
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd dashboard && npm test -- runs`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add dashboard/src/routes/runs.ts dashboard/src/index.ts dashboard/test/runs.test.ts
git commit -m "feat(dashboard): GET /api/runs and /api/runs/:id"
```

---

### Task 5: Trade list and trade detail endpoints

**Files:**
- Create: `dashboard/src/routes/trades.ts`
- Modify: `dashboard/src/index.ts`
- Create: `dashboard/test/trades.test.ts`

**Interfaces:**
- Consumes: `Env`, `TradeRow`, `seedRun`, `seedTrade`.
- Produces: `GET /api/runs/:id/trades?filter=all|losers|winners` → trade rows **without** `bars_json`; `GET /api/trades/:id` → one `TradeRow` **with** `bars_json` (404 if missing).

- [ ] **Step 1: Write the failing test**

```ts
import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";
import app from "../src/index";
import { seedRun, seedTrade } from "./seed";

beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

describe("trades api", () => {
  it("lists trades without bars_json and filters losers", async () => {
    const run = await seedRun(env.DB);
    await seedTrade(env.DB, run, { id: "t-win", is_win: 1, signal_key: "US500|2025-03-04T14:30:00Z|LONG" });
    await seedTrade(env.DB, run, { id: "t-loss", is_win: 0, signal_key: "US500|2025-03-05T14:30:00Z|LONG" });

    const all = await (await app.request(`/api/runs/${run}/trades`, {}, env)).json<any[]>();
    expect(all.length).toBe(2);
    expect(all[0].bars_json).toBeUndefined();

    const losers = await (await app.request(`/api/runs/${run}/trades?filter=losers`, {}, env)).json<any[]>();
    expect(losers.map((t) => t.id)).toEqual(["t-loss"]);
  });

  it("returns one trade with bars_json", async () => {
    const run = await seedRun(env.DB);
    await seedTrade(env.DB, run, { id: "t-1", bars_json: '[{"t":1,"o":1,"h":2,"l":0,"c":1}]' });
    const res = await app.request("/api/trades/t-1", {}, env);
    expect(res.status).toBe(200);
    const row = await res.json<{ bars_json: string }>();
    expect(JSON.parse(row.bars_json)).toHaveLength(1);
    expect((await app.request("/api/trades/missing", {}, env)).status).toBe(404);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd dashboard && npm test -- trades`
Expected: FAIL (routes missing).

- [ ] **Step 3: Create `dashboard/src/routes/trades.ts`**

```ts
import { Hono } from "hono";
import type { Env } from "../index";
import type { TradeRow } from "../schema";

export const tradesRoutes = new Hono<{ Bindings: Env }>();

// Columns for the list view — everything EXCEPT bars_json (keep list light).
const LIST_COLS =
  `id, run_id, signal_key, entry_time, exit_time, direction, entry_price, exit_price,
   stop_price, target_price, pnl, net_pnl, r_multiple, exit_reason, is_win, rsi_value,
   dist_to_trend_ema_atr, atr_value, atr_percentile, volume_ratio, hour_utc, day_of_week,
   volatility_regime, confluence_score, pillars_fired`;

tradesRoutes.get("/runs/:id/trades", async (c) => {
  const filter = c.req.query("filter") ?? "all";
  let where = "run_id = ?";
  if (filter === "losers") where += " AND is_win = 0";
  else if (filter === "winners") where += " AND is_win = 1";
  const { results } = await c.env.DB.prepare(
    `SELECT ${LIST_COLS} FROM trades WHERE ${where} ORDER BY entry_time`
  ).bind(c.req.param("id")).all<Omit<TradeRow, "bars_json">>();
  return c.json(results);
});

tradesRoutes.get("/trades/:id", async (c) => {
  const row = await c.env.DB.prepare("SELECT * FROM trades WHERE id = ?")
    .bind(c.req.param("id")).first<TradeRow>();
  if (!row) return c.json({ error: "not found" }, 404);
  return c.json(row);
});
```

- [ ] **Step 4: Mount it in `dashboard/src/index.ts`**

Add after the runs route:

```ts
import { tradesRoutes } from "./routes/trades";
// ...
app.route("/api", tradesRoutes);
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd dashboard && npm test -- trades`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add dashboard/src/routes/trades.ts dashboard/src/index.ts dashboard/test/trades.test.ts
git commit -m "feat(dashboard): trade list (no bars) + trade detail (with bars)"
```

---

### Task 6: `GET /api/runs/:id/slices` — conditional win% / expectancy per feature bucket

**Files:**
- Create: `dashboard/src/routes/slices.ts`
- Modify: `dashboard/src/index.ts`
- Create: `dashboard/test/slices.test.ts`

**Interfaces:**
- Consumes: `Env`, `seedRun`, `seedTrade`.
- Produces: `GET /api/runs/:id/slices?feature=<col>&buckets=N` → `{ baseline_win: number, trades: number, buckets: { lo: number, hi: number, count: number, win_pct: number, net_avg_pnl: number }[] }`. Mirrors `experiments/query.py slice`: order trades by the feature value, split into N equal-count buckets. `feature` must be an allowlisted numeric column.

- [ ] **Step 1: Write the failing test**

```ts
import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";
import app from "../src/index";
import { seedRun, seedTrade } from "./seed";

beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

describe("slices api", () => {
  it("buckets by feature and reports conditional win%", async () => {
    const run = await seedRun(env.DB);
    // near-EMA winners, far-EMA losers
    for (let i = 0; i < 4; i++)
      await seedTrade(env.DB, run, { id: `near-${i}`, dist_to_trend_ema_atr: 0.5, is_win: 1, net_pnl: 1,
        signal_key: `US500|2025-03-0${i + 1}T14:30:00Z|LONG` });
    for (let i = 0; i < 4; i++)
      await seedTrade(env.DB, run, { id: `far-${i}`, dist_to_trend_ema_atr: 5.0, is_win: 0, net_pnl: -2,
        signal_key: `US500|2025-04-0${i + 1}T14:30:00Z|LONG` });

    const res = await app.request(`/api/runs/${run}/slices?feature=dist_to_trend_ema_atr&buckets=2`, {}, env);
    expect(res.status).toBe(200);
    const body = await res.json<any>();
    expect(body.baseline_win).toBeCloseTo(0.5);
    expect(body.buckets[0].win_pct).toBeCloseTo(1.0); // near-EMA bucket
    expect(body.buckets[1].win_pct).toBeCloseTo(0.0); // far-EMA bucket
  });

  it("rejects a non-allowlisted feature", async () => {
    const run = await seedRun(env.DB);
    const res = await app.request(`/api/runs/${run}/slices?feature=id;DROP`, {}, env);
    expect(res.status).toBe(400);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd dashboard && npm test -- slices`
Expected: FAIL (route missing).

- [ ] **Step 3: Create `dashboard/src/routes/slices.ts`**

```ts
import { Hono } from "hono";
import type { Env } from "../index";

// Allowlist — prevents SQL injection via the feature name (it goes into the SELECT list).
const FEATURES = new Set([
  "rsi_value", "dist_to_trend_ema_atr", "atr_value", "atr_percentile",
  "volume_ratio", "hour_utc", "day_of_week", "r_multiple",
]);

export const slicesRoutes = new Hono<{ Bindings: Env }>();

slicesRoutes.get("/runs/:id/slices", async (c) => {
  const feature = c.req.query("feature") ?? "";
  if (!FEATURES.has(feature)) return c.json({ error: "unknown feature" }, 400);
  const buckets = Math.max(1, Math.min(10, Number(c.req.query("buckets") ?? 5)));

  const { results } = await c.env.DB.prepare(
    `SELECT ${feature} AS v, is_win, net_pnl FROM trades
     WHERE run_id = ? AND ${feature} IS NOT NULL ORDER BY v`
  ).bind(c.req.param("id")).all<{ v: number; is_win: number; net_pnl: number }>();

  const n = results.length;
  if (n === 0) return c.json({ baseline_win: 0, trades: 0, buckets: [] });
  const baselineWin = results.reduce((s, r) => s + r.is_win, 0) / n;

  const per = Math.max(1, Math.floor(n / buckets));
  const out = [];
  for (let start = 0; start < n; start += per) {
    const chunk = results.slice(start, start + per);
    if (chunk.length === 0) continue;
    out.push({
      lo: chunk[0].v,
      hi: chunk[chunk.length - 1].v,
      count: chunk.length,
      win_pct: chunk.reduce((s, r) => s + r.is_win, 0) / chunk.length,
      net_avg_pnl: chunk.reduce((s, r) => s + r.net_pnl, 0) / chunk.length,
    });
  }
  return c.json({ baseline_win: baselineWin, trades: n, buckets: out });
});
```

- [ ] **Step 4: Mount it in `dashboard/src/index.ts`**

```ts
import { slicesRoutes } from "./routes/slices";
// ...
app.route("/api", slicesRoutes);
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd dashboard && npm test -- slices`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add dashboard/src/routes/slices.ts dashboard/src/index.ts dashboard/test/slices.test.ts
git commit -m "feat(dashboard): conditional feature-bucket slices endpoint"
```

---

### Task 7: Feedback endpoints (upsert by `signal_key`, survives re-runs)

**Files:**
- Create: `dashboard/src/routes/feedback.ts`
- Modify: `dashboard/src/index.ts`
- Create: `dashboard/test/feedback.test.ts`

**Interfaces:**
- Consumes: `Env`, `FeedbackRow`, `seedRun`, `seedTrade`.
- Produces: `POST /api/feedback` body `{ signal_key, flag, note }` → upserts one row per `signal_key`, returns the row; `GET /api/feedback` → all rows; `GET /api/feedback?signal_key=...` → that one or 404.

- [ ] **Step 1: Write the failing test**

```ts
import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";
import app from "../src/index";
import { seedRun, seedTrade } from "./seed";

beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

const post = (body: unknown) =>
  app.request("/api/feedback", { method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify(body) }, env);

describe("feedback api", () => {
  it("upserts by signal_key (second post updates, not duplicates)", async () => {
    const key = "US500|2025-03-04T14:30:00Z|LONG";
    await post({ signal_key: key, flag: "hypothesis", note: "first" });
    const second = await post({ signal_key: key, flag: "knife-catch", note: "second" });
    expect(second.status).toBe(200);
    const all = await (await app.request("/api/feedback", {}, env)).json<any[]>();
    const forKey = all.filter((f) => f.signal_key === key);
    expect(forKey).toHaveLength(1);
    expect(forKey[0].note).toBe("second");
  });

  it("re-attaches across runs via signal_key", async () => {
    const key = "US500|2025-06-06T14:30:00Z|LONG";
    await post({ signal_key: key, flag: "hypothesis", note: "flagged in run A" });
    // simulate a later backtest: a NEW run + NEW trade id, SAME signal_key
    const runB = await seedRun(env.DB, { id: "run-B" });
    await seedTrade(env.DB, runB, { id: "trade-B", signal_key: key });
    const fb = await (await app.request(`/api/feedback?signal_key=${encodeURIComponent(key)}`, {}, env)).json<any>();
    expect(fb.note).toBe("flagged in run A");
  });

  it("400 on missing signal_key", async () => {
    expect((await post({ flag: "x" })).status).toBe(400);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd dashboard && npm test -- feedback`
Expected: FAIL (route missing).

- [ ] **Step 3: Create `dashboard/src/routes/feedback.ts`**

```ts
import { Hono } from "hono";
import type { Env } from "../index";
import type { FeedbackRow } from "../schema";

export const feedbackRoutes = new Hono<{ Bindings: Env }>();

feedbackRoutes.get("/feedback", async (c) => {
  const key = c.req.query("signal_key");
  if (key) {
    const row = await c.env.DB.prepare("SELECT * FROM feedback WHERE signal_key = ?")
      .bind(key).first<FeedbackRow>();
    if (!row) return c.json({ error: "not found" }, 404);
    return c.json(row);
  }
  const { results } = await c.env.DB.prepare("SELECT * FROM feedback ORDER BY created_at DESC")
    .all<FeedbackRow>();
  return c.json(results);
});

feedbackRoutes.post("/feedback", async (c) => {
  const body = await c.req.json<{ signal_key?: string; flag?: string; note?: string }>();
  if (!body.signal_key) return c.json({ error: "signal_key required" }, 400);
  const now = new Date().toISOString();
  await c.env.DB.prepare(
    `INSERT INTO feedback (id, signal_key, flag, note, created_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(signal_key) DO UPDATE SET
       flag = excluded.flag, note = excluded.note, created_at = excluded.created_at`
  ).bind(crypto.randomUUID(), body.signal_key, body.flag ?? null, body.note ?? null, now).run();
  const row = await c.env.DB.prepare("SELECT * FROM feedback WHERE signal_key = ?")
    .bind(body.signal_key).first<FeedbackRow>();
  return c.json(row);
});
```

- [ ] **Step 4: Mount it in `dashboard/src/index.ts`**

```ts
import { feedbackRoutes } from "./routes/feedback";
// ...
app.route("/api", feedbackRoutes);
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd dashboard && npm test -- feedback`
Expected: PASS (3 tests).

- [ ] **Step 6: Run the whole suite and commit**

Run: `cd dashboard && npm test`
Expected: PASS (all suites).

```bash
git add dashboard/src/routes/feedback.ts dashboard/src/index.ts dashboard/test/feedback.test.ts
git commit -m "feat(dashboard): feedback upsert-by-signal_key + re-attach across runs"
```

---

### Task 8: Java `DashboardExporter` — emit `run.json`

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/backtest/experiment/DashboardExporter.java`
- Create: `src/test/java/io/g3tech/axetrader/backtest/experiment/DashboardExporterTest.java`
- Modify: `src/test/java/io/g3tech/axetrader/backtest/ConfluenceSweepTest.java`

**Interfaces:**
- Consumes (existing types): `BacktestProperties.Strategy` (getters `getStopAtrMultiple()`, `getTargetAtrMultiple()`), `TradeResult` (`entryTime()`, `exitTime()`, `direction()`, `entryPrice()`, `exitPrice()`, `pnl()`, `rMultiple()`, `regime()`, `isWin()`, `exitReason()`, `features()`, `reasons()`), `TradeFeatures` (`rsi()`, `distToTrendEmaAtr()`, `atr()`, `atrPercentile()`, `volumeRatio()`, `hourUtc()`, `dayOfWeek()`, `confluenceScore()`), `org.ta4j.core.BarSeries`.
- Produces: `DashboardExporter.export(Path out, String label, String runId, BacktestProperties.Strategy config, String configJson, String instrument, int timeframeMin, Instant windowFrom, Instant windowTo, double valuePerPoint, double avgSpread, long tradingDays, BarSeries series, List<TradeResult> trades)` — writes a `run.json` with the exact shape the push script (Task 9) expects. Static `String signalKey(String instrument, Instant entry, Direction dir)`.

- [ ] **Step 1: Write the failing test**

```java
package io.g3tech.axetrader.backtest.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.ExitReason;
import io.g3tech.axetrader.backtest.runner.TradeFeatures;
import io.g3tech.axetrader.backtest.runner.TradeResult;
import io.g3tech.axetrader.backtest.runner.VolatilityRegime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DecimalNum;

class DashboardExporterTest {

  private BarSeries seriesOf(Instant start, int count, double price) {
    BarSeries s = new BaseBarSeriesBuilder().withName("t").build();
    for (int i = 0; i < count; i++) {
      Instant end = start.plus(Duration.ofMinutes(5L * (i + 1)));
      s.addBar(Duration.ofMinutes(5), end.atZone(ZoneOffset.UTC),
          DecimalNum.valueOf(price), DecimalNum.valueOf(price + 1),
          DecimalNum.valueOf(price - 1), DecimalNum.valueOf(price), DecimalNum.valueOf(100));
    }
    return s;
  }

  @Test
  void writesRunJsonWithDerivedStopTargetAndBarWindow(@TempDir Path dir) throws Exception {
    Instant t0 = Instant.parse("2025-03-04T14:00:00Z");
    BarSeries series = seriesOf(t0, 200, 5000);
    ZonedDateTime entry = t0.plus(Duration.ofMinutes(5L * 100)).atZone(ZoneOffset.UTC);
    ZonedDateTime exit = t0.plus(Duration.ofMinutes(5L * 110)).atZone(ZoneOffset.UTC);

    TradeFeatures f = new TradeFeatures(28, 0, 0, 0, 0, 1.2, 0, 4.0, 0.6, 1.0, 14, 2, 3);
    TradeResult tr = new TradeResult(entry, exit, Direction.LONG, 5000, 5003, 3.0, 0.75,
        VolatilityRegime.NORMAL, true, ExitReason.TARGET, f, List.of("RSI", "BB", "S/R"));

    BacktestProperties.Strategy cfg = new BacktestProperties.Strategy();
    cfg.setStopAtrMultiple(3.0);
    cfg.setTargetAtrMultiple(0.75);

    Path out = dir.resolve("run.json");
    DashboardExporter.export(out, "test", "US500-1", cfg, "{}", "US500", 5,
        t0, t0.plus(Duration.ofDays(1)), 1.0, 0.5, 1, series, List.of(tr));

    JsonNode root = new ObjectMapper().readTree(Files.readString(out));
    JsonNode t = root.get("trades").get(0);
    assertThat(t.get("signal_key").asText()).isEqualTo("US500|2025-03-04T22:20:00Z|LONG");
    // LONG stop = entry - 3.0*atr(4) = 4988 ; target = entry + 0.75*4 = 5003
    assertThat(t.get("stop_price").asDouble()).isEqualTo(4988.0);
    assertThat(t.get("target_price").asDouble()).isEqualTo(5003.0);
    // window: 50 bars before entry-index .. 50 after exit-index, all present
    assertThat(t.get("bars").isArray()).isTrue();
    assertThat(t.get("bars").size()).isGreaterThan(50);
    assertThat(root.get("run").get("net_avg_pnl_usd").asDouble())
        .isEqualTo(root.get("run").get("net_avg_pnl").asDouble()); // valuePerPoint=1.0
  }
}
```

Note: the expected `signal_key` timestamp (`22:20:00Z`) is `t0 + 5min*100`. Compute it from the test's own `entry` value if the literal differs — the assertion's point is that `signal_key` equals `instrument|entry.toInstant()|direction`.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=DashboardExporterTest`
Expected: FAIL (compile error — `DashboardExporter` does not exist).

- [ ] **Step 3: Create `DashboardExporter.java`**

```java
package io.g3tech.axetrader.backtest.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.TradeFeatures;
import io.g3tech.axetrader.backtest.runner.TradeResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.ta4j.core.BarSeries;

/** Read-only exporter: turns an in-memory sweep result into the run.json the D1 push script loads. */
public final class DashboardExporter {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int PAD = 50; // bars kept before entry index and after exit index

  private DashboardExporter() {}

  public static String signalKey(String instrument, Instant entry, Direction dir) {
    return instrument + "|" + entry.toString() + "|" + dir.name();
  }

  public static void export(
      Path out, String label, String runId, BacktestProperties.Strategy config, String configJson,
      String instrument, int timeframeMin, Instant windowFrom, Instant windowTo,
      double valuePerPoint, double avgSpread, long tradingDays,
      BarSeries series, List<TradeResult> trades) {
    try {
      int count = trades.size();
      long wins = trades.stream().filter(t -> t.pnl() > 0).count();
      double winRate = count == 0 ? 0 : (double) wins / count;
      double netAvgPnl = trades.stream().mapToDouble(t -> t.pnl() - avgSpread).average().orElse(0);
      double avgR = trades.stream().mapToDouble(TradeResult::rMultiple).average().orElse(0);
      double tradesPerDay = tradingDays == 0 ? 0 : (double) count / tradingDays;

      ObjectNode root = MAPPER.createObjectNode();
      ObjectNode run = root.putObject("run");
      run.put("id", runId);
      run.put("created_at", Instant.now().toString());
      run.put("label", label);
      run.put("config_json", configJson);
      run.put("instrument", instrument);
      run.put("timeframe_min", timeframeMin);
      run.put("window_start", windowFrom.toString());
      run.put("window_end", windowTo.toString());
      run.put("trades_count", count);
      run.put("trades_per_day", tradesPerDay);
      run.put("win_rate", winRate);
      run.put("net_avg_pnl", netAvgPnl);
      run.put("net_avg_pnl_usd", netAvgPnl * valuePerPoint);
      run.put("avg_r", avgR);

      ArrayNode arr = root.putArray("trades");
      for (TradeResult t : trades) {
        arr.add(tradeNode(t, config, instrument, series));
      }
      Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to write dashboard run.json to " + out, e);
    }
  }

  private static ObjectNode tradeNode(
      TradeResult t, BacktestProperties.Strategy config, String instrument, BarSeries series) {
    TradeFeatures f = t.features();
    double atr = f == null ? 0 : f.atr();
    double stopDist = config.getStopAtrMultiple() * atr;
    double targetDist = config.getTargetAtrMultiple() * atr;
    boolean isLong = t.direction() == Direction.LONG;
    double stop = isLong ? t.entryPrice() - stopDist : t.entryPrice() + stopDist;
    double target = isLong ? t.entryPrice() + targetDist : t.entryPrice() - targetDist;

    ObjectNode n = MAPPER.createObjectNode();
    n.put("signal_key", signalKey(instrument, t.entryTime().toInstant(), t.direction()));
    n.put("entry_time", t.entryTime().toInstant().toString());
    n.put("exit_time", t.exitTime().toInstant().toString());
    n.put("direction", t.direction().name());
    n.put("entry_price", t.entryPrice());
    n.put("exit_price", t.exitPrice());
    n.put("stop_price", stop);
    n.put("target_price", target);
    n.put("pnl", t.pnl());
    n.put("net_pnl", t.pnl()); // net = pnl - spread is applied at run level; per-trade keeps raw pnl
    n.put("r_multiple", t.rMultiple());
    n.put("exit_reason", t.exitReason() == null ? null : t.exitReason().name());
    n.put("is_win", t.isWin() ? 1 : 0);
    if (f != null) {
      n.put("rsi_value", f.rsi());
      if (f.distToTrendEmaAtr() != null) n.put("dist_to_trend_ema_atr", f.distToTrendEmaAtr());
      n.put("atr_value", f.atr());
      n.put("atr_percentile", f.atrPercentile());
      n.put("volume_ratio", f.volumeRatio());
      n.put("hour_utc", f.hourUtc());
      n.put("day_of_week", f.dayOfWeek());
      n.put("confluence_score", f.confluenceScore());
    }
    n.put("volatility_regime", t.regime() == null ? null : t.regime().name());
    n.put("pillars_fired", String.join("+", t.reasons()));
    n.set("bars", barWindow(series, t));
    return n;
  }

  /** Bars from (entryIndex - PAD) through (exitIndex + PAD), clamped to the series. */
  private static ArrayNode barWindow(BarSeries series, TradeResult t) {
    int entryIdx = indexAtOrAfter(series, t.entryTime().toInstant());
    int exitIdx = indexAtOrAfter(series, t.exitTime().toInstant());
    int from = Math.max(series.getBeginIndex(), entryIdx - PAD);
    int to = Math.min(series.getEndIndex(), exitIdx + PAD);
    ArrayNode bars = MAPPER.createArrayNode();
    for (int i = from; i <= to; i++) {
      var bar = series.getBar(i);
      ObjectNode b = bars.addObject();
      b.put("t", bar.getEndTime().getEpochSecond());
      b.put("o", bar.getOpenPrice().doubleValue());
      b.put("h", bar.getHighPrice().doubleValue());
      b.put("l", bar.getLowPrice().doubleValue());
      b.put("c", bar.getClosePrice().doubleValue());
    }
    return bars;
  }

  private static int indexAtOrAfter(BarSeries series, Instant ts) {
    for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
      if (!series.getBar(i).getEndTime().toInstant().isBefore(ts)) return i;
    }
    return series.getEndIndex();
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw test -Dtest=DashboardExporterTest`
Expected: PASS. (If the `signal_key` literal in the test mismatches, correct the literal to the printed `entry` instant — do not change the exporter.)

- [ ] **Step 5: Wire it into the sweep behind a flag**

In `ConfluenceSweepTest.java`, inside the per-config loop where `series`, `config`, `trades` are in scope (next to the `store.save(...)` call), add:

```java
if (Boolean.getBoolean("sweep.exportDashboard")) {
    String configJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(config);
    String runId = backtestProperties.getEpic() + "-" + System.currentTimeMillis();
    java.nio.file.Path out = java.nio.file.Path.of("dashboard/run.json");
    DashboardExporter.export(out, entry.getKey(), runId, config, configJson,
        backtestProperties.getEpic(), backtestProperties.getTimeframeMinutes(),
        from, to, backtestProperties.getContract().getValuePerPoint(), avgSpread, tradingDays,
        series, trades);
    System.out.println("  wrote dashboard export -> " + out);
}
```

Confirm the exact accessors for `from`, `to`, `avgSpread`, `tradingDays`, and the value-per-point path (`backtestProperties.getContract().getValuePerPoint()` vs `backtestProperties.getValuePerPoint()`) against the surrounding code before compiling; use whatever the existing `store.save(...)` call already passes.

- [ ] **Step 6: Verify the wired export runs end-to-end**

Run: `gzip -dc data/axe-trader.sqlite.gz > data/axe-trader.sqlite && ./mvnw test -Dtest=ConfluenceSweepTest -Dsweep=true -Dsweep.exportDashboard=true`
Expected: build passes and `dashboard/run.json` exists with a `run` object and non-empty `trades`.

- [ ] **Step 7: Commit** (ensure `dashboard/run.json` is gitignored)

```bash
grep -qxF 'dashboard/run.json' .gitignore || echo 'dashboard/run.json' >> .gitignore
git add src/main/java/io/g3tech/axetrader/backtest/experiment/DashboardExporter.java \
        src/test/java/io/g3tech/axetrader/backtest/experiment/DashboardExporterTest.java \
        src/test/java/io/g3tech/axetrader/backtest/ConfluenceSweepTest.java .gitignore
git commit -m "feat(export): DashboardExporter emits enriched run.json for D1"
```

---

### Task 9: `push-run` script — load `run.json` into D1

**Files:**
- Create: `dashboard/scripts/push-run.ts`
- Create: `dashboard/scripts/sql.ts`
- Create: `dashboard/test/push-sql.test.ts`
- Modify: `dashboard/package.json` (add `tsx` dev dep + `push` script)

**Interfaces:**
- Consumes: the `run.json` shape from Task 8.
- Produces: `buildPushSql(run): string` (pure function → the SQL text: `DELETE` existing rows for this run id, `INSERT` run + all trades, all values escaped). `push-run.ts` writes that SQL to a temp file and shells `wrangler d1 execute`.

- [ ] **Step 1: Write the failing test (pure SQL builder)**

```ts
import { describe, it, expect } from "vitest";
import { buildPushSql } from "../scripts/sql";

const run = {
  run: { id: "US500-1", created_at: "2026-07-19T00:00:00Z", label: "l", config_json: "{}",
    instrument: "US500", timeframe_min: 5, window_start: "a", window_end: "b",
    trades_count: 1, trades_per_day: 0.9, win_rate: 0.8, net_avg_pnl: -0.14,
    net_avg_pnl_usd: -0.13, avg_r: 0.1 },
  trades: [{
    signal_key: "US500|2025-03-04T14:30:00Z|LONG", entry_time: "2025-03-04T14:30:00Z",
    exit_time: "2025-03-04T15:30:00Z", direction: "LONG", entry_price: 5000, exit_price: 5003,
    stop_price: 4988, target_price: 5003, pnl: 3, net_pnl: 3, r_multiple: 0.75,
    exit_reason: "TARGET", is_win: 1, rsi_value: 28, dist_to_trend_ema_atr: 1.2, atr_value: 4,
    atr_percentile: 0.6, volume_ratio: 1, hour_utc: 14, day_of_week: 2,
    volatility_regime: "NORMAL", confluence_score: 3, pillars_fired: "RSI+BB", bars: [{ t: 1, o: 1, h: 2, l: 0, c: 1 }],
  }],
};

describe("buildPushSql", () => {
  it("is idempotent (deletes the run + its trades first)", () => {
    const sql = buildPushSql(run as any);
    expect(sql).toContain("DELETE FROM trades WHERE run_id = 'US500-1'");
    expect(sql).toContain("DELETE FROM runs WHERE id = 'US500-1'");
    expect(sql.indexOf("DELETE")).toBeLessThan(sql.indexOf("INSERT"));
  });

  it("escapes single quotes in text (e.g. config_json / pillars)", () => {
    const dirty = { ...run, run: { ...run.run, config_json: "{\"a\":\"o'brien\"}" } };
    const sql = buildPushSql(dirty as any);
    expect(sql).toContain("o''brien");
  });

  it("stores the bar window as bars_json text", () => {
    const sql = buildPushSql(run as any);
    expect(sql).toContain('[{"t":1,"o":1,"h":2,"l":0,"c":1}]'.replace(/'/g, "''"));
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd dashboard && npm test -- push-sql`
Expected: FAIL (`scripts/sql.ts` missing).

- [ ] **Step 3: Create `dashboard/scripts/sql.ts`**

```ts
type Bar = { t: number; o: number; h: number; l: number; c: number };
type Trade = Record<string, unknown> & { signal_key: string; bars: Bar[] };
type RunFile = { run: Record<string, unknown> & { id: string }; trades: Trade[] };

function q(v: unknown): string {
  if (v === null || v === undefined) return "NULL";
  if (typeof v === "number") return String(v);
  return "'" + String(v).replace(/'/g, "''") + "'";
}

export function buildPushSql(file: RunFile): string {
  const r = file.run;
  const id = String(r.id);
  const lines: string[] = [];
  lines.push(`DELETE FROM trades WHERE run_id = ${q(id)};`);
  lines.push(`DELETE FROM runs WHERE id = ${q(id)};`);
  lines.push(
    `INSERT INTO runs (id, created_at, label, config_json, instrument, timeframe_min,
      window_start, window_end, trades_count, trades_per_day, win_rate, net_avg_pnl,
      net_avg_pnl_usd, avg_r, max_drawdown, worst_quarter_net) VALUES (` +
    [r.id, r.created_at, r.label, r.config_json, r.instrument, r.timeframe_min,
     r.window_start, r.window_end, r.trades_count, r.trades_per_day, r.win_rate, r.net_avg_pnl,
     r.net_avg_pnl_usd, r.avg_r, r.max_drawdown ?? null, r.worst_quarter_net ?? null]
      .map(q).join(", ") + `);`
  );
  for (const t of file.trades) {
    lines.push(
      `INSERT INTO trades (id, run_id, signal_key, entry_time, exit_time, direction,
        entry_price, exit_price, stop_price, target_price, pnl, net_pnl, r_multiple,
        exit_reason, is_win, rsi_value, dist_to_trend_ema_atr, atr_value, atr_percentile,
        volume_ratio, hour_utc, day_of_week, volatility_regime, confluence_score,
        pillars_fired, bars_json) VALUES (` +
      [`${id}:${t.signal_key}`, id, t.signal_key, t.entry_time, t.exit_time, t.direction,
       t.entry_price, t.exit_price, t.stop_price, t.target_price, t.pnl, t.net_pnl, t.r_multiple,
       t.exit_reason, t.is_win, t.rsi_value, t.dist_to_trend_ema_atr, t.atr_value, t.atr_percentile,
       t.volume_ratio, t.hour_utc, t.day_of_week, t.volatility_regime, t.confluence_score,
       t.pillars_fired, JSON.stringify(t.bars)].map(q).join(", ") + `);`
    );
  }
  return lines.join("\n");
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd dashboard && npm test -- push-sql`
Expected: PASS (3 tests).

- [ ] **Step 5: Create `dashboard/scripts/push-run.ts`** (the CLI wrapper)

```ts
import { readFileSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { buildPushSql } from "./sql";

const runFile = process.argv[2] ?? "run.json";
const remote = process.argv.includes("--remote");
const file = JSON.parse(readFileSync(runFile, "utf8"));
const sql = buildPushSql(file);
const tmp = ".push.generated.sql";
writeFileSync(tmp, sql);

const args = ["d1", "execute", "axe-trader-dashboard", `--file=${tmp}`, remote ? "--remote" : "--local"];
console.log(`wrangler ${args.join(" ")}`);
execFileSync("npx", ["wrangler", ...args], { stdio: "inherit" });
console.log(`pushed ${file.trades.length} trades for run ${file.run.id}`);
```

- [ ] **Step 6: Add `tsx` and the `push` script to `dashboard/package.json`**

Add to `devDependencies`: `"tsx": "^4"`. Add to `scripts`:

```json
    "push": "tsx scripts/push-run.ts run.json"
```

- [ ] **Step 7: Smoke-test the push against local D1**

Run: `cd dashboard && npm install && npm run migrate:local && npx tsx scripts/push-run.ts run.json`
Expected: wrangler reports rows written. (`run.json` from Task 8 step 6.) Then verify: `npx wrangler d1 execute axe-trader-dashboard --local --command "SELECT COUNT(*) FROM trades"` returns > 0.

- [ ] **Step 8: Commit** (gitignore the generated SQL)

```bash
grep -qxF 'dashboard/.push.generated.sql' .gitignore || echo 'dashboard/.push.generated.sql' >> .gitignore
git add dashboard/scripts/sql.ts dashboard/scripts/push-run.ts dashboard/test/push-sql.test.ts dashboard/package.json .gitignore
git commit -m "feat(dashboard): push-run script loads run.json into D1"
```

---

### Task 10: `pull-feedback` script — D1 feedback → laptop

**Files:**
- Create: `dashboard/scripts/pull-feedback.ts`
- Modify: `dashboard/package.json` (add `pull` script)

**Interfaces:**
- Produces: writes `experiments/feedback.json` (an array of `{ signal_key, flag, note, created_at }`) that `experiments/query.py` can join against on `signal_key`.

- [ ] **Step 1: Create `dashboard/scripts/pull-feedback.ts`**

```ts
import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";

const remote = process.argv.includes("--remote");
const args = ["wrangler", "d1", "execute", "axe-trader-dashboard",
  remote ? "--remote" : "--local", "--json",
  "--command", "SELECT signal_key, flag, note, created_at FROM feedback ORDER BY created_at"];
const raw = execFileSync("npx", args, { encoding: "utf8" });
// wrangler --json returns an array of statement results: [{ results: [...] }]
const parsed = JSON.parse(raw);
const rows = Array.isArray(parsed) ? parsed[0]?.results ?? [] : parsed.results ?? [];
const out = "../experiments/feedback.json";
writeFileSync(out, JSON.stringify(rows, null, 2));
console.log(`wrote ${rows.length} feedback rows -> experiments/feedback.json`);
```

Confirm the exact `wrangler d1 execute --json` output shape with the `wrangler` skill; adjust the `parsed[0]?.results` unwrap if the current CLI differs.

- [ ] **Step 2: Add the `pull` script to `dashboard/package.json`**

```json
    "pull": "tsx scripts/pull-feedback.ts"
```

- [ ] **Step 3: Smoke-test the round trip against local D1**

Run (from `dashboard/`):
```bash
npx wrangler d1 execute axe-trader-dashboard --local --command "INSERT INTO feedback (id, signal_key, flag, note, created_at) VALUES ('x','US500|2025-03-04T14:30:00Z|LONG','hypothesis','test','2026-07-19T00:00:00Z')"
npx tsx scripts/pull-feedback.ts
```
Expected: `experiments/feedback.json` contains the one row with `signal_key` `US500|2025-03-04T14:30:00Z|LONG`.

- [ ] **Step 4: Commit** (gitignore the pulled file)

```bash
grep -qxF 'experiments/feedback.json' .gitignore || echo 'experiments/feedback.json' >> .gitignore
git add dashboard/scripts/pull-feedback.ts dashboard/package.json .gitignore
git commit -m "feat(dashboard): pull-feedback script syncs D1 feedback to laptop"
```

---

### Task 11: Provision D1 + deploy + Cloudflare Access

**Files:**
- Modify: `dashboard/wrangler.jsonc` (real `database_id`)
- Create: `dashboard/DEPLOY.md` (the runbook)

**Interfaces:** none (ops task). Deliverable: a deployed Worker reachable only after Access login, serving `/api/*` from remote D1.

> Load the `wrangler` and `cloudflare` skills before this task. Some steps need the user's Cloudflare login — surface them for the user to run via `! <command>` if this session isn't authenticated.

- [ ] **Step 1: Create the remote D1 database**

Run: `cd dashboard && npx wrangler d1 create axe-trader-dashboard`
Expected: prints a `database_id`. Copy it into `wrangler.jsonc` (`d1_databases[0].database_id`), replacing `PLACEHOLDER_SET_IN_TASK_11`.

- [ ] **Step 2: Apply migrations to remote D1**

Run: `cd dashboard && npm run migrate:remote`
Expected: `0001_init.sql` applied; reports 3 tables created.

- [ ] **Step 3: Deploy the Worker**

Run: `cd dashboard && npm run deploy`
Expected: prints the `*.workers.dev` URL. Verify unauthenticated (before Access): `curl <url>/api/health` → `{"ok":true}`.

- [ ] **Step 4: Push a real run to remote and smoke the API**

Run (from `dashboard/`, after a `-Dsweep.exportDashboard=true` run produced `run.json`):
```bash
npx tsx scripts/push-run.ts run.json --remote
curl <url>/api/runs
```
Expected: `/api/runs` returns the pushed run.

- [ ] **Step 5: Put Cloudflare Access in front of the Worker**

In the Cloudflare Zero Trust dashboard (or via the `cloudflare` skill / API): add a **self-hosted Access application** covering the Worker's hostname, with a policy allowing only the owner's email (One-time PIN). Document the exact steps taken in `dashboard/DEPLOY.md`.

- [ ] **Step 6: Verify Access gates the app**

Run: `curl -sS -o /dev/null -w "%{http_code}" <url>/api/runs`
Expected: `302`/`403` (redirect to Access login) — i.e. the API is no longer reachable without login. Confirm in a phone browser that the email-OTP login then grants access.

- [ ] **Step 7: Write `dashboard/DEPLOY.md` and commit**

Document: the `d1 create` → `migrate:remote` → `deploy` → `push --remote` → Access-policy sequence, plus the day-to-day loop (`-Dsweep.exportDashboard=true` → `npm run push -- --remote` → review on phone → `npm run pull -- --remote`).

```bash
git add dashboard/wrangler.jsonc dashboard/DEPLOY.md
git commit -m "chore(dashboard): provision D1, deploy Worker, gate with Access"
```

---

## Self-Review

**Spec coverage:**
- D1 as single source of truth (runs + trades + feedback) → Tasks 2, 4–7. ✅
- `bars_json` inlined on trade row → Task 2 schema + Task 8 exporter + Task 5 detail endpoint. ✅
- `signal_key = instrument|entry_ts|direction` stable across runs → Global Constraints + Task 8 (`signalKey`) + Task 7 (re-attach test). ✅
- Java exporter reuses aggregation, derives stop/target → Task 8. ✅
- Push (dumb) + pull scripts → Tasks 9, 10. ✅
- API endpoints (runs, trades, trade detail, slices, feedback) → Tasks 4–7. ✅
- Cloudflare Access → Task 11. ✅
- Testing (API handlers, signal_key re-attach, sync transform) → Tasks 4–10. ✅
- Out of scope (frontend, MONITOR/TRADE) → not in this plan (Plan 2). ✅

**Open items deliberately deferred to execution (not placeholders):** exact `vitest-pool-workers` migration-binding wiring, `wrangler d1 execute --json` output shape, and the value-per-point accessor path — each flagged inline to confirm via the `wrangler`/`cloudflare` skills or the surrounding Java code. These are external-API confirmations, not undecided logic.

**Type consistency:** `signalKey`/`signal_key`, `run.json` field names (Task 8) ↔ `buildPushSql` (Task 9) ↔ D1 columns (Task 2) ↔ TS row types (Task 3) cross-checked and consistent. `net_pnl` per-trade carries raw pnl (spread applied at run level) — noted in Task 8 to avoid double-subtracting.
