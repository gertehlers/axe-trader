# Trade-Review Phone UI Implementation Plan (Plan 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the phone UI on top of the deployed Plan 1 API — an Overview dashboard and a swipe deck that reviews trades one per screen, flags each from a fixed cause taxonomy, and pins `better-entry`/`T1`/`T2`/`T3`/`exit-here` marks to individual candles.

**Architecture:** A Vite + React + TypeScript app in `dashboard/frontend/` builds into `dashboard/public/`, served by the `ASSETS` binding on the already-deployed Worker. Candles are hand-rolled SVG. All chart geometry (scales, bar hit-testing, focus windowing) lives in pure functions so it is unit-testable without a DOM. One new D1 table (`marks`) and three new routes extend the existing API; the flag and mark vocabularies live in one shared module imported by both the Worker and the frontend.

**Tech Stack:** React 19 + Vite 8 + TypeScript (frontend); Hono + D1 (existing Worker); Vitest 4 — the existing Workers-pool project for API tests, a new jsdom project for UI tests; wrangler 4.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-07-20-trade-review-phone-ui-design.md`. It wins over this plan on intent; this plan wins on mechanics. Keep them in sync in the same commit (SDD — see `.claude/CLAUDE.md`).
- **Do not modify the existing Workers-pool test setup** (`dashboard/vitest.config.ts`, `dashboard/test/*`) except where a task explicitly says so. UI tests get their own config.
- **`signal_key` format is exactly `instrument|entryTsIso|direction`** — the join key for both feedback and marks.
- **Flags:** `knife-catch`, `chop`, `news-spike`, `late-entry`, `bad-exit`, `good-signal`. One per `signal_key`.
- **Mark kinds:** `better-entry`, `T1`, `T2`, `T3`, `exit-here`. One per `(signal_key, kind)`; re-placing moves it.
- **Marks store a bar timestamp** (`bar_ts`, ISO-8601 UTC), never a bar index — they must survive re-runs.
- **Live deployment already exists:** Worker `axe-trader-dashboard`, D1 `axe-trader-dashboard` (`fd847584-8fd9-427e-b869-9423a6c5b419`), gated by Cloudflare Access. Free tier — do not add paid resources.
- **Never commit** `dashboard/node_modules`, `dashboard/.wrangler`, `dashboard/public` build output, or `dashboard/run.json`.
- Work from `dashboard/` for all npm commands unless a step says otherwise.

---

### Task 1: Shared vocabulary + flag validation

**Files:**
- Create: `dashboard/shared/vocab.ts`
- Modify: `dashboard/src/routes/feedback.ts`
- Modify: `dashboard/test/feedback.test.ts`
- Modify: `dashboard/tsconfig.json`

**Interfaces:**
- Produces: `FLAGS`, `MARK_KINDS` (readonly tuples), types `Flag`, `MarkKind`, guards `isFlag(v)`, `isMarkKind(v)`. Every later task imports these rather than repeating string literals.
- Produces: `POST /api/feedback` now returns 400 for a `flag` outside `FLAGS`.

- [ ] **Step 1: Create `dashboard/shared/vocab.ts`**

```ts
/** The closed vocabularies shared by the Worker API and the phone UI. */

export const FLAGS = [
  "knife-catch",
  "chop",
  "news-spike",
  "late-entry",
  "bad-exit",
  "good-signal",
] as const;
export type Flag = (typeof FLAGS)[number];

export const MARK_KINDS = ["better-entry", "T1", "T2", "T3", "exit-here"] as const;
export type MarkKind = (typeof MARK_KINDS)[number];

export function isFlag(v: unknown): v is Flag {
  return typeof v === "string" && (FLAGS as readonly string[]).includes(v);
}

export function isMarkKind(v: unknown): v is MarkKind {
  return typeof v === "string" && (MARK_KINDS as readonly string[]).includes(v);
}
```

- [ ] **Step 2: Add `shared` to the Worker tsconfig include list**

In `dashboard/tsconfig.json`, change the `include` array to:

```json
  "include": ["src", "shared", "test", "scripts", "vitest.config.ts", "worker-configuration.d.ts"]
```

- [ ] **Step 3: Update the existing feedback tests to the real taxonomy, and add a rejection test**

The current tests post `flag: "hypothesis"` and `flag: "x"`, which are not in `FLAGS`. In `dashboard/test/feedback.test.ts`, replace every `"hypothesis"` with `"chop"`, and replace the whole third `it(...)` block with these two:

```ts
  it("400 on missing signal_key", async () => {
    expect((await post({ flag: "chop" })).status).toBe(400);
  });

  it("400 on a flag outside the taxonomy", async () => {
    const res = await post({ signal_key: "US500|2025-09-09T14:30:00Z|LONG", flag: "banana" });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "unknown flag" });
  });
```

- [ ] **Step 4: Run the tests to verify the new one fails**

Run: `cd dashboard && npx vitest run feedback`
Expected: FAIL — "400 on a flag outside the taxonomy" gets 200, because nothing validates `flag` yet. The other feedback tests pass.

- [ ] **Step 5: Validate the flag in the route**

In `dashboard/src/routes/feedback.ts`, add the import:

```ts
import { isFlag } from "../../shared/vocab";
```

and in the `POST /feedback` handler, immediately after the `signal_key` check, insert:

```ts
  if (body.flag !== undefined && body.flag !== null && !isFlag(body.flag)) {
    return c.json({ error: "unknown flag" }, 400);
  }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd dashboard && npx vitest run feedback && npx tsc --noEmit`
Expected: PASS (4 tests), no type errors.

- [ ] **Step 7: Commit**

```bash
git add dashboard/shared/vocab.ts dashboard/src/routes/feedback.ts dashboard/test/feedback.test.ts dashboard/tsconfig.json
git commit -m "feat(dashboard): shared flag/mark vocabulary + server-side flag validation"
```

---

### Task 2: `marks` table migration

**Files:**
- Create: `dashboard/migrations/0002_marks.sql`
- Modify: `dashboard/src/schema.ts`
- Create: `dashboard/test/marks-schema.test.ts`

**Interfaces:**
- Produces: table `marks(id, signal_key, kind, bar_ts, created_at)` with `UNIQUE(signal_key, kind)`; TS type `MarkRow`.

- [ ] **Step 1: Write the failing test**

Create `dashboard/test/marks-schema.test.ts`:

```ts
import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";

beforeAll(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

const insert = (id: string, key: string, kind: string, barTs: string) =>
  env.DB.prepare(
    "INSERT INTO marks (id, signal_key, kind, bar_ts, created_at) VALUES (?,?,?,?,?)"
  ).bind(id, key, kind, barTs, "2026-07-20T00:00:00Z").run();

describe("marks schema", () => {
  it("allows different kinds on the same trade", async () => {
    const key = "US500|2025-03-04T14:30:00Z|LONG";
    await insert("m1", key, "T1", "2025-03-04T15:00:00Z");
    await insert("m2", key, "T2", "2025-03-04T15:20:00Z");
    const { results } = await env.DB.prepare(
      "SELECT kind FROM marks WHERE signal_key = ? ORDER BY kind"
    ).bind(key).all<{ kind: string }>();
    expect(results.map((r) => r.kind)).toEqual(["T1", "T2"]);
  });

  it("rejects a second mark of the same kind on one trade", async () => {
    const key = "US500|2025-04-04T14:30:00Z|LONG";
    await insert("m3", key, "better-entry", "2025-04-04T14:00:00Z");
    await expect(insert("m4", key, "better-entry", "2025-04-04T14:05:00Z")).rejects.toThrow();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd dashboard && npx vitest run marks-schema`
Expected: FAIL — "no such table: marks".

- [ ] **Step 3: Create `dashboard/migrations/0002_marks.sql`**

```sql
-- Human marks pinned to a specific bar: "T1 should have been here".
-- Keyed on signal_key + bar timestamp so they survive re-running the backtest.
CREATE TABLE marks (
  id          TEXT PRIMARY KEY,
  signal_key  TEXT NOT NULL,
  kind        TEXT NOT NULL,
  bar_ts      TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  UNIQUE (signal_key, kind)
);
CREATE INDEX idx_marks_signal ON marks(signal_key);
```

- [ ] **Step 4: Add the row type**

Append to `dashboard/src/schema.ts`:

```ts
export interface MarkRow {
  id: string;
  signal_key: string;
  kind: string;
  bar_ts: string;
  created_at: string;
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `cd dashboard && npx vitest run marks-schema`
Expected: PASS (2 tests).

- [ ] **Step 6: Apply the migration to local and remote D1**

```bash
cd dashboard
npm run migrate:local
npm run migrate:remote
```
Expected: `0002_marks.sql` applied in both, status ✅.

- [ ] **Step 7: Commit**

```bash
git add dashboard/migrations/0002_marks.sql dashboard/src/schema.ts dashboard/test/marks-schema.test.ts
git commit -m "feat(dashboard): marks table — one mark per kind per signal"
```

---

### Task 3: `/api/marks` routes

**Files:**
- Create: `dashboard/src/routes/marks.ts`
- Modify: `dashboard/src/index.ts`
- Create: `dashboard/test/marks.test.ts`

**Interfaces:**
- Consumes: `Env`, `MarkRow`, `isMarkKind`.
- Produces: `GET /api/marks` → `MarkRow[]`; `GET /api/marks?signal_key=…` → that trade's marks; `POST /api/marks` body `{ signal_key, kind, bar_ts }` → upserted `MarkRow` (moves an existing kind); `DELETE /api/marks?signal_key=…&kind=…` → `{ deleted: number }`. 400 on missing fields or a kind outside `MARK_KINDS`.

- [ ] **Step 1: Write the failing test**

Create `dashboard/test/marks.test.ts`:

```ts
import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";
import app from "../src/index";
import type { MarkRow } from "../src/schema";

beforeAll(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

const post = (body: unknown) =>
  app.request(
    "/api/marks",
    { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) },
    env
  );

describe("marks api", () => {
  it("places a mark and moves it when the same kind is re-placed", async () => {
    const signal_key = "US500|2025-05-05T14:30:00Z|LONG";
    await post({ signal_key, kind: "T1", bar_ts: "2025-05-05T15:00:00Z" });
    const moved = await post({ signal_key, kind: "T1", bar_ts: "2025-05-05T15:30:00Z" });
    expect(moved.status).toBe(200);

    const rows = (await (
      await app.request(`/api/marks?signal_key=${encodeURIComponent(signal_key)}`, {}, env)
    ).json()) as MarkRow[];
    expect(rows).toHaveLength(1);
    expect(rows[0].bar_ts).toBe("2025-05-05T15:30:00Z");
  });

  it("keeps different kinds side by side and deletes one", async () => {
    const signal_key = "US500|2025-06-06T14:30:00Z|SHORT";
    await post({ signal_key, kind: "better-entry", bar_ts: "2025-06-06T14:00:00Z" });
    await post({ signal_key, kind: "exit-here", bar_ts: "2025-06-06T16:00:00Z" });

    const del = await app.request(
      `/api/marks?signal_key=${encodeURIComponent(signal_key)}&kind=exit-here`,
      { method: "DELETE" },
      env
    );
    expect(del.status).toBe(200);
    expect(await del.json()).toEqual({ deleted: 1 });

    const rows = (await (
      await app.request(`/api/marks?signal_key=${encodeURIComponent(signal_key)}`, {}, env)
    ).json()) as MarkRow[];
    expect(rows.map((r) => r.kind)).toEqual(["better-entry"]);
  });

  it("400s on an unknown kind or a missing field", async () => {
    const bad = await post({ signal_key: "US500|2025-07-07T14:30:00Z|LONG", kind: "T9", bar_ts: "x" });
    expect(bad.status).toBe(400);
    expect(await bad.json()).toEqual({ error: "unknown kind" });

    const missing = await post({ signal_key: "US500|2025-07-07T14:30:00Z|LONG", kind: "T1" });
    expect(missing.status).toBe(400);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd dashboard && npx vitest run marks.test`
Expected: FAIL — routes missing (404s).

- [ ] **Step 3: Create `dashboard/src/routes/marks.ts`**

```ts
import { Hono } from "hono";
import type { Env } from "../index";
import type { MarkRow } from "../schema";
import { isMarkKind } from "../../shared/vocab";

export const marksRoutes = new Hono<{ Bindings: Env }>();

marksRoutes.get("/marks", async (c) => {
  const key = c.req.query("signal_key");
  const stmt = key
    ? c.env.DB.prepare("SELECT * FROM marks WHERE signal_key = ? ORDER BY kind").bind(key)
    : c.env.DB.prepare("SELECT * FROM marks ORDER BY signal_key, kind");
  const { results } = await stmt.all<MarkRow>();
  return c.json(results);
});

marksRoutes.post("/marks", async (c) => {
  const body = await c.req.json<{ signal_key?: string; kind?: string; bar_ts?: string }>();
  if (!body.signal_key || !body.bar_ts) return c.json({ error: "signal_key and bar_ts required" }, 400);
  if (!isMarkKind(body.kind)) return c.json({ error: "unknown kind" }, 400);

  // One mark per (signal_key, kind): re-placing a kind moves it rather than adding a second.
  await c.env.DB.prepare(
    `INSERT INTO marks (id, signal_key, kind, bar_ts, created_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(signal_key, kind) DO UPDATE SET
       bar_ts = excluded.bar_ts, created_at = excluded.created_at`
  )
    .bind(crypto.randomUUID(), body.signal_key, body.kind, body.bar_ts, new Date().toISOString())
    .run();

  const row = await c.env.DB.prepare("SELECT * FROM marks WHERE signal_key = ? AND kind = ?")
    .bind(body.signal_key, body.kind)
    .first<MarkRow>();
  return c.json(row);
});

marksRoutes.delete("/marks", async (c) => {
  const key = c.req.query("signal_key");
  const kind = c.req.query("kind");
  if (!key || !kind) return c.json({ error: "signal_key and kind required" }, 400);
  const res = await c.env.DB.prepare("DELETE FROM marks WHERE signal_key = ? AND kind = ?")
    .bind(key, kind)
    .run();
  return c.json({ deleted: res.meta.changes ?? 0 });
});
```

- [ ] **Step 4: Mount it**

In `dashboard/src/index.ts`, add the import next to the others and the route next to the others:

```ts
import { marksRoutes } from "./routes/marks";
// ...
app.route("/api", marksRoutes);
```

- [ ] **Step 5: Run the whole API suite**

Run: `cd dashboard && npx vitest run && npx tsc --noEmit`
Expected: PASS — all suites, no type errors.

- [ ] **Step 6: Commit**

```bash
git add dashboard/src/routes/marks.ts dashboard/src/index.ts dashboard/test/marks.test.ts
git commit -m "feat(dashboard): /api/marks — place, move, delete bar marks"
```

---

### Task 4: Pull marks down to the laptop

**Files:**
- Modify: `dashboard/scripts/pull-feedback.ts`
- Modify: `.gitignore` (repo root)
- Modify: `dashboard/DEPLOY.md`

**Interfaces:**
- Produces: `npm run pull [-- --remote]` writes both `experiments/feedback.json` and `experiments/marks.json` (array of `{ signal_key, kind, bar_ts, created_at }`).

- [ ] **Step 1: Rewrite `dashboard/scripts/pull-feedback.ts`**

```ts
import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";

const remote = process.argv.includes("--remote");

function query(sql: string): Record<string, unknown>[] {
  const args = [
    "wrangler",
    "d1",
    "execute",
    "axe-trader-dashboard",
    remote ? "--remote" : "--local",
    "--json",
    "-y",
    "--command",
    sql,
  ];
  const raw = execFileSync("npx", args, { encoding: "utf8" });
  // wrangler --json returns an array of statement results: [{ results: [...] }]
  const parsed = JSON.parse(raw);
  return Array.isArray(parsed) ? (parsed[0]?.results ?? []) : (parsed.results ?? []);
}

const feedback = query(
  "SELECT signal_key, flag, note, created_at FROM feedback ORDER BY created_at"
);
writeFileSync("../experiments/feedback.json", JSON.stringify(feedback, null, 2));
console.log(`wrote ${feedback.length} feedback rows -> experiments/feedback.json`);

const marks = query(
  "SELECT signal_key, kind, bar_ts, created_at FROM marks ORDER BY signal_key, kind"
);
writeFileSync("../experiments/marks.json", JSON.stringify(marks, null, 2));
console.log(`wrote ${marks.length} mark rows -> experiments/marks.json`);
```

- [ ] **Step 2: Gitignore the pulled marks file**

```bash
grep -qxF 'experiments/marks.json' .gitignore || echo 'experiments/marks.json' >> .gitignore
```

- [ ] **Step 3: Smoke-test the round trip against local D1**

```bash
cd dashboard
npx wrangler d1 execute axe-trader-dashboard --local -y --command "INSERT OR REPLACE INTO marks (id, signal_key, kind, bar_ts, created_at) VALUES ('smoke','US500|2024-12-10T12:17:00Z|LONG','T1','2024-12-10T12:45:00Z','2026-07-20T00:00:00Z')"
npx tsx scripts/pull-feedback.ts
cat ../experiments/marks.json
```
Expected: the file contains the one row with `kind: "T1"` and `bar_ts: "2024-12-10T12:45:00Z"`.

- [ ] **Step 4: Document it in `dashboard/DEPLOY.md`**

In the "Local loop" section, change the `npm run pull` comment line to:

```
npm run pull            # writes ../experiments/feedback.json AND ../experiments/marks.json (both gitignored)
```

- [ ] **Step 5: Commit**

```bash
git add dashboard/scripts/pull-feedback.ts dashboard/DEPLOY.md .gitignore
git commit -m "feat(dashboard): pull marks alongside feedback"
```

---

### Task 5: Frontend scaffold

**Files:**
- Create: `dashboard/frontend/index.html`
- Create: `dashboard/frontend/vite.config.ts`
- Create: `dashboard/frontend/vitest.config.ts`
- Create: `dashboard/frontend/tsconfig.json`
- Create: `dashboard/frontend/src/main.tsx`
- Create: `dashboard/frontend/src/App.tsx`
- Create: `dashboard/frontend/src/App.test.tsx`
- Modify: `dashboard/package.json`
- Modify: `.gitignore` (repo root)
- Delete: `dashboard/public/.gitkeep`

**Interfaces:**
- Produces: `npm run build` emits the app into `dashboard/public/`; `npm test` runs API tests then UI tests; `npm run dev:ui` serves the app with `/api` proxied to `wrangler dev`.
- Produces: `App` renders a two-tab shell — tabs labelled `Overview` and `Trades`, `Trades` active by default (review is the primary activity).

- [ ] **Step 1: Install the frontend toolchain**

```bash
cd dashboard
npm i -D react@^19 react-dom@^19 @types/react@^19 @types/react-dom@^19 \
  vite@^8 @vitejs/plugin-react@^6 jsdom@^29 @testing-library/react@^16 \
  @testing-library/user-event@^14 @testing-library/jest-dom@^6
```

Expected: installs cleanly. If npm reports a peer-dependency conflict, resolve it by pinning the *lower* conflicting package rather than passing `--force` (Plan 1 hit the same class of problem with the vitest pool).

- [ ] **Step 2: Create `dashboard/frontend/index.html`**

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
    <meta name="color-scheme" content="dark light" />
    <title>axe-trader · trade review</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 3: Create `dashboard/frontend/vite.config.ts`**

```ts
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  // shared/vocab.ts lives above this root and is imported by src/ — allow it.
  server: {
    fs: { allow: [".."] },
    proxy: { "/api": "http://localhost:8787" },
  },
  build: { outDir: "../public", emptyOutDir: true },
});
```

- [ ] **Step 4: Create `dashboard/frontend/vitest.config.ts`**

```ts
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test-setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
```

- [ ] **Step 5: Create `dashboard/frontend/src/test-setup.ts`**

```ts
import "@testing-library/jest-dom/vitest";
```

- [ ] **Step 6: Create `dashboard/frontend/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "es2022",
    "module": "es2022",
    "moduleResolution": "bundler",
    "lib": ["es2022", "dom", "dom.iterable"],
    "jsx": "react-jsx",
    "types": ["vitest/globals", "@testing-library/jest-dom"],
    "strict": true,
    "skipLibCheck": true,
    "noEmit": true,
    "allowImportingTsExtensions": false
  },
  "include": ["src", "../shared", "vite.config.ts", "vitest.config.ts"]
}
```

- [ ] **Step 7: Write the failing shell test**

Create `dashboard/frontend/src/App.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect } from "vitest";
import App from "./App";

describe("App shell", () => {
  it("shows both tabs with Trades active by default", async () => {
    render(<App />);
    expect(screen.getByRole("tab", { name: /trades/i })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: /overview/i })).toHaveAttribute("aria-selected", "false");
  });

  it("switches to Overview when its tab is tapped", async () => {
    render(<App />);
    await userEvent.click(screen.getByRole("tab", { name: /overview/i }));
    expect(screen.getByRole("tab", { name: /overview/i })).toHaveAttribute("aria-selected", "true");
  });
});
```

- [ ] **Step 8: Run it to verify it fails**

Run: `cd dashboard/frontend && npx vitest run`
Expected: FAIL — cannot resolve `./App`.

- [ ] **Step 9: Create `dashboard/frontend/src/App.tsx`**

```tsx
import { useState } from "react";

export type Tab = "trades" | "overview";

export default function App() {
  const [tab, setTab] = useState<Tab>("trades");

  return (
    <div className="app">
      <main className="panel">
        {tab === "trades" ? <p>Trades</p> : <p>Overview</p>}
      </main>
      <nav className="tabbar" role="tablist">
        <button role="tab" aria-selected={tab === "overview"} onClick={() => setTab("overview")}>
          Overview
        </button>
        <button role="tab" aria-selected={tab === "trades"} onClick={() => setTab("trades")}>
          Trades
        </button>
      </nav>
    </div>
  );
}
```

- [ ] **Step 10: Create `dashboard/frontend/src/main.tsx`**

```tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>
);
```

- [ ] **Step 11: Wire the npm scripts**

In `dashboard/package.json`, replace the `test` script and add the new ones:

```json
    "test": "npm run test:api && npm run test:ui",
    "test:api": "vitest run",
    "test:ui": "vitest run --root frontend",
    "typecheck": "tsc --noEmit && tsc --noEmit -p frontend",
    "build": "vite build --config frontend/vite.config.ts",
    "dev:ui": "vite --config frontend/vite.config.ts",
    "deploy": "npm run build && wrangler deploy",
```

- [ ] **Step 12: Make `public/` a build artifact**

```bash
cd /Users/gertehlers/Development/projects/axe-trader
git rm --cached dashboard/public/.gitkeep
rm -f dashboard/public/.gitkeep
grep -qxF 'dashboard/public/' .gitignore || echo 'dashboard/public/' >> .gitignore
```

- [ ] **Step 13: Verify everything runs**

```bash
cd dashboard
npm run test:ui      # expect PASS (2 tests)
npm test             # expect PASS (API suites + UI suites)
npm run typecheck    # expect no errors
npm run build        # expect dist emitted into ../public
ls public            # expect index.html and an assets/ directory
```

- [ ] **Step 14: Commit**

```bash
git add dashboard/frontend dashboard/package.json dashboard/package-lock.json .gitignore
git commit -m "feat(ui): Vite/React scaffold, jsdom test project, build into public/"
```

---

### Task 6: Typed API client

**Files:**
- Create: `dashboard/frontend/src/types.ts`
- Create: `dashboard/frontend/src/api.ts`
- Create: `dashboard/frontend/src/api.test.ts`

**Interfaces:**
- Produces types: `Bar { t: number; o: number; h: number; l: number; c: number }`, `Run`, `TradeSummary`, `Trade` (a `TradeSummary` plus `bars_json`), `Feedback`, `Mark`, `Slices`.
- Produces functions: `getRuns()`, `getTrades(runId, filter)`, `getTrade(id)`, `getBars(trade)`, `getFeedback()`, `postFeedback(signalKey, flag)`, `getMarks()`, `postMark(signalKey, kind, barTs)`, `deleteMark(signalKey, kind)`, `getSlices(runId, feature, buckets)`. All throw `ApiError` on a non-2xx response.

- [ ] **Step 1: Create `dashboard/frontend/src/types.ts`**

```ts
import type { Flag, MarkKind } from "../../shared/vocab";

export type Bar = { t: number; o: number; h: number; l: number; c: number };

export interface Run {
  id: string;
  created_at: string;
  label: string | null;
  instrument: string | null;
  timeframe_min: number | null;
  trades_count: number | null;
  trades_per_day: number | null;
  win_rate: number | null;
  net_avg_pnl: number | null;
  net_avg_pnl_usd: number | null;
  avg_r: number | null;
  max_drawdown: number | null;
  worst_quarter_net: number | null;
}

export interface TradeSummary {
  id: string;
  run_id: string;
  signal_key: string;
  entry_time: string;
  exit_time: string;
  direction: "LONG" | "SHORT";
  entry_price: number;
  exit_price: number;
  stop_price: number;
  target_price: number;
  pnl: number;
  net_pnl: number;
  r_multiple: number;
  exit_reason: string | null;
  is_win: number;
  rsi_value: number | null;
  dist_to_trend_ema_atr: number | null;
  atr_value: number | null;
  volume_ratio: number | null;
  volatility_regime: string | null;
  confluence_score: number | null;
  pillars_fired: string | null;
}

export interface Trade extends TradeSummary {
  bars_json: string;
}

export interface Feedback {
  signal_key: string;
  flag: Flag | null;
  note: string | null;
  created_at: string;
}

export interface Mark {
  signal_key: string;
  kind: MarkKind;
  bar_ts: string;
  created_at: string;
}

export interface Slices {
  baseline_win: number;
  trades: number;
  buckets: { lo: number; hi: number; count: number; win_pct: number; net_avg_pnl: number }[];
}

export type TradeFilter = "all" | "losers" | "winners";
```

- [ ] **Step 2: Write the failing test**

Create `dashboard/frontend/src/api.test.ts`:

```ts
import { describe, it, expect, vi, afterEach } from "vitest";
import { getTrades, postMark, deleteMark, ApiError, getBars } from "./api";
import type { Trade } from "./types";

function mockFetch(body: unknown, status = 200) {
  const fn = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } })
  );
  vi.stubGlobal("fetch", fn);
  return fn;
}

afterEach(() => vi.unstubAllGlobals());

describe("api client", () => {
  it("requests the filtered trade list for a run", async () => {
    const fetchMock = mockFetch([]);
    await getTrades("run-1", "losers");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/runs/run-1/trades?filter=losers");
  });

  it("posts a mark as JSON", async () => {
    const fetchMock = mockFetch({ signal_key: "k", kind: "T1", bar_ts: "ts", created_at: "now" });
    await postMark("k", "T1", "ts");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/marks");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ signal_key: "k", kind: "T1", bar_ts: "ts" });
  });

  it("deletes a mark via query parameters, not a body", async () => {
    const fetchMock = mockFetch({ deleted: 1 });
    await deleteMark("US500|2025-03-04T14:30:00Z|LONG", "T1");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/marks?signal_key=US500%7C2025-03-04T14%3A30%3A00Z%7CLONG&kind=T1");
    expect(init.method).toBe("DELETE");
    expect(init.body).toBeUndefined();
  });

  it("throws ApiError on a non-2xx response", async () => {
    mockFetch({ error: "unknown kind" }, 400);
    await expect(postMark("k", "T1", "ts")).rejects.toBeInstanceOf(ApiError);
  });

  it("parses bars_json into bar objects", () => {
    const trade = { bars_json: '[{"t":1,"o":2,"h":3,"l":1,"c":2}]' } as Trade;
    expect(getBars(trade)).toEqual([{ t: 1, o: 2, h: 3, l: 1, c: 2 }]);
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd dashboard && npm run test:ui`
Expected: FAIL — cannot resolve `./api`.

- [ ] **Step 4: Create `dashboard/frontend/src/api.ts`**

```ts
import type { Flag, MarkKind } from "../../shared/vocab";
import type { Bar, Feedback, Mark, Run, Slices, Trade, TradeFilter, TradeSummary } from "./types";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) throw new ApiError(res.status, `${init?.method ?? "GET"} ${url} → ${res.status}`);
  return (await res.json()) as T;
}

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "content-type": "application/json" },
  body: JSON.stringify(body),
});

export const getRuns = () => request<Run[]>("/api/runs");

export const getTrades = (runId: string, filter: TradeFilter) =>
  request<TradeSummary[]>(`/api/runs/${runId}/trades?filter=${filter}`);

export const getTrade = (id: string) => request<Trade>(`/api/trades/${id}`);

export const getSlices = (runId: string, feature: string, buckets = 4) =>
  request<Slices>(`/api/runs/${runId}/slices?feature=${feature}&buckets=${buckets}`);

export const getFeedback = () => request<Feedback[]>("/api/feedback");

export const postFeedback = (signal_key: string, flag: Flag) =>
  request<Feedback>("/api/feedback", json("POST", { signal_key, flag }));

export const getMarks = () => request<Mark[]>("/api/marks");

export const postMark = (signal_key: string, kind: MarkKind, bar_ts: string) =>
  request<Mark>("/api/marks", json("POST", { signal_key, kind, bar_ts }));

export const deleteMark = (signal_key: string, kind: MarkKind) =>
  request<{ deleted: number }>(
    `/api/marks?signal_key=${encodeURIComponent(signal_key)}&kind=${encodeURIComponent(kind)}`,
    { method: "DELETE" }
  );

/** Bars travel as a JSON string on the trade row; parse once per trade. */
export const getBars = (trade: Trade): Bar[] => JSON.parse(trade.bars_json) as Bar[];
```

- [ ] **Step 5: Run it to verify it passes**

Run: `cd dashboard && npm run test:ui && npm run typecheck`
Expected: PASS (7 tests: 2 shell + 5 api), no type errors.

- [ ] **Step 6: Commit**

```bash
git add dashboard/frontend/src/types.ts dashboard/frontend/src/api.ts dashboard/frontend/src/api.test.ts
git commit -m "feat(ui): typed API client for runs, trades, feedback and marks"
```

---

### Task 7: Chart geometry (pure functions)

**Files:**
- Create: `dashboard/frontend/src/chart.ts`
- Create: `dashboard/frontend/src/chart.test.ts`

**Interfaces:**
- Produces: `barIndexAtOrAfter(bars, iso)`, `focusWindow(bars, entryIso, exitIso, pad?)` → `{ from: number; to: number }`, `priceRange(bars, extras)` → `{ min: number; max: number }`, `makeScales({ bars, min, max, width, height })` → `{ x(i): number; y(price): number; barWidth: number }`, `barIndexAtX(x, count, width)`.
- These carry all the maths so the SVG component stays declarative and every geometry rule is unit-tested without a DOM.

- [ ] **Step 1: Write the failing test**

Create `dashboard/frontend/src/chart.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { barIndexAtOrAfter, focusWindow, priceRange, makeScales, barIndexAtX } from "./chart";
import type { Bar } from "./types";

// 20 bars, 5 minutes apart, starting 2025-03-04T14:00:00Z, price walking 100 → 119
const bars: Bar[] = Array.from({ length: 20 }, (_, i) => ({
  t: Date.parse("2025-03-04T14:00:00Z") / 1000 + i * 300,
  o: 100 + i,
  h: 101 + i,
  l: 99 + i,
  c: 100 + i,
}));

describe("chart geometry", () => {
  it("finds the first bar at or after a timestamp", () => {
    expect(barIndexAtOrAfter(bars, "2025-03-04T14:00:00Z")).toBe(0);
    expect(barIndexAtOrAfter(bars, "2025-03-04T14:12:00Z")).toBe(3); // 14:15 bar
    expect(barIndexAtOrAfter(bars, "2030-01-01T00:00:00Z")).toBe(19); // clamps to last
  });

  it("windows to entry..exit with padding, clamped to the series", () => {
    expect(focusWindow(bars, "2025-03-04T14:25:00Z", "2025-03-04T14:40:00Z", 2)).toEqual({
      from: 3,
      to: 10,
    });
    // padding beyond the ends clamps instead of going negative
    expect(focusWindow(bars, "2025-03-04T14:00:00Z", "2025-03-04T15:35:00Z", 10)).toEqual({
      from: 0,
      to: 19,
    });
  });

  it("includes stop/target prices in the vertical range", () => {
    const r = priceRange(bars.slice(0, 3), [90, 130]);
    expect(r.min).toBeLessThanOrEqual(90);
    expect(r.max).toBeGreaterThanOrEqual(130);
  });

  it("maps prices and indices into the viewport", () => {
    const s = makeScales({ count: 10, min: 100, max: 200, width: 300, height: 100 });
    expect(s.y(200)).toBeCloseTo(0); // highest price at the top
    expect(s.y(100)).toBeCloseTo(100); // lowest at the bottom
    expect(s.x(0)).toBeGreaterThan(0);
    expect(s.x(9)).toBeLessThan(300);
    expect(s.x(9)).toBeGreaterThan(s.x(0));
  });

  it("hit-tests a tap to the nearest bar", () => {
    const width = 300;
    const count = 10;
    const s = makeScales({ count, min: 0, max: 1, width, height: 100 });
    expect(barIndexAtX(s.x(4), count, width)).toBe(4);
    expect(barIndexAtX(-50, count, width)).toBe(0); // clamps left
    expect(barIndexAtX(9999, count, width)).toBe(9); // clamps right
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd dashboard && npm run test:ui`
Expected: FAIL — cannot resolve `./chart`.

- [ ] **Step 3: Create `dashboard/frontend/src/chart.ts`**

```ts
import type { Bar } from "./types";

/** Index of the first bar whose end time is at or after `iso`; clamps to the last bar. */
export function barIndexAtOrAfter(bars: Bar[], iso: string): number {
  const target = Date.parse(iso) / 1000;
  for (let i = 0; i < bars.length; i++) {
    if (bars[i].t >= target) return i;
  }
  return Math.max(0, bars.length - 1);
}

/** Focus view: entry → exit with `pad` bars either side, clamped to the series. */
export function focusWindow(
  bars: Bar[],
  entryIso: string,
  exitIso: string,
  pad = 10
): { from: number; to: number } {
  const entry = barIndexAtOrAfter(bars, entryIso);
  const exit = barIndexAtOrAfter(bars, exitIso);
  return {
    from: Math.max(0, Math.min(entry, exit) - pad),
    to: Math.min(bars.length - 1, Math.max(entry, exit) + pad),
  };
}

/** Vertical range over the visible bars, widened to include stop/target and padded 2%. */
export function priceRange(bars: Bar[], extras: number[] = []): { min: number; max: number } {
  const lows = bars.map((b) => b.l).concat(extras);
  const highs = bars.map((b) => b.h).concat(extras);
  const min = Math.min(...lows);
  const max = Math.max(...highs);
  const pad = (max - min) * 0.02 || 1;
  return { min: min - pad, max: max + pad };
}

export interface Scales {
  x(index: number): number;
  y(price: number): number;
  barWidth: number;
}

export function makeScales(opts: {
  count: number;
  min: number;
  max: number;
  width: number;
  height: number;
}): Scales {
  const { count, min, max, width, height } = opts;
  const slot = width / Math.max(1, count);
  return {
    x: (i) => slot * (i + 0.5),
    y: (price) => height - ((price - min) / (max - min || 1)) * height,
    barWidth: Math.max(1, slot * 0.6),
  };
}

/** Nearest bar index for a tap at `x` pixels; clamps to the series. */
export function barIndexAtX(x: number, count: number, width: number): number {
  const slot = width / Math.max(1, count);
  return Math.min(count - 1, Math.max(0, Math.floor(x / slot)));
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd dashboard && npm run test:ui`
Expected: PASS (12 tests total).

- [ ] **Step 5: Commit**

```bash
git add dashboard/frontend/src/chart.ts dashboard/frontend/src/chart.test.ts
git commit -m "feat(ui): chart geometry — windowing, scales, bar hit-testing"
```

---

### Task 8: TradeCard — candles, levels, markers, Focus/Full

**Files:**
- Create: `dashboard/frontend/src/components/TradeCard.tsx`
- Create: `dashboard/frontend/src/components/TradeCard.test.tsx`

**Interfaces:**
- Consumes: `chart.ts`, `types.ts`, `MARK_KINDS`.
- Produces: `<TradeCard trade bars marks zoom onZoomChange onBarTap />`.
  - `zoom: "focus" | "full"`, `onZoomChange(z)`.
  - `onBarTap(barIso: string)` fires with the ISO timestamp of the nearest bar to the tap.
  - Renders `data-testid="candles"` (the SVG), one `data-testid="mark-<kind>"` line per mark, `data-testid="stop-line"`, `data-testid="target-line"`, `data-testid="entry-marker"`, `data-testid="exit-marker"`, and a self-describing text block.
- The SVG uses a fixed 320×180 viewBox so tap coordinates map through `barIndexAtX` deterministically in tests and scale responsively in the browser.

- [ ] **Step 1: Write the failing test**

Create `dashboard/frontend/src/components/TradeCard.test.tsx`:

```tsx
import { render, screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";
import TradeCard, { VIEW_W } from "./TradeCard";
import type { Bar, Trade } from "../types";

const t0 = Date.parse("2025-03-04T14:00:00Z") / 1000;
const bars: Bar[] = Array.from({ length: 60 }, (_, i) => ({
  t: t0 + i * 300,
  o: 100 + i * 0.1,
  h: 101 + i * 0.1,
  l: 99 + i * 0.1,
  c: 100 + i * 0.1,
}));

const trade = {
  id: "t-1",
  signal_key: "US500|2025-03-04T16:00:00Z|LONG",
  entry_time: "2025-03-04T16:00:00Z", // bar 24
  exit_time: "2025-03-04T16:30:00Z", // bar 30
  direction: "LONG",
  entry_price: 102.4,
  exit_price: 103.0,
  stop_price: 99.4,
  target_price: 103.15,
  pnl: 0.6,
  net_pnl: 0.4,
  r_multiple: 0.2,
  exit_reason: "TARGET",
  is_win: 1,
  rsi_value: 28,
  pillars_fired: "RSI+BB",
} as unknown as Trade;

describe("TradeCard", () => {
  it("draws stop and target lines at their prices, and entry/exit markers", () => {
    render(<TradeCard trade={trade} bars={bars} marks={[]} zoom="full" onZoomChange={() => {}} onBarTap={() => {}} />);
    const stop = screen.getByTestId("stop-line");
    const target = screen.getByTestId("target-line");
    // stop is below target on screen → larger y
    expect(Number(stop.getAttribute("y1"))).toBeGreaterThan(Number(target.getAttribute("y1")));
    expect(screen.getByTestId("entry-marker")).toBeInTheDocument();
    expect(screen.getByTestId("exit-marker")).toBeInTheDocument();
  });

  it("shows fewer candles in focus than in full", () => {
    const { rerender } = render(
      <TradeCard trade={trade} bars={bars} marks={[]} zoom="full" onZoomChange={() => {}} onBarTap={() => {}} />
    );
    const full = screen.getAllByTestId("candle").length;
    rerender(
      <TradeCard trade={trade} bars={bars} marks={[]} zoom="focus" onZoomChange={() => {}} onBarTap={() => {}} />
    );
    expect(screen.getAllByTestId("candle").length).toBeLessThan(full);
  });

  it("reports the tapped bar's timestamp", () => {
    const onBarTap = vi.fn();
    render(<TradeCard trade={trade} bars={bars} marks={[]} zoom="full" onZoomChange={() => {}} onBarTap={onBarTap} />);
    const svg = screen.getByTestId("candles");
    svg.getBoundingClientRect = () =>
      ({ left: 0, top: 0, width: VIEW_W, height: 180 }) as DOMRect;
    // tap in the middle → middle bar of the 60-bar full window (index 29 or 30)
    fireEvent.click(svg, { clientX: VIEW_W / 2, clientY: 90 });
    expect(onBarTap).toHaveBeenCalledTimes(1);
    const iso = onBarTap.mock.calls[0][0] as string;
    expect([bars[29].t, bars[30].t]).toContain(Date.parse(iso) / 1000);
  });

  it("renders one line per mark", () => {
    render(
      <TradeCard
        trade={trade}
        bars={bars}
        marks={[
          { signal_key: trade.signal_key, kind: "T1", bar_ts: "2025-03-04T16:20:00Z", created_at: "" },
          { signal_key: trade.signal_key, kind: "better-entry", bar_ts: "2025-03-04T15:50:00Z", created_at: "" },
        ]}
        zoom="full"
        onZoomChange={() => {}}
        onBarTap={() => {}}
      />
    );
    expect(screen.getByTestId("mark-T1")).toBeInTheDocument();
    expect(screen.getByTestId("mark-better-entry")).toBeInTheDocument();
  });

  it("switches zoom when the toggle is tapped", async () => {
    const onZoomChange = vi.fn();
    render(<TradeCard trade={trade} bars={bars} marks={[]} zoom="focus" onZoomChange={onZoomChange} onBarTap={() => {}} />);
    await userEvent.click(screen.getByRole("button", { name: /full/i }));
    expect(onZoomChange).toHaveBeenCalledWith("full");
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd dashboard && npm run test:ui`
Expected: FAIL — cannot resolve `./TradeCard`.

- [ ] **Step 3: Create `dashboard/frontend/src/components/TradeCard.tsx`**

```tsx
import type { Bar, Mark, Trade } from "../types";
import { barIndexAtOrAfter, barIndexAtX, focusWindow, makeScales, priceRange } from "../chart";

export const VIEW_W = 320;
export const VIEW_H = 180;

export type Zoom = "focus" | "full";

interface Props {
  trade: Trade;
  bars: Bar[];
  marks: Mark[];
  zoom: Zoom;
  onZoomChange: (z: Zoom) => void;
  onBarTap: (barIso: string) => void;
}

const iso = (bar: Bar) => new Date(bar.t * 1000).toISOString();

export default function TradeCard({ trade, bars, marks, zoom, onZoomChange, onBarTap }: Props) {
  const win =
    zoom === "focus"
      ? focusWindow(bars, trade.entry_time, trade.exit_time)
      : { from: 0, to: Math.max(0, bars.length - 1) };
  const visible = bars.slice(win.from, win.to + 1);
  const { min, max } = priceRange(visible, [trade.stop_price, trade.target_price, trade.entry_price]);
  const s = makeScales({ count: visible.length, min, max, width: VIEW_W, height: VIEW_H });

  const localIndex = (i: number) => i - win.from;
  const entryIdx = localIndex(barIndexAtOrAfter(bars, trade.entry_time));
  const exitIdx = localIndex(barIndexAtOrAfter(bars, trade.exit_time));
  const inView = (i: number) => i >= 0 && i < visible.length;

  function handleClick(e: React.MouseEvent<SVGSVGElement>) {
    const box = e.currentTarget.getBoundingClientRect();
    const x = ((e.clientX - box.left) / box.width) * VIEW_W;
    const idx = barIndexAtX(x, visible.length, VIEW_W);
    onBarTap(iso(visible[idx]));
  }

  return (
    <div className="card">
      <header className="card-head">
        <span>
          {trade.direction} · {trade.entry_time.replace("T", " ").slice(0, 16)}Z
        </span>
        <span className={trade.net_pnl >= 0 ? "win" : "loss"}>
          {trade.net_pnl >= 0 ? "+" : ""}
          {trade.net_pnl.toFixed(2)} pts
        </span>
      </header>

      <svg
        data-testid="candles"
        viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
        width="100%"
        onClick={handleClick}
        role="img"
        aria-label="price chart"
      >
        <line
          data-testid="target-line"
          x1={0}
          x2={VIEW_W}
          y1={s.y(trade.target_price)}
          y2={s.y(trade.target_price)}
          className="level target"
        />
        <line
          data-testid="stop-line"
          x1={0}
          x2={VIEW_W}
          y1={s.y(trade.stop_price)}
          y2={s.y(trade.stop_price)}
          className="level stop"
        />

        {visible.map((b, i) => (
          <g key={b.t} data-testid="candle">
            <line x1={s.x(i)} x2={s.x(i)} y1={s.y(b.h)} y2={s.y(b.l)} className="wick" />
            <rect
              x={s.x(i) - s.barWidth / 2}
              width={s.barWidth}
              y={s.y(Math.max(b.o, b.c))}
              height={Math.max(1, Math.abs(s.y(b.o) - s.y(b.c)))}
              className={b.c >= b.o ? "body up" : "body down"}
            />
          </g>
        ))}

        {inView(entryIdx) && (
          <circle
            data-testid="entry-marker"
            cx={s.x(entryIdx)}
            cy={s.y(trade.entry_price)}
            r={4}
            className="entry"
          />
        )}
        {inView(exitIdx) && (
          <circle
            data-testid="exit-marker"
            cx={s.x(exitIdx)}
            cy={s.y(trade.exit_price)}
            r={4}
            className="exit"
          />
        )}

        {marks.map((m) => {
          const i = localIndex(barIndexAtOrAfter(bars, m.bar_ts));
          if (!inView(i)) return null;
          return (
            <g key={m.kind} data-testid={`mark-${m.kind}`}>
              <line x1={s.x(i)} x2={s.x(i)} y1={0} y2={VIEW_H} className={`mark ${m.kind}`} />
              <text x={s.x(i)} y={10} className="mark-label" textAnchor="middle">
                {m.kind}
              </text>
            </g>
          );
        })}
      </svg>

      <div className="zoom">
        <button aria-pressed={zoom === "focus"} onClick={() => onZoomChange("focus")}>
          Focus
        </button>
        <button aria-pressed={zoom === "full"} onClick={() => onZoomChange("full")}>
          Full
        </button>
      </div>

      <dl className="facts">
        <div>
          <dt>R</dt>
          <dd>{trade.r_multiple.toFixed(2)}</dd>
        </div>
        <div>
          <dt>exit</dt>
          <dd>{trade.exit_reason ?? "—"}</dd>
        </div>
        <div>
          <dt>RSI</dt>
          <dd>{trade.rsi_value?.toFixed(0) ?? "—"}</dd>
        </div>
        <div>
          <dt>pillars</dt>
          <dd>{trade.pillars_fired ?? "—"}</dd>
        </div>
      </dl>
    </div>
  );
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd dashboard && npm run test:ui && npm run typecheck`
Expected: PASS (17 tests), no type errors.

- [ ] **Step 5: Commit**

```bash
git add dashboard/frontend/src/components/TradeCard.tsx dashboard/frontend/src/components/TradeCard.test.tsx
git commit -m "feat(ui): TradeCard — SVG candles, stop/target, markers, Focus/Full"
```

---

### Task 9: Annotations state — optimistic flags and marks

**Files:**
- Create: `dashboard/frontend/src/useAnnotations.ts`
- Create: `dashboard/frontend/src/useAnnotations.test.tsx`

**Interfaces:**
- Produces: `useAnnotations()` → `{ flags: Map<string, Flag>, marks: Map<string, Mark[]>, setFlag(signalKey, flag), toggleMark(signalKey, kind, barIso), error: string | null, loading: boolean }`.
- `setFlag` and `toggleMark` update state immediately and revert if the request fails, setting `error`.
- `toggleMark` removes the mark when the same kind is already on the same bar, otherwise places/moves it.

- [ ] **Step 1: Write the failing test**

Create `dashboard/frontend/src/useAnnotations.test.tsx`:

```tsx
import { renderHook, act, waitFor } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { useAnnotations } from "./useAnnotations";
import * as api from "./api";

const KEY = "US500|2025-03-04T16:00:00Z|LONG";

beforeEach(() => {
  vi.spyOn(api, "getFeedback").mockResolvedValue([]);
  vi.spyOn(api, "getMarks").mockResolvedValue([]);
});
afterEach(() => vi.restoreAllMocks());

describe("useAnnotations", () => {
  it("applies a flag immediately and keeps it when the request succeeds", async () => {
    vi.spyOn(api, "postFeedback").mockResolvedValue({
      signal_key: KEY,
      flag: "chop",
      note: null,
      created_at: "",
    });
    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      result.current.setFlag(KEY, "chop");
    });
    expect(result.current.flags.get(KEY)).toBe("chop");
  });

  it("reverts the flag when the request fails", async () => {
    vi.spyOn(api, "postFeedback").mockRejectedValue(new api.ApiError(500, "boom"));
    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      result.current.setFlag(KEY, "chop");
    });
    await waitFor(() => expect(result.current.flags.get(KEY)).toBeUndefined());
    expect(result.current.error).toBeTruthy();
  });

  it("places, moves and removes a mark", async () => {
    vi.spyOn(api, "postMark").mockImplementation(async (signal_key, kind, bar_ts) => ({
      signal_key,
      kind,
      bar_ts,
      created_at: "",
    }));
    vi.spyOn(api, "deleteMark").mockResolvedValue({ deleted: 1 });

    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:10:00Z");
    });
    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:10:00Z" }),
    ]);

    // same kind, different bar → moves
    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:20:00Z");
    });
    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:20:00Z" }),
    ]);

    // same kind, same bar → removes
    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:20:00Z");
    });
    expect(result.current.marks.get(KEY) ?? []).toEqual([]);
    expect(api.deleteMark).toHaveBeenCalledWith(KEY, "T1");
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd dashboard && npm run test:ui`
Expected: FAIL — cannot resolve `./useAnnotations`.

- [ ] **Step 3: Create `dashboard/frontend/src/useAnnotations.ts`**

```ts
import { useCallback, useEffect, useState } from "react";
import type { Flag, MarkKind } from "../../shared/vocab";
import * as api from "./api";
import type { Mark } from "./types";

/**
 * Flags and marks for every trade, keyed by signal_key. Writes are optimistic:
 * state changes immediately and reverts if the API call fails, so tapping stays
 * instant on a phone without risking a silent divergence from D1.
 */
export function useAnnotations() {
  const [flags, setFlags] = useState<Map<string, Flag>>(new Map());
  const [marks, setMarks] = useState<Map<string, Mark[]>>(new Map());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    Promise.all([api.getFeedback(), api.getMarks()])
      .then(([fb, mk]) => {
        if (!live) return;
        setFlags(new Map(fb.filter((f) => f.flag).map((f) => [f.signal_key, f.flag as Flag])));
        const byKey = new Map<string, Mark[]>();
        for (const m of mk) byKey.set(m.signal_key, [...(byKey.get(m.signal_key) ?? []), m]);
        setMarks(byKey);
      })
      .catch((e: unknown) => live && setError(String(e)))
      .finally(() => live && setLoading(false));
    return () => {
      live = false;
    };
  }, []);

  const setFlag = useCallback((signalKey: string, flag: Flag) => {
    let previous: Flag | undefined;
    setFlags((prev) => {
      previous = prev.get(signalKey);
      const next = new Map(prev);
      next.set(signalKey, flag);
      return next;
    });
    api.postFeedback(signalKey, flag).catch((e: unknown) => {
      setError(String(e));
      setFlags((prev) => {
        const next = new Map(prev);
        if (previous === undefined) next.delete(signalKey);
        else next.set(signalKey, previous);
        return next;
      });
    });
  }, []);

  const toggleMark = useCallback((signalKey: string, kind: MarkKind, barIso: string) => {
    let previous: Mark[] = [];
    let removing = false;

    setMarks((prev) => {
      previous = prev.get(signalKey) ?? [];
      const existing = previous.find((m) => m.kind === kind);
      removing = existing?.bar_ts === barIso;
      const withoutKind = previous.filter((m) => m.kind !== kind);
      const next = new Map(prev);
      next.set(
        signalKey,
        removing
          ? withoutKind
          : [...withoutKind, { signal_key: signalKey, kind, bar_ts: barIso, created_at: "" }]
      );
      return next;
    });

    const revert = (e: unknown) => {
      setError(String(e));
      setMarks((prev) => {
        const next = new Map(prev);
        next.set(signalKey, previous);
        return next;
      });
    };

    if (removing) api.deleteMark(signalKey, kind).catch(revert);
    else api.postMark(signalKey, kind, barIso).catch(revert);
  }, []);

  return { flags, marks, setFlag, toggleMark, loading, error };
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd dashboard && npm run test:ui`
Expected: PASS (20 tests).

- [ ] **Step 5: Commit**

```bash
git add dashboard/frontend/src/useAnnotations.ts dashboard/frontend/src/useAnnotations.test.tsx
git commit -m "feat(ui): optimistic flag + mark state with revert on failure"
```

---

### Task 10: The deck — navigation, filter, kind chips, prefetch

**Files:**
- Create: `dashboard/frontend/src/components/TradeDeck.tsx`
- Create: `dashboard/frontend/src/components/TradeDeck.test.tsx`

**Interfaces:**
- Consumes: `api.getTrades`, `api.getTrade`, `api.getBars`, `TradeCard`, `useAnnotations` (passed in as props so the deck stays testable), `FLAGS`, `MARK_KINDS`.
- Produces: `<TradeDeck runId flags marks onFlag onToggleMark />` rendering one trade at a time with Prev/Next buttons (`aria-label="previous trade"` / `"next trade"`), a filter selector, flag chips, and mark-kind chips. Horizontal swipe uses the same handlers as the buttons.
- Prefetches the neighbouring trades' bars so swiping does not wait on the network.

- [ ] **Step 1: Write the failing test**

Create `dashboard/frontend/src/components/TradeDeck.test.tsx`:

```tsx
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import TradeDeck from "./TradeDeck";
import * as api from "../api";
import type { Trade, TradeSummary } from "../types";

const t0 = Date.parse("2025-03-04T14:00:00Z") / 1000;
const bars = Array.from({ length: 40 }, (_, i) => ({
  t: t0 + i * 300,
  o: 100,
  h: 101,
  l: 99,
  c: 100,
}));

const summary = (id: string, key: string): TradeSummary =>
  ({
    id,
    run_id: "run-1",
    signal_key: key,
    entry_time: "2025-03-04T15:00:00Z",
    exit_time: "2025-03-04T15:30:00Z",
    direction: "LONG",
    entry_price: 100,
    exit_price: 101,
    stop_price: 97,
    target_price: 101.5,
    pnl: 1,
    net_pnl: 0.8,
    r_multiple: 0.3,
    exit_reason: "TARGET",
    is_win: 1,
    rsi_value: 28,
    pillars_fired: "RSI+BB",
  }) as unknown as TradeSummary;

const KEY_A = "US500|2025-03-04T15:00:00Z|LONG";
const KEY_B = "US500|2025-03-05T15:00:00Z|LONG";

beforeEach(() => {
  vi.spyOn(api, "getTrades").mockResolvedValue([summary("t-a", KEY_A), summary("t-b", KEY_B)]);
  vi.spyOn(api, "getTrade").mockImplementation(
    async (id) => ({ ...summary(id, id === "t-a" ? KEY_A : KEY_B), bars_json: JSON.stringify(bars) }) as Trade
  );
});
afterEach(() => vi.restoreAllMocks());

const noop = () => {};

function renderDeck(overrides: Partial<React.ComponentProps<typeof TradeDeck>> = {}) {
  return render(
    <TradeDeck
      runId="run-1"
      flags={new Map()}
      marks={new Map()}
      onFlag={noop}
      onToggleMark={noop}
      {...overrides}
    />
  );
}

describe("TradeDeck", () => {
  it("shows one trade and its position, and advances on next", async () => {
    renderDeck();
    await waitFor(() => expect(screen.getByText(/1 of 2/i)).toBeInTheDocument());
    await userEvent.click(screen.getByRole("button", { name: /next trade/i }));
    expect(screen.getByText(/2 of 2/i)).toBeInTheDocument();
  });

  it("refetches when the filter changes", async () => {
    renderDeck();
    await waitFor(() => expect(api.getTrades).toHaveBeenCalledWith("run-1", "all"));
    await userEvent.selectOptions(screen.getByLabelText(/filter/i), "losers");
    await waitFor(() => expect(api.getTrades).toHaveBeenCalledWith("run-1", "losers"));
  });

  it("reports a flag tap with the current trade's signal_key", async () => {
    const onFlag = vi.fn();
    renderDeck({ onFlag });
    await waitFor(() => expect(screen.getByText(/1 of 2/i)).toBeInTheDocument());
    await userEvent.click(screen.getByRole("button", { name: "chop" }));
    expect(onFlag).toHaveBeenCalledWith(KEY_A, "chop");
  });

  it("taps a candle with the selected kind and reports the bar timestamp", async () => {
    const onToggleMark = vi.fn();
    renderDeck({ onToggleMark });
    await waitFor(() => expect(screen.getByText(/1 of 2/i)).toBeInTheDocument());

    await userEvent.click(screen.getByRole("button", { name: "T1" }));
    const svg = screen.getByTestId("candles");
    svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 320, height: 180 }) as DOMRect;
    await userEvent.click(svg);

    expect(onToggleMark).toHaveBeenCalledTimes(1);
    const [key, kind, iso] = onToggleMark.mock.calls[0];
    expect(key).toBe(KEY_A);
    expect(kind).toBe("T1");
    expect(Number.isNaN(Date.parse(iso))).toBe(false);
  });

  it("does not place a mark when no kind is selected", async () => {
    const onToggleMark = vi.fn();
    renderDeck({ onToggleMark });
    await waitFor(() => expect(screen.getByText(/1 of 2/i)).toBeInTheDocument());
    const svg = screen.getByTestId("candles");
    svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 320, height: 180 }) as DOMRect;
    await userEvent.click(svg);
    expect(onToggleMark).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd dashboard && npm run test:ui`
Expected: FAIL — cannot resolve `./TradeDeck`.

- [ ] **Step 3: Create `dashboard/frontend/src/components/TradeDeck.tsx`**

```tsx
import { useEffect, useRef, useState } from "react";
import { FLAGS, MARK_KINDS, type Flag, type MarkKind } from "../../../shared/vocab";
import * as api from "../api";
import type { Bar, Mark, Trade, TradeFilter, TradeSummary } from "../types";
import TradeCard, { type Zoom } from "./TradeCard";

interface Props {
  runId: string;
  flags: Map<string, Flag>;
  marks: Map<string, Mark[]>;
  onFlag: (signalKey: string, flag: Flag) => void;
  onToggleMark: (signalKey: string, kind: MarkKind, barIso: string) => void;
}

const SWIPE_PX = 50;

export default function TradeDeck({ runId, flags, marks, onFlag, onToggleMark }: Props) {
  const [filter, setFilter] = useState<TradeFilter>("all");
  const [list, setList] = useState<TradeSummary[]>([]);
  const [index, setIndex] = useState(0);
  const [detail, setDetail] = useState<Record<string, { trade: Trade; bars: Bar[] }>>({});
  const [kind, setKind] = useState<MarkKind | null>(null);
  const [zoom, setZoom] = useState<Zoom>("focus");
  const touchStartX = useRef<number | null>(null);

  useEffect(() => {
    let live = true;
    api.getTrades(runId, filter).then((rows) => {
      if (!live) return;
      setList(rows);
      setIndex(0);
    });
    return () => {
      live = false;
    };
  }, [runId, filter]);

  // Fetch the current trade's bars, and prefetch its neighbours so swiping is instant.
  useEffect(() => {
    let live = true;
    for (const i of [index, index + 1, index - 1]) {
      const row = list[i];
      if (!row || detail[row.id]) continue;
      api.getTrade(row.id).then((trade) => {
        if (!live) return;
        setDetail((prev) => ({ ...prev, [trade.id]: { trade, bars: api.getBars(trade) } }));
      });
    }
    return () => {
      live = false;
    };
  }, [list, index, detail]);

  if (list.length === 0) return <p className="empty">No trades for this filter.</p>;

  const current = list[index];
  const loaded = detail[current.id];
  const go = (delta: number) => setIndex((i) => Math.min(list.length - 1, Math.max(0, i + delta)));

  return (
    <section
      className="deck"
      onTouchStart={(e) => (touchStartX.current = e.touches[0].clientX)}
      onTouchEnd={(e) => {
        const start = touchStartX.current;
        touchStartX.current = null;
        if (start === null) return;
        const dx = e.changedTouches[0].clientX - start;
        if (Math.abs(dx) >= SWIPE_PX) go(dx < 0 ? 1 : -1);
      }}
    >
      <header className="deck-head">
        <label>
          Filter
          <select value={filter} onChange={(e) => setFilter(e.target.value as TradeFilter)}>
            <option value="all">all</option>
            <option value="losers">losers</option>
            <option value="winners">winners</option>
          </select>
        </label>
        <span>
          {index + 1} of {list.length}
        </span>
      </header>

      {loaded ? (
        <TradeCard
          trade={loaded.trade}
          bars={loaded.bars}
          marks={marks.get(current.signal_key) ?? []}
          zoom={zoom}
          onZoomChange={setZoom}
          onBarTap={(barIso) => {
            if (kind) onToggleMark(current.signal_key, kind, barIso);
          }}
        />
      ) : (
        <p className="loading">Loading chart…</p>
      )}

      <div className="chips kinds" role="group" aria-label="mark kind">
        {MARK_KINDS.map((k) => (
          <button
            key={k}
            aria-pressed={kind === k}
            onClick={() => setKind(kind === k ? null : k)}
          >
            {k}
          </button>
        ))}
      </div>

      <div className="chips flags" role="group" aria-label="cause">
        {FLAGS.map((f) => (
          <button
            key={f}
            aria-pressed={flags.get(current.signal_key) === f}
            onClick={() => onFlag(current.signal_key, f)}
          >
            {f}
          </button>
        ))}
      </div>

      <nav className="deck-nav">
        <button aria-label="previous trade" onClick={() => go(-1)} disabled={index === 0}>
          ‹
        </button>
        <button
          aria-label="next trade"
          onClick={() => go(1)}
          disabled={index === list.length - 1}
        >
          ›
        </button>
      </nav>
    </section>
  );
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd dashboard && npm run test:ui && npm run typecheck`
Expected: PASS (25 tests), no type errors.

- [ ] **Step 5: Commit**

```bash
git add dashboard/frontend/src/components/TradeDeck.tsx dashboard/frontend/src/components/TradeDeck.test.tsx
git commit -m "feat(ui): trade deck — swipe/prev/next, filter, flag + mark chips"
```

---

### Task 11: Overview tab

**Files:**
- Create: `dashboard/frontend/src/components/Overview.tsx`
- Create: `dashboard/frontend/src/components/Overview.test.tsx`

**Interfaces:**
- Consumes: `api.getTrades`, `api.getSlices`, `Run`.
- Produces: `<Overview run />` — KPI tiles (win rate, net pts/trade, $/trade, trades/day, avg R), an equity curve built by cumulating `net_pnl` over the run's trades ordered by entry time, and a slice bar chart for `dist_to_trend_ema_atr` showing conditional win% per bucket against the baseline.

- [ ] **Step 1: Write the failing test**

Create `dashboard/frontend/src/components/Overview.test.tsx`:

```tsx
import { render, screen, waitFor } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import Overview from "./Overview";
import * as api from "../api";
import type { Run, TradeSummary } from "../types";

const run = {
  id: "run-1",
  label: "emaCeil_3,0atr",
  win_rate: 0.882,
  net_avg_pnl: 0.85,
  net_avg_pnl_usd: 0.85,
  trades_per_day: 0.3,
  avg_r: 0.1,
} as unknown as Run;

const trades = [
  { entry_time: "2025-01-01T10:00:00Z", net_pnl: 1 },
  { entry_time: "2025-01-02T10:00:00Z", net_pnl: -2 },
  { entry_time: "2025-01-03T10:00:00Z", net_pnl: 3 },
] as unknown as TradeSummary[];

beforeEach(() => {
  vi.spyOn(api, "getTrades").mockResolvedValue(trades);
  vi.spyOn(api, "getSlices").mockResolvedValue({
    baseline_win: 0.5,
    trades: 8,
    buckets: [
      { lo: 0.1, hi: 1.0, count: 4, win_pct: 1.0, net_avg_pnl: 1 },
      { lo: 4.0, hi: 6.0, count: 4, win_pct: 0.0, net_avg_pnl: -2 },
    ],
  });
});
afterEach(() => vi.restoreAllMocks());

describe("Overview", () => {
  it("shows the headline KPIs", async () => {
    render(<Overview run={run} />);
    expect(screen.getByText("88%")).toBeInTheDocument();
    expect(screen.getByText("+0.85")).toBeInTheDocument();
    expect(screen.getByText("0.3")).toBeInTheDocument();
  });

  it("plots an equity curve with one point per trade", async () => {
    render(<Overview run={run} />);
    await waitFor(() => expect(screen.getByTestId("equity-curve")).toBeInTheDocument());
    const points = screen.getByTestId("equity-curve").getAttribute("points") ?? "";
    expect(points.trim().split(/\s+/)).toHaveLength(3);
  });

  it("renders one bar per slice bucket", async () => {
    render(<Overview run={run} />);
    await waitFor(() => expect(screen.getAllByTestId("slice-bar")).toHaveLength(2));
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd dashboard && npm run test:ui`
Expected: FAIL — cannot resolve `./Overview`.

- [ ] **Step 3: Create `dashboard/frontend/src/components/Overview.tsx`**

```tsx
import { useEffect, useState } from "react";
import * as api from "../api";
import type { Run, Slices, TradeSummary } from "../types";

const W = 320;
const H = 90;

function Kpi({ label, value }: { label: string; value: string }) {
  return (
    <div className="kpi">
      <span>{label}</span>
      <b>{value}</b>
    </div>
  );
}

export default function Overview({ run }: { run: Run }) {
  const [trades, setTrades] = useState<TradeSummary[]>([]);
  const [slices, setSlices] = useState<Slices | null>(null);

  useEffect(() => {
    let live = true;
    api.getTrades(run.id, "all").then((rows) => live && setTrades(rows));
    api
      .getSlices(run.id, "dist_to_trend_ema_atr", 4)
      .then((s) => live && setSlices(s))
      .catch(() => live && setSlices(null));
    return () => {
      live = false;
    };
  }, [run.id]);

  // Equity curve: cumulative net pnl in entry order.
  const ordered = [...trades].sort((a, b) => a.entry_time.localeCompare(b.entry_time));
  let cum = 0;
  const cumulative = ordered.map((t) => (cum += t.net_pnl));
  const lo = Math.min(0, ...cumulative);
  const hi = Math.max(0, ...cumulative);
  const points = cumulative
    .map((v, i) => {
      const x = (i / Math.max(1, cumulative.length - 1)) * W;
      const y = H - ((v - lo) / (hi - lo || 1)) * H;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");

  const pct = (v: number | null) => (v === null ? "—" : `${Math.round(v * 100)}%`);
  const signed = (v: number | null) => (v === null ? "—" : `${v >= 0 ? "+" : ""}${v.toFixed(2)}`);

  return (
    <section className="overview">
      <h2>{run.label ?? run.id}</h2>

      <div className="kpis">
        <Kpi label="win rate" value={pct(run.win_rate)} />
        <Kpi label="net pts/trade" value={signed(run.net_avg_pnl)} />
        <Kpi label="net $/trade" value={signed(run.net_avg_pnl_usd)} />
        <Kpi label="trades/day" value={run.trades_per_day?.toFixed(1) ?? "—"} />
        <Kpi label="avg R" value={signed(run.avg_r)} />
        <Kpi label="trades" value={String(run.trades_count ?? trades.length)} />
      </div>

      <h3>Equity (cumulative net pts)</h3>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="equity curve">
        <polyline data-testid="equity-curve" points={points} className="equity" fill="none" />
      </svg>

      <h3>Win% by distance to trend EMA</h3>
      {slices && slices.buckets.length > 0 ? (
        <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="win rate by bucket">
          <line
            x1={0}
            x2={W}
            y1={H - slices.baseline_win * H}
            y2={H - slices.baseline_win * H}
            className="baseline"
          />
          {slices.buckets.map((b, i) => {
            const bw = W / slices.buckets.length;
            const h = b.win_pct * H;
            return (
              <rect
                key={i}
                data-testid="slice-bar"
                x={i * bw + 4}
                width={bw - 8}
                y={H - h}
                height={h}
                className={b.win_pct >= slices.baseline_win ? "bucket good" : "bucket bad"}
              />
            );
          })}
        </svg>
      ) : (
        <p className="empty">No slice data.</p>
      )}
    </section>
  );
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd dashboard && npm run test:ui`
Expected: PASS (28 tests).

- [ ] **Step 5: Commit**

```bash
git add dashboard/frontend/src/components/Overview.tsx dashboard/frontend/src/components/Overview.test.tsx
git commit -m "feat(ui): Overview — KPI tiles, equity curve, EMA-distance slices"
```

---

### Task 12: Wire the shell, style it, deploy

**Files:**
- Modify: `dashboard/frontend/src/App.tsx`
- Modify: `dashboard/frontend/src/App.test.tsx`
- Create: `dashboard/frontend/src/styles.css`
- Modify: `dashboard/frontend/src/main.tsx`
- Modify: `dashboard/DEPLOY.md`

**Interfaces:**
- Produces: the finished app — run picker, both tabs wired to real data, annotations shared across tabs.

> **Load the `frontend-design` skill before Step 5** (visual pass) and the `dataviz` skill for the KPI/equity/slice styling. The CSS below is a working baseline to iterate on, not the final look.

- [ ] **Step 1: Extend the shell test**

Replace the contents of `dashboard/frontend/src/App.test.tsx` with:

```tsx
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import App from "./App";
import * as api from "./api";
import type { Run } from "./types";

const runs = [
  { id: "run-1", label: "emaCeil_3,0atr", win_rate: 0.88, net_avg_pnl: 0.85 } as unknown as Run,
  { id: "run-0", label: "older", win_rate: 0.5, net_avg_pnl: -0.1 } as unknown as Run,
];

beforeEach(() => {
  vi.spyOn(api, "getRuns").mockResolvedValue(runs);
  vi.spyOn(api, "getTrades").mockResolvedValue([]);
  vi.spyOn(api, "getFeedback").mockResolvedValue([]);
  vi.spyOn(api, "getMarks").mockResolvedValue([]);
  vi.spyOn(api, "getSlices").mockResolvedValue({ baseline_win: 0, trades: 0, buckets: [] });
});
afterEach(() => vi.restoreAllMocks());

describe("App shell", () => {
  it("shows both tabs with Trades active by default", async () => {
    render(<App />);
    expect(screen.getByRole("tab", { name: /trades/i })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: /overview/i })).toHaveAttribute("aria-selected", "false");
  });

  it("selects the newest run and lets you switch runs", async () => {
    render(<App />);
    await waitFor(() => expect(screen.getByLabelText(/run/i)).toHaveValue("run-1"));
    await userEvent.selectOptions(screen.getByLabelText(/run/i), "run-0");
    expect(screen.getByLabelText(/run/i)).toHaveValue("run-0");
  });

  it("switches to Overview when its tab is tapped", async () => {
    render(<App />);
    await userEvent.click(screen.getByRole("tab", { name: /overview/i }));
    expect(screen.getByRole("tab", { name: /overview/i })).toHaveAttribute("aria-selected", "true");
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd dashboard && npm run test:ui`
Expected: FAIL — no run picker in `App`.

- [ ] **Step 3: Rewrite `dashboard/frontend/src/App.tsx`**

```tsx
import { useEffect, useState } from "react";
import * as api from "./api";
import type { Run } from "./types";
import { useAnnotations } from "./useAnnotations";
import Overview from "./components/Overview";
import TradeDeck from "./components/TradeDeck";

export type Tab = "trades" | "overview";

export default function App() {
  const [tab, setTab] = useState<Tab>("trades");
  const [runs, setRuns] = useState<Run[]>([]);
  const [runId, setRunId] = useState<string>("");
  const { flags, marks, setFlag, toggleMark, error } = useAnnotations();

  useEffect(() => {
    let live = true;
    api.getRuns().then((rows) => {
      if (!live) return;
      setRuns(rows);
      if (rows.length > 0) setRunId(rows[0].id); // API returns newest first
    });
    return () => {
      live = false;
    };
  }, []);

  const run = runs.find((r) => r.id === runId);

  return (
    <div className="app">
      <header className="app-head">
        <label>
          Run
          <select value={runId} onChange={(e) => setRunId(e.target.value)}>
            {runs.map((r) => (
              <option key={r.id} value={r.id}>
                {r.label ?? r.id}
              </option>
            ))}
          </select>
        </label>
      </header>

      {error && <p className="error">{error}</p>}

      <main className="panel">
        {!run ? (
          <p className="loading">Loading runs…</p>
        ) : tab === "trades" ? (
          <TradeDeck
            runId={run.id}
            flags={flags}
            marks={marks}
            onFlag={setFlag}
            onToggleMark={toggleMark}
          />
        ) : (
          <Overview run={run} />
        )}
      </main>

      <nav className="tabbar" role="tablist">
        <button role="tab" aria-selected={tab === "overview"} onClick={() => setTab("overview")}>
          Overview
        </button>
        <button role="tab" aria-selected={tab === "trades"} onClick={() => setTab("trades")}>
          Trades
        </button>
      </nav>
    </div>
  );
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd dashboard && npm run test:ui`
Expected: PASS (29 tests).

- [ ] **Step 5: Create `dashboard/frontend/src/styles.css` and import it**

Baseline styling — dark, thumb-reachable, safe-area aware. Iterate with the `frontend-design` skill.

```css
:root {
  --bg: #0f1218;
  --panel: #171b23;
  --line: #39404e;
  --text: #e6e9ef;
  --dim: #8b93a1;
  --up: #4fa87a;
  --down: #d1614a;
  --accent: #4c8fd6;
  color-scheme: dark;
}

* { box-sizing: border-box; }

body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font: 15px/1.45 system-ui, -apple-system, sans-serif;
}

.app { display: flex; flex-direction: column; min-height: 100dvh; }
.app-head { padding: 10px 12px; border-bottom: 1px solid var(--line); }
.panel { flex: 1; padding: 12px; padding-bottom: 76px; }

select {
  background: var(--panel);
  color: var(--text);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 6px 8px;
  margin-left: 8px;
}

.kpis { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.kpi { background: var(--panel); border-radius: 10px; padding: 8px 10px; }
.kpi span { display: block; font-size: 10px; text-transform: uppercase; letter-spacing: .05em; color: var(--dim); }
.kpi b { font-size: 18px; }

.card { background: var(--panel); border-radius: 12px; padding: 10px; }
.card-head { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 6px; }
.win { color: var(--up); }
.loss { color: var(--down); }

.wick { stroke: var(--dim); stroke-width: 1; }
.body.up { fill: var(--up); }
.body.down { fill: var(--down); }
.level { stroke-dasharray: 3 3; stroke-width: 1; }
.level.stop { stroke: var(--down); }
.level.target { stroke: var(--up); }
.entry { fill: var(--accent); stroke: #fff; }
.exit { fill: #fff; stroke: var(--accent); }
.mark { stroke: var(--accent); stroke-width: 1.5; stroke-dasharray: 2 3; }
.mark-label { fill: var(--accent); font-size: 8px; }
.equity { stroke: var(--up); stroke-width: 2; }
.baseline { stroke: var(--dim); stroke-dasharray: 3 3; }
.bucket.good { fill: var(--up); }
.bucket.bad { fill: var(--down); }

.facts { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; margin: 8px 0 0; font-size: 11px; }
.facts dt { color: var(--dim); text-transform: uppercase; font-size: 9px; }
.facts dd { margin: 0; }

.chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.chips button {
  background: transparent;
  color: var(--dim);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 8px 12px;
  font-size: 12px;
}
.chips.kinds button[aria-pressed="true"] { background: var(--accent); border-color: var(--accent); color: #fff; }
.chips.flags button[aria-pressed="true"] { background: var(--down); border-color: var(--down); color: #fff; }

.zoom { display: flex; gap: 6px; margin-top: 8px; }
.zoom button {
  flex: 1; background: transparent; color: var(--dim);
  border: 1px solid var(--line); border-radius: 8px; padding: 6px;
}
.zoom button[aria-pressed="true"] { background: var(--panel); color: var(--text); }

.deck-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; font-size: 13px; }
.deck-nav { display: flex; gap: 8px; margin-top: 10px; }
.deck-nav button { flex: 1; font-size: 20px; padding: 10px; background: var(--panel); color: var(--text); border: 1px solid var(--line); border-radius: 10px; }

.tabbar {
  position: fixed; left: 0; right: 0; bottom: 0;
  display: flex; background: var(--panel); border-top: 1px solid var(--line);
  padding-bottom: env(safe-area-inset-bottom);
}
.tabbar button { flex: 1; background: none; border: 0; color: var(--dim); padding: 14px 0; font-size: 13px; }
.tabbar button[aria-selected="true"] { color: var(--text); box-shadow: inset 0 2px 0 var(--accent); }

.error { background: var(--down); color: #fff; padding: 8px 12px; margin: 0; font-size: 13px; }
.empty, .loading { color: var(--dim); }
```

Add the import at the top of `dashboard/frontend/src/main.tsx`:

```tsx
import "./styles.css";
```

- [ ] **Step 6: Full verification**

```bash
cd dashboard
npm test          # API + UI suites, all green
npm run typecheck # both tsconfigs clean
npm run build     # emits into ../public
```

- [ ] **Step 7: Manual check against local data**

```bash
cd dashboard
npx wrangler dev          # terminal 1 — serves /api from local D1
npm run dev:ui            # terminal 2 — Vite on :5173, /api proxied to :8787
```
Open the Vite URL, switch to a phone-sized viewport, and confirm: the run picker lists the pushed run; the deck shows a trade with stop/target lines; Focus/Full changes the candle count; tapping a kind chip then a candle drops a marker; a flag chip highlights. Then `npm run pull` and confirm the mark and flag appear in `experiments/marks.json` and `experiments/feedback.json`.

- [ ] **Step 8: Deploy and verify behind Access**

```bash
cd dashboard
npm run deploy
curl -sS -o /dev/null -w "%{http_code}\n" https://axe-trader-dashboard.g3tech.workers.dev/
```
Expected: `302` (Access still gating). Then open the URL on the phone, log in with the email PIN, and repeat the Step 7 checks against remote data. Finish with `npm run pull -- --remote` and confirm both files contain what you entered on the phone.

- [ ] **Step 9: Update `dashboard/DEPLOY.md`**

Under "What's here", add the frontend to the tree (`frontend/` — Vite app, builds into `public/`) and document the new scripts: `npm run dev:ui`, `npm run build`, `npm run test:ui`, and that `npm run deploy` now builds first.

- [ ] **Step 10: Commit**

```bash
git add dashboard/frontend dashboard/DEPLOY.md
git commit -m "feat(ui): wire run picker + tabs, baseline styling, deploy"
```

---

## Self-Review

**Spec coverage:**
- Two tabs, Trades default, run picker → Tasks 5, 12. ✅
- Swipe deck, one trade per screen, all/losers/winners filter → Task 10. ✅
- Focus/Full toggle, no pinch-zoom → Tasks 7, 8. ✅
- Fixed 6-flag taxonomy, one per trade, server-validated → Tasks 1, 10. ✅
- Marks: 5 kinds, one per kind, bar-timestamp keyed, tap-to-place/move/delete → Tasks 2, 3, 8, 9, 10. ✅
- `marks` table + `GET/POST/DELETE /api/marks` → Tasks 2, 3. ✅
- Lazy bar loading with neighbour prefetch → Task 10. ✅
- Optimistic writes with revert → Task 9. ✅
- KPI tiles, equity curve, slices → Task 11. ✅
- `experiments/marks.json` alongside feedback → Task 4. ✅
- Vite build into `public/`, served by existing ASSETS binding, no new Cloudflare resources → Tasks 5, 12. ✅
- Separate jsdom test project, Workers pool untouched → Task 5. ✅
- Out of scope (slice drill-down, pinch-zoom, mark notes, multi-flag) → absent by design. ✅

**Type consistency:** `Flag`/`MarkKind` from `shared/vocab.ts` are used unchanged in the Worker routes (Tasks 1, 3) and the frontend (Tasks 6, 9, 10). `Mark` fields (`signal_key`, `kind`, `bar_ts`, `created_at`) match the migration, `MarkRow`, the API responses, and the hook. `TradeCard` props (`trade`, `bars`, `marks`, `zoom`, `onZoomChange`, `onBarTap`) match its call site in `TradeDeck`. `makeScales` takes `{ count, min, max, width, height }` in both its definition and every use.

**Known follow-ups (deliberately not in this plan):** drill-down from an Overview slice into a filtered deck; a second slice feature selector; validating the Access JWT inside the Worker.
