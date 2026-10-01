---
date: 2026-10-01
status: open
branch: feat/confluence-exit-matrix
head: 416e3fd
next: Brent 5m ST 7/3 - design a sideways detector and kill exit with the owner (plan proposed, not approved)
---

# Supertrend (Capital.com) on Brent 5m: clean look, backtest, failure anatomy; sideways losers next

## Where this stands

**The owner's direction today.** The owner brought Capital.com's SuperTrend: Brent 5m (Length 7, Factor 3)
and US100 1h (10, 3). They said "i do think we are on to something". These are their words, in order:
- "don't let our config interfere with this now. I want a clean look" — no rebound watch, pillars or
  `application.yaml`.
- "not the tighter one i don't like that. The original supertrend 7 3 but find a way to spot failures
  sooner so we can kill while letting winners run".
- "let's tackle each loser category on it's own with it's own exit strategy with sideways as our first
  target. How can we say that a run is likely going sideways".

The "redo the days with 3atr" note from the 2026-09-29 handover is a **separate idea**. The owner said
so, and it is still unexplained (see Open questions).

**Done**

All of this is committed and pushed (0 unpushed per evidence).

*Engine and data*
- `engine.indicators.supertrend()` follows TradingView `ta.supertrend` and matches Capital.com exactly:
  - Brent 5m bid at 2026-10-01 06:30Z reads 96.557, the same as the owner's screenshot.
  - US100 1h at 06:00Z reads 30,589.5, also the same.
- **US100 is seeded** into the local DB: 989,797 one-minute rows, 2024-01-01 → 2026-10-01.
- Brent and US500 are topped up to 2026-10-01 ~11:38Z.

*The "Supertrend Watch" page*
- URL: https://claude.ai/artifact/QohZQ9xKqbtsMoM23MSxa4, source `research/review/supertrend.html`.
- Recent clean charts: Brent 5m 7/3, US100 1h 10/3, US500 1h 10/3 and US500 5m 7/3.
- **Backtest views** (practice period): Brent 5m 7/3 flip to flip, and 7/3 with a 7/1 exit line. Each
  shows:
  - a running-total chart
  - a month picker and previous/next trade buttons
  - per trade: ▲/▼ entry, × exit, and a dotted entry→exit line coloured by the result
  - a hover readout

*Trade logic* — `engine.flips.flip_trades()` / `summarise()`:
- Always in the market, entering at the open after the flip candle.
- Longs buy the Ask and sell the Bid; shorts the reverse. Overnight funding uses today's rates applied
  to the past.
- An optional `exit_direction` (a tighter line) closes the trade early, then it stays flat until the
  next flip.

*Results — practice period only.* The owner chose that; nothing ≥ 2026-02-01 was loaded.

| Run | File | Result |
|---|---|---|
| Flip to flip | `research/experiments/2026-10-01-supertrend-flip-to-flip.*` | US500 5m −8 pts, US500 1h −1,381, US100 1h −2,962, Brent 5m −205.3 (4,078 trades) |
| Tighter exit, Brent | `research/experiments/2026-10-01-brent-supertrend-tight-exit.*` | 7/2 −171, 7/1.5 −165, 7/1 −143.5; 7/1 is +10.7 before costs. **Owner rejected the tighter line.** |
| Failure anatomy, Brent 7/3 | `research/experiments/2026-10-01-brent-st-failure-anatomy.*` | see below |

Failure anatomy for Brent 7/3:
- **Trade kinds:**
  - winners: 31%, +486
  - "gave it back" (got ≥1 ATR, ended a loser): 34%, −233
  - **sideways** (never got 1 ATR): **28%, −365**
  - fast reversal (flipped back within 30 min): 7%, −94
- **Pillars at the flip candle are weak.** A high ATR7/ATR100 and 12–16 UTC raise the win rate in both
  years, but no bucket turns profitable.
- **Progress after 3 or 6 candles separates strongly in both years.** After 6 candles, trades more than
  1.2 ATR up won 62% / 59% and earned +0.23 / +0.22 per trade.
- **Kill rule** (not in profit after 3 candles → exit): 2024 goes from −104.8 to −74.9, 2025 from −85.8
  to −70.6. Still negative.

**In flight:** nothing is half-edited. The last plan was **proposed, and the owner interrupted before
approving it**. It is written below under Next action. The plan file was
`~/.claude/plans/on-capital-com-i-have-dreamy-sundae.md`, which is outside the repo.

**Not started:**
- The sideways detector study.
- Exits for "gave it back" and for fast reversals.
- Colouring trades by kind on the page.

## Next action

Answer the owner's question, "how can we say a run is likely going sideways", **with them, one step
at a time**. The candidate detectors were already explained to the owner in chat. Each is checked
after k = 3 / 6 / 12 candles, using closed candles only:
1. **Flat line:** the Supertrend line hasn't stepped for N candles. This is the most visual one.
2. **No progress:** the best point so far is < X ATR.
3. **Efficiency ratio:** net move ÷ total path is near 0.
4. **Tight box:** the k-candle range is ≤ Y ATR.
5. **Volatility dying:** ATR now is below ATR at entry.
6. **Choppiness Index (14).**
7. **Closes back across the entry price.**
8. **Cushion to the line not growing.**

The proposed study:
- Add `efficiency_ratio`, `choppiness` and a flat-line counter to `engine/indicators.py`, each with
  tests.
- Write an experiment that reuses the trade build and `kind` labels from
  `2026-10-01-brent-st-failure-anatomy.py`.
- Per detector, measure:
  - the share of flagged trades that really were sideways
  - the share of sideways trades it catches
  - the **winners killed**
- Simulate a kill exit at the next open. Use 2024 to discover and 2025 to check with the same
  thresholds.
- Then put it on the page: trades coloured by kind, plus a ⊘ marker where the rule would have killed.

**Get the owner's go-ahead before building.** They interrupted the plan approval.

## Verify current state

Observed at `416e3fd`, 2026-10-01 15:47:
```
cd research/engine && .venv/bin/pytest -q tests
# exit 0 — 202 passed in 19.90s

mvn -B -DskipTests package
# exit 0 — BUILD SUCCESS

git status --short   (pre-existing leftovers only, same as the 2026-09-29 handover)
#  M data/axe-trader.sqlite.gz, output/charts/chart.html, output/charts/runner-results.html
# ?? data/axe-trader.sqlite.pre-consolidation-backup, research/review-spike/, scripts/,
#    research/review/runs/…-2024-01-11-… (20 dirs)
```

Rebuild the page data. It is gitignored under `research/review/supertrend/`. Run these from
`research/engine` with `PYTHONPATH=.`:
```
.venv/bin/python ../review/build_supertrend.py --epic OIL_BRENT --timeframe 5min --length 7 --factor 3 --days 14
.venv/bin/python ../review/build_supertrend.py --epic US100 --timeframe 1h --length 10 --factor 3 --days 60
.venv/bin/python ../review/build_supertrend.py --epic US500 --timeframe 1h --length 10 --factor 3 --days 60
.venv/bin/python ../review/build_supertrend.py --epic US500 --timeframe 5min --length 7 --factor 3 --days 14
.venv/bin/python ../review/build_flip_backtest.py --epic OIL_BRENT --timeframe 5min --length 7 --factor 3 [--exit-factor 1]
```
The flip backtest's expected print is `4078 trades, net -205.34 pts` (with `--exit-factor 1`, −143.49).

## Landmines

- **US500 from 2026-02-01 is the owner's final exam** (`engine.dayrun.RESERVED_FROM`). The recent
  US500 charts on the page sit inside it, which the owner allowed for a look. **Never add up trades
  there.**
  - Every backtest/experiment filters `minutes.index < RESERVED_FROM`.
  - Brent and US100 recent charts are display only as well.
- **Artifact `files` sources must be absolute paths, or set `root`.** Relative paths fail with
  "not found", because the session cwd drifts into `research/engine` after `cd`.
- **The page needs its data files republished.**
  - `supertrend/index.js`, `supertrend/<key>.js`, `supertrend/bt/index.js`, `supertrend/bt/<key>/summary.js`
    and every `supertrend/bt/<key>/YYYY-MM.js`. That is 27 files per backtest key, about 8 MB each key.
  - Files left out stay as they were on the server.
  - A republish from a new session needs an Artifact `read` of the URL first.
- **Backtest buttons use the prefix `bt:`** in `data-key`. The recent Brent chart and the Brent
  backtest share the key `OIL_BRENT-5min-7-3`, and without the prefix both buttons light up.
- **Capital.com screenshots are taken mid-candle.** Compare against the last *completed* candle, or
  rebuild with minutes up to the screenshot time. The Supertrend checkpoint matched because the line
  was flat across those bars.
- **Seeding a new instrument** needs `--axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2024-01-01T00:00:00Z`.
  - US100 took ~25 min and logs nothing until the end.
  - `docs/local-price-history.md` has the commands.
- **Headless smoke test:** run `npm i playwright@1.55.0` in the session scratchpad. That version wants
  chromium 1187, which is not cached, so pass
  `executablePath: ~/Library/Caches/ms-playwright/chromium_headless_shell-1228/chrome-headless-shell-mac-arm64/chrome-headless-shell`.
- **The owner wants it explained before it is built.** They said "nothing yet, i want to understand
  first" and rejected two option popups in favour of typing. Memory: `feedback_clean_indicator_look.md`.
- Still true: **never `git add -u` / `git add .`**. The 253 MB `sqlite.gz` is modified in the tree.

## Open questions

- Go-ahead and scope for the sideways detector study (Next action). Should it be numbers first, then the
  page, or both together?
- What "redo the days with 3atr" means. The owner said "something else entirely, will give more input".
- The indicator-knob matrix (RSI 7/14, SMA 20/50/200, …) from 2026-09-29: whether it still stands
  next to the Supertrend work.
- Carried over:
  - when to merge `feat/confluence-exit-matrix`
  - the 253 MB `sqlite.gz`
  - the 20 untracked 01-11 rerun dirs

## Files that matter

- `research/engine/engine/indicators.py`: `supertrend()`, matching Capital.com.
- `research/engine/engine/flips.py`: flip-to-flip trades, the optional exit line, and `summarise()`.
- `research/engine/tests/test_supertrend.py`, `tests/test_flips.py`: hand-computed cases.
- `research/review/build_supertrend.py`: data for the recent clean charts.
- `research/review/build_flip_backtest.py`: backtest data for the page (summary plus month files).
- `research/review/supertrend.html`: the Supertrend Watch page.
- `research/experiments/2026-10-01-*.py/.txt`: the three runs and their outputs.
- `TODO.md`: `⭐ SESSION STATE — 2026-10-01`.
- `docs/handoffs/2026-09-29-rebound-watch-and-indicator-matrix.md`: superseded by this one. Its 3atr
  and matrix asks are carried over in Open questions.