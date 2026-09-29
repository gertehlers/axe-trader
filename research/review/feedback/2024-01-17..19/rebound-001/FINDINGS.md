# 2024-01-17..19 · rebound watch feedback 001

Owner judged every v1 marker (Reset on): 31 markers (21 5m + 9 15m judged, 1 exit only), 3 missed moves,
3 trade grades (reversal exit rule). Scoring against the frozen rules: `research/review/watch/rebound-v2.md`.

## Frozen-rule test

- Drop ≥ 3 ATR (rule A) did not separate good from bad on the new days. Dropped.
- 5m + 15m agreement is the only filter that holds so far: 7 good / 1 unsure / 1 bad over all five judged
  days (3 / 0 / 1 on the new days) against 16 / 9 / 10 without it. Nine markers; a lead, not a rule.
- Base rate fell: 43 % of 5m markers good on 01-17..19 vs 61 % on 01-12 + 01-16.

## Exploration on all judged 5m markers (in-sample, from the owner's notes)

| idea (owner's words) | good / unsure / bad where true | where false |
|---|---|---|
| with the trend: EMA(200) gate open ("overall trend is bearish, so that could have informed this") | 10 / 4 / 5 | 13 / 6 / 6 |
| streak moved ≥ 2 ATR ("candles barely moved", "not truly convincing") | 10 / 4 / 3 | 13 / 6 / 8 |
| 5m + 15m agree | 7 / 1 / 1 | 16 / 9 / 10 |

Neither trend nor streak size separates. Median streak: good 1.81 ATR, bad 1.70.

## The two "slow trend" missed moves

The owner asked to check the pillars for confluence during slow trends (15m, SHORT 01-16 23:35 → 01-17
09:15, 29 pts; LONG 01-18 05:35 → 14:10, 24 pts).

- Votes for the move never reached 3: at most 2 on any bar (SHORT: 13/22/4 bars with 0/1/2 votes; LONG 9/19/6).
- RSI+BB voted with the move on 0 bars in both: it detects extremes, and a slow trend never is one.
- Vol+Trend is the only pillar that followed them (21 of 39 bars, 22 of 34).
- The EMA(200) gate was open for the whole slow SHORT (39/39), 14/34 for the slow LONG.

The current pillars are built to find extremes and turns, so they cannot see a slow grind. Catching one
needs a different kind of tool (trend-following: slope of an average, higher lows / lower highs).
