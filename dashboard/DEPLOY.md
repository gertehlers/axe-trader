# Trade-review dashboard — local usage & Cloudflare hookup

Plan 1 (data pipeline) is built and verified **locally**. The Cloudflare provisioning steps at the
bottom are deliberately **not run** — do them when you're ready to put it online.

## What's here

```
dashboard/
├── migrations/0001_init.sql   # runs, trades, feedback (feedback.signal_key UNIQUE)
├── src/index.ts               # Hono app, mounts everything under /api
├── src/routes/                # runs, trades, slices, feedback
├── src/schema.ts              # D1 row types
├── scripts/sql.ts             # buildPushSql(run.json) -> SQL text (pure, unit-tested)
├── scripts/push-run.ts        # run.json -> D1     (`npm run push`)
├── scripts/pull-feedback.ts   # D1 -> experiments/feedback.json (`npm run pull`)
└── test/                      # vitest + @cloudflare/vitest-pool-workers (15 tests)
```

API: `GET /api/health`, `/api/runs`, `/api/runs/:id`, `/api/runs/:id/trades?filter=all|losers|winners`,
`/api/trades/:id` (includes `bars_json`), `/api/runs/:id/slices?feature=<col>&buckets=N`,
`GET|POST /api/feedback`.

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
npm run migrate:local
npm run push            # add -- --remote once Cloudflare is wired up

# 3. Serve the API locally
npm run dev             # http://localhost:8787/api/runs

# 4. Bring feedback back to the laptop
npm run pull            # writes ../experiments/feedback.json (gitignored)

# tests / typecheck
npm test
npm run typecheck
```

Note: with a multi-config sweep grid, `-Dsweep.exportDashboard=true` writes one
`dashboard/run.json` per config to the same path — **the last config in the grid wins**. Narrow the
grid (or export one config at a time) when you care which run lands.

## Cloudflare hookup (not done — your call when to run it)

1. `npx wrangler d1 create axe-trader-dashboard` → copy the printed `database_id` into
   `wrangler.jsonc`, replacing `PLACEHOLDER_SET_AT_PROVISION_TIME`.
2. `npm run migrate:remote` — applies `0001_init.sql` to remote D1.
3. `npm run deploy` — prints the `*.workers.dev` URL. Sanity: `curl <url>/api/health` → `{"ok":true}`.
4. `npm run push -- --remote`, then `curl <url>/api/runs` to confirm the run landed.
5. **Gate it with Cloudflare Access** before anything real is on it: add a self-hosted Access
   application covering the Worker hostname, policy = allow only your email (one-time PIN).
   Verify: `curl -sS -o /dev/null -w "%{http_code}" <url>/api/runs` returns 302/403 rather than 200.
6. Day-to-day after that: sweep with `-Dsweep.exportDashboard=true` → `npm run push -- --remote` →
   review on the phone → `npm run pull -- --remote`.

`public/` holds only a `.gitkeep`; Plan 2 (the phone UI) fills it and it is served via the `ASSETS`
binding already declared in `wrangler.jsonc`.

## Toolchain notes

- Types come from wrangler-generated `worker-configuration.d.ts` (`npx wrangler types`), which
  supersedes `@cloudflare/workers-types`. Re-run it after changing `wrangler.jsonc`.
- `@cloudflare/vitest-pool-workers` is pinned to `0.18.4`: 0.18.5/0.18.6 declare dependencies on
  miniflare builds that aren't on npm. Its v4 config API is the `cloudflareTest()` Vite plugin —
  `defineWorkersConfig` and the `/config` subpath are gone.
