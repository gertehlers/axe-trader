# Trade-Review Phone UI — Design (Plan 2)

Extends `2026-07-19-cloudflare-trade-review-dashboard-design.md`, which specified the hosting,
D1 schema, and `/api/*` surface (all built and deployed 2026-07-20). This document covers the
frontend the spec left as "React + Vite, hand-rolled SVG, visual pass during implementation",
and the one data-model addition that came out of designing it: **bar marks**.

Where this contradicts the earlier spec's sketch of the UI (a single scrolling page), this
document wins.

## Purpose

The engine can already tell you *that* a trade lost. Only a human looking at the chart can say
*why*, or where the exit should have been. This UI is the "eyes propose" half of the loop in
`docs/instrument-personality-playbook.md`: review trades one at a time on a phone, attribute each
loser to a cause, and mark on the chart where a better entry or exit tier actually was. The laptop
disposes — every hypothesis it produces still has to earn its place out-of-sample.

## Decisions

### Swipe deck, not a scrolling page

Trade review is the primary activity, not an afterthought below the stats. The app is two tabs:

- **Overview** — KPI tiles (win%, net expectancy in pts and $, trades/day, avg R, max DD,
  worst quarter), equity curve, loser-cause slices.
- **Trades** — one trade per screen; swipe left/right through the filtered set
  (`all` / `losers` / `winners`, matching the `filter` param the API already takes).

A run picker in the header selects which run is displayed, from `GET /api/runs`.

Rejected: the single scrolling feed from the earlier spec (trade cards buried under stats you
re-scroll every session), and a trades-only app (the KPIs are how you know whether the run is worth
reviewing at all).

### Focus / Full toggle instead of pinch-zoom

The card holds 50 bars before entry plus 50 after exit — over 100 candles, ~3px each on a phone.
Two fixed zoom states:

- **Focus** (default) — entry → exit, ±10 bars. Where essentially all marking happens; candles are
  readable and comfortably tappable.
- **Full** — the whole window, for "what happened after the stop" context.

Rejected: pinch-zoom and pan. Horizontal drag on the chart is the same gesture as swipe-to-next-
trade, so free panning needs either a modal chart state or threshold heuristics, while tap-to-mark
has to stay unambiguous throughout. The cost is real and the benefit over Focus/Full is speculative.
Revisit only if Focus/Full proves insufficient against real trades.

### Flags: one per trade, from a fixed taxonomy

`knife-catch`, `chop`, `news-spike`, `late-entry`, `bad-exit`, `good-signal`.

One flag per `signal_key` — the dominant cause — which is what the deployed `feedback` table already
enforces via its UNIQUE constraint. A closed set is the point: free text cannot be grouped, and
`GROUP BY flag` over losers is the artifact the hypothesis loop consumes. Notes remain available in
the existing `note` column but the UI does not surface a note field in v1.

Rejected: multiple flags per trade (double-counting when grouping, and a schema migration on top of
what is deployed).

### Marks: a new kind of data

A flag says *this trade was a knife-catch*. A mark says *T1 should have been **here***, which is a
timestamp and a price — the ground truth the 3-tier scale-out work (`TODO.md` iteration 9) needs and
does not currently have from any source.

Kinds: `better-entry`, `T1`, `T2`, `T3`, `exit-here`. One mark per kind per trade; placing a kind
that already exists moves it. No note attached — kind plus bar is the whole payload.

Marks are stored against the **bar timestamp**, not a bar index or pixel, so like flags they survive
re-running the backtest with different config. A "T1 should have been here" mark made today can be
replayed against tomorrow's exit logic to ask whether the new geometry would have caught it.

Interaction: select a kind chip once, then tap candles — each tap snaps to the nearest bar and drops
a marker; tapping an existing marker deletes it. Chosen over tap-then-choose-from-a-sheet because
placing entry + T1 + T2 on one trade is three taps with no menus, which suits the deck's rhythm.

## Data model

One new table. Nothing about `runs`, `trades`, or `feedback` changes.

```sql
CREATE TABLE marks (
  id          TEXT PRIMARY KEY,
  signal_key  TEXT NOT NULL,
  kind        TEXT NOT NULL,
  bar_ts      TEXT NOT NULL,   -- ISO-8601 UTC, the marked bar's end time
  created_at  TEXT NOT NULL,
  UNIQUE (signal_key, kind)
);
CREATE INDEX idx_marks_signal ON marks(signal_key);
```

`signal_key` keeps its established format, `instrument|entryTsIso|direction`.

## API additions

- `GET /api/marks` — all marks; `?signal_key=` filters to one trade.
- `POST /api/marks` — body `{ signal_key, kind, bar_ts }`; upserts on `(signal_key, kind)`.
- `DELETE /api/marks?signal_key=…&kind=…` — query parameters, not a body (DELETE bodies are
  inconsistently handled by intermediaries).

Both `flag` (on `POST /api/feedback`) and `kind` are validated server-side against their allowlists,
returning 400 on anything else, so a client bug cannot silently poison grouping. The allowlists live
in one module shared by the routes and the frontend.

## Frontend architecture

Vite + React + TypeScript in `dashboard/frontend/`, building into `dashboard/public/`, served by the
`ASSETS` binding already declared in `wrangler.jsonc`. Dev runs `vite dev` with `/api` proxied to
`wrangler dev`. No new Cloudflare resources; auth stays entirely with Cloudflare Access.

Candlesticks are hand-rolled SVG — around 100 bars per card is trivial to draw, and it avoids a
charting dependency on a phone bundle. Visual design goes through the frontend-design skill, with
the dataviz skill for KPI tiles, equity curve, and slice bars.

**Loading.** The deck fetches `GET /api/trades/:id` for the current trade and prefetches its
immediate neighbours, so bars arrive as you swipe. The list endpoint keeps omitting `bars_json`;
fetching every trade's bars up front would be ~660 KB for the current 102-trade run and far worse
for a large sweep.

**Writes.** Flag and mark taps POST immediately with optimistic UI, reverting on failure. Feedback
and marks are each loaded once per run and merged into the trade list by `signal_key`.

## Sync back to the laptop

`dashboard/scripts/pull-feedback.ts` gains a second output alongside `experiments/feedback.json`:
`experiments/marks.json`, an array of `{ signal_key, kind, bar_ts, created_at }`, joinable on
`signal_key` exactly as feedback is.

## Testing

- **API** (existing Workers-pool vitest project): `marks` CRUD, upsert-moves-not-duplicates,
  allowlist rejection for both `flag` and `kind`.
- **Frontend** (new jsdom vitest project; the Workers-pool config is untouched): the candle card
  renders entry/exit markers and stop/target lines at the correct prices; tap-snapping resolves to
  the correct bar timestamp; flag and mark writes are optimistic and revert on failure.
- **End-to-end smoke** (manual, documented in `DEPLOY.md`): push a run → flag and mark a trade on
  the phone → `npm run pull -- --remote` → confirm both files contain the entries.

## Out of scope

- Drill-down from an Overview slice into a filtered deck. Obvious follow-up; not needed to close the
  loop.
- Pinch-zoom / pan (see Focus/Full above).
- Notes on marks, multiple flags per trade, multiple marks of one kind.
- Editing strategy config from the phone — the phone proposes, the laptop disposes.
- Live/streaming updates; MONITOR-mode alerts.

## Acceptance criteria

1. On a phone, behind Access, pick a run and see its KPI tiles, equity curve, and loser-cause slices.
2. Swipe through that run's trades one per screen, filtered to all / losers / winners, with
   stop/target lines and entry/exit markers drawn at the right prices.
3. Toggle Focus/Full and have marks stay on their bars.
4. Flag a trade from the fixed taxonomy; the flag persists across a reload.
5. Place `better-entry` / `T1` / `T2` / `T3` / `exit-here` marks by tapping candles; re-placing a
   kind moves it, tapping a marker removes it.
6. `npm run pull -- --remote` brings both flags and marks back to `experiments/`, keyed on
   `signal_key`, and they survive a re-run of the backtest under different config.
