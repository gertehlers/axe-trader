# 2024-01-11 · review iteration 001 · strategy v001

Owner feedback: 4 grades, 6 active marks (1 withdrawn), exported here from
https://claude.ai/artifact/UhUZuY6FgUGCHwY18s3WEp. Answers were written back to the page's
`explanations/` collection. Every statement below comes from the engine's trace and pillar readings
for run `us500-5min-v001-confluence-2024-01-11-085ef2a095` (and its 15m sibling). Anything that is
hindsight is labelled as such.

## What the grades say

| trade | entry | exit |
|---|---|---|
| LONG 01-10 11:35 (carried in) | ok | bad: too late, "missed a take profit … around 4800" |
| LONG 01-11 20:25 | bad: too late, "missed the run just before … a correction" | bad: too late, "take profit around 4790" |

## What blocked the marked moves

| mark | engine record at the move's start | type |
|---|---|---|
| LONG 01-12 11:55, +34 | full 3/3 bullish at 11:50 (RSI 11 at the lower band, candle, support); trend gate shut; LONG already open | 1 + 2 |
| SHORT 01-12 14:50, +25.5 | 2/3 bearish (RSI 87–89 at the upper band, resistance); short gate shut; LONG open | 1 + 2 |
| LONG 01-11 17:00, +36 | flat after the 16:46 stop; max 2/3; trend gate shut for 29 of 35 bars | 1 |
| LONG 01-11 14:00, +16 | 3/3 bullish at 13:45 (5m); trend gate shut; LONG open | 1 + 2 |
| SHORT 01-11 14:40, +46 | max 2/3; RSI peaked 72–73, under the 75 line; LONG open | 3 + 2 |
| LONG 01-10 20:55, +24 | the strategy was already LONG throughout: not missed, held and given back | exit |

Two constraints account for almost everything: the **EMA(200) trend gate** (it refuses the
counter-trend corrections the owner reads as predictable) and **one position at a time with no
take-profit** (the carried LONG was open for 29 h, blocking later setups, and gave back a +47 pt
excursion to a −17 pt stop).

## Take-profit candidates, measured on the two trades (hindsight, one day, illustration only)

| rule | LONG 01-10 11:35 | LONG 01-11 20:25 |
|---|---|---|
| owner's call | ~4800 (+43) | ~4790 (+14) |
| 3 × ATR(14) at entry | +5.1 (01-10 14:31) | +13.9 at 4790.4 (01-12 14:25) |
| 5 × ATR at entry | +8.4 | +23.1 at 4799.6 |
| 12 × ATR at entry | +20.3 | not reached |
| first close ≥ upper Bollinger | +8.4 | +0.0 |
| opposite RSI+BB extreme | +8.4 | +5.7 |

Entry-time ATR varies ~3× between the 11:35 UTC entry (1.69) and the cash session (4.62), so no
single ATR multiple reproduces both calls.

## Hypothesis logged, not acted on: 5m/15m agreement at extremes (owner, iteration 001)

The owner noted that three missed moves looked correlated on 5m and 15m. The record agrees: before
LONG 01-11 14:00 (15m 13:30–13:45: 2/3, RSI 18–23 at the lower band, **15m gate open**), LONG
01-11 17:00 (15m 16:45: candle + support) and SHORT 01-12 14:50 (15m 14:15–14:30: RSI 86–88 at the
upper band + resistance), the 15m bar showed 2 votes in the same direction while 5m sat at an
extreme. This is a candidate *entry* change for a later iteration. The two timeframes share their
data, so their agreement is not two independent confirmations; it gets tested as one change on
its own, per plan §4.
