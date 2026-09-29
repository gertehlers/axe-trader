# Rebound watch v2 — frozen 2026-09-29, before 2024-01-17..19 were reviewed

Owner's observations after reviewing 2024-01-16: "when 5min and 15min agree it seems to be a good run" and
"3 ATR seems nice". Both were checked against the markers the owner had already judged (2024-01-12 and
2024-01-16, 5m only, v1 defaults: fell ≥ 2 ATR over 12 bars, then 3 candles the other way). Days
2024-01-17..19 are built but unreviewed; they are the first test of these ideas. Nothing below may be
changed after looking at them.

## In-sample evidence (23 judged 5m markers; one person, two days)

| filter | good | unsure | bad |
|---|---|---|---|
| all v1 markers | 14 | 6 | 3 |
| drop ≥ 3 ATR | 11 | 2 | 1 |
| a same-side 15m marker (v1 settings) closed within ±45 min | 4 | 1 | 0 |
| both | 3 | 0 | 0 |

In every agreeing case the 15m marker closed 10–40 min *after* the 5m one, so waiting for agreement
means entering later.

## The rules to test on 2024-01-17..19

- **A: drop ≥ 3 ATR.** Page knobs 3 / 12 / 3, "Only 5m + 15m agree" off.
- **B: v2 = drop ≥ 3 ATR on both timeframes, and they agree.** A marker is kept only if a same-side
  marker on the other timeframe (same settings) has its bar close within ±45 min. The signal is known
  when the later of the two bars closes. Page button "Rebound v2: ≥ 3 ATR + agree".
  On the judged days B keeps 01-12 12:00 L (good), 01-12 14:55 S (good), 15m 01-12 15:15 S (unsure),
  01-16 01:15 L (exit drawn, no verdict) and two unjudged 15m markers. It is very selective.

Counts the rules produce on the review days (in window):

| day | 5m v1 | 5m A | 15m v1 | 15m A | B (5m + 15m) |
|---|---|---|---|---|---|
| 2024-01-17 | 8 | 5 | 3 | 1 | 0 |
| 2024-01-18 | 6 | 2 | 1 | 0 | 0 |
| 2024-01-19 | 11 | 6 | 5 | 3 | 2: 5m 13:25 S + 15m 13:45 S, known 14:00 |

## How the test is scored

The owner judges 2024-01-17..19 as before. Scoring counts good / unsure / bad among the markers each
rule keeps against the markers it drops. The cleanest test judges every v1 marker (settings 2 / 12 / 3,
agreement off), so the dropped ones get verdicts too. Code: `research/review/day-review.html`,
`reboundSignals` + `markAgreement`, at the commit that adds this file.

## Result on 2024-01-17..19 (scored 2026-09-29; the rules above were not changed)

Owner judged with Reset on (v1 markers). 5m: 21 judged (9 good / 4 unsure / 8 bad), 4 not judged.

| 5m markers | kept: good / unsure / bad | dropped: good / unsure / bad |
|---|---|---|
| A: drop ≥ 3 ATR | 5 / 2 / 5 | 4 / 2 / 3 |
| B: v2 | 1 / 0 / 0 (01-19 13:25 S, +20 pts) | 8 / 4 / 8 |
| 5m + 15m agree (v1 settings) | 3 / 0 / 1 | 6 / 4 / 7 |

15m: 9 judged (3 / 3 / 3); A kept 0 / 2 / 2; agreement kept 1 / 1 / 2.

- **A failed.** In-sample it kept 11 of 14 good; on new days kept and dropped markers are equally good.
- **B is untestable at this size**: one marker in three days (it was good).
- **5m + 15m agreement held on 5m**: 3 of 4 kept were good vs 6 of 17 dropped. Across all five judged
  days it is 7 good / 1 unsure / 1 bad (9 markers) against 16 / 9 / 10 without it. Still small.
- It did not hold on 15m markers.
