# 2024-01-12 · rebound watch feedback 001

Owner feedback on the page-computed rebound markers (default settings: fell ≥ 2 ATR over 12 bars, then 3
candles the other way). 15 markers judged (11 on 5m, 4 on 15m), exported here from
https://claude.ai/artifact/UhUZuY6FgUGCHwY18s3WEp (`watch_feedback/`, `watch_feedback_revisions/`).
"Worst" is the largest move against the entry, from the signal close, before the owner's exit (or within
3 h when no exit was drawn). Every exit is a hindsight choice; worst and best are hindsight too.

| tf | signal (UTC) | side | verdict | fall | ATR | owner's exit | worst before it | note |
|---|---|---|---|---|---|---|---|---|
| 15m | 01-12 09:45 | SHORT | bad | 12.5 pt · 3.2 ATR | 3.95 | – | −4.7 pt (1.2 ATR); +20.5 within 3 h | |
| 15m | 01-12 11:00 | SHORT | bad | 10.2 pt · 2.4 ATR | 4.28 | – | −8.5 pt (2.0 ATR); +17.5 within 3 h | |
| 15m | 01-12 13:30 | LONG | unsure | 10.5 pt · 2.2 ATR | 4.82 | +10.1 pt, 105 min | −3.1 pt (0.6 ATR) | |
| 15m | 01-12 15:15 | SHORT | unsure | 31.3 pt · 5.0 ATR | 6.28 | – | −4.2 pt (0.7 ATR); +12.8 within 3 h | |
| 5m | 01-12 01:45 | LONG | good | 5.2 pt · 3.2 ATR | 1.60 | +12.7 pt, 455 min | −6.1 pt (3.8 ATR) | |
| 5m | 01-12 03:05 | SHORT | good | 4.2 pt · 3.3 ATR | 1.27 | +4.6 pt, 95 min | −1.5 pt (1.2 ATR) | |
| 5m | 01-12 04:45 | LONG | unsure | 5.8 pt · 5.6 ATR | 1.04 | +12.9 pt, 275 min | −5.7 pt (5.5 ATR) | "looks lucky that it went up" |
| 5m | 01-12 08:10 | LONG | good | 8.3 pt · 5.0 ATR | 1.66 | +21.4 pt, 395 min | **−20.2 pt (12.2 ATR)** | |
| 5m | 01-12 12:00 | LONG | good | 18.2 pt · 5.7 ATR | 3.22 | +31.0 pt, 160 min | −4.1 pt (1.3 ATR) | |
| 5m | 01-12 12:30 | LONG | good | 9.0 pt · 2.7 ATR | 3.32 | +28.7 pt, 135 min | −5.7 pt (1.7 ATR) | |
| 5m | 01-12 14:55 | SHORT | good | 21.8 pt · 5.1 ATR | 4.25 | +18.9 pt, 70 min | −3.3 pt (0.8 ATR) | |
| 5m | 01-12 16:05 | LONG | good | 27.0 pt · 4.3 ATR | 6.35 | +7.2 pt, 195 min | −11.2 pt (1.8 ATR) | |
| 5m | 01-12 19:30 | SHORT | unsure | 6.5 pt · 2.1 ATR | 3.13 | +8.6 pt, over the weekend | −4.2 pt (1.3 ATR) | "did not dip low enough but still went down" |
| 5m | 01-14 23:35 | LONG | unsure | 4.7 pt · 2.1 ATR | 2.18 | – | −2.5 pt (1.1 ATR); +5.3 within 3 h | "went choppy, still went up though" |
| 5m | 01-15 01:15 | SHORT | bad | 3.1 pt · 2.3 ATR | 1.38 | – | −4.8 pt (3.5 ATR); +2.3 within 3 h | "choppy" |

Not judged: 5m 08:45 SHORT, 10:25 LONG.

## What it says (one day, 15 markers: leads, not rules)

- **5m: 7 good, 3 unsure, 1 bad. 15m: 0 good, 2 unsure, 2 bad.** Matches the owner's read that 5m looks
  more solid. Far too few to conclude.
- **The cleanest good ones are in or near the cash session** (12:00, 12:30, 14:55): at most 1.7 ATR
  against, then 19–31 pts in 70–160 min.
- **The overnight ones (ATR 1.0–1.7) needed a lot of room** before the owner's exit: 3.8, 5.5 and
  12.2 ATR against. The 08:10 "good" LONG went 20 pts against first; any normal stop would have ended
  it. Whether a signal is good depends on the stop as much as on the entry.
- **Both "bad" 15m shorts later went 17–20 pts the right way** within 3 h, after 1.2–2.0 ATR against.
  Open question to the owner: did "bad" mean the direction was wrong, or too early / too much heat?
- **Owner's exits are long holds**: 4.6–31 pts over 70–455 min, far beyond v002's 3 ATR target.
  They are hindsight peaks, an upper bound for any exit rule.
- The owner's own words: "choppy", "lucky", "did not dip low enough", "still went up". These are the
  seed for structured reasons.
