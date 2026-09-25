---
date: 2026-09-19
status: closed
closed: 2026-09-25
resolution: Owner decided — change the question. Oil dropped; US500 day-by-day review loop per docs/superpowers/plans/2026-09-25-us500-day-review-loop.md
branch: feat/confluence-exit-matrix
head: 59b04e2
next: Decide between acquiring 20-30 years of daily history and accepting that these grounds are untradeable — no hypothesis should be run before that call
---

# Direction exists on oil, as mean reversion, and it is smaller than the spread

The session began at a decision about how to weight a 51-configuration exit matrix. **The matrix was
never built.** Four hypotheses (H-0006 … H-0009) ran instead, and between them they moved the
project's open question from "which exit?" to "is there any ground where a real edge exceeds its own
costs?"

Nothing here is tradeable. Two hypotheses were rejected on pre-registered criteria, one is
`inconclusive`, one is `uneconomic`. **That is the output**: the ledger is now a more honest map
than it was, and the next decision is about data, not ideas.

## Where this stands

**Done — the exit matrix was abandoned, on evidence.** H-0006 measured the OIL_CRUDE 15m entry's
excursion profile: median favourable **+1.67 ATR** against median adverse **+2.08 ATR**. The typical
trade must survive more than it stands to make, and **no exit rule fixes MAE > MFE**. The symmetric
1:1 arm's 36.1% win rate is geometry, not luck — a 1.0 ATR stop against a 2.08 ATR median adverse
excursion is hit before the move arrives by construction. The question the matrix was built to
answer dissolved.

**Done — four hypotheses, all recorded in `research/ledger/`:**

| id | title | status | what it settled |
|---|---|---|---|
| H-0006 | The confluence entry has the wrong sign on OIL_CRUDE | `inconclusive` | Placebo **failed** at percentile 6.2 vs a required 5.0. Fade line closed. Diagnostics survive: the entry is **not late** (fires *against* a completed ~1.1 ATR move by design) and the **chop is the instrument** (forward efficiency 0.21–0.24 vs 0.230 for any bar). |
| H-0007 | Fast runs are predictable in timing but not direction | `signal present` | Quiet + volume + session doubles the odds of a fast 5-ATR move and **holds into validation** (×1.66 → ×1.90). Direction is a **coin flip** (47.9% / 50.8% LONG). |
| H-0008 | A volatility-compression breakout converts that timing | `rejected: no signal` | **Pre-registered, then failed 3 of 4 criteria.** −0.0851R, CI crosses zero, placebo percentile **74.5** vs a required 95. The placebo also loses, so **breakouts on 15m oil are negative-expectancy regardless of the filter**. |
| H-0009 | RSI+Bollinger mean reversion on oil | `uneconomic` | Direction **is** available — as mean reversion, ~10× base rate, both samples. And it is **always inside the spread**: 0.094 ATR against a 0.484 ATR round trip. |

**Done — a correction to a claim made in this session.** After H-0008 the reading was "direction is
unavailable". H-0009 shows that is too broad: it is unavailable to **continuation** designs, which is
every momentum hypothesis in the ledger (H-0002, H-0003, H-0007, H-0008). The variance ratios in
`research/ground-selection/2026-09-19.md` say why — 53 of 60 cells sit below 1.0 and **nothing trends
anywhere**.

**Done — ground selection measured, not assumed.** `research/ground-selection/2026-09-19.md`:
60 instrument × timeframe × lag cells, **one significant, three expected by chance**. No ground shows
unconditional directional structure. The one qualitative pattern is mean reversion strengthening with
horizon on **daily** bars (VR(16) = 0.641 / 0.709 / 0.759) — the cheapest ground, and the one with
843–848 bars of power, which is none.

**Done — two review pages, both with the full annotation layer.**

| page | artifact | what it shows |
|---|---|---|
| `research/review/trade-review.html` | https://claude.ai/artifact/GrqdRANKK6AnzCGcgRyXiU | 506 entries, all four exit arms on one candle window, plus the MFE/MAE excursion lines |
| `research/review/signal-review.html` | https://claude.ai/artifact/WxLJfpFZzKhLSfVbyRLb9w | 400 randomly sampled H-0009 signals, each with a **break-even line** at entry ± one spread |

**The review loop produced a real hypothesis.** The owner marked 13 runs across 5 trades on the
trade-review page; three of five had the entry pointing the wrong way, and that became H-0006
(`source: owner-comment`) — the first hypothesis in this project raised by the owner's eye rather
than a spec. His marks are preserved in `research/marks/2026-09-19-owner-oil-runs.json`, because an
artifact comment thread is not storage.

**In flight: nothing.** No half-finished edit, no uncommitted work beyond the known dirty files.

## Next action

**Decide between two paths, and run no hypothesis until that call is made.**

1. **Acquire 20–30 years of daily history** for US500, OIL_CRUDE and OIL_BRENT. Daily data that far
   back is cheap and widely available, unlike intraday. It is the only thing that would convert the
   mean-reversion-at-horizon pattern into a hypothesis with power. The scoping work is concrete:
   which provider, what it costs, how it lands in `historical_price` alongside `DatabaseBootstrap`.
2. **Accept that these three instruments at these timeframes are untradeable by this family of
   methods**, and change the question — a different instrument class, or something other than a
   directional edge.

This is the owner's call because it is a spend-and-scope decision, not a technical one. **H-0004's
parked condition already recorded the same wall in different words: "31 years of data, not a better
idea."** The evidence has now arrived at it from four more directions.

Do **not** start by proposing another variant on the confluence entry. It has been examined at 50
cells down one parent chain, and `research/ledger/INDEX.md` now records 392 variants across 9
hypotheses with 0 reaching G1.

## Verify current state

Observed at `59b04e2` on `feat/confluence-exit-matrix`, 2026-09-19 22:50, tree DIRTY:

```
cd research/engine && .venv/bin/pytest -q tests
# exit 0 — 141 passed in 15.29s
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

The evidence file also listed `?? engine/` and `?? research/experiments/__pycache__/`. Both were
dealt with after it was taken — see the first landmine — so the status above is what a fresh
checkout should now show.

**The branch is pushed** (0 ahead / 0 behind at the time of the evidence run) and is **16 commits
ahead of `origin/main`**. Note: the fix and cleanup described in the landmines were committed
*after* the evidence file was written, so `head` will be ahead of `59b04e2` by one commit.

No lint command is documented for this project and none was run.

Reproduce the two live findings:

```
cd research/engine
PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-ground-selection.py
PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-rsi-bb-across-grounds.py
```

## Landmines

- **`research/review/export_trade_review.py` wrote its bar cache to the repo root.** It used
  `parents[2] / "engine"` where `parents[1]` is `research/`, so it silently built a **22 MB**
  duplicate cache at `<repo>/engine/.cache/` instead of reusing `research/engine/.cache/`. It still
  produced correct output, which is why it went unnoticed for hours. Fixed, the stray directory
  deleted, and `/engine/` added to `.gitignore` so a recurrence cannot be committed. **Check
  `parents[n]` against the actual path depth — a script that works is not a script whose paths are
  right.**

- **Two intrabar bugs in one strategy, both found by disbelieving a result rather than by a test.**
  H-0008's first run gave a 12.7% win rate because the **stop was checked against the entry bar's own
  15-minute low**, which includes price action from before the fill; a bar that breaks upward out of
  a range has its low back inside the range. Its second run was still wrong because **minutes were
  mapped to bars by dividing elapsed time by the bar width** — that assumes a contiguous series, and
  oil closes overnight and at weekends, so 19,636 validation bars sat in a span that would hold
  ~29,000 and minutes attached to the wrong bars entirely. **Before interpreting any intrabar
  result, verify the minute-to-bar mapping against the aggregated bars** (`bar.high_bid` must equal
  `max(minute.high_bid)`; mid-of-max vs max-of-mid mismatches are expected and harmless), **and never
  test a stop against a bar that contains the entry.**

- **Scoring indicators against future-located pivots is near-tautological.** H-0009's first pass
  showed a ×4.7 lift with 94% of runs going up for "close ≤ lower Bollinger band". An up-leg starts
  at a swing low *by definition*, and a swing low is where RSI is low and price is at the lower band
  — it rediscovers the definition of a pivot. **Forward returns are the honest measure**, and they
  were three to five times smaller. This nearly shipped as a headline.

- **NEVER `git add -u` or `git add .` in this repo.** `data/axe-trader.sqlite.gz` is a tracked binary,
  permanently modified in the working tree, **253 MB against 31 MB committed**. A previous session
  staged it; GitHub's pre-receive hook rejected the push. **Stage explicit paths and run
  `git diff --cached --name-only` before every commit.**

- **A near miss is a miss.** H-0006's placebo came back at percentile 6.2 against a pre-registered
  5.0, with the other two criteria passing. It is recorded as a failure and was **not re-run** to
  chase 4.9. H-0008 failed 3 of 4 and was closed **without a proposed variant**. Preserving this is
  the point of pre-registering; the temptation arrives exactly when a result is nearly good.

- **`mvn package` / `./mvnw test` rewrite tracked chart files** (`output/charts/chart.html`,
  `runner-results.html`). Expected, not a regression. Restore with `git checkout -- output/charts/`.

- **`research/engine/.venv` is gitignored and does not survive a worktree removal.** A git worktree is
  the *wrong* isolation here: `data/axe-trader.sqlite` (4 GB, gitignored) would not exist in it and no
  experiment could run. Use a branch in place.

- **NATURALGAS is excluded from research permanently** — `research/EXCLUDED-INSTRUMENTS.md`. Read it
  before adding any instrument to a grid.

## Open questions

- **Daily history, or change the question?** The Next action. Owner's call, and it is a spend
  decision.
- **When to merge `feat/confluence-exit-matrix` into `main`.** 16 commits ahead. The branch no longer
  matches its name — it contains the abandonment of the exit matrix and four unrelated hypotheses.
  The owner has committed directly to main for all project history until now.
- **The 253 MB `sqlite.gz`**: leave untracked, use LFS, or keep a smaller committed snapshot.
  Unresolved across five sessions.
- **Slippage is modelled only in H-0008** (10 ticks). Every other figure in the project still assumes
  zero, so every cost number outside H-0008 is a floor.
- **The trade-review page's coverage gap.** It only shows windows where the strategy entered, so a
  large move the strategy ignored entirely cannot be marked. "What are we missing?" needs a
  different export.
- **Spec §7.3 says to republish the trade review over the research-state artifact**
  (`YTkPaQr27cT4njL7uEnjx9`). It was published separately instead, since the two answer different
  questions. Folding them is still open.

## Files that matter

- `research/ledger/INDEX.md` — **read first.** Nine hypotheses, 392 variants, 0 reaching G1.
- `research/ledger/H-0006-…` / `H-0007-…` / `H-0008-…` / `H-0009-…` — this session's four, each with
  its pre-registration, result and what it does not establish.
- `research/ground-selection/2026-09-19.md` — the variance-ratio map. The argument for the Next
  action lives here.
- `research/cost-reality/2026-09-19.md` — the cost half of the same question; the two are meant to be
  read together.
- `research/marks/2026-09-19-owner-oil-runs.json` — the owner's 13 hand-drawn runs, the spec that
  calibrated H-0007's run definition.
- `research/review/trade-review.html`, `signal-review.html`, `README.md` — the two published pages
  and how to rebuild their (gitignored) data.
- `research/engine/engine/review.py` — the trade→review adapter, 11 tests.
- `docs/superpowers/specs/2026-09-19-compression-breakout-design.md` — H-0008's spec; the model for
  what a pre-registration should contain.
- `TODO.md` — `⭐ SESSION STATE` updated for this session.
