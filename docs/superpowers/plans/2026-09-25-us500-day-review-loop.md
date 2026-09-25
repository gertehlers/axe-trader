# Plan: day-by-day review loop on US500, grade → explain → re-run → compare

## Context

**Where we are (checked 2026-09-25, `fba7afc`, `feat/confluence-exit-matrix`, 1 commit after the handover, known dirty files only):**
- The research engine (`research/engine`, Python, 141 tests) is the live backend. It runs strategies with honest costs and feeds the review pages. The ledger has 9 hypotheses and 392 variants, and **none is tradeable**. The last four ran on oil, which you now call a mistake.
- The review pages let you mark bars, but:
  1. they only show windows where the strategy entered, so a missed run can't be marked;
  2. marks sit in browser storage and I copy them in by hand;
  3. nothing turns a mark into a changed backtest, or shows whether the change worked;
  4. **the page doesn't show why the strategy acted**: which indicators were checked, their values, and which pillars voted.
- The handover's open decision (buy daily history, or change the question) is answered: **change the question.** US500, day by day, small steps, with a loop between us.
- Much of the repo is dead: the pre-restart Java strategy and backtest code, the Cloudflare `dashboard/`, spike pages, stale files.

**Goal:** you review one trading day of strategy entries and exits and grade them. For each one you see why the strategy acted. On your grades and marks, I tell you which indicators I checked and what they read. We agree a change, re-run the same day, and you see whether your feedback took effect. Then the next day, and later several days at once.

## 1. What the strategy looks at today (and what it doesn't)

The confluence strategy is 4 voting pillars plus a trend gate (`research/engine/engine/pillars.py`). An entry fires when ≥ 3 pillars agree and the trend gate allows that side:

| pillar | indicators, as coded today |
|---|---|
| RSI+BB | RSI(7) < 25 **and** close ≤ lower Bollinger(20, 2σ). Mirrored for short: > 75 and ≥ upper band |
| Candle | bullish/bearish engulfing, harami, hammer, shooting star |
| S/R | close within 0.5 × ATR(14) of the lowest/highest **close** of the last 10 bars |
| Vol+Trend | volume > SMA(20) of volume **and** close above/below EMA(50) |
| trend gate | long only above EMA(200), short only below |

**Not covered today:** SMA on price, Supertrend, MACD, ADX/DI (coded in `indicators.py` but unused), VWAP (the main day-trading reference for US500), and the old "structure / break-of-structure" pillar (dropped in the port). **Exits use no indicators at all.** They are stop, trail, time or reversal rules from `strategies/confluence.py`. So an exit you grade "bad" can only be explained by its rule today.

**Proposal: two tiers of indicators, both computed on every bar and both shown in explanations.**
- **Voting:** the 4 pillars above, unchanged until a round changes them.
- **Watching (non-voting):** SMA(20/50), Supertrend(10, 3), MACD(12, 26, 9), ADX/DI(14), session VWAP, and prior-day high/low/close. They never trigger a trade. They are recorded at every entry, exit and mark, so when your "good" grades share a pattern (e.g. "all above VWAP with ADX > 25"), it shows up. A watched indicator that keeps explaining your grades gets promoted to a voting pillar in a later round. That promotion is one of the changes you approve.

## 2. The cycle (one round = one US500 session)

1. **Run** the strategy over one US500 cash session (13:30–20:00 UTC; the indicators warm up on earlier history), **on 5m and 15m side by side**, so the difference between the two timeframes is visible trade by trade.
2. **Review page** for that day: a full-session chart per timeframe, so a missed run can be marked anywhere, with entries and exits drawn on it.
   - **"Why" panel, uncluttered:** each entry/exit marker carries a compact row of pillar chips (lit = voted), the vote count against the threshold, and for an exit its rule ("trail hit at 1.8 ATR"). Clicking the marker expands the raw readings: voting indicators first, watched ones below.
   - **Grades:** entry and exit graded separately on 5 steps: good · ok · meh · poor · bad. **Ungraded = not reviewed.** Items I need you to check get a "please grade" flag, and I say so in chat.
   - Mark tools stay: click a bar for "better entry/exit here", drag for a missed run.
   - Grades and marks save to the page's **shared database** (`db` capability), not browser storage. They're durable, and I read them directly.
3. **Explain.** For every grade and mark, I publish back which indicators were checked at that bar, what they read, the vote count, and why the strategy did or didn't act. If nothing stood out, I say "nothing found". I also note which watched indicators agree with your grade.
4. **Agree one change** in plain words. I write it as the next variant file and log it (`TODO.md` plus the ledger, source `owner-comment`).
5. **Re-run the same day** with the new variant. The page shows before/after for every graded item (still taken? gone? moved?) and a small scorecard: bad entries removed, good kept, marked runs caught.
6. **Next day**, then several days per round once you're happy we read each other right. Later, when rounds are big enough, also measure on days you never looked at, after costs. That check is how the project decides "tradeable", so it's postponed, not dropped.

## 3. What gets built

- **A. Variant files and a round runner:** `research/rounds/rNN/variant.yaml` (pillar config, exit arm and parameters, timeframes) plus `research/review/export_round.py --day 2026-MM-DD`. It reuses:
  - `engine.run.build_strategy` (`research/engine/engine/run.py`);
  - `compute_pillars` / `PillarConfig` (`engine/pillars.py`);
  - `candle_window` / `trade_payloads` (`engine/review.py`).
  It runs 5m and 15m for that session and writes `round-data.js`.
- **B. Watched indicators:** add Supertrend, MACD and session VWAP to `engine/indicators.py`, each with tests next to the existing ones. SMA, ADX/DI and ATR already exist. Then a `watch_indicators(frame)` that returns a per-bar table.
- **C. `explain_bar(frame, votes, watch, bar, side)`** in `engine/review.py`. It works on **any** bar, and returns:
  - each pillar's vote with its readings;
  - count vs threshold and the gate state;
  - the exit rule state when a position was open;
  - the watched readings.

  Tests: on an existing entry bar it must reproduce `voting_pillars`.
- **D. Day review page:** `research/review/day-review.html`. Port the canvas and annotation layer from `trade-review.html` and add:
  - the 5m | 15m panes;
  - the chip row and expandable why-panel;
  - the grade controls, backed by `db`;
  - the explanation panel;
  - the before/after strip and scorecard.

  It's one artifact, republished by URL each round, with its source kept in `research/review/`.
- **E. Ingest and compare:** `research/review/ingest_round.py`. Grades, pulled with `ArtifactData`, land in `research/rounds/rNN/marks.json` (committed). It runs `explain_bar` on each, and diffs round N against N−1 for the scorecard.

## 4. Cleanup (first, on its own commits)

**Delete (dead, pre-restart):**
- Java `backtest/`, `strategy/` and chart code, plus their tests;
- the Cloudflare `dashboard/`;
- `research/review-spike/`, top-level `experiments/`, `task-10-escalation-report.md`;
- tracked `output/charts/*`, and the stale `data/*.staging.sqlite` and `sqlite.pre-consolidation-backup` files;
- oil-only exports that nothing points at any more.

Old results stay reachable in git history and the ledger.

**Keep:**
- the Java **history importer** (`history/`, `brokers/capital`, `DatabaseBootstrap`, Flyway). It's the only thing that tops up `historical_price`. `AxeTraderRunner` gets trimmed to the import and monitor modes.
- the research engine;
- the ledger;
- the two current review pages, until `day-review` replaces `trade-review`.

**Database:**
- delete NATURALGAS rows (permanently excluded) and VACUUM;
- decide the 253 MB `sqlite.gz`: stop tracking it and keep a small US500-only snapshot, or move it to LFS;
- update `CLAUDE.md`, `TODO.md` and `docs/` so they describe the Python engine and the Java importer, not the old backtest.

Each deletion step ends with `mvn -B -DskipTests package` plus the engine pytest, both green. Stage explicit paths only, never `git add .`. Commit and push after each step.

## 5. Order of work

1. Cleanup (§4), in small commits.
2. B + C (indicators, `explain_bar`), test-first.
3. A + D: **round 1 = one US500 session, 5m vs 15m**, current confluence variant. Publish, then I check the page myself before handing it over.
4. You grade → E ingest → explanations published → agree change #1 → round 2 on the same day.
5. Next day. Grow the round size when you say so.

## Verification

- `mvn -B -DskipTests package` and `cd research/engine && .venv/bin/pytest -q tests` both green after every cleanup commit and feature step.
- New indicators match a reference computation in tests (Supertrend and MACD against hand-worked values; VWAP resets at session open).
- `explain_bar` reproduces the recorded pillar votes on known entries.
- Round trip: a grade entered on the page appears via `ArtifactData`, lands in `rounds/r01/marks.json`, and its explanation shows on the republished page.
- Round 2 on the same day changes exactly what the variant says, and the scorecard matches a hand count.
