---
date: 2026-09-29
status: open
branch: feat/confluence-exit-matrix
head: 6774338
next: Process the owner's verbatim notes below - redo the review days at 3 ATR, then design an indicator-knob matrix to find slow-trend patterns
---

# Rebound watch on the review page; 3 ATR and 5m/15m agreement tested; owner wants an indicator matrix

## Owner's notes, verbatim (2026-09-29, end of session) — process these first

> i think it's too early to say that 3 atr failed. 5m and 15m agreement is not strong enough (it could
> still work for swing trading). It also seems like we need better pillars, or maybe more sensitive
> pillars. I want to redo the days with 3atr, and then a matrix of knobs need to be checked for patterns,
> im talking supertrend, 7 rsi, 14 rsi, sma 200 but also 50 adn 20 and whatever else makes sense, and
> this thinking across all the indicators. we are mising something (when it comes ot slow trends at
> least, the reversal we might be on to something already - maybe 3atr with a wide stop makes money).
> Im done for tonight, use handover skill, record these last notes verbatim to be processed tomorrow

Earlier in the same session, also verbatim, still in force:

> forget the 5 quality trade rule, we are way too early in the process to put such a hard rule. It could
> have a 1000 trades, i don't care as long as it makes money. Rather more and then we reduce with logic
> than this approach where we miss everything and end up in choppy water everytime.

> no i want to keep going like this for a while. it is quite clear that we need to let the exits run, im
> just not sure how far and what kind of defense would we need to put in place so it doesn't wipe the
> account

## Where this stands

**Done this session** (all committed and pushed; 0 unpushed per evidence):
- **Owner decision: no trade-count target.** Net expectancy after costs is the bar. Recorded in
  `CLAUDE.md` (Trading Goals, cadence bullet), `TODO.md`, plan §13, memory.
- **Rebound watch on the day-review page** (https://claude.ai/artifact/UhUZuY6FgUGCHwY18s3WEp,
  source `research/review/day-review.html`). It is computed in the page from the candles and never traded.
  - **Rule:** the close moved ≥ `drop` × ATR(14) over `look` bars, then `run` candles in a row the other
    way; it fires once per streak. Defaults are 2 / 12 / 3.
  - **Knobs** are saved per browser in localStorage. "Send these settings" writes `watch_settings/rebound`,
    which the owner never used.
  - **Feedback gestures:**
    - Click a ◆ → a popup with Good entry / Bad entry / Not sure plus a note.
    - Drag from a ◆ → an ideal exit, measured in pts, ATR and minutes from the signal close.
    - Both are stored in db `watch_feedback/<day>--<tf>--<bar ts>--<side>`, with `watch_feedback_revisions/`.
  - **Display:**
    - Teal ◆ means 5m and 15m agree (a same-side marker on the other timeframe closed within ±45 min).
    - There is an "Only 5m + 15m agree" toggle and a "Rebound v2" preset (3 ATR + agree).
    - There is a "Strategy trades" toggle.
    - The cursor is a pointer over clickable things.
- **Review days built:** 2024-01-11, 01-12, 01-16, 01-17, 01-18, 01-19. Each is a v002 first pass,
  committed before review (01-16..19 in `2622002`).
- **Owner feedback exported and analysed:**
  - `research/review/feedback/2024-01-12/rebound-001/FINDINGS.md`: cash-session signals are clean, and
    overnight ones needed 4–12 ATR of room.
  - A stop 0.25 ATR beyond the drop's extreme stops the overnight longs and lets the cash-session ones
    survive. That check is recorded in `TODO.md`.
  - `research/review/feedback/2024-01-16/rebound-001/`: raw export only.
  - `research/review/feedback/2024-01-17..19/rebound-001/FINDINGS.md`: the scored test plus exploration.
- **Frozen test:** `research/review/watch/rebound-v2.md`. The rules were frozen before 01-17..19 were
  reviewed, and the result is appended.
  - **5m markers on 01-17..19:**
    - drop ≥ 3 ATR kept 5 good / 2 unsure / 5 bad, and dropped 4 / 2 / 3. **I called this "failed". The
      owner disagrees: "too early"** (see notes above).
    - 5m + 15m agreement kept 3 / 0 / 1 and dropped 6 / 4 / 7. Over five days it is 7 / 1 / 1 on
      9 markers. The owner says it is "not strong enough (could still work for swing trading)".
    - v2 (both) fired once and was good.
  - **Exploration, in-sample:** the EMA(200) trend gate and streak size do not separate good from bad.
  - **Slow-trend missed moves:**
    - Pillars never reach 3 votes during them.
    - RSI+BB votes with the move on 0 bars.
    - Only Vol+Trend follows them, on ~60% of bars.

**In flight:** nothing half-edited.

**Not started:**
- The owner's asks above.
- Porting the rebound rule into `research/engine`, and any aggregate measurement over dev history (the
  owner deferred these: "keep going like this for a while").

## Next action

Process the verbatim notes, one step at a time, with the owner:

1. **"redo the days with 3atr"**
   - Most likely means: show the review days with the drop knob at 3 and have the owner re-judge / look
     again. The page already supports this: knob 3, agreement off.
   - Possibly also: measure 3 ATR entries with a **wide stop** (owner: "maybe 3atr with a wide stop makes
     money").
   - Confirm which he means before building. Existing verdicts at drop 2 already cover every 3 ATR marker
     on the judged days, since 3 ATR markers are a subset.
2. **"a matrix of knobs … for patterns"**
   - Indicators named: Supertrend, RSI 7, RSI 14, SMA 200 / 50 / 20, "and whatever else makes sense".
   - Purpose: find what identifies slow trends ("we are missing something") and make pillars more
     sensitive.
   - Design it spec-first (SDD) and show it visually on the page before any hypothesis is thrown out
     (memory: visual review loop). Plan §13 Milestone 2 ("watched indicators: Supertrend, MACD, VWAP, …")
     is the existing hook for this.

## Verify current state

Observed at `6774338`, 2026-09-29 22:44:

```
cd research/engine && .venv/bin/pytest -q tests
# exit 0 — 190 passed in 18.10s

mvn -B -DskipTests package
# exit 0 — BUILD SUCCESS

git status --short   (pre-existing leftovers only)
#  M data/axe-trader.sqlite.gz
#  M output/charts/chart.html
#  M output/charts/runner-results.html
# ?? data/axe-trader.sqlite.pre-consolidation-backup
# ?? research/review-spike/
# ?? research/review/runs/…-2024-01-11-… (20 dirs: v001/v002 reruns of 01-11 on a newer engine commit)
# ?? scripts/
```

Rebuild a day's page data (`days/*.js` are gitignored):
`cd research/engine && PYTHONPATH=. ./.venv/bin/python ../review/build_day.py --day <date> --strategy v002`.
Republish with **every** `days/*.js` plus `days/index.js` in `files`.

## Landmines

- **The page must be published with every day file.** Pass `files: {"days/index.js": …, "days/<date>.js": …}`
  for all six days. A day left out stays as it was on the server (files not passed are kept), but a
  brand-new day must be passed or its button loads nothing.
- **Changing only `location.hash` does not reload the page.** A headless test that `goto`s `…#2024-01-17`
  after `…#2024-01-16` silently shows the old day. Click `#daypick button[data-day=…]` instead.
- **Headless smoke test:** Playwright lives in the session scratchpad, not the repo. Storage can be faked
  with an `addInitScript` stub of `window.claude.use("db")` (see this session's `smoke4.mjs` pattern).
  Without it the page says "Storage unavailable" locally, which is expected.
- **The rebound rule exists only in the page (JS).** Analysis scripts replicate it in node against
  `days/*.js`. Any engine port must reproduce the page's markers exactly: once per streak, the fall
  measured over `look` bars ending at the bar before the streak, and ATR read at that bar.
- **Feedback docs carry `session` = the review day, but `bar_time` can fall outside it.** Markers in
  the display window before or after the review day, e.g. the weekend, still get judged. Join on
  tf + bar ts + side, not on session.
- **The auto-mode permission classifier failed intermittently** on long inline heredoc Bash commands.
  Writing the script with Write and running a short `node file.js` worked.
- **Artifact republish from a fresh session** needs a `read` of the URL first (publish refused
  otherwise). The live copy equals `day-review.html` plus a host wrapper.
- Still true: **never `git add -u` / `git add .`**, because the 253 MB `sqlite.gz` is modified in the tree.
  Run ids hash the engine commit, so commit engine changes before building runs.

## Open questions

- What "redo the days with 3atr" means exactly: re-judge at knob 3, or simulate 3 ATR entries with a
  wide stop for P&L (see Next action 1).
- Which indicators and parameter grid go into the matrix, and whether it is visual-first (overlays and
  vote strips on the page) or a measured grid over dev history (< 2026-02-01).
- Carried over: when to merge `feat/confluence-exit-matrix` into `main`; what to do with the 253 MB
  `sqlite.gz`; the 20 untracked 01-11 rerun dirs (harmless, never committed).

## Files that matter

- `research/review/day-review.html` — the page, with the rebound watch (`reboundSignals`, `markAgreement`,
  `saveWatch`, `openWatchPopup`).
- `research/review/watch/rebound-v2.md` — the frozen rules and their out-of-sample result.
- `research/review/feedback/2024-01-12/rebound-001/FINDINGS.md` — first feedback, heat and exits.
- `research/review/feedback/2024-01-17..19/rebound-001/FINDINGS.md` — scored test, exploration, slow trends.
- `research/review/feedback/2024-01-16/rebound-001/` — 01-16 export (no FINDINGS written).
- `research/review/build_day.py` — builds day data and run records.
- `docs/superpowers/plans/2026-09-25-us500-day-review-loop.md` — the plan; §13 holds the decisions
  (incl. no trade-count target).
- `TODO.md` — `⭐ SESSION STATE — 2026-09-29`.
- `CLAUDE.md` — Trading Goals cadence bullet updated to the owner's decision.
