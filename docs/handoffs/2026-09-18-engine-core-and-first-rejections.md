---
date: 2026-09-18
status: landed
landed: aa7f0e6
branch: main
head: 228ba76
next: Choose the next hypothesis source — H-0002 follow-up on a longer timeframe, the untested 4-pillar confluence, or spec 6.2.1 known-effect sourcing
---

# Research engine core built; the strategy premise falsified twice

The Python research engine (spec step 2) is built and tested, and it was immediately used to test
the two hypotheses that mattered. Both failed. That is the substance of this session: the project
now has a working measuring instrument and two fewer live ideas.

## Where this stands

**Done — engine core (spec §3), 85 tests passing, all committed:**

| module | responsibility |
|---|---|
| `cache.py` | parquet cache of minute bars, keyed on row count + newest snapshot; applies `price_exclusion` at load |
| `bars.py` | UTC-clock-grid resampling; incomplete bars kept and flagged |
| `strategy.py` | `Strategy` protocol, `Enter`/`Exit`/`MoveStop`, lookahead-safe `BarsView` |
| `lookahead.py` | truncation guard; **exceeds spec** (see Landmines) |
| `sizing.py` | Decimal-floored sizing, min-deal-size skip |
| `costs.py` | daily-cut-off financing, USD conversion |
| `simulator.py` | 1-minute bid/ask exit loop |
| `run.py` | net expectancy, bootstrap CI, drawdown, parquet + `summary.json` |
| `indicators.py` | Wilder RSI, population-stdev Bollinger, EMA |
| `diagnose.py` | diagnosis layers 0 (power) and 1 (signal), two placebo methods |

Full US500 run: 958,849 minutes → 195,428 bars → 16,366 trades in **4.3s** against a 120s budget.
The numba dependency spec §3.4 anticipates is therefore **not** needed; that is recorded in commit
`29dfde0` with the reasoning so it can be revisited.

**Done — data quality.** `research/data-quality/2026-09-18.json` committed. US500, OIL_BRENT,
OIL_CRUDE PASS (1.7–2.3% missing core). NATURALGAS FAILS at 16.2% (see Landmines).

**Done — two hypotheses, both rejected.** Write-ups in `research/ledger/`:

- **H-0001** (`rejected: no signal`) — the archived Java engine's entry, `RSI(7)<25 AND close<=BB
  lower AND close>EMA(200)`. Against random entries at matched times it is anti-predictive: −0.77
  pts at 60m, −1.37 at 120m, −2.62 at 240m, with *less* MFE and *more* MAE than placebo. The 88.2%
  win rate was geometry: a 3.0-ATR stop against a 0.75-ATR target wins ~80% of the time on a
  driftless walk.
- **H-0002** (`uneconomic`) — the inversion, tested as a 96-cell grid. 47 of 64 tested
  configurations positive, 40 on a plateau, so the direction is real. But the best config does not
  beat the placebo grid (+3.154 vs +3.582 at the 95th percentile), and the family edge among
  configurations with ≥500 fires is **+0.565 pts/hour against a 0.561-pt spread**. The whole edge
  is one spread wide.

**Not started:** spec steps 3–6 (review page proper, ledger gates G1–G3, research program, path to
money). The `research/ledger/` directory and INDEX exist but no hypothesis has reached G1.

**In flight: nothing.** No half-finished edit. The session ends at a decision point, not mid-task.

## Next action

Pick the next hypothesis source, because both closed paths came from the same exhausted one:

1. **Follow up H-0002 where spec §6.2.3 layer 2 permits** — a child hypothesis on a longer
   timeframe or a lower-cost instrument. The 120m column is already computed per configuration in
   `research/experiments/2026-09-18-us500-momentum-grid.json` and has not been analysed. Cheapest
   next step by a wide margin.
2. **Test the full 4-pillar confluence.** H-0001 does *not* transfer to it: `confluence-threshold:
   3` over four enabled pillars means pillar 1 need not fire, so the confluence's entry set is not a
   subset of H-0001's. It is formally untested. Needs candles, S/R and volume/trend implemented in
   Python.
3. **Mine spec §6.2.1 sources that have never been touched** — `known-effect` and
   `structured-brainstorm`. No `research/sources/known-effects.md` exists yet.

## Verify current state

Observed at `228ba76` on `main`, 2026-09-18 22:28, tree DIRTY:

```
cd research/engine && .venv/bin/pytest -q tests
# exit 0 — 85 passed in 13.82s
```

```
mvn -B -DskipTests package
# exit 0 — BUILD SUCCESS
```

```
git status --short
#  M data/axe-trader.sqlite.gz
#  M output/charts/chart.html
#  M output/charts/runner-results.html
# ?? data/axe-trader.sqlite.pre-consolidation-backup
# ?? docs/handoffs/2026-09-18-review-spike-and-consolidation.md
# ?? research/review-spike/
# ?? scripts/
```

```
sqlite3 -readonly data/axe-trader.sqlite "SELECT epic, COUNT(*) FROM historical_price GROUP BY epic;"
# NATURALGAS 801226 · OIL_BRENT 901618 · OIL_CRUDE 944105 · US500 958849
```

**17 commits are unpushed.** The owner chose commit-per-task, no push, this session.

No lint command is documented for this project and none was run.

## Landmines

- **`research/engine/.venv` does not survive a worktree removal and is gitignored.** The
  2026-09-17 handover recorded "18 passed" from a venv inside `.worktrees/delta-price-import`,
  which was deleted during consolidation. A fresh checkout has no venv and every engine command
  fails with "no such file or directory". Rebuild:
  `cd research/engine && python3 -m venv .venv && .venv/bin/python -m pip install -e ".[dev]"`.

- **The lookahead guard in spec §3.3 has a hole, and the implementation now exceeds the spec.**
  Re-running a single strategy *instance* on truncated data cannot catch a strategy that
  precomputed an indicator over the whole series in `__init__` — the captured array is never
  truncated, so both runs agree and the leak passes. `check_lookahead` also accepts a factory (a
  class or callable taking the frame) and rebuilds per truncation. **Strategies with precomputed
  state must be passed as a factory, not an instance**, or they are not actually being checked. A
  test documents the limit.

- **Independent placebo draws overstate significance.** RSI extremes cluster inside selloffs, so
  1,656 clustered entries carry far less independent information than 1,656 scattered ones. Use
  `diagnose.shifted_placebo_entries` (whole-week shifts of the entire entry set, preserving both
  clustering and weekday/hour). H-0001's 60m percentile moved from 0.0 to 6.2 under the stricter
  method — still a rejection, but the weak method flattered it, and it flattered the mirror rule
  much more (99.5 → 91.5).

- **NATURALGAS fails data quality at 16.2% missing core, and re-importing cannot fix it.** The
  shape proves illiquidity rather than a broken import: 30–41% missing 00:00–06:00 UTC against
  1.5–2.4% during US hours, which matches the instruments that pass. Usable only inside roughly
  11:00–19:00 UTC. Capital.com's gaps are permanent — re-requesting returns `error.prices.not-found`.

- **The 12-instrument seed was stopped deliberately and must not be blindly restarted.** NATURALGAS
  completed (801,226 rows, merged). GOLD was killed mid-staging; its staging file in
  `data/.staging/` is resumable. Each instrument costs ~5–6 hours, of which ~4.5 is re-requesting
  permanently-empty minute gaps: 87,130 refetch work units, 80,500 of which closed as
  `EMPTY_REFETCH`. Budget ~5h per instrument, not the ~25–80 min the older handover estimated.

- **`data/axe-trader.sqlite.gz` is a tracked 242 MB binary showing as modified.** It has never been
  staged in any commit this session and must not be — check `git diff --cached --name-only` before
  every commit. Committing it puts a quarter-gigabyte blob in history permanently.

- **`mvn package` / `./mvnw test` rewrite tracked chart files** (`output/charts/chart.html`,
  `runner-results.html`). That is why they show as modified above. Restore with
  `git checkout -- output/charts/` before committing. Expected, not a regression.

- **Layer 1 is deliberately cost-free and exit-free.** Do not "improve" it by adding spread or
  stops: spread is layer 2's question and exits are layer 3's. A signal with no edge before costs
  has none after them.

## Open questions

- **Weekend financing multiplier.** Capital.com charges a multi-day fee on one weekday to cover the
  weekend. The multiplier is not in `instruments.yaml` and is recorded nowhere in this repo, so
  `costs.financing_usd` takes it as a parameter defaulting to `1.0`, which **understates the cost of
  any position carried over a weekend**. Every run records the value used. Needs a documented value
  or an explicit decision to accept the understatement.
- **Slippage defaults to 0**, which spec §3.4 permits but which assumes perfect fills on market
  entries and stops. `instruments.yaml` has `min_step_distance`, which is the minimum stop distance,
  not a tick size — so there is no data source for a number. Needs an owner decision.
- **The 242 MB `sqlite.gz`**: leave untracked/gitignored, use LFS, or keep a smaller committed
  snapshot. Unresolved from the previous session.
- **`research/review-spike/` and `scripts/` are still untracked.** The review spike is published as
  an Artifact (v9) but exists in git nowhere.
- **Whether to push.** 17 unpushed commits; the owner chose no-push this session.

## Files that matter

- `research/ledger/INDEX.md` — the running hypothesis and trial count; read first for research state.
- `research/ledger/H-0001-rsi-bb-mean-reversion.md` — why the old entry is dead, with the numbers.
- `research/ledger/H-0002-short-horizon-continuation.md` — why its inversion is uneconomic.
- `research/engine/engine/diagnose.py` — layers 0 and 1, both placebo methods.
- `research/engine/engine/simulator.py` — the 1-minute bid/ask fill rules.
- `research/experiments/2026-09-18-us500-layer1.py` + `.json` — H-0001's run and results.
- `research/experiments/2026-09-18-us500-momentum-grid.py` + `.json` — H-0002's grid; the 120m
  column is computed and unanalysed.
- `research/data-quality/2026-09-18.json` — the four instruments' verdicts.
- `docs/superpowers/plans/2026-09-18-research-engine-core.md` — the plan just executed.
- `docs/superpowers/specs/2026-09-17-research-restart-design.md` — the plan of record.
- `TODO.md` — `⭐ SESSION STATE` at the top is current as of this handover.
