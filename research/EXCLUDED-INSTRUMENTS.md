# Instruments excluded from research

Read this before adding an instrument to any experiment, grid or candidate list. An instrument
listed here is **not** to be reconsidered without new evidence of the kind named in its entry —
"it looked interesting" is not new evidence.

## NATURALGAS — excluded 2026-09-19, permanent

**Decision: owner, 2026-09-19.** Do not consider NATURALGAS for the research engine again.

**The data is broken at the provider and cannot be repaired.** `research/data-quality/2026-09-18.json`
measures **16.23% missing core minutes**, against 1.68–2.29% for the three instruments that pass:

| instrument | missing core | verdict |
|---|---|---|
| OIL_CRUDE | 1.68% | PASS |
| US500 | 2.15% | PASS |
| OIL_BRENT | 2.29% | PASS |
| **NATURALGAS** | **16.23%** | **FAIL** |

Re-importing does not fix it. Capital.com returns `error.prices.not-found` for the missing
intervals — the gaps are permanent on their side, not an artefact of our importer. The shape is
illiquidity rather than a broken import: 30–41% missing between 00:00–06:00 UTC against 1.5–2.4%
during US hours.

**And it was never worth the trouble.** Even restricted to roughly 11:00–19:00 UTC where its data
holds up, the cost reality check found it is **never the cheapest ground at any timeframe** — it
placed 8th, 12th, 16th and 19th of 19 on the 2026-09-19 ranking before removal. There is no
timeframe at which it beats US500 or OIL_CRUDE for the same effort.

**What this means in practice:**

- Leave it out of every grid, candidate list, ranking and per-instrument profile.
- Its 801,226 rows stay in `data/axe-trader.sqlite`; nothing needs deleting, and the importer may
  keep topping it up harmlessly. The exclusion is about **research**, not storage.
- Do not spend import time on it. Each instrument costs ~5h, most of it re-requesting gaps that are
  permanently empty.
- `research/engine/instruments.yaml` still carries its spec because that file is generated from the
  Capital.com API and is not edited by hand. Its presence there is not a signal to use it.

**Usable instruments for research: US500, OIL_CRUDE, OIL_BRENT.**
