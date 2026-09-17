---
date: 2026-09-17
status: open
branch: worktree-ma-cross-spike
head: 7b1b5bb
next: Ask the owner which direction to take the surviving 7/14+RSI-extremity candidate — harden with honest intrabar exits, try a genuinely different MA pair, or stop this line — then act on their answer.
---

# MA-cross + RSI-extremity strategy spikes

Six disposable JUnit spikes testing whether an always-in-market MA-cross strategy (plus an
RSI(7) filter) makes money on US500, run entirely on the `worktree-ma-cross-spike` branch —
never merged toward `main`, no production code touched. All six are pushed to `origin`.

## Where this stands

**Done — six spikes, in this order, each a separate `@SpringBootTest` gated by its own
`-D<flag>=true` system property (so none of them run under a plain `./mvnw test`):**

1. `MovingAverageCrossSpikeTest` (`-Dmacross=true`) — naive always-in-market MA(7/14) cross,
   5m candles, full ~17-month dataset, RSI(7) logged per trade for eyeballing only. **Result:
   loses money** — 7,687 trades (17.5/day), 37% win rate, -0.39 net pts/trade, -2,992 pts
   total. Informational RSI-at-signal breakdown suggested trades confirmed by RSI <30 or ≥70
   outperformed the 30-50 "weak momentum" band.
2. `MovingAverageCrossRsiFilterSpikeTest` (`-Dmacrossrsi=true`) — promoted that RSI observation
   to a real entry filter (enter only when RSI(7) is <30 or ≥70 at the cross bar; exits stay on
   the unconditional opposite cross), tested with an in-sample/out-of-sample split (IS:
   2024-12-04→2026-01-01, OOS: 2026-01-01→dataset end — same boundary `ConfluenceSweepTest`
   uses). **Result: works, both windows** — IS: 988 trades (2.9/day), +0.19 net pts/trade,
   +185 pts total. OOS: 290 trades (2.8/day), +0.56 net pts/trade, +163 pts total, 2/2 positive
   quarters. A leak check (recomputing the RSI breakdown on in-sample trades only) reproduced
   the same shape, so the finding doesn't appear to depend on having seen the OOS window.
3. `MovingAverageCrossRsiResetSweepTest` (`-Dmacrossreset=true`) — owner's alternative read of
   the price action ("RSI spikes, pulls back, THEN the cross fires and it runs") reformulated
   as: RSI must have *touched* an extreme within a lookback window K before the cross, paired
   by direction (long needs a recent oversold touch, short needs a recent overbought touch).
   Swept K∈{3,5,8,12} on IS, confirmed the winner once on OOS. **Falsified** — every K
   underperforms the at-signal filter; the naive IS "winner" (K=3, 62 trades) collapsed OOS
   (23 trades, -2.57 net pts/trade). Lesson logged: ranking by MAR alone with no trade-count
   floor lets a tiny sample masquerade as a winner.
4. `MovingAverageCrossRsiTouchSymmetricSweepTest` (`-Dmacrosstouch=true`) — isolation test: kept
   the lookback/touch mechanic but dropped the direction-pairing (either extreme confirms
   either direction, like spike 2). Added a 300-trade in-sample floor before a candidate is
   OOS-eligible. **Result: touch_K3 ≈ the at-signal filter** (1,287 IS trades, MAR 0.41 vs
   0.43) — statistically indistinguishable. Diagnosis: the *direction-pairing* broke spike 3,
   not the lookback mechanic. OOS for touch_K3: 389 trades (3.7/day), +0.33 net pts/trade, 2/2
   positive quarters.
5. `MovingAverageCross1mSweepTest` (`-Dmacross1m=true`) — same variant lineup on 1-minute
   candles instead of 5-minute, sweeping both RSI threshold (20/80, 25/75, 30/70) and lookback
   K. **Falsified across the board** — every one of 16 filtered candidates plus baseline is
   net-negative in-sample; baseline is a near-total wipeout (30,166 trades, netTot -14,737 pts,
   MAR -1.00). Spread dominates any edge at 1m because average range is much smaller than 5m
   while average spread per round trip is the same (~0.48 pts).
6. `MovingAverageCross50v200SweepTest` (`-Dmacross50v200=true`) — slower MA(50/200) pair on 5m
   instead of 7/14, same threshold×K sweep (20 candidates, 40-trade IS floor given the pair's
   much lower natural frequency). **Overfitting trap, falsified** — IS "winner" (touch_K12_20_80,
   177 trades, MAR 0.96) collapsed OOS (54 trades, -5.76 net pts/trade, MAR -0.93). The
   unfiltered baseline (474 IS trades, not screened against 20 competitors) went from weakly
   positive IS (MAR 0.09) to negative OOS (MAR -0.29) too — no real edge at 50/200 either.

**Net standing candidate:** MA(7/14) on 5m + RSI(7) extremity filter (at-signal 30/70, or
touch_K3 — the two are statistically equivalent) is the only formulation across six spikes that
holds a positive net expectancy in *both* IS and OOS. It trades ~2.9-3.8/day at ~38-44% win
rate — a trend-confirmation edge via bigger average winners, not the 80%+ mean-reversion edge
the 5-pillar confluence strategy targets. **Not yet hardened**: still uses ta4j's close-based
exit (fires on the bar the opposite cross confirms) and a flat average-spread cost model, not
the honest intrabar stop/target fill modeling that collapsed the confluence strategy's apparent
edge (see `CLAUDE.md`'s Trading Goals section). The real number is probably worse than shown.

**Not started:** intrabar-exit hardening, any different (non-7/14, non-50/200, non-1m) MA
pairing, integrating any of this into `application.yaml`/`StrategyFactory` as a real strategy
option, or a decision on whether this line should ever merge toward `main`.

## Next action

The owner was asked, at the end of the conversation this handover closes out, which direction
to take the surviving 7/14+RSI-extremity candidate: (a) harden it with honest intrabar exits
before trusting the positive OOS number, (b) try a genuinely different MA pair (not just
faster/slower candles — 1m and 50/200 both failed), or (c) stop this line here. **No answer was
given before the session ended.** Get that answer and act on it.

## Verify current state

Observed just now, at `7b1b5bb` on `worktree-ma-cross-spike` (pushed to `origin`):

```
./mvnw test
# exit 0 — Tests run: 239, Failures: 0, Errors: 0, Skipped: 9
```

All 6 spike tests above are among the skipped — each requires its own `-D<flag>=true` (see
each file's class-level Javadoc for the exact command) and none run under a plain `mvnw test`.

```
./mvnw clean package -DskipTests
# exit 1 — Failed to execute goal spring-boot-maven-plugin:4.0.5:repackage
#          Unable to find main class
```

Pre-existing, unrelated to this work: `AxeTraderApplication.java:21` declares `static void
main`, not `public static void main` — same root cause the 2026-08-08 handover documented on
`main` at the time. Untouched by any of these spikes.

```
git status --short
#  M output/charts/runner-results.html
```

Expected: running `./mvnw test` rewrites this tracked file every time (documented in the
2026-08-08 handover too). Not a sign of stray work — leave it or discard it, it regenerates.

## Landmines

**`main`'s SQLite dataset has 573 bad-tick rows** (0.1% of 500k, `open/high/low/close_ask <
bid` on the same field) that `BarSeriesFactory.validate()` rejects outright. Every spike here
filters them inline (`hasSaneSpread`) rather than touching production validation — `main`
lacks the `price_exclusion` ledger the cleaner worktrees (`.worktrees/clean-local-price-history`,
`.worktrees/delta-price-import`) already have for this. Any NEW test against `main`'s DB that
loads raw prices without this filter will throw `IllegalArgumentException: close ask must be
greater than or equal to bid`.

**`BacktestRunner`'s two `run()` overloads have very different exit semantics** — the one
with a non-null `BacktestProperties.Strategy config` silently discards whatever exit rule the
`Strategy` object encodes and re-walks bars with an ATR stop/target bracket instead. Every spike
here calls `run(series, ConfluenceStrategies, indicators, null)` — passing `null` for config —
specifically to get ta4j's own exit-on-opposite-cross behavior. Passing a real config to any of
these would silently break "exit on next cross" semantics without an error.

**Ranking a parameter sweep by MAR (net÷maxDD) alone, with no trade-count floor, produces false
winners.** Spike 3 (`MovingAverageCrossRsiResetSweepTest`) picked a 62-trade in-sample "winner"
that collapsed out-of-sample. Spikes 4 and 6 added floors (300 trades for the 7/14 family, 40
for the much-lower-frequency 50/200 pair) — but the floor has to match the candidate's expected
trade frequency; copying a number from a different MA pair without thinking about it will either
be too strict (nothing qualifies) or too loose (same trap again).

**The OOS window (2026-01-01 onward) has now been used as a confirmation target six times
across six different spikes.** Each spike only confirmed its own single in-sample winner once
against it — no spike retuned after seeing its own OOS result — but the window as a whole is no
longer as pristine as a true one-shot holdout would be, since six independent hypotheses have
each gotten one look at it. A future harder validation of the surviving candidate might want a
genuinely fresh slice (e.g. data ingested after 2026-05-01, if any exists by then) rather than
reusing this same window a seventh time.

**This is a separate line of work from the OPEN `2026-08-08-research-agent-design.md`
handover.** That one is about designing a durable research agent (a different, meta-level
project) and was mid-flight (Section 2 not yet re-approved) when this conversation started;
this handover does not touch or resolve it. Don't conflate the two when picking up either one.

## Open questions

- Harden with honest intrabar exits, try a different MA pair, or stop? (see Next action)
- Should this ever merge toward `main`, or stay a permanently disposable spike branch? The
  brainstorming classification at the start was "spike" (throwaway), but six iterations in with
  a genuinely-surviving candidate is more invested than a typical one-shot spike now — worth the
  owner explicitly deciding rather than defaulting either way.
- If hardened, does the surviving candidate get its own `application.yaml` profile / `AxeTraderMode`
  concern the way the confluence strategy has one, or does it stay test-only? Not discussed.

## Files that matter

- `src/test/java/io/g3tech/axetrader/backtest/MovingAverageCrossSpikeTest.java` — spike 1, the
  naive baseline and the informational RSI breakdown that seeded everything after it.
- `src/test/java/io/g3tech/axetrader/backtest/MovingAverageCrossRsiFilterSpikeTest.java` —
  spike 2, the surviving at-signal RSI filter with the IS/OOS split and leak check.
- `src/test/java/io/g3tech/axetrader/backtest/MovingAverageCrossRsiResetSweepTest.java` —
  spike 3, the falsified direction-paired lookback reset filter.
- `src/test/java/io/g3tech/axetrader/backtest/MovingAverageCrossRsiTouchSymmetricSweepTest.java`
  — spike 4, the isolation test that cleared the lookback mechanic and indicted direction-pairing.
- `src/test/java/io/g3tech/axetrader/backtest/MovingAverageCross1mSweepTest.java` — spike 5,
  1-minute candles, falsified across the whole grid.
- `src/test/java/io/g3tech/axetrader/backtest/MovingAverageCross50v200SweepTest.java` — spike 6,
  MA(50/200), the overfitting-trap demonstration.
- `src/main/java/io/g3tech/axetrader/backtest/runner/BacktestRunner.java` — the `config == null`
  legacy path every spike depends on for "exit on opposite cross" semantics (lines 37-39,
  95-104); the confluence path (lines 47-57, 105-123) cannot express this.
- `src/main/java/io/g3tech/axetrader/backtest/experiment/TradeStatistics.java` — public
  `maxDrawdown`/`positiveQuarters`/`quarterCount` statics, reused unmodified by every spike.
- `src/main/java/io/g3tech/axetrader/AxeTraderApplication.java:21` — the missing `public`
  modifier blocking `mvnw clean package`, unrelated to and untouched by this work.
