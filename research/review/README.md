# Review pages

Spec §5. The published page is the surface the owner reads and comments on; a comment becomes a
`source: owner-comment` hypothesis in `research/ledger/` (spec §5.3).

**Keep the source here.** The 2026-09-18 review spike was published as an Artifact and existed in
git nowhere, so a fresh session could see the page but not rebuild it. Every published review page
keeps its source in this directory.

## Current pages

| page | artifact | built from | published |
|---|---|---|---|
| `research-state.html` — **Four Dead Hypotheses** | https://claude.ai/artifact/YTkPaQr27cT4njL7uEnjx9 | `research/cost-reality/2026-09-19.md`, `research/ledger/INDEX.md`, `research/experiments/2026-09-19-*.json` | 2026-09-19 |
| `trade-review.html` — **Four Exits, One Entry** | https://claude.ai/artifact/GrqdRANKK6AnzCGcgRyXiU | `research/runs/2026-09-19-oil-15m/`, via `export_trade_review.py` | 2026-09-19 |

To update it, republish the same file and pass that URL as `url` — publishing without it creates a
separate artifact instead of a new version.

## What `research-state.html` is, and is not

It is the **research state**: which hypotheses are dead and why, the cost ranking that explains
them, and what is queued next. Its numbers are transcribed from the committed run artefacts named
above. It is **not** the trade-review page — that is now `trade-review.html`, below.

Note: spec §7.3 says to republish the trade-review surface *over* the research-state artifact.
That was written before either page existed. They answer different questions and the research-state
page is still wanted, so the trade review was published as its own artifact instead. Folding them
into one page is still open.

## `trade-review.html` — the trade-review surface

Spec §7.3, built once a strategy finally produced trades. Every OIL_CRUDE 15m confluence entry with
**all four exit arms on one candle window**, because the entry is frozen and 426 of 506 entries are
taken by all four arms — so the arms are comparable candle by candle, and the only difference is
where each got out.

Each window also reaches the **4h horizon** where H-0005 measured this entry's edge, so "the exit
cut the measured edge short" is visible rather than inferred.

**The annotation layer is ported from `research/review-spike/`** and is the point of the page — a
canvas chart with pan/zoom where you *click any bar* to mark it, or *drag a line* from where a move
could have started to where it ran out. A drawn run comes back **measured** (points, x ATR,
bars/minutes, and how it compares with what each arm actually took), so a mark is evidence rather
than a doodle. Mark kinds: missed run, better/bad/late entry, better/early/late exit, chop, "this
was right", note. Exit kinds must name **which arm's** exit — only this four-arm view can tell them
apart. Runs can also carry a cause (spike-correction, stretched-trend, breakout, reversal, unsure),
each with the hint that travels to the reader.

Marks live in `localStorage` until sent; sending batches them under 4 KiB through the `comments`
capability's `sendToClaude`, and each becomes a `source: owner-comment` hypothesis (spec §5.3).

Rebuild its data (gitignored, ~1.4 MB, reproducible):

```
cd research/engine
for arm in symmetric trailing time reversal; do
  PYTHONPATH=. .venv/bin/python -m engine.run --db ../../data/axe-trader.sqlite \
    --epic OIL_CRUDE --timeframe 15min --arm $arm --brake-atr 10 --max-bars 6 \
    --out ../runs/2026-09-19-oil-15m/$arm
done
PYTHONPATH=. .venv/bin/python ../review/export_trade_review.py --runs ../runs/2026-09-19-oil-15m
```

The adapter is `engine/review.py` (`align_entries`, `candle_window`, `voting_pillars`), tested in
`tests/test_review.py`. `research/review-spike/` remains the 2026-09-18 proof of the candle/comment
mechanism against the old Java engine; this page supersedes it.
