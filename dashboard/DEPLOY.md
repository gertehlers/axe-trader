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

## Cloudflare status

Done: D1 created, `0001_init.sql` applied remotely, Worker deployed, `/api/*` verified live.

**Outstanding — Cloudflare Access (do this before pushing real runs).** The workers.dev URL is
public until it's gated. It's a dashboard click, not a CLI step:

1. Cloudflare dashboard → **Workers & Pages** → `axe-trader-dashboard` → **Settings** →
   **Domains & Routes**.
2. On the `workers.dev` row, click **Enable Cloudflare Access**, then **Manage Cloudflare Access**
   to restrict the policy to your email (one-time PIN).
3. Verify: `curl -sS -o /dev/null -w "%{http_code}" https://axe-trader-dashboard.g3tech.workers.dev/api/runs`
   returns 302/403 instead of 200.

Access gates the request at the edge. If you later want defence in depth, the Worker can also
validate the `Cf-Access-Jwt-Assertion` JWT — not done, and not required while the policy is on.

## Day-to-day loop (once Access is on)

Sweep with `-Dsweep.exportDashboard=true` → `npm run push -- --remote` → review on the phone →
`npm run pull -- --remote`.

`public/` holds only a `.gitkeep`; Plan 2 (the phone UI) fills it and it is served via the `ASSETS`
binding already declared in `wrangler.jsonc`.

## Toolchain notes

- Types come from wrangler-generated `worker-configuration.d.ts` (`npx wrangler types`), which
  supersedes `@cloudflare/workers-types`. Re-run it after changing `wrangler.jsonc`.
- `@cloudflare/vitest-pool-workers` is pinned to `0.18.4`: 0.18.5/0.18.6 declare dependencies on
  miniflare builds that aren't on npm. Its v4 config API is the `cloudflareTest()` Vite plugin —
  `defineWorkersConfig` and the `/config` subpath are gone.
