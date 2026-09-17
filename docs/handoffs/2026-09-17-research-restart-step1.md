---
date: 2026-09-17
status: open
branch: feature/research-restart
head: b25eaff
next: Re-seed NATURALGAS..ETHUSD with the fixed importer from this worktree, then run the full data-quality report
---

# Research restart — step 1 (importer fixes + data verification)

The owner decided on 2026-09-17 to restart strategy research from scratch. Read these first, in order:

1. `docs/superpowers/specs/2026-09-17-research-restart-design.md` — the approved plan (decisions D1–D9,
   build steps 1–6, hypothesis sourcing and diagnosis §6.2). Primary bar is **net expectancy after costs**;
   win rate is not a gate; nothing from before is trusted.
2. `docs/superpowers/plans/2026-09-17-importer-fixes.md` and
   `docs/superpowers/plans/2026-09-17-data-verification-report.md` — step 1's two plans.
3. `docs/learnings/2026-09-17-code-and-research-sweep.md` — informational sweep of the old code/research.

## Where this stands

**Done (all committed and pushed to `origin/feature/research-restart`):**
- Importer plan Tasks 1–8 (Java, `io.g3tech.axetrader.history`): Flyway V2 (one row per minute, canonical
  timestamps), neutral empty-interval provenance (`EMPTY_/NOT_FOUND_` × `BASE/REFETCH`), unknown-epic refusal,
  history-start probe, empty top-up = already current, 2-minute settle + 60-minute re-fetch + `price_revision`,
  401/403 re-auth once, resume/quarantine retained staging files. Task 9 docs done (`docs/local-price-history.md`).
- Data-verification plan Tasks 1–6 code (Python, `research/engine`): package + venv, `instruments.yaml`
  fetched from the demo API (15 instruments), SQLite loader, DST-safe core sessions, `verify_instrument`,
  `python -m engine.verify` CLI writing JSON.
- Owner decision on provider gaps ("accept + handle honestly") applied; see spec §2.2 revisions.
- Preliminary report on the 3 fully imported instruments: **US500, OIL_BRENT, OIL_CRUDE all PASS**
  (missing core 1.7–2.3%, holes >30 min 1.5–7.4/yr, early closes 1.5–8.1/yr, missing sessions 2.6–3.0/yr).
  Output was written to a temp file only — not committed.

**Not done:**
- Importer plan Task 9 steps 4–5 (real-API update run + unknown-epic check with the fixed importer).
- Data plan Task 6 steps 6–10: full 15-instrument run, commit `research/data-quality/<date>.json`,
  publish the Artifact report page.
- Price data for 12 instruments: NATURALGAS, GOLD, SILVER, US100, DE40, UK100, J225, EURUSD, GBPUSD,
  USDJPY, BTCUSD, ETHUSD. The seed queue ran the **old** importer and was **stopped** at 18:06 (see Landmines).
- Steps 2–6 of the spec (engine core, review page, ledger + gates, research program, path to money) — not started.

## Next action

1. From this worktree, seed each missing instrument with the **fixed** importer, one at a time (single SQLite
   writer), against the shared DB. Example for one epic:

       DB=/Users/gertehlers/Development/projects/axe-trader/.worktrees/delta-price-import/data
       ./mvnw -q spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication \
         "-Dspring-boot.run.arguments=--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=update --axe-trader.history-import.epic=NATURALGAS --axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2024-01-01T00:00:00Z --axe-trader.history-import.active-database=$DB/axe-trader.sqlite --axe-trader.history-import.archive=$DB/axe-trader.sqlite.gz --axe-trader.history-import.staging-directory=$DB/.staging"

   Expect ~25–80 min per instrument. The first run will quarantine the old-version staging files (Landmines).
2. Then run the report on all 15 and finish data plan Task 6 steps 6–10:

       cd research/engine && .venv/bin/python -m engine.verify --db $DB/axe-trader.sqlite --instruments instruments.yaml --out ../data-quality/$(date +%F).json

3. Then close step 1 (merge decision is the owner's) and start the design check for spec step 2 (engine core).

## Verify current state

Observed at `b25eaff` (tree clean, nothing unpushed) on 2026-09-17 18:05:

- `./mvnw test` → exit 0, `Tests run: 328, Failures: 0, Errors: 0, Skipped: 3`, BUILD SUCCESS.
  Requires `data/axe-trader.sqlite` decompressed in this worktree (it is, locally; gitignored).
- `research/engine/.venv/bin/pytest -q research/engine/tests` → `18 passed`.
- Shared DB row counts (`sqlite3 -readonly .../delta-price-import/data/axe-trader.sqlite "select epic,count(*) from historical_price group by 1"`):
  OIL_BRENT 901,618 · OIL_CRUDE 944,105 · US500 958,849 (2024-01-01 → 2026-09-17).

## Landmines

- **The seed queue was running the OLD importer** (code in `.worktrees/delta-price-import`, branch
  `feature/delta-price-import`). NATURALGAS failed after 25 min with `401 error.invalid.session.token` — the
  exact bug fixed in importer Task 7. The queue was killed while GOLD was staging. Do not re-run imports from
  that worktree.
- **Stale staging files** in `.worktrees/delta-price-import/data/.staging/`: `GOLD-…`, `NATURALGAS-…`,
  `US500-MINUTE-09cb3e1e…` (algorithm version 3) plus orphan `.stage.lock` files. The fixed importer moves
  version-3 files to `.staging/incompatible/` on the next update for that epic — expected, not an error.
- **The price DB lives in another worktree**: `.worktrees/delta-price-import/data/axe-trader.sqlite` (gitignored,
  per checkout). Running the importer from this worktree without the `active-database/archive/staging-directory`
  overrides writes to this worktree's small test DB instead.
- **`./mvnw test` needs `gzip -dc data/axe-trader.sqlite.gz > data/axe-trader.sqlite` in a fresh checkout**;
  without it 6 backtest tests fail with 0 bars, which looks like a regression but isn't.
- **`./mvnw test` rewrites the tracked `output/charts/runner-results.html`**. Restore it with
  `git checkout -- output/charts/runner-results.html` before committing.
- **Capital.com API accounts**: the REST API works only with a Capital.com platform account (not MT4/MT5).
  `CAPITAL_API_PASSWORD` is the API key's custom password. Demo API only (owner rule). `.env` is in the main
  checkout root; never print it.
- **Epic names**: UKOIL = `OIL_BRENT`, WTI = `OIL_CRUDE`; natural gas = `NATURALGAS`.
- **Capital.com gaps are permanent**: re-requesting flagged holes returns `error.prices.not-found`. ~2% of core
  minutes are missing in every hour; do not "fix" by re-import. Published hours ignore DST (US500 reopens 23:00
  UTC in winter, not 22:05; 21:05–22:00 has bars on only 411/848 days).
- **pandas is 3.x** (3.0.5 in the venv): datetime integer units are not nanoseconds; compare `Timedelta`s,
  never `asi8` arithmetic.
- **Sandbox**: this session refused `git` with `cd` into other worktrees, `xargs sed`, and shell pipelines it
  could not parse; use plain commands with absolute paths.
- **Old handovers**: `docs/handoffs/2026-08-08-research-agent-design.md` and
  `docs/superpowers/handoffs/2026-07-28-empirical-discovery-progress.md` were deleted on this branch; copies
  still exist on `main` and `feature/delta-price-import`, and `worktree-ma-cross-spike` has
  `docs/handoffs/2026-09-17-ma-cross-rsi-filter-spikes.md`. They describe superseded work — ignore them.

## Open questions

- Merge strategy for `feature/research-restart` (it contains all of `feature/delta-price-import`); the owner
  decides when and how it reaches `main`.
- Branch `worktree-sr-bounce-test` has 3 local-only docs commits (learnings, spec, plans) — already cherry-picked
  here; its push was blocked by a permission check. Owner can push or discard it.
- Deletion of the old Cloudflare dashboard Worker/D1 (spec §7) needs explicit owner approval when step 2 lands.

## Files that matter

- `docs/superpowers/specs/2026-09-17-research-restart-design.md` — the plan of record, with 2026-09-17 revisions.
- `docs/superpowers/plans/2026-09-17-importer-fixes.md` — importer tasks (Tasks 1–8 done, Task 9 steps 4–5 pending).
- `docs/superpowers/plans/2026-09-17-data-verification-report.md` — data tasks (Task 6 steps 6–10 pending).
- `docs/local-price-history.md` — importer operations and post-fix behaviour.
- `src/main/java/io/g3tech/axetrader/history/` — importer (UpdateService, StagingStore, StartProbe, DeltaMerger).
- `src/main/resources/db/migration/V2__history_integrity.sql` — deliberately excludes the ledger tables.
- `research/engine/engine/verify.py` — data-quality rules and thresholds (constants at top, rationale in comments).
- `research/engine/instruments.yaml` — generated specs for 15 instruments (fetched 2026-09-17; do not hand-edit).
- `docs/learnings/2026-09-17-code-and-research-sweep.md` — why old results are untrusted.
