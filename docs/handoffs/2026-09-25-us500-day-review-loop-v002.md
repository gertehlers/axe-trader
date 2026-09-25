---
date: 2026-09-25
status: landed
landed: f909ba2
branch: feat/confluence-exit-matrix
head: 74cede0
next: Owner grades the v002 exit on the day page and accepts or rejects v002; if accepted, build the next-day first pass for 2024-01-12
---

# US500 day-review loop works end to end; iteration 001 produced v002

## Where this stands

The owner dropped oil and chose to "change the question". Work follows
`docs/superpowers/plans/2026-09-25-us500-day-review-loop.md`: the owner's own text, plus §13 with
the decisions from this session. The loop is: review one US500 day → grade entries and exits, mark
missed moves → Claude answers from the engine's record → one agreed change → rerun the same day →
before/after.

**Done (Milestone 1, all steps):**
- **Engine** (`research/engine`):
  - per-bar decision trace (`simulate(..., trace=)`);
  - `engine.explain.explain_bar`, which quotes the votes, the raw readings and what blocked an entry;
  - NYSE calendar (`engine.cash_session`);
  - fifth exit arm `confluence` (`OppositeConfluenceExit`): leave when the opposite side reaches
    3 votes, with an optional `target_atr`.
- **Simulator fix** `5d80834`: intents used to fill inside the signal bar, at label + 1 minute. They
  now fill at the bar's close, and stops are resolved before the decision on each bar. The ledger
  `research/ledger/INDEX.md` has a note on which old figures carried the early fill.
- **Strategy versions** are immutable files: `research/review/strategies/v001.yaml` (baseline) and
  `v002.yaml` (+ a 3 ATR take-profit on the confluence exit, the owner's change #1).
- **Run records**, append-only, with the id derived from all inputs: `research/review/runs/<run_id>/`.
  `engine.dayrun` refuses any day from the reserved period (US500 2026-08-01 onward).
- **Day-review page** https://claude.ai/artifact/UhUZuY6FgUGCHwY18s3WEp (source
  `research/review/day-review.html`, data from `build_day.py`). Storage (artifact `db`):
  - `feedback/` and `marks/` hold the owner's grades and marks, each with append-only `*_revisions/`;
  - `explanations/` holds Claude's answers;
  - `requests/` holds "please grade" flags.
- **Session 2024-01-11, iteration 001:**
  - The owner's 4 grades and 7 marks are exported with their revisions to
    `research/review/feedback/2024-01-11/iteration-001/`, with `FINDINGS.md`.
  - v002 vs v001 on the same day: both exits the owner graded bad changed; day total −1.05 → +0.55 R;
    0 of 6 marked moves caught (no entry change).
  - The regression check passes: v001 rerun on today's engine equals the reviewed run.
- **Dev-history context** (to 2026-07-31), confluence exit, not yet recorded in any file: 5m
  −0.078 → −0.032 R/trade; 15m −0.040 → −0.038. Still negative after costs.

**In flight:** nothing half-edited. A "please grade" request for the v002 exit is waiting for the
owner on the page.

**Not started:** Milestone 3 tooling (freeze a version, next-day first pass, reruns of earlier days),
Milestone 2 watched indicators (Supertrend, MACD, VWAP, …), replay mode. Cleanup (§11) is deferred.

## Next action

1. Read the owner's v002 exit grade from the page:
   `ArtifactData query feedback where session == 2024-01-11`, keys
   `us500-5min-v002-confluence-2024-01-11-4b45553a25--…`. Export it to
   `research/review/feedback/2024-01-11/iteration-002/`, answer it in `explanations/`, and ask
   the owner to accept or reject v002.
2. If accepted: build the next-day first pass. Run the frozen version unchanged on 2024-01-12, save
   that result before looking at any feedback, publish it, then run the review loop. Needs a
   `--day 2024-01-12` build plus a way to rerun previously reviewed days for regressions (plan §2B).

## Verify current state

Observed at `74cede0`, 2026-09-25 09:13, tree dirty with the pre-existing files only:

```
cd research/engine && .venv/bin/pytest -q tests
# exit 0 — 190 passed in 14.67s

mvn -B -DskipTests package
# exit 0 — BUILD SUCCESS

git status --short
#  M data/axe-trader.sqlite.gz
#  M output/charts/chart.html
#  M output/charts/runner-results.html
# ?? data/axe-trader.sqlite.pre-consolidation-backup
# ?? research/review-spike/
# ?? scripts/
```

Pushed (0 unpushed). Rebuild the current page data (gitignored `research/review/day-review-data.js`):

```
cd research/engine && PYTHONPATH=. ./.venv/bin/python ../review/build_day.py --day 2024-01-11 \
  --strategy v002 --compare v001 --feedback ../review/feedback/2024-01-11/iteration-001
```

Then republish `research/review/day-review.html` with
`files: {"day-review-data.js": "<abs path>"}`, passing the page URL above.

## Landmines

- **Run ids change with every engine commit, by design.** They hash the engine commit, so any engine
  change gives v001 new run ids even when its trades are identical. The owner's grades carry the
  *reviewed* run id; `build_day.py` maps them by trade-id suffix (`T20240111T2025-LONG`) and a
  regression check confirms the rerun equals the reviewed `trades.json`. **Commit engine changes
  before building runs**, or the records say `engine.dirty: true`.
- **`engine.run` used to load the reserved period.** Earlier this session, full-history runs loaded
  US500 through 2026-09-17 and aggregate numbers were printed. It now excludes 2026-08-01 onward
  unless `--include-reserved` is passed. Whether the reserved start moves is the owner's open call.
- **Unit tests with 1-minute signal bars cannot see timing bugs.** The early-fill bug hid behind
  them because label and close coincide at 1 minute. `tests/test_simulator.py` now has 5-minute cases.
- **A trade carried in from the previous day** is part of the review day. Under a child version it
  may exit *before* the day; `compare_payload` finds it in `DayRun.all_trades` and reports
  `exit_changed`, not `removed`.
- **The page must be published with its data file:** `files: {"day-review-data.js": ...}`. Without
  it the page renders blank.
- **Live db updates re-render the panel**; `state.editing` stops that wiping a half-entered grade.
  Keep that guard when editing the page.
- **US500 "volume" is Capital.com tick activity** (price updates per minute), not exchange volume.
  It feeds the Vol+Trend pillar; the page labels it.
- Still true from earlier: **never `git add -u` / `git add .`** (the 253 MB `sqlite.gz` is modified in
  the tree). `mvn package` rewrites `output/charts/*`, which is expected.

## Open questions

- **Accept or reject v002** once the owner has graded its exit.
- **Reserved period:** keep 2026-08-01 onward (seen only in aggregate, never used to choose a rule),
  or treat it as used and reserve only data imported from now on.
- **The 5m/15m agreement hypothesis** (owner, iteration 001) is logged in `FINDINGS.md` as a
  candidate *entry* change for a later version. When to test it is the owner's call.
- Carried over: when to merge `feat/confluence-exit-matrix` into `main`; what to do with the 253 MB
  `sqlite.gz`.

## Files that matter

- `docs/superpowers/plans/2026-09-25-us500-day-review-loop.md` — the plan; §13 holds the decisions.
- `research/engine/engine/simulator.py` — fill timing fix and decision trace.
- `research/engine/engine/explain.py` — `explain_bar`, the only source of explanations.
- `research/engine/engine/dayrun.py` — run records, review window, reserved-period guard.
- `research/engine/engine/compare.py` — trade matching, catch tolerance, scorecard.
- `research/engine/engine/strategies/confluence.py` — `OppositeConfluenceExit` (+ `target_atr`).
- `research/review/build_day.py` — builds runs and page data, `--compare` and `--feedback`.
- `research/review/day-review.html` — the review page; `README.md` documents its storage.
- `research/review/strategies/v001.yaml`, `v002.yaml` — immutable versions.
- `research/review/feedback/2024-01-11/iteration-001/FINDINGS.md` — what the first review established.
- `research/ledger/INDEX.md` — the engine-correction note at the end.
- `TODO.md` — `⭐ SESSION STATE — 2026-09-25`.
