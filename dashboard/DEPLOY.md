# Trade-review dashboard — local usage & Cloudflare hookup

Deployed 2026-07-20 (free tier):

- Worker: <https://axe-trader-dashboard.g3tech.workers.dev>
- D1: `axe-trader-dashboard`, id `fd847584-8fd9-427e-b869-9423a6c5b419`, region WEUR
- Account: gert.ehlers@gmail.com (`d0ff79cadf8d88376b187161b8385648`)

Free-tier headroom: D1 allows 10 databases / 5 GB and 100k row writes/day (one push is ~103 rows);
Workers free is 100k requests/day; Zero Trust free covers 50 users. Nothing here approaches those.

## What's here

```
dashboard/
├── migrations/0001_init.sql   # runs, trades, feedback (feedback.signal_key UNIQUE)
├── migrations/0002_marks.sql  # marks (per-bar T1/T2/... annotations)
├── src/index.ts               # Hono app, mounts everything under /api
├── src/routes/                # runs, trades, slices, feedback, marks
├── src/schema.ts              # D1 row types
├── shared/vocab.ts            # flag/mark vocabularies — imported by BOTH worker and UI
├── frontend/                  # the phone UI (React + Vite); builds into public/
├── scripts/sql.ts             # buildPushSql(run.json) -> SQL text (pure, unit-tested)
├── scripts/push-run.ts        # run.json -> D1     (`npm run push`)
├── scripts/fix-net-pnl.sql    # one-off repair for runs exported before 2cd6944
├── scripts/pull-feedback.ts   # D1 -> experiments/feedback.json (`npm run pull`)
└── test/                      # vitest + @cloudflare/vitest-pool-workers
```

API: `GET /api/health`, `/api/runs`, `/api/runs/:id`, `/api/runs/:id/trades?filter=all|losers|winners`,
`/api/trades/:id` (includes `bars_json`), `/api/runs/:id/slices?feature=<col>&buckets=N`,
`GET|POST /api/feedback`, `GET|POST|DELETE /api/marks`.

`signal_key` is exactly `instrument|entryTsIso|direction` (e.g. `US500|2025-03-04T14:30:00Z|LONG`).
Feedback is keyed on it, so a verdict entered on the phone re-attaches to the same signal after the
backtest is re-run with new trade ids.

## Local loop (works today, no Cloudflare account needed)

```bash
# 1. Export a run from the engine (repo root). Writes dashboard/run.json.
gzip -dc data/axe-trader.sqlite.gz > data/axe-trader.sqlite   # fresh container only
./mvnw test -Dtest=ConfluenceSweepTest -Dsweep=true -Dsweep.exportDashboard=true

# 2. Load it into local D1 (miniflare state under dashboard/.wrangler/)
cd dashboard
npm install
npm run migrate:local   # applies EVERY pending migration, incl. 0002_marks.sql
npm run push            # add -- --remote to target production

# 3. Serve the whole app locally (builds the frontend first, then serves API + UI)
npm run dev             # http://localhost:8787  — UI; /api/runs for the API

# 4. Bring feedback back to the laptop
npm run pull            # writes ../experiments/feedback.json AND ../experiments/marks.json (both gitignored)

# tests / typecheck
npm test
npm run typecheck
```

Note: with a multi-config sweep grid, `-Dsweep.exportDashboard=true` writes one
`dashboard/run.json` per config to the same path — **the last config in the grid wins**. Narrow the
grid (or export one config at a time) when you care which run lands.

## Cloudflare status

**Deployed (Plan 1 API only):** D1 created, `0001_init.sql` applied remotely, Worker deployed,
`/api/*` verified live.

**Cloudflare Access: ON.** Verified 2026-07-21 — `/`, `/api/health`, `/api/runs` and `/api/marks`
all return `302` to the Access login. (Enable/manage it under Cloudflare dashboard → **Workers &
Pages** → `axe-trader-dashboard` → **Settings** → **Domains & Routes**, on the `workers.dev` row.)

**Phone UI deployed 2026-07-21**, version `640a185f-9b98-4ba9-9b6c-30bd2dab9773`. All migrations
are applied remotely (`wrangler d1 migrations list --remote` → "No migrations to apply").

Production data as of 2026-07-21: **one run, `US500-1784664611692`** (`emaCeil_3,0atr`, 102 trades,
win rate 88%, net +0.85/trade, max drawdown 83.54, worst quarter −46.27). `trades.net_pnl` is
genuinely net and reconciles with `runs.net_avg_pnl` exactly. The earlier run
`US500-1784554730790` was repaired, superseded by the re-export, and deleted.

⚠️ **Access is the only thing gating writes.** `POST /api/feedback`, `POST /api/marks` and
`DELETE /api/marks` have no in-Worker authentication — `src/index.ts` mounts every route with no
middleware. Access gates per-hostname at the edge, so **adding a custom domain or route later
silently un-gates the entire write surface**. Defence in depth (validating the
`Cf-Access-Jwt-Assertion` JWT in the Worker) is deliberately not done; revisit it before any
second hostname is added.

## Day-to-day loop

Sweep with `-Dsweep.exportDashboard=true` → `npm run push -- --remote` → review on the phone →
`npm run pull -- --remote`.

Run `npm run migrate:remote` after any new migration lands, before the next push. It is safe to
re-run — D1 tracks which migrations have been applied and skips them.

`public/` holds only a tracked `.gitkeep`; everything else in it is gitignored build output. The
frontend build fills it and it is served via the `ASSETS` binding declared in `wrangler.jsonc`.
Both `npm run dev` and `npm run deploy` build first, so the directory is never served empty.

## Toolchain notes

- Types come from wrangler-generated `worker-configuration.d.ts` (`npx wrangler types`), which
  supersedes `@cloudflare/workers-types`. Re-run it after changing `wrangler.jsonc`.
- `@cloudflare/vitest-pool-workers` is pinned to `0.18.4`: 0.18.5/0.18.6 declare dependencies on
  miniflare builds that aren't on npm. Its v4 config API is the `cloudflareTest()` Vite plugin —
  `defineWorkersConfig` and the `/config` subpath are gone.
