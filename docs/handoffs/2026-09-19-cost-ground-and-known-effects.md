---
date: 2026-09-19
status: superseded
superseded_by: docs/handoffs/2026-09-19-confluence-arms-runnable.md
branch: main
head: aa7f0e6
next: Run known-effect B — Brent-WTI spread mean reversion — starting with whether daily spread changes even exceed the 0.0765-pt two-leg cost
---

# The search was aimed at the wrong ground, and now it is measured

> **Superseded 2026-09-19 by `2026-09-19-confluence-arms-runnable.md`.** Its next action — the
> Brent–WTI kill test — was **not** performed. The owner redirected the session to the strategy and
> review-page path instead: seeing entries and exits on a chart was the priority, not another
> hypothesis. Brent–WTI remains a live, untouched candidate in
> `research/sources/known-effects.md` (entry B) and the 0.0765-pt two-leg cost figure in it is
> still the right first measurement. Everything else below is accurate as recorded.

Two hypotheses were closed and two things were built that had never existed: the spec's own
cost-reality ranking, and a hypothesis source that is not a variation on the archived Java config.
The headline is not that more ideas died — it is **why** they died, which is now a number rather
than a suspicion.

## Where this stands

**Done — H-0003, the continuation family's one permitted follow-up (`rejected: no signal`).**
H-0002 died `uneconomic` at +0.565 pts/hour against a 0.561-pt spread; spec §6.2.3 layer 2 permits
one follow-up on a longer horizon, on the theory that spread is paid once however long you hold.
The same 96-cell grid at 120m and 240m, with a placebo computed **at each horizon** (H-0002's raw
120m column had none, and US500 drifts upward, so a longer horizon inflates any long-side mean for
free):

| horizon | family edge, fires ≥ 500 | in spreads |
|---|---|---|
| 60m (parent) | +0.565 | 1.01 |
| 120m | +0.870 | 1.55 |
| 240m | +0.864 | 1.54 |

It improves once and flatlines — a one-off repricing, not a drift. **The family is closed.**
Cumulative trial count 289.

**Done — the cost reality check (spec §6.1 step 1, never run before).** Ranks every instrument ×
timeframe held by the share of a typical bar's true range one round trip consumes:

    OIL_CRUDE 1d   2.4%   <- cheapest
    US500     1d   3.0%
    US500     4h   3.5%
    ...
    US500     5m  20.7%   <- rank 13 of 19; H-0001, H-0002 and H-0003 were all tested here
    OIL_BRENT 5m  45.0%
    NATGAS    5m  50.0%

Spread is near-constant in points (US500: 0.559 at 5m, 0.548 at 4h) while median true range goes
2.70 → 21.40, so the timeframe choice is almost the entire hurdle. Moving US500 from 5m to 4h is a
six-fold cost improvement requiring no new idea. Full write-up: `research/cost-reality/2026-09-19.md`.

**Done — `research/sources/known-effects.md` (spec §6.2.1 source 1, never started).** Five
candidates, each with its economic reason, its **losing side**, the power available in our window,
and a confidence label. Only effects testable at 4h or slower are listed live.

**Done — H-0004, the first hypothesis from that source (`inconclusive`, layer 0).** Trial count
starts at 4, not 289. The US500 overnight premium is real and large — **+3.173 pts/session
overnight against +0.974 intraday**, so 77% of the daily return accrues while the cash market is
shut — and **financing takes 46% of it** (gross 3.173 against costs of 2.005). The +1.168 net
remainder has a standard deviation of 36.6 and needs **7,689 sessions (~31 years)** at 80% power;
we have 664. Parked: the parked condition is a longer history, not a better idea.

One result is statistically solid and it is a prohibition: **holding US500 short overnight nets
−3.774 with a CI entirely below zero** (−6.488 … −0.979), positive in only 2 of 11 quarters. Carry
it as a risk control.

**Done — the research-state review page, published and kept in git.**
https://claude.ai/artifact/YTkPaQr27cT4njL7uEnjx9 — "Four Dead Hypotheses". Source at
`research/review/research-state.html`; `research/review/README.md` records how to republish it and
what it deliberately is not.

**Not started:** spec steps 3–6 proper — the §5.2 trade-review page, ledger gates G1–G3, the rest of
the research program, path to money. **No hypothesis has reached G1**, and the 2026-08-01 holdout
has never been touched.

**In flight: nothing.** The session ends at a clean decision point, not mid-task.

## Next action

**Run known-effect B — Brent–WTI spread mean reversion** (`research/sources/known-effects.md`
entry B). It is the top live candidate now that A is parked, and it is the only one using two
instruments we hold at minute resolution, so it is close to market-neutral — index drift cannot
contaminate it, which is exactly what flattered H-0003's raw columns.

**Do the cheap kill first.** Before building any strategy: measure the distribution of the daily
Brent−WTI spread change and what fraction of it exceeds the **0.0765-pt** two-leg round-trip cost
(0.0351 OIL_CRUDE + 0.0414 OIL_BRENT). If little of it does, the idea is dead in an hour and
nothing further is spent. Write the ledger entry with its pass/fail criteria **before** the run, as
H-0003 and H-0004 both were.

Watch the two series' minute grids not aligning: resample both to the same clock grid and **drop**
any bar missing from either, never forward-fill — a forward fill manufactures false dislocations,
which is precisely the signal being measured.

## Verify current state

Observed at `aa7f0e6` on `main`, 2026-09-19 09:54, tree DIRTY:

```
cd research/engine && .venv/bin/pytest -q tests
# exit 0 — 85 passed in 14.23s
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
# ?? research/review-spike/
# ?? scripts/
```

**22 commits are unpushed.** The owner's standing choice this session and last: commit per task, do
not push. No lint command is documented for this project and none was run.

## Landmines

- **`data/axe-trader.sqlite.gz` is a tracked binary that is now 253 MB in the working tree against
  31 MB committed** (`git diff --stat` confirms `31880705 -> 253417095 bytes`). It has never been
  staged in any commit across two sessions and must not be. **Check `git diff --cached --name-only`
  before every commit** — committing it puts a quarter-gigabyte blob in history permanently.

- **Filtering resampled bars on `complete` silently corrupts any cost or financing measurement.**
  A bar is `complete` only with every nominal minute present, which excludes every end-of-session
  bar — and those are exactly the bars spanning the 21:00 UTC financing cut-off. The first cost
  reality run therefore reported financing as **zero everywhere**, and deleted every daily bar as
  well, since no trading day has 1440 minutes. Filter on `minutes_present >= 0.5 * median` instead.

- **Session-based analysis must use the exchange clock, not UTC.** H-0004 defines the US cash
  session on `America/New_York`. `instruments.yaml` records opening hours in **one DST state only**
  (the fetch date's), so a hardcoded UTC hour silently shifts the session by an hour twice a year.

- **Check that a decomposition telescopes before trusting it.** H-0004's overnight and intraday legs
  must sum to the index's actual move or the session marks are misaligned; they did (2,754 vs 2,702
  pts, 4,774 → 7,476), and the residual is dropped stub sessions plus the window edges. This check
  costs one command and is the only thing standing between a subtle marks bug and a published number.

- **A pre-registered criterion that fuses two diagnosis layers cannot assign a status.** H-0004's
  criterion 1 combined "does the move cover costs?" (layer 2) with "can this sample resolve it?"
  (layer 0) into one pass/fail and mapped the failure to `uneconomic` — which the data contradicts,
  since layer 2 passes. Write layer-0 and layer-2 checks as **separate** criteria. The thresholds
  were not moved; only the wrong label was corrected, and the correction is recorded in the ledger
  and in `INDEX.md`'s threshold-changes section.

- **`research/engine/.venv` is gitignored and does not survive a worktree removal.** Rebuild with
  `cd research/engine && python3 -m venv .venv && .venv/bin/python -m pip install -e ".[dev]"`.

- **Strategies with precomputed state must be passed to `check_lookahead` as a factory, not an
  instance**, or the guard silently passes them — a captured whole-series array is never truncated.

- **Use `diagnose.shifted_placebo_entries`, not independent draws.** RSI extremes cluster inside
  selloffs, so independent placebos overstate significance badly.

- **`mvn package` / `./mvnw test` rewrite tracked chart files** (`output/charts/chart.html`,
  `runner-results.html`). Expected, not a regression. Restore with `git checkout -- output/charts/`.

- **NATURALGAS fails data quality at 16.2% missing core and re-importing cannot fix it** — the gaps
  are illiquidity, and Capital.com returns `error.prices.not-found` on re-request. Usable only
  ~11:00–19:00 UTC, and the cost check shows it is never the cheapest ground at any timeframe.

- **Do not blindly restart the 12-instrument seed.** Budget ~5h per instrument, most of it
  re-requesting permanently-empty minute gaps. GOLD's staging file in `data/.staging/` is resumable.

## Open questions

- **Whether to push.** 22 unpushed commits. `CLAUDE.md`'s tuning workflow says an unpushed commit is
  as fragile as an uncommitted one, but the owner has chosen no-push for two sessions running. This
  is the one open question worth raising early.
- **Weekend financing multiplier.** Still undocumented. `costs.financing_usd` defaults it to 1.0,
  understating weekend carry. H-0004 sidestepped it by counting every 21:00 UTC cut-off actually
  crossed, which is closer to right; the default remains wrong for anything using it.
- **Slippage defaults to 0.** Every cost number in `research/cost-reality/2026-09-19.md` is therefore
  a **floor**, and the floor is tightest at short timeframes. Needs an owner decision.
- **The 242/253 MB `sqlite.gz`**: leave untracked/gitignored, use LFS, or keep a smaller committed
  snapshot. Unresolved across three sessions now.
- **`research/review-spike/` and `scripts/` are still untracked.** The spike proved the candle and
  comment mechanism; its future is decided by whether a strategy ever earns a §5.2 trade page.

## Files that matter

- `research/ledger/INDEX.md` — hypothesis and trial counts; read first for research state.
- `research/ledger/H-0003-continuation-longer-horizon.md` — why the continuation family is closed.
- `research/ledger/H-0004-us500-overnight-premium.md` — the overnight split, and the status-label
  correction in full.
- `research/cost-reality/2026-09-19.md` — the 19-row ranking and what it implies for aiming.
- `research/sources/known-effects.md` — the five candidates and the recommended order; **the next
  action comes from entry B**.
- `research/review/README.md` + `research-state.html` — the published page and how to republish it.
- `research/experiments/2026-09-19-*.py` + `.json` — the three runs and their full outputs.
- `docs/superpowers/specs/2026-09-17-research-restart-design.md` — the plan of record.
- `TODO.md` — `⭐ SESSION STATE` at the top is current as of this handover.
