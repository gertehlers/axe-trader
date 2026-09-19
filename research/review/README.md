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

To update it, republish the same file and pass that URL as `url` — publishing without it creates a
separate artifact instead of a new version.

## What this page is, and is not

It is the **research state**: which hypotheses are dead and why, the cost ranking that explains
them, and what is queued next. Its numbers are transcribed from the committed run artefacts named
above.

It is **not** a trade-review page. Spec §5.2 describes one — pannable candle chart, entry/exit
markers, trade list, per-trade slices — and that needs a strategy run through the simulator to have
anything to show. No strategy has survived layer-1 diagnosis yet, so there are no trades. The
2026-09-18 spike (`research/review-spike/`, still untracked) proved the candle/comment mechanism
against a run exported by hand from the **old Java engine**; wiring it to the Python engine's
`Trade` output is the remaining work, and it is only worth doing once a strategy earns it.
