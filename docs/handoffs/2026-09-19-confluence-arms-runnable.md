---
date: 2026-09-19
status: open
branch: feat/confluence-exit-matrix
head: b0d7cf9
next: Decide whether the 51-config matrix runs as specced before building Part 2 — the symmetric 1:1 arm lost 20x more than the wide-brake arms on the first real runs
---

# The engine produces trades, and the 1:1 exit is the worst of the four

Part 1 of the exit-matrix plan is complete: the confluence entry and all four exit arms are built,
tested and runnable end to end. **This is the first time a strategy has run through the Python
simulator** — every prior result in this project was layer-1 diagnosis with no trades at all.

The first real runs contradict the hypothesis that motivated the work, which is why this handover
exists at a decision point rather than at the end of Part 2.

## Where this stands

**Done — Part 1, all 12 tasks, 130 tests passing, branch pushed.**

| task | module | what it adds |
|---|---|---|
| 1 | `engine/indicators.py` | `wilder_mma`, `true_range`, `atr`, `highest`, `lowest` |
| 2 | `engine/indicators.py` | `plus_di`, `minus_di`, `adx`, `up_trend`, `down_trend` |
| 3 | `engine/candles.py` | the six ta4j candle patterns |
| 4 | `engine/pillars.py` | the 4-pillar vote, confluence score, `fire_rates` |
| 5 | `research/experiments/2026-09-19-confluence-layer1.py` | H-0005 |
| 6–9 | `engine/strategies/confluence.py` | frozen entry + E1–E4 |
| 10–11 | `engine/run.py` | R/% reporting, per-side split, `risk_measures` |
| 12 | `engine/run.py` | the CLI (the stub that raised `SystemExit` is gone) |

**Done — H-0005 (`signal present`), the first economically meaningful edge this project has
produced.** OIL_CRUDE SHORT at 15m: 222 entries, positive at all five horizons, percentile 100.0 at
the 4h horizon, edge **+0.1385 pts against a 0.0351 spread — 3.9 spreads**, where H-0002 managed
1.01 and H-0003 ceilinged at 1.55. Two caveats are recorded in the ledger and must travel with it:
Brent and WTI are not independent evidence, and the result does not clearly clear a Bonferroni bar
at 30 trials. **It is a lead to test, not a finding.**

**Done — the first real runs.** OIL_CRUDE, 15m, both sides, $2,000 at 1% risk:

| arm | trades | expectancy | net | max DD | mean hold | exits |
|---|---|---|---|---|---|---|
| **symmetric 1:1** | 482 | **−0.218R** | **−66.3%** | **68.6%** | 0.7h | TARGET 188 / STOP 294 |
| trailing | 456 | −0.036R | −15.1% | 15.4% | 2.2h | STOP 456 |
| time | 429 | −0.011R | −4.8% | 6.5% | 1.5h | SIGNAL 429 |
| reversal | 492 | −0.011R | −5.1% | 5.2% | 0.3h | SIGNAL 492 |

The time arm's SHORT side is **+0.006R** — the only positive cell, and it is OIL_CRUDE SHORT,
exactly where H-0005 found signal.

**Not started — Part 2:** the 51-configuration matrix (H-0006) and the trade-review page. Part 2 was
deliberately left unwritten until Part 1 landed, so it could be written against real APIs.

**In flight: nothing.** No half-finished edit. The session ends at a decision, not mid-task.

## Next action

**Ask the owner whether the matrix runs as specced, then write Part 2 of the plan.**

The spec's §4 matrix allocates 8 of its 17 configurations per instrument to the symmetric arm and
the three brake widths behind it. The first runs suggest that allocation is wrong: the 1:1 arm lost
**20× the expectancy and 13× the drawdown** of the wide-brake arms. Spending a third of the matrix
on it may be wasted, or may be exactly the point if the owner wants the comparison documented.

This is the owner's call because D7 (test the symmetric 1:1 variant) is an explicit owner decision,
and one run on one instrument is not grounds to overturn it unilaterally.

Once decided, write `docs/superpowers/plans/2026-09-19-confluence-exit-matrix-part2.md` covering:
the H-0006 ledger entry with pre-registered criteria **before** any run, the 51-config sweep plus
the placebo grid (spec §4.2, the one spec section Part 1 deliberately did not cover), the per-arm
and per-instrument comparison tables (§7.2), and the trade-review page (§7.3) republished to
`https://claude.ai/artifact/YTkPaQr27cT4njL7uEnjx9` by passing that URL as `url`.

## Verify current state

Observed at `b0d7cf9` on `feat/confluence-exit-matrix`, 2026-09-19 11:05, tree DIRTY:

```
cd research/engine && .venv/bin/pytest -q tests
# exit 0 — 130 passed in 11.17s
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

Reproduce any arm (outputs go to a directory of your choosing; the ones quoted above were written
to `/tmp` and are not preserved):

```
cd research/engine && PYTHONPATH=. .venv/bin/python -m engine.run \
  --db ../../data/axe-trader.sqlite --epic OIL_CRUDE --timeframe 15min \
  --arm time --brake-atr 10 --max-bars 6 --out /tmp/oil-time
```

**The branch is pushed** (`origin/feat/confluence-exit-matrix`, 0 ahead / 0 behind) and is **8
commits ahead of `origin/main`**. The owner switched from no-push to push-per-commit this session.

No lint command is documented for this project and none was run.

## Landmines

- **NEVER `git add -u` or `git add .` in this repo.** `data/axe-trader.sqlite.gz` is a tracked
  binary, permanently modified in the working tree, and **253 MB** against 31 MB committed. Using
  `git add -u` this session staged it into a commit; GitHub's pre-receive hook rejected the push and
  the commit had to be reset. Had the hook not existed it would be in history forever. **Stage
  explicit paths and run `git diff --cached --name-only` before every commit.**

- **A Python script that patches two files must write each to its own path variable.** A
  copy-paste of `p.write_text(t)` while `t` held the *spec's* content clobbered
  `research/ledger/INDEX.md` with the spec. Recovered with `git checkout HEAD -- <path>` because it
  was committed. **Add an `assert` on expected content before `write_text` when patching more than
  one file in a script.**

- **The confluence fires on ~0.6% of bars, so the timeframe decides whether it is testable at all.**
  At 4h (~4,100 bars in the window) it yields **11–25 entries per instrument** — the first H-0005
  run was `inconclusive` for that reason alone. At 15m it yields 214–356 per side. The cost ranking
  says trade slower; the entry's rarity says it needs many bars. The binding pillar is **RSI+BB at
  ~4%**.

- **`inconclusive` is not `rejected`, and the distinction has now been got wrong twice.** H-0004's
  criterion fused a layer-2 and a layer-0 question; H-0005's verdict logic printed
  `rejected: no signal` when every cell was under the entry floor and therefore untested. Layer 0
  precedes layer 1 — **an untested cell cannot be rejected.** Both fixed; expect the trap again.

- **Strategies must be passed to `check_lookahead` as a factory, never an instance.** Indicators are
  precomputed over the whole frame in `__init__`, so an instance's captured arrays are never
  truncated and an instance-based check silently passes a leak.

- **A test that skips is not a test that passes.** Three strategy tests silently skipped because a
  3-of-4 confluence essentially never fires on a short synthetic frame, so brake placement, target
  symmetry and the time stop went unverified. They now use `PillarConfig(confluence_threshold=1)`
  and `pytest.fail` rather than `pytest.skip`.

- **`tests/` has no `__init__.py`,** so pytest imports test modules top-level and
  `from tests.test_x import y` does not resolve. Shared helpers go in `tests/conftest.py`
  (`make_bar_frame` is there).

- **ta4j fidelity traps, all replicated deliberately:** `lowest`/`highest` include the current bar,
  so pillar 3 votes at every new extreme (measured 27–46%, elevated but not degenerate); ATR uses
  Wilder seeding on the *first value*, not the mean of the first *n*; and `HammerIndicator` /
  `ShootingStarIndicator` are **not shape-only** — both gate on an ADX trend filter (period 5,
  threshold 25, comparing ±DI at the previous bar).

- **`mvn package` / `./mvnw test` rewrite tracked chart files** (`output/charts/chart.html`,
  `runner-results.html`). Expected, not a regression. Restore with `git checkout -- output/charts/`.

- **NATURALGAS is excluded from research permanently** — `research/EXCLUDED-INSTRUMENTS.md`. Read it
  before adding any instrument to a grid or candidate list.

- **`research/engine/.venv` is gitignored and does not survive a worktree removal.** Also: a git
  worktree is the *wrong* isolation for this repo, because `data/axe-trader.sqlite` (4 GB,
  gitignored) would not exist in it and no experiment could run. Use a branch in place.

## Open questions

- **Does the matrix run as specced?** See Next action. The symmetric arm's first result argues for
  re-weighting; D7 argues for keeping it. Owner's call.
- **Slippage is still 0**, so every cost figure is a floor. The wide-brake arms exit almost entirely
  via `STOP`, which is exactly where slippage bites hardest, so this understates them most.
- **Weekend financing multiplier still defaults to 1.0.** The brake arms hold 1.5–2.2h on average so
  weekend carry is rare here, but it remains wrong for anything longer.
- **The 253 MB `sqlite.gz`**: leave untracked/gitignored, use LFS, or keep a smaller committed
  snapshot. Unresolved across four sessions.
- **When to merge `feat/confluence-exit-matrix` into `main`.** 8 commits ahead; the owner has
  committed directly to main all project history until now.
- **The published review page is stale.** It still shows the 19-row cost ranking including
  NATURALGAS; the live table is 15 rows. It should be republished with Part 2's results.

## Files that matter

- `docs/superpowers/specs/2026-09-19-confluence-exit-matrix-design.md` — the design of record,
  including D1–D10 and the §4 matrix whose weighting is now in question.
- `docs/superpowers/plans/2026-09-19-confluence-exit-matrix.md` — Part 1, fully executed; its
  closing section states what Part 2 must cover.
- `research/ledger/INDEX.md` — read first for research state. Five hypotheses, 323 trials.
- `research/ledger/H-0005-confluence-entry-signal.md` — both runs, the 4h `inconclusive` and the
  15m signal, with the multiple-testing caveats.
- `engine/pillars.py` — the frozen entry. **Do not re-tune**; the freeze is what makes exit
  differences attributable.
- `engine/strategies/confluence.py` — `ConfluenceBase` plus E1–E4; §4.1 of the spec is the contract.
- `engine/run.py` — R/% reporting, `risk_measures`, `ARMS`, `build_strategy`, the CLI.
- `engine/candles.py` — the ta4j port, including the ADX dependency.
- `research/EXCLUDED-INSTRUMENTS.md` — read before adding an instrument anywhere.
- `TODO.md` — `⭐ SESSION STATE` is current as of the *previous* handover, not this one.
