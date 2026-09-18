---
date: 2026-09-18
status: landed
landed: artifact v9 (claude.ai/artifact/Uk2eshf413WWW3eUdhFXwJ)
branch: main
head: 08e30f0
next: Fix the run-line first-click bug in research/review-spike/index.html onCanvasClick, then publish v8 to the existing artifact URL
---

# Review-page spike, main consolidation, and a stalled 12-instrument seed

Three strands ran this session: everything was consolidated onto `main`, a throwaway
review-page spike was built and iterated with the owner through its own comment loop,
and a background price-history seed was started that has since stalled in practice.

**Nothing was committed all session — that was an explicit owner instruction** ("we
don't need to commit anything until we're ready, this is a pet project"). The working
tree is dirty by design. Do not commit without asking; see Open questions first.

## Where this stands

**Done — consolidation onto `main`:**
- `feature/research-restart` merged into `main` as a **clean fast-forward** (`49b6a09`
  → `08e30f0`). This was 54 unmerged commits and is the real current state of the
  project: the Flyway V2 history-integrity migration, the rewritten importer
  (`update`/`report`/`archive` modes), the `DISCOVERY` entry point, and the Python
  `research/engine` package.
- **All worktree checkouts removed** — `git worktree list` now shows only `main`.
  Branches were deliberately **not** deleted.
- **`main`'s dataset replaced** with the current one: US500 958,849 · OIL_BRENT
  901,618 · OIL_CRUDE 944,105 rows, 2024-01-01 → 2026-09-17, copied from the old
  `.worktrees/delta-price-import`. The previous legacy 500k-row file is preserved,
  untracked, at `data/axe-trader.sqlite.pre-consolidation-backup`.
- `data/axe-trader.sqlite.gz` rebuilt via `mode=archive` and verified to decompress
  to the same row counts. **It is now 242 MB, up from 31 MB** — see Open questions.

**Done — review-page spike, published and iterated to v7:**
`research/review-spike/` (`index.html` + generated `run-data.js`) at
<https://claude.ai/artifact/Uk2eshf413WWW3eUdhFXwJ>, capabilities `{comments: {}}`.
102 archived US500 trades from `dashboard/run.json`, candle chart with pan/zoom,
entry/exit markers, labelled stop/target, per-trade run analysis (MFE/MAE, best price
after entry, "left on the table"), page-drawn numbered note pins, and batched
`sendToClaude`. Verified working end to end: the owner sent real feedback through it
and replies landed in the threads.

**In flight — the run-line feature is written but NOT published and has a live bug.**
The owner asked for click → rubber-band line → click, with direction inferred from
the geometry (up = missed long, down = missed short) and the endpoints treated as
*indicative zones*, not exact prices. The code is in the working copy of
`research/review-spike/index.html`: `runMode`/`runStart`/`runCursor` state, a
`drawRun()` renderer, `runMetrics()`, span-aware `noteLines()` payload with explicit
±0.25 ATR / ±2 bar tolerance language, and endpoint clamping to the bar's traded
range. **Measured output is correct** when it completes (verified once:
`−4.63 pts · 3.4× ATR · 13 bars (65 min) · 5.4× the target`, direction inferred SHORT).

**The bug:** in run mode the **first** canvas click is swallowed — `runStart` is not
set and the button stays on "Click the start…"; the *second* click sets it. Console
shows **no errors**. Observed only through synthetic `PointerEvent` dispatch in the
test harness, so it is **unverified whether a real mouse click reproduces it** — that
is the first thing to check, because the harness may be at fault, not the page.

**Not started:** any strategy change (deliberate — see below); the Python engine core
(spec step 2).

## Next action

In `research/review-spike/index.html`, `onCanvasClick`: find why the first click in
`runMode` does not set `runStart`. **First check whether a real mouse click reproduces
it at all** — every observation so far came from synthetic `PointerEvent`s dispatched
via the browser tool, and the earlier point-note flow behaved differently under real
clicks than under synthetic ones. If it reproduces, suspect the `pointerup` →
`onCanvasClick` path and `setRunMode()` calling `draw()` (which reassigns `frame`).
Then publish v8 by republishing the same file path to the existing artifact URL.

## Verify current state

Observed at `08e30f0` on `main`, 2026-09-18 16:07, tree DIRTY:

```
./mvnw test
# exit 0 — Tests run: 328, Failures: 0, Errors: 0, Skipped: 3, BUILD SUCCESS
```

```
./mvnw clean package -DskipTests
# exit 0 — BUILD SUCCESS
```

```
git status --short
#  M TODO.md
#  M data/axe-trader.sqlite.gz
# ?? data/axe-trader.sqlite.pre-consolidation-backup
# ?? research/review-spike/
# ?? scripts/
```

```
sqlite3 -readonly data/axe-trader.sqlite "SELECT epic, COUNT(*) FROM historical_price GROUP BY epic;"
# OIL_BRENT 901618 · OIL_CRUDE 944105 · US500 958849
```

No lint command is documented for this project and none was run.

## Landmines

**The background seed has been on its FIRST instrument for 5h18m and may never
finish.** `scripts/seed-remaining-instruments.sh` (PID 36263, launched detached with
`nohup … & disown`, PPID 1, so it survives sessions) is still on NATURALGAS, the first
of 12. `logs/seed-remaining-instruments.status` is **empty — zero instruments have
completed**. The staging file grew 277 MB → 310 MB in roughly four hours, versus
277 MB in the first ninety minutes, so it has slowed by roughly an order of magnitude.
It is not hung (the file still grows, CPU is near zero because it waits on rate-limited
API calls) but at this rate twelve instruments is not achievable. **Decide whether to
kill it before assuming the data will arrive.** Kill with `kill 36263` plus the child
`java` process; a partial staging file is resumable — the importer quarantines or
resumes retained staging files on the next run for that epic.

**`data/axe-trader.sqlite.gz` is a tracked 242 MB binary in the working tree** (was
31 MB). Committing it will put a quarter-gigabyte blob in git history permanently.
Nothing has been committed yet. Resolve this before any `git add`.

**`./mvnw test` rewrites tracked chart files.** It dirties
`output/charts/runner-results.html` and sometimes `output/charts/chart.html`. Restore
with `git checkout -- output/charts/` before committing. This is expected, not a
regression.

**A fresh checkout needs the DB decompressed before tests:**
`gzip -dc data/axe-trader.sqlite.gz > data/axe-trader.sqlite`. `DatabaseBootstrap` only
runs in `main()`, not under `@SpringBootTest`; without it six backtest tests fail with
0 bars and look like a regression.

**`customAnchors` comment pins cannot work for a page-initiated composer.** From
`comments.d.ts` on the `threads` callback: the thread list is *"sent only in sessions
the viewer entered from the shell's own controls — a session the page started with
compose receives none, even after its draft posts."* No thread list means no handle,
so `placed()` is never called and no pin is ever drawn. This killed the original
§5.1 approach. The spike now owns its markup instead and declares plain
`{comments: {}}`. **Do not rebuild the customAnchors path expecting pins.**

**Comment text caps at 4 KiB, and truncating it loses data silently.** The first real
owner batch was cut off mid-trade-6 and never delivered. Worse, v4 then marked *every*
note `sent`, so the lost notes could not be resent — a silent no-op. Now fixed
(`buildChunks()` splits across messages; per-chunk marking; a "Resend all" escape
hatch). **Rule for the real review page: never mark a unit delivered until its own
payload is confirmed delivered.**

**The old Java engine, discovery pipeline and `dashboard/` are scheduled for
deletion** (spec D3 / §7) once the new Python engine core passes its tests. Do not
invest in tuning `application.yaml`'s 5-pillar config — that work would be discarded.

**`file://` URLs are blocked by the browser tool.** To look at the spike locally,
serve it: `python3 -m http.server 8765 --directory research/review-spike` and wrap
`index.html` in a doctype/`<body>` shell first (the artifact platform adds that
wrapper at publish time; without it the page renders in quirks mode). Delete the
`_preview.html` wrapper afterwards — it is a build artifact, not source.

## Open questions

- **Kill or keep the 12-instrument seed?** Five hours, zero instruments finished. The
  owner may prefer cutting the list to the few instruments actually being researched.
- **What to do about the 242 MB `data/axe-trader.sqlite.gz`** before anything is
  committed. Options: leave untracked/gitignored and rely on the local file, use LFS,
  or keep a smaller committed snapshot. Needs the owner.
- **When to commit at all.** The owner explicitly deferred all commits this session.
  `research/review-spike/` and `scripts/` are entirely untracked.
- **Two comment threads were auto-replied to but never read by this session** —
  `6c21ad41-1061-4615-a70b-667e2b9df0bf` and `6c428c7e-920d-4b58-8988-27982aa473fa`.
  Both arrived as the session ran out of budget, so their content is unknown here; the
  owner has seen an auto-reply in each, but neither was reviewed and neither was
  resolved. **Read both first** — either may contain a change request or a correction,
  and the owner's last several messages each changed the direction of the work. Read
  with `ArtifactComments` action "read" against
  <https://claude.ai/artifact/Uk2eshf413WWW3eUdhFXwJ>.

## Strategy finding worth carrying (owner's, and it supersedes mine)

The owner's read is that **the entries are the problem**, not the exit geometry:
"almost every entry is in choppy waters and real runs are missed." The arithmetic
supports it — with a 3.0 ATR stop and a 0.75 ATR target, a driftless random walk hits
the target first **80%** of the time (3.0/3.75). The run scores 88.2%, and breakeven is
exactly 80%. So the entries are worth roughly **8 percentage points, not 88**, and all
profitability rests on that thin margin. The first test for the new engine should be
spec §6.2.3 layer 1: the entry signal against **random entries at matched times**. If
it does not beat that, tuning exits is polishing noise.

Two unvalidated leads from the owner's marked-up trades, already logged in `TODO.md`:
a possible minimum-ATR / volatility gate (four pillars "agreeing" on a flat tape), and
several cases where the opposite direction was the better trade (`enable-short: false`
is current config, justified by an earlier tuning iteration).

## Files that matter

- `TODO.md` — the running session log, `⭐ SESSION STATE` section at the top. It is the
  fullest narrative of this session and is **modified but uncommitted**.
- `research/review-spike/index.html` — the spike page; **untracked**, contains the
  unpublished, buggy run-line feature. The published artifact is v7, which does *not*
  contain it.
- `research/review-spike/run-data.js` — generated from `dashboard/run.json`; the
  artifact's supporting file.
- `scripts/seed-remaining-instruments.sh` — the detached seeding loop; **untracked**.
- `logs/seed-remaining-instruments.{log,status}` — its progress; status file is empty.
- `docs/handoffs/2026-09-17-research-restart-step1.md` — still `status: open`, and
  still the authoritative plan of record for the research restart.
- `docs/superpowers/specs/2026-09-17-research-restart-design.md` — decisions D1–D9,
  §5 review page, §6.2.3 diagnosis layers, §7 decommissioning.
- `data/axe-trader.sqlite.pre-consolidation-backup` — the old 500k-row dataset, kept
  so the consolidation is reversible. Safe to delete once confident.


---

## Closure — 2026-09-18 evening

The next action is done. **The first-click bug did not reproduce under real mouse clicks**, exactly
as this document suspected: every observation had come from synthetic `PointerEvent` dispatch, and
the harness was at fault, not the page. Run marking works.

Published since: **v8** (run lines plus optional "what kind of run?" cause chips — spike correction
/ stretched trend / breakout / reversal at level / unsure, each carrying its rationale into the
payload) and **v9** (a send receipt that survives a reload, answering the owner's comment thread
asking to be told Claude received a batch).

Both comment threads this document flagged as unread were read. `6c428c7e` was answered and
resolved. `6c21ad41` and `df359f2c` remain **open**: they carry the owner's marked-up review notes
and want analysis, not a code change. They were deliberately not acted on — the review data is the
archived Java run, which H-0001 has since falsified, so analysing those notes would be analysing a
dead strategy.

`research/review-spike/` is still untracked in git.
