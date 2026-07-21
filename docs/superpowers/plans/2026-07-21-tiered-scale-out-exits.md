# Tiered Scale-Out Exits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the all-or-nothing exit bracket with a 3-tier scale-out (bank ⅓ at each of three
rising targets, ratcheting the stop as tiers fill), then sweep 9 arms plus a control to find whether
it fixes the negative expectancy.

**Architecture:** `BacktestRunner.intrabarExit` becomes a thin wrapper over a new `tieredExit` that
walks a ladder of tier levels. A one-tier 100% ladder with no ratchet reproduces today's behaviour
byte-for-byte, so the six existing `BacktestRunnerIntrabarTest` tests pass unchanged and pin the
refactor. Config carries ATR *multiples*; the exit engine works in price *distances*.

**Tech Stack:** Java 21, Spring Boot 4, ta4j, JUnit 5 + AssertJ, Maven.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-07-21-tiered-scale-out-exits-design.md`. Read it first.
- **Conservative tie-break is non-negotiable:** if a bar's range spans the (current) stop and any
  unfilled tier, the **stop is assumed** and **no tier banks on that bar**. Never book an intrabar
  win that OHLC cannot prove.
- **Ratchet takes effect on the bar AFTER the tier fills.** Within the filling bar, OHLC cannot show
  whether the ratcheted level was touched before or after the tier, so it is not applied.
- **A one-tier 100% ladder with `Ratchet.NONE` must reproduce current behaviour exactly.** If any
  existing test in `BacktestRunnerIntrabarTest` needs editing, you have broken something — stop and
  report rather than editing the test.
- **This is money-handling code.** Every test asserts a pnl/price figure computed **by hand in the
  test**, never a value copied from a failing run's actual output.
- Run tests with `./mvnw test -Dtest=<TestClass>`. Full suite: `./mvnw test`.
- On a fresh container, decompress the DB first:
  `gzip -dc data/axe-trader.sqlite.gz > data/axe-trader.sqlite`
- Commit after every task. Push after every commit.

---

### Task 1: Config model — tiers, ratchet, and startup validation

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/backtest/config/Ratchet.java`
- Modify: `src/main/java/io/g3tech/axetrader/backtest/config/BacktestProperties.java` (add nested
  `Exit` + `ExitTier` classes to `Strategy`, at the end of the `Strategy` class body, after
  `enableShort` and its accessors)
- Test: `src/test/java/io/g3tech/axetrader/backtest/config/ExitConfigTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum Ratchet { NONE, BREAKEVEN_AFTER_T1, LAGGED }`
  - `BacktestProperties.Strategy.Exit` with `List<ExitTier> getTiers()`, `Ratchet getRatchet()`,
    `void validate()`
  - `BacktestProperties.Strategy.ExitTier` with `double getFraction()`,
    `double getTargetAtrMultiple()`
  - `Strategy.getExit()` / `setExit(Exit)` — never null, defaults to an empty ladder

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/g3tech/axetrader/backtest/config/ExitConfigTest.java`:

```java
package io.g3tech.axetrader.backtest.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The tier ladder is money-critical config: fractions that silently fail to sum to 1.0 would size
 * every trade wrongly and quietly bias every pnl figure in the sweep. So a bad ladder is a startup
 * error, never a silent renormalisation.
 */
class ExitConfigTest {

    private static BacktestProperties.Strategy.ExitTier tier(double fraction, double target) {
        BacktestProperties.Strategy.ExitTier t = new BacktestProperties.Strategy.ExitTier();
        t.setFraction(fraction);
        t.setTargetAtrMultiple(target);
        return t;
    }

    @Test
    void emptyLadderIsValidAndMeansCurrentBehaviour() {
        BacktestProperties.Strategy.Exit exit = new BacktestProperties.Strategy.Exit();

        assertThatCode(exit::validate).doesNotThrowAnyException();
        assertThat(exit.getTiers()).isEmpty();
        assertThat(exit.getRatchet()).isEqualTo(Ratchet.NONE);
    }

    @Test
    void fractionsSummingToOneAreAccepted() {
        BacktestProperties.Strategy.Exit exit = new BacktestProperties.Strategy.Exit();
        exit.setTiers(List.of(tier(0.3333, 0.75), tier(0.3333, 1.5), tier(0.3334, 3.0)));

        assertThatCode(exit::validate).doesNotThrowAnyException();
    }

    @Test
    void fractionsNotSummingToOneAreRejected() {
        BacktestProperties.Strategy.Exit exit = new BacktestProperties.Strategy.Exit();
        exit.setTiers(List.of(tier(0.5, 0.75), tier(0.3, 1.5)));

        assertThatThrownBy(exit::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0.8");
    }

    @Test
    void nonPositiveFractionIsRejected() {
        BacktestProperties.Strategy.Exit exit = new BacktestProperties.Strategy.Exit();
        exit.setTiers(List.of(tier(1.0, 0.75), tier(0.0, 1.5)));

        assertThatThrownBy(exit::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void tiersMustBeInAscendingTargetOrder() {
        BacktestProperties.Strategy.Exit exit = new BacktestProperties.Strategy.Exit();
        exit.setTiers(List.of(tier(0.5, 1.5), tier(0.5, 0.75)));

        assertThatThrownBy(exit::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ascending");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ExitConfigTest`
Expected: FAIL — compilation error, `Ratchet` and `BacktestProperties.Strategy.Exit` do not exist.

- [ ] **Step 3: Create the Ratchet enum**

Create `src/main/java/io/g3tech/axetrader/backtest/config/Ratchet.java`:

```java
package io.g3tech.axetrader.backtest.config;

/**
 * What the stop does as scale-out tiers fill. See
 * {@code docs/superpowers/specs/2026-07-21-tiered-scale-out-exits-design.md}.
 *
 * <p>All three are swept rather than assumed — which one suits an instrument is part of its
 * "personality", not a universal truth.
 */
public enum Ratchet {
    /** Stop never moves; stays at the original ATR multiple for the life of the trade. */
    NONE,
    /** After tier 1 fills the stop moves to entry; after tier 2 fills it moves to tier 1's level. */
    BREAKEVEN_AFTER_T1,
    /** Stop holds at the original level until tier 2 fills, then moves to entry. */
    LAGGED
}
```

- [ ] **Step 4: Add Exit and ExitTier to Strategy**

In `BacktestProperties.java`, inside `public static class Strategy`, add the field near the other
exit-geometry fields (after `private double trendEmaMaxAtr;`):

```java
        // Scale-out exit ladder. Empty (the default) means the current all-or-nothing behaviour:
        // one tier at 100% using targetAtrMultiple, no ratchet.
        private Exit exit = new Exit();
```

Add these accessors and nested classes at the **end of the `Strategy` class body**:

```java
        public Exit getExit() {
            return exit;
        }

        public void setExit(Exit exit) {
            this.exit = exit == null ? new Exit() : exit;
        }

        /** The scale-out ladder and its ratchet rule. */
        public static class Exit {
            private List<ExitTier> tiers = List.of();
            private Ratchet ratchet = Ratchet.NONE;

            public List<ExitTier> getTiers() {
                return tiers;
            }

            public void setTiers(List<ExitTier> tiers) {
                this.tiers = tiers == null ? List.of() : List.copyOf(tiers);
            }

            public Ratchet getRatchet() {
                return ratchet;
            }

            public void setRatchet(Ratchet ratchet) {
                this.ratchet = ratchet == null ? Ratchet.NONE : ratchet;
            }

            /**
             * Fails fast on a ladder that would mis-size every trade. Deliberately does NOT
             * renormalise fractions: silently "fixing" the config would bias every pnl figure in a
             * sweep with nothing on screen to show it happened.
             */
            public void validate() {
                if (tiers.isEmpty()) {
                    return;
                }
                double sum = 0.0;
                double previousTarget = Double.NEGATIVE_INFINITY;
                for (ExitTier tier : tiers) {
                    if (tier.getFraction() <= 0.0) {
                        throw new IllegalStateException(
                                "exit tier fraction must be positive, got " + tier.getFraction());
                    }
                    if (tier.getTargetAtrMultiple() <= previousTarget) {
                        throw new IllegalStateException(
                                "exit tiers must be in ascending target order, got "
                                        + tier.getTargetAtrMultiple() + " after " + previousTarget);
                    }
                    previousTarget = tier.getTargetAtrMultiple();
                    sum += tier.getFraction();
                }
                if (Math.abs(sum - 1.0) > 1e-6) {
                    throw new IllegalStateException(
                            "exit tier fractions must sum to 1.0, got " + sum);
                }
            }
        }

        /** One rung: bank {@code fraction} of the position at {@code targetAtrMultiple} ATR. */
        public static class ExitTier {
            private double fraction;
            private double targetAtrMultiple;

            public double getFraction() {
                return fraction;
            }

            public void setFraction(double fraction) {
                this.fraction = fraction;
            }

            public double getTargetAtrMultiple() {
                return targetAtrMultiple;
            }

            public void setTargetAtrMultiple(double targetAtrMultiple) {
                this.targetAtrMultiple = targetAtrMultiple;
            }
        }
```

Add `import java.util.List;` to the top of `BacktestProperties.java` if it is not already present.

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=ExitConfigTest`
Expected: PASS, 5 tests.

- [ ] **Step 6: Wire validation into startup**

In `BacktestProperties.java`, add to the imports:

```java
import jakarta.annotation.PostConstruct;
```

and add this method to the **top-level `BacktestProperties` class** (not to `Strategy`):

```java
    /** Fail fast on a mis-specified exit ladder rather than silently mis-sizing every trade. */
    @PostConstruct
    void validateExitLadder() {
        if (getStrategy() != null) {
            getStrategy().getExit().validate();
        }
    }
```

If the accessor for the strategy block is named something other than `getStrategy()`, use the actual
name — check the top of the file.

- [ ] **Step 7: Run the full suite to confirm nothing regressed**

Run: `./mvnw test`
Expected: BUILD SUCCESS. 34 tests run, 0 failures, 0 errors, 1 skipped.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/backtest/config/Ratchet.java \
        src/main/java/io/g3tech/axetrader/backtest/config/BacktestProperties.java \
        src/test/java/io/g3tech/axetrader/backtest/config/ExitConfigTest.java
git commit -m "feat(config): exit tier ladder + ratchet enum, validated at startup

Fractions that do not sum to 1.0 are a startup error rather than a silent
renormalisation: quietly 'fixing' them would mis-size every trade and bias every
pnl figure in a sweep with nothing on screen to show it happened."
```

---

### Task 2: The tier ladder — `tieredExit`, with today's behaviour as the degenerate case

**Files:**
- Modify: `src/main/java/io/g3tech/axetrader/backtest/runner/BacktestRunner.java:140-171`
- Test: `src/test/java/io/g3tech/axetrader/backtest/runner/BacktestRunnerTieredExitTest.java` (create)
- Must keep passing untouched:
  `src/test/java/io/g3tech/axetrader/backtest/runner/BacktestRunnerIntrabarTest.java`

**Interfaces:**
- Consumes: `Ratchet` from Task 1.
- Produces:
  - `record TierLevel(double fraction, double targetDist)` — engine-side, price distances not ATR
    multiples.
  - `record TierFill(int index, double price, double fraction, ExitReason reason)`
  - `record TieredExitOutcome(List<TierFill> fills, int tiersFilled, boolean hitT1)` with derived
    `int index()` (last fill's bar) and `double weightedPrice()` (size-weighted average fill).
  - `static TieredExitOutcome tieredExit(BarSeries series, Direction direction, int entryIndex,
    double entryPrice, double stopDist, List<TierLevel> tiers, Ratchet ratchet, int maxHoldingBars)`
  - `static ExitOutcome intrabarExit(...)` — unchanged signature, now delegating.

**Why `weightedPrice()` matters:** pnl is linear in exit price and the fractions sum to 1, so
`pnl(dir, entry, weightedPrice()) == Σ fractionᵢ × pnl(dir, entry, priceᵢ)` exactly. That means
`toTradeResult` keeps its existing `double pnl = pnl(direction, entryPrice, exitPrice);` line
unchanged in Task 4. Task 2 Step 1 pins this identity with a test.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/g3tech/axetrader/backtest/runner/BacktestRunnerTieredExitTest.java`:

```java
package io.g3tech.axetrader.backtest.runner;

import io.g3tech.axetrader.backtest.config.Ratchet;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the scale-out ladder ({@link BacktestRunner#tieredExit}).
 *
 * <p>Fixture mirrors the stage-1 sweep geometry: LONG entry at 100, stop 3.0 (level 97), tiers at
 * 0.75 / 1.5 / 3.0 points (levels 100.75, 101.5, 103.0), one third each.
 *
 * <p>Every expected pnl here is computed by hand in the test, never copied from a run.
 */
class BacktestRunnerTieredExitTest {

    private static final double ENTRY = 100.0;
    private static final double STOP_DIST = 3.0;

    private static final List<BacktestRunner.TierLevel> THIRDS = List.of(
            new BacktestRunner.TierLevel(1.0 / 3.0, 0.75),
            new BacktestRunner.TierLevel(1.0 / 3.0, 1.5),
            new BacktestRunner.TierLevel(1.0 / 3.0, 3.0));

    private static final List<BacktestRunner.TierLevel> WHOLE =
            List.of(new BacktestRunner.TierLevel(1.0, 0.75));

    @Test
    void singleTierLadderReproducesTheAllOrNothingTarget() {
        BarSeries series = series(
                bar(100, 100, 100),
                bar(101.0, 99.5, 100.5));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, WHOLE, Ratchet.NONE, 0);

        assertThat(out.tiersFilled()).isEqualTo(1);
        assertThat(out.hitT1()).isTrue();
        assertThat(out.index()).isEqualTo(1);
        assertThat(out.weightedPrice()).isCloseTo(100.75, within(1e-9));
        assertThat(out.fills()).singleElement()
                .satisfies(f -> assertThat(f.reason()).isEqualTo(ExitReason.TARGET));
    }

    @Test
    void allThreeTiersFillAcrossSeparateBars() {
        // Bar1 tags 100.75 only; bar2 tags 101.5 only; bar3 tags 103.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.8, 99.5, 100.6),
                bar(101.6, 100.4, 101.2),
                bar(103.2, 101.0, 103.0));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS, Ratchet.NONE, 0);

        assertThat(out.tiersFilled()).isEqualTo(3);
        assertThat(out.hitT1()).isTrue();
        assertThat(out.index()).isEqualTo(3);
        // (100.75 + 101.5 + 103.0) / 3 = 101.75
        assertThat(out.weightedPrice()).isCloseTo(101.75, within(1e-9));
    }

    @Test
    void weightedPriceEqualsTheSizeWeightedSumOfTierPnls() {
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.8, 99.5, 100.6),
                bar(101.6, 100.4, 101.2),
                bar(103.2, 101.0, 103.0));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS, Ratchet.NONE, 0);

        double summed = out.fills().stream()
                .mapToDouble(f -> f.fraction() * (f.price() - ENTRY))
                .sum();

        assertThat(out.weightedPrice() - ENTRY).isCloseTo(summed, within(1e-9));
    }

    @Test
    void multipleTiersFillWithinOneBarWhenTheStopIsUntouched() {
        // Bar 1 runs 99.8 -> 102: clears T1 (100.75) and T2 (101.5) but not T3 (103).
        BarSeries series = series(
                bar(100, 100, 100),
                bar(102.0, 99.8, 101.8));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS, Ratchet.NONE, 0);

        assertThat(out.tiersFilled()).isEqualTo(2);
        assertThat(out.fills()).hasSize(3); // two tier fills + the remainder closed at END
        assertThat(out.fills().get(0).price()).isCloseTo(100.75, within(1e-9));
        assertThat(out.fills().get(1).price()).isCloseTo(101.5, within(1e-9));
    }

    @Test
    void barSpanningStopAndAnUnfilledTierBanksNothingAndStopsOut() {
        // Bar 1 range 96..102 touches the stop AND T1/T2. Conservative: stop assumed, no tier banks.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(102.0, 96.0, 100.5));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS, Ratchet.NONE, 0);

        assertThat(out.tiersFilled()).isZero();
        assertThat(out.hitT1()).isFalse();
        assertThat(out.weightedPrice()).isCloseTo(97.0, within(1e-9));
        assertThat(out.fills()).singleElement()
                .satisfies(f -> assertThat(f.reason()).isEqualTo(ExitReason.STOP));
    }

    @Test
    void remainderStopsOutAfterTwoTiersBanked() {
        // Bar1 fills T1 and T2; bar2 collapses to the stop with T3 unfilled.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(102.0, 99.8, 101.8),
                bar(101.9, 96.5, 97.0));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS, Ratchet.NONE, 0);

        assertThat(out.tiersFilled()).isEqualTo(2);
        // (100.75 + 101.5 + 97.0) / 3 = 99.75
        assertThat(out.weightedPrice()).isCloseTo(99.75, within(1e-9));
        assertThat(out.fills()).last()
                .satisfies(f -> assertThat(f.reason()).isEqualTo(ExitReason.STOP));
    }

    @Test
    void timeStopClosesTheRemainderAtThatBarsClose() {
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.8, 99.5, 100.6),   // T1 fills
                bar(100.9, 99.9, 100.2),
                bar(100.9, 99.9, 100.4));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS, Ratchet.NONE, 2);

        assertThat(out.tiersFilled()).isEqualTo(1);
        assertThat(out.fills()).last().satisfies(f -> {
            assertThat(f.reason()).isEqualTo(ExitReason.TIME);
            assertThat(f.index()).isEqualTo(2);
            assertThat(f.price()).isCloseTo(100.2, within(1e-9));
        });
        // (100.75 + 2 x 100.2) / 3
        assertThat(out.weightedPrice()).isCloseTo((100.75 + 100.2 + 100.2) / 3.0, within(1e-9));
    }

    @Test
    void shortLadderFillsDownward() {
        // SHORT at 100: tier levels 99.25, 98.5, 97.0; stop 103.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.2, 98.4, 98.6),   // clears T1 (99.25) and T2 (98.5)
                bar(98.7, 96.9, 97.0));   // clears T3 (97.0)

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.SHORT, 0, ENTRY, STOP_DIST, THIRDS, Ratchet.NONE, 0);

        assertThat(out.tiersFilled()).isEqualTo(3);
        // (99.25 + 98.5 + 97.0) / 3
        assertThat(out.weightedPrice()).isCloseTo((99.25 + 98.5 + 97.0) / 3.0, within(1e-9));
    }

    private static BarSeries series(double[]... bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName("tiered-test").build();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < bars.length; i++) {
            double[] b = bars[i];
            series.barBuilder()
                    .timePeriod(Duration.ofMinutes(5))
                    .endTime(start.plus(Duration.ofMinutes(5L * (i + 1))))
                    .openPrice(b[2])
                    .highPrice(b[0])
                    .lowPrice(b[1])
                    .closePrice(b[2])
                    .volume(1)
                    .add();
        }
        return series;
    }

    /** {@code {high, low, close}} for one bar. */
    private static double[] bar(double high, double low, double close) {
        return new double[] {high, low, close};
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=BacktestRunnerTieredExitTest`
Expected: FAIL — compilation error, `tieredExit`, `TierLevel`, `TierFill`, `TieredExitOutcome` do
not exist.

- [ ] **Step 3: Implement the ladder**

In `BacktestRunner.java`, add `import io.g3tech.axetrader.backtest.config.Ratchet;`,
`import java.util.ArrayList;` and `import java.util.List;` if not present.

Replace the existing `intrabarExit` method and the `ExitOutcome` record (lines ~140-171) with:

```java
    /**
     * Walks bars forward from the fill and returns the first stop/target/time exit, filled at the
     * bracket <em>level</em> (not bar close). Retained for the single-target path and for callers
     * that want one outcome; implemented as a one-tier {@link #tieredExit} ladder so there is a
     * single exit engine to reason about.
     */
    static ExitOutcome intrabarExit(
            BarSeries series, Direction direction, int entryIndex, double entryPrice,
            double stopDist, double targetDist, int maxHoldingBars) {
        TieredExitOutcome out = tieredExit(
                series, direction, entryIndex, entryPrice, stopDist,
                List.of(new TierLevel(1.0, targetDist)), Ratchet.NONE, maxHoldingBars);
        TierFill only = out.fills().get(out.fills().size() - 1);
        return new ExitOutcome(only.index(), only.price(), only.reason());
    }

    /**
     * Scale-out exit: walks bars forward banking a fraction of the position at each tier level and
     * ratcheting the stop as tiers fill.
     *
     * <p><b>Conservative tie-break.</b> When one bar's range spans the current stop and any unfilled
     * tier, OHLC cannot reveal the order, so the stop is assumed and <b>no tier banks on that
     * bar</b> — we never book an intrabar win we cannot prove.
     *
     * <p><b>Ratchet timing.</b> A ratchet triggered by a tier filling on bar {@code i} takes effect
     * from bar {@code i+1}. Within the filling bar itself OHLC cannot show whether the ratcheted
     * level was touched before or after the tier.
     *
     * @param tiers ascending target distances in price units (not ATR multiples), fractions summing
     *              to 1.0; validated by {@code BacktestProperties.Strategy.Exit#validate()}
     */
    static TieredExitOutcome tieredExit(
            BarSeries series, Direction direction, int entryIndex, double entryPrice,
            double stopDist, List<TierLevel> tiers, Ratchet ratchet, int maxHoldingBars) {
        boolean isLong = direction == Direction.LONG;
        double stopLevel = isLong ? entryPrice - stopDist : entryPrice + stopDist;
        int lastIndex = series.getEndIndex();

        List<TierFill> fills = new ArrayList<>();
        int nextTier = 0;
        double remaining = 1.0;

        for (int i = entryIndex + 1; i <= lastIndex && nextTier < tiers.size(); i++) {
            Bar bar = series.getBar(i);
            double high = bar.getHighPrice().doubleValue();
            double low = bar.getLowPrice().doubleValue();

            boolean stopHit = isLong ? low <= stopLevel : high >= stopLevel;
            if (stopHit) {
                fills.add(new TierFill(i, stopLevel, remaining, ExitReason.STOP));
                return outcome(fills, nextTier);
            }

            // Bank every tier this bar reaches, in order.
            while (nextTier < tiers.size()) {
                TierLevel tier = tiers.get(nextTier);
                double level = isLong ? entryPrice + tier.targetDist() : entryPrice - tier.targetDist();
                boolean tierHit = isLong ? high >= level : low <= level;
                if (!tierHit) {
                    break;
                }
                fills.add(new TierFill(i, level, tier.fraction(), ExitReason.TARGET));
                remaining -= tier.fraction();
                nextTier++;
            }
            if (nextTier >= tiers.size()) {
                return outcome(fills, nextTier);
            }

            // Ratchet applies from the NEXT bar (see javadoc).
            stopLevel = ratchetedStop(
                    ratchet, nextTier, isLong, entryPrice, stopLevel, tiers);

            if (maxHoldingBars > 0 && (i - entryIndex) >= maxHoldingBars) {
                fills.add(new TierFill(
                        i, bar.getClosePrice().doubleValue(), remaining, ExitReason.TIME));
                return outcome(fills, nextTier);
            }
        }

        if (remaining > 0.0) {
            fills.add(new TierFill(
                    lastIndex, series.getBar(lastIndex).getClosePrice().doubleValue(),
                    remaining, ExitReason.END));
        }
        return outcome(fills, nextTier);
    }

    /**
     * The stop level to use from the next bar onward, given how many tiers have filled.
     * {@code Ratchet.NONE} always returns the current level unchanged.
     */
    private static double ratchetedStop(
            Ratchet ratchet, int tiersFilled, boolean isLong, double entryPrice,
            double currentStop, List<TierLevel> tiers) {
        return switch (ratchet) {
            case NONE -> currentStop;
            case BREAKEVEN_AFTER_T1 -> {
                if (tiersFilled >= 2) {
                    double t1 = tiers.get(0).targetDist();
                    yield isLong ? entryPrice + t1 : entryPrice - t1;
                }
                yield tiersFilled >= 1 ? entryPrice : currentStop;
            }
            case LAGGED -> tiersFilled >= 2 ? entryPrice : currentStop;
        };
    }

    private static TieredExitOutcome outcome(List<TierFill> fills, int tiersFilled) {
        return new TieredExitOutcome(List.copyOf(fills), tiersFilled, tiersFilled >= 1);
    }

    /** One resolved exit: the bar it happened on, the fill price, and why. */
    record ExitOutcome(int index, double price, ExitReason reason) {
    }

    /** One rung of the ladder, in price distance from entry (not ATR multiples). */
    record TierLevel(double fraction, double targetDist) {
    }

    /** One tranche closing: which bar, at what price, how much of the position, and why. */
    record TierFill(int index, double price, double fraction, ExitReason reason) {
    }

    /**
     * The full scale-out result. {@code weightedPrice()} is the size-weighted average fill, which —
     * because pnl is linear in price and the fractions sum to 1 — yields exactly the size-weighted
     * sum of the per-tier pnls when passed to {@code pnl(direction, entryPrice, ...)}.
     */
    record TieredExitOutcome(List<TierFill> fills, int tiersFilled, boolean hitT1) {

        /** The bar the position finished closing on. */
        int index() {
            return fills.get(fills.size() - 1).index();
        }

        double weightedPrice() {
            double sum = 0.0;
            for (TierFill fill : fills) {
                sum += fill.fraction() * fill.price();
            }
            return sum;
        }

        /** Reason the final tranche closed. */
        ExitReason finalReason() {
            return fills.get(fills.size() - 1).reason();
        }
    }
```

- [ ] **Step 4: Run the new tests**

Run: `./mvnw test -Dtest=BacktestRunnerTieredExitTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Run the existing intrabar tests UNCHANGED — the regression gate**

Run: `./mvnw test -Dtest=BacktestRunnerIntrabarTest`
Expected: PASS, 6 tests, **with no edits to that file**. If any fails, the degenerate case has
diverged from current behaviour — fix `tieredExit`, do not touch the test.

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 42 tests run (34 + 8), 0 failures, 0 errors, 1 skipped.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/backtest/runner/BacktestRunner.java \
        src/test/java/io/g3tech/axetrader/backtest/runner/BacktestRunnerTieredExitTest.java
git commit -m "feat(backtest): scale-out tier ladder, with today's exit as the degenerate case

intrabarExit now delegates to tieredExit with a one-tier 100% ladder, so there is
one exit engine rather than two. The six existing intrabar tests pass unchanged,
which is what pins the refactor of money-handling code.

Conservative tie-break extended to tiers: a bar spanning the stop and any unfilled
tier banks nothing and stops out."
```

---

### Task 3: Ratchet behaviour

**Files:**
- Modify: none (implemented in Task 2's `ratchetedStop`) — this task is the test gate that proves it.
- Test: `src/test/java/io/g3tech/axetrader/backtest/runner/BacktestRunnerRatchetTest.java` (create)

**Interfaces:**
- Consumes: `tieredExit`, `TierLevel`, `TieredExitOutcome`, `Ratchet` from Tasks 1-2.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/g3tech/axetrader/backtest/runner/BacktestRunnerRatchetTest.java`:

```java
package io.g3tech.axetrader.backtest.runner;

import io.g3tech.axetrader.backtest.config.Ratchet;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The ratchet is the lever aimed at drawdown: it converts would-be losers into scratches, at the
 * cost of scratching out trades that would have run. All three arms are swept, so all three are
 * pinned here.
 *
 * <p>Fixture: LONG at 100, stop 3.0 (level 97), tiers at 0.75 / 1.5 / 3.0.
 */
class BacktestRunnerRatchetTest {

    private static final double ENTRY = 100.0;
    private static final double STOP_DIST = 3.0;

    private static final List<BacktestRunner.TierLevel> THIRDS = List.of(
            new BacktestRunner.TierLevel(1.0 / 3.0, 0.75),
            new BacktestRunner.TierLevel(1.0 / 3.0, 1.5),
            new BacktestRunner.TierLevel(1.0 / 3.0, 3.0));

    @Test
    void breakevenAfterT1ClosesRemainderAtEntryNotAtTheOriginalStop() {
        // Bar1 fills T1 only. Bar2 falls back to 99.5 -- through breakeven (100) but nowhere near 97.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.8, 99.9, 100.6),
                bar(100.7, 99.5, 99.6));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS,
                Ratchet.BREAKEVEN_AFTER_T1, 0);

        assertThat(out.tiersFilled()).isEqualTo(1);
        assertThat(out.fills()).last().satisfies(f -> {
            assertThat(f.reason()).isEqualTo(ExitReason.STOP);
            assertThat(f.price()).isCloseTo(100.0, within(1e-9)); // breakeven, not 97
        });
        // (100.75 + 2 x 100.0) / 3 -- a small net win rather than a full loss.
        assertThat(out.weightedPrice()).isCloseTo((100.75 + 200.0) / 3.0, within(1e-9));
    }

    @Test
    void breakevenAfterT1MovesStopToT1LevelOnceT2Fills() {
        // Bar1 fills T1 and T2. Bar2 falls to 100.5 -- through the T1 level (100.75).
        BarSeries series = series(
                bar(100, 100, 100),
                bar(101.6, 99.9, 101.4),
                bar(101.5, 100.5, 100.6));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS,
                Ratchet.BREAKEVEN_AFTER_T1, 0);

        assertThat(out.tiersFilled()).isEqualTo(2);
        assertThat(out.fills()).last().satisfies(f -> {
            assertThat(f.reason()).isEqualTo(ExitReason.STOP);
            assertThat(f.price()).isCloseTo(100.75, within(1e-9)); // ratcheted to T1
        });
    }

    @Test
    void noneLeavesTheStopAtItsOriginalLevelAfterT1() {
        // Same bars as the breakeven test: with NONE the dip to 99.5 must NOT close anything.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.8, 99.9, 100.6),
                bar(100.7, 99.5, 99.6));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS, Ratchet.NONE, 0);

        assertThat(out.tiersFilled()).isEqualTo(1);
        assertThat(out.fills()).last().satisfies(f -> {
            assertThat(f.reason()).isEqualTo(ExitReason.END);
            assertThat(f.price()).isCloseTo(99.6, within(1e-9)); // last close, stop never hit
        });
    }

    @Test
    void laggedStillUsesTheOriginalStopAfterOnlyT1() {
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.8, 99.9, 100.6),
                bar(100.7, 99.5, 99.6));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS, Ratchet.LAGGED, 0);

        assertThat(out.fills()).last()
                .satisfies(f -> assertThat(f.reason()).isEqualTo(ExitReason.END));
    }

    @Test
    void laggedMovesToBreakevenOnceT2Fills() {
        BarSeries series = series(
                bar(100, 100, 100),
                bar(101.6, 99.9, 101.4),  // T1 + T2 fill
                bar(101.5, 99.8, 99.9));  // dips through breakeven (100), far above 97

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS, Ratchet.LAGGED, 0);

        assertThat(out.tiersFilled()).isEqualTo(2);
        assertThat(out.fills()).last().satisfies(f -> {
            assertThat(f.reason()).isEqualTo(ExitReason.STOP);
            assertThat(f.price()).isCloseTo(100.0, within(1e-9));
        });
    }

    @Test
    void ratchetDoesNotApplyWithinTheBarThatFilledTheTier() {
        // Bar1 tags T1 (100.75) AND dips to 99.5 -- below breakeven. Because the ratchet only takes
        // effect from the next bar, the position must NOT close at breakeven on this bar.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.8, 99.5, 100.2),
                bar(100.4, 100.1, 100.3));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS,
                Ratchet.BREAKEVEN_AFTER_T1, 0);

        assertThat(out.tiersFilled()).isEqualTo(1);
        assertThat(out.fills()).last().satisfies(f -> {
            assertThat(f.reason()).isEqualTo(ExitReason.END);
            assertThat(f.index()).isEqualTo(2);
        });
    }

    private static BarSeries series(double[]... bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName("ratchet-test").build();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < bars.length; i++) {
            double[] b = bars[i];
            series.barBuilder()
                    .timePeriod(Duration.ofMinutes(5))
                    .endTime(start.plus(Duration.ofMinutes(5L * (i + 1))))
                    .openPrice(b[2])
                    .highPrice(b[0])
                    .lowPrice(b[1])
                    .closePrice(b[2])
                    .volume(1)
                    .add();
        }
        return series;
    }

    /** {@code {high, low, close}} for one bar. */
    private static double[] bar(double high, double low, double close) {
        return new double[] {high, low, close};
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./mvnw test -Dtest=BacktestRunnerRatchetTest`
Expected: PASS, 6 tests. If `ratchetDoesNotApplyWithinTheBarThatFilledTheTier` or
`breakevenAfterT1MovesStopToT1LevelOnceT2Fills` fails, the bug is in `ratchetedStop` or in where it
is called relative to the tier-filling loop in Task 2 — fix the implementation, not the test.

- [ ] **Step 3: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 48 tests run, 0 failures, 0 errors, 1 skipped.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/io/g3tech/axetrader/backtest/runner/BacktestRunnerRatchetTest.java
git commit -m "test(backtest): pin all three ratchet arms

Includes the case that ratcheting must NOT apply within the bar that filled the
tier -- OHLC cannot show whether the ratcheted level was touched before or after
the tier, so applying it early would book exits that cannot be proven."
```

---

### Task 4: Wire the ladder into `TradeResult` and the runner

**Files:**
- Modify: `src/main/java/io/g3tech/axetrader/backtest/runner/TradeResult.java`
- Modify: `src/main/java/io/g3tech/axetrader/backtest/runner/BacktestRunner.java:72-130`
  (`toTradeResult`)
- Modify (compile fixes only — add the two new args): `BacktestChartExporter.java`,
  `ExperimentStore.java`, `DashboardExporter.java`, and any test constructing a `TradeResult`
  directly.
- Test: `src/test/java/io/g3tech/axetrader/backtest/runner/BacktestRunnerTieredExitTest.java` (add
  cases)

**Interfaces:**
- Consumes: `tieredExit`, `TieredExitOutcome` (Task 2); `Strategy.getExit()` (Task 1).
- Produces: `TradeResult` with two new trailing components `int tiersFilled` and `boolean hitT1`.

- [ ] **Step 1: Add the fields to TradeResult**

Edit `src/main/java/io/g3tech/axetrader/backtest/runner/TradeResult.java` to append two components:

```java
public record TradeResult(
        ZonedDateTime entryTime,
        ZonedDateTime exitTime,
        Direction direction,
        double entryPrice,
        double exitPrice,
        double pnl,
        double rMultiple,
        VolatilityRegime regime,
        boolean isWin,
        ExitReason exitReason,
        TradeFeatures features,
        List<String> reasons,
        int tiersFilled,
        boolean hitT1
) {
}
```

- [ ] **Step 2: Run the build to find every call site**

Run: `./mvnw -q test-compile`
Expected: FAIL, with a compilation error at each place a `TradeResult` is constructed. Note them —
that list is the work for Step 3.

- [ ] **Step 3: Update the runner to use the ladder**

In `BacktestRunner.toTradeResult`, replace the `else` branch (the confluence path) with:

```java
        } else {
            // Confluence path: model the stop/target as a bracket posted at entry and fill AT the
            // level intrabar. With a tier ladder configured, bank a fraction at each rung and
            // ratchet the stop; with no ladder, this is one 100% tier at targetAtrMultiple, which
            // is exactly the previous behaviour.
            double stopDist = config.getStopAtrMultiple() * atrAtEntry;
            List<TierLevel> tiers = tierLevels(config, atrAtEntry);

            TieredExitOutcome outcome = tieredExit(
                    series, direction, entryIndex, entryPrice, stopDist, tiers,
                    config.getExit().getRatchet(), config.getMaxHoldingBars());

            exitIndex = outcome.index();
            exitPrice = outcome.weightedPrice();
            exitReason = outcome.finalReason();
            riskPerUnit = stopDist == 0.0 ? atrAtEntry : stopDist;
            tiersFilled = outcome.tiersFilled();
            hitT1 = outcome.hitT1();
        }
```

Declare the two new locals alongside the existing ones, above the `if (config == null)`:

```java
        int tiersFilled = 0;
        boolean hitT1 = false;
```

In the `config == null` legacy branch, set them to reflect a single all-or-nothing exit. Key off the
**exit reason**, not the sign of pnl — "a tier filled" means the target was reached, which is not the
same thing as finishing in profit (a TIME exit can close green without ever tagging the target):

```java
            tiersFilled = exitReason == ExitReason.TARGET ? 1 : 0;
            hitT1 = tiersFilled == 1;
```

Add this helper next to `tieredExit`:

```java
    /**
     * Converts the configured ladder (ATR multiples) into engine tiers (price distances). An empty
     * ladder yields the single 100% tier at {@code targetAtrMultiple} — today's behaviour.
     */
    private static List<TierLevel> tierLevels(
            BacktestProperties.Strategy config, double atrAtEntry) {
        List<BacktestProperties.Strategy.ExitTier> configured = config.getExit().getTiers();
        if (configured.isEmpty()) {
            return List.of(new TierLevel(1.0, config.getTargetAtrMultiple() * atrAtEntry));
        }
        List<TierLevel> levels = new ArrayList<>(configured.size());
        for (BacktestProperties.Strategy.ExitTier tier : configured) {
            levels.add(new TierLevel(
                    tier.getFraction(), tier.getTargetAtrMultiple() * atrAtEntry));
        }
        return levels;
    }
```

Finally, pass the two new values into the `TradeResult` constructor at the end of `toTradeResult`,
after `reasons`:

```java
                reasons,
                tiersFilled,
                hitT1);
```

- [ ] **Step 4: Fix the remaining call sites**

For each compile error from Step 2 in `BacktestChartExporter.java`, `ExperimentStore.java`,
`DashboardExporter.java` and any test: these all *read* `TradeResult` rather than construct it, so
most need no change. Where a test constructs one directly, append `, 1, true` for a winning fixture
and `, 0, false` for a losing one. Do not change any existing assertion.

- [ ] **Step 5: Add end-to-end assertions for the new fields**

Append to `BacktestRunnerTieredExitTest`:

```java
    @Test
    void tiersFilledAndHitT1ReportTheFullLossCase() {
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.2, 96.5, 97.0));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS, Ratchet.NONE, 0);

        assertThat(out.tiersFilled()).isZero();
        assertThat(out.hitT1()).isFalse();
    }

    @Test
    void tiersFilledAndHitT1ReportTheStopAfterT1Case() {
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.8, 99.9, 100.6),
                bar(100.7, 96.5, 97.0));

        BacktestRunner.TieredExitOutcome out = BacktestRunner.tieredExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, THIRDS, Ratchet.NONE, 0);

        assertThat(out.tiersFilled()).isEqualTo(1);
        assertThat(out.hitT1()).isTrue();
    }
```

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 50 tests run, 0 failures, 0 errors, 1 skipped. **`BacktestRunnerTest` and
`BacktestChartExporterTest` must pass unchanged** — they exercise the real backtest, so an unchanged
result there is the end-to-end proof that the empty ladder is behaviour-preserving.

- [ ] **Step 7: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(backtest): wire the tier ladder through TradeResult

exitPrice is now the size-weighted average fill, which -- because pnl is linear
in price and fractions sum to 1 -- leaves the existing pnl calculation correct
without change. Adds tiersFilled and hitT1; hitT1 is the metric comparable to
the pre-scale-out win rate, since pnl>0 is inflated by construction once a trade
can bank a third and scratch the rest."
```

---

### Task 5: Sweep arms and honest metrics

**Files:**
- Modify: `src/test/java/io/g3tech/axetrader/backtest/ConfluenceSweepTest.java` (grid builder around
  line 222; results table around line 155)

**Interfaces:**
- Consumes: everything above.
- Produces: sweep output with a `hitT1%` column and 10 arms (9 + control).

- [ ] **Step 1: Add the stage-1 arms to the grid**

In the grid builder in `ConfluenceSweepTest.java`, after the existing `emaCeil_*` loop, add:

```java
        // ---- Stage 1 of the tiered scale-out experiment ----
        // Spec: docs/superpowers/specs/2026-07-21-tiered-scale-out-exits-design.md
        // Anchor is the emaCeil champion. T1/T2 fixed at 0.75/1.5 ATR (T1 is where the ~80% hit
        // rate is already demonstrated -- moving it would change entry edge and exit geometry at
        // once). T3 and the ratchet are swept. The single-target control runs on the same data and
        // the same code, so nothing is compared against historical numbers from another window.
        BacktestProperties.Strategy scaleOutAnchor = variant(promoted, s -> s.setTrendEmaMaxAtr(3.0));

        grid.put("control_singleTarget_0.75", variant(scaleOutAnchor, s -> {
            // Empty ladder == today's all-or-nothing exit. This is the baseline every arm must beat.
        }));

        for (double t3 : new double[] {2.0, 3.0, 4.0}) {
            for (Ratchet ratchet : Ratchet.values()) {
                grid.put("tier3_t3-%.1f_%s".formatted(t3, ratchet.name().toLowerCase()),
                        variant(scaleOutAnchor, s -> s.setExit(ladder(t3, ratchet))));
            }
        }
```

Add this helper to the same test class:

```java
    /** A three-equal-thirds ladder at 0.75 / 1.5 / t3 ATR with the given ratchet. */
    private static BacktestProperties.Strategy.Exit ladder(double t3, Ratchet ratchet) {
        BacktestProperties.Strategy.Exit exit = new BacktestProperties.Strategy.Exit();
        exit.setTiers(List.of(tier(1.0 / 3.0, 0.75), tier(1.0 / 3.0, 1.5), tier(1.0 / 3.0, t3)));
        exit.setRatchet(ratchet);
        exit.validate();
        return exit;
    }

    private static BacktestProperties.Strategy.ExitTier tier(double fraction, double target) {
        BacktestProperties.Strategy.ExitTier t = new BacktestProperties.Strategy.ExitTier();
        t.setFraction(fraction);
        t.setTargetAtrMultiple(target);
        return t;
    }
```

Add `import io.g3tech.axetrader.backtest.config.Ratchet;` and `import java.util.List;` if absent.

- [ ] **Step 2: 🔴 Teach `copy()` about the exit ladder — without this the whole sweep is a no-op**

`ConfluenceSweepTest.copy(...)` (line ~252) copies the `Strategy` **field by field**. It does not
know about `exit`, so every arm built through `variant(...)` would receive a fresh, *empty* ladder —
all nine arms would silently run as the single-target control, produce nine identical rows, and
nothing on screen would say so.

Add to `copy(...)`, alongside the other field copies, a **deep** copy:

```java
        BacktestProperties.Strategy.Exit exitCopy = new BacktestProperties.Strategy.Exit();
        exitCopy.setRatchet(s.getExit().getRatchet());
        List<BacktestProperties.Strategy.ExitTier> tierCopies = new ArrayList<>();
        for (BacktestProperties.Strategy.ExitTier t : s.getExit().getTiers()) {
            tierCopies.add(tier(t.getFraction(), t.getTargetAtrMultiple()));
        }
        exitCopy.setTiers(tierCopies);
        c.setExit(exitCopy);
```

Add `import java.util.ArrayList;` if absent.

- [ ] **Step 3: Prove the arms actually differ**

Add this test to `ConfluenceSweepTest` — it runs without `-Dsweep=true` and guards the trap above:

```java
    @Test
    void gridArmsCarryDistinctExitLaddersRatherThanSharingOne() {
        Map<String, BacktestProperties.Strategy> grid = grid();

        assertThat(grid).containsKey("control_singleTarget_0.75");
        assertThat(grid.get("control_singleTarget_0.75").getExit().getTiers()).isEmpty();

        BacktestProperties.Strategy none = grid.get("tier3_t3-3.0_none");
        BacktestProperties.Strategy breakeven = grid.get("tier3_t3-3.0_breakeven_after_t1");

        assertThat(none.getExit().getTiers()).hasSize(3);
        assertThat(none.getExit().getRatchet()).isEqualTo(Ratchet.NONE);
        assertThat(breakeven.getExit().getRatchet()).isEqualTo(Ratchet.BREAKEVEN_AFTER_T1);

        // T3 must actually vary across arms.
        assertThat(grid.get("tier3_t3-2.0_none").getExit().getTiers().get(2).getTargetAtrMultiple())
                .isEqualTo(2.0);
        assertThat(grid.get("tier3_t3-4.0_none").getExit().getTiers().get(2).getTargetAtrMultiple())
                .isEqualTo(4.0);
    }
```

If the grid builder method is not named `grid()`, use its actual name. Run:
`./mvnw test -Dtest=ConfluenceSweepTest` — expected PASS. **If this fails with all ladders empty,
Step 2 was not applied.**

- [ ] **Step 4: Add the hitT1 column to the results table**

`SweepResult` holds `int trades` (a *count*, not a list), so the rate must be computed in
`SweepResult.of(...)` where the `List<TradeResult>` is still in scope. Add a `double hitT1Rate`
component to the record — place it immediately after `netWinRate` — and compute it in `of(...)`:

```java
            long t1Hits = trades.stream().filter(TradeResult::hitT1).count();
```

passing `(double) t1Hits / count` in the constructor call, and `0` in the empty-result branch
(`new SweepResult(id, 0, 0, 0, 0, 0, 0, 0, 0)` — note the extra zero).

In the results-printing loop (around line 155), print `hitT1%` as
`100.0 * r.hitT1Rate()` immediately after the existing `win%` column. Both are printed because —
per the spec — `win%` is inflated by construction under scale-out and `hitT1%` is the figure
comparable to the pre-scale-out ~80%.

- [ ] **Step 5: Run the sweep in-sample**

Run:
```bash
gzip -dc data/axe-trader.sqlite.gz > data/axe-trader.sqlite   # fresh container only
./mvnw test -Dtest=ConfluenceSweepTest -Dsweep=true
```
Expected: a results table with 10 new rows — `control_singleTarget_0.75` plus nine `tier3_*` arms —
each showing trades, per-day, win%, hitT1%, netAvgPnl and $net.

**Do not consult the out-of-sample window yet.** Rank in-sample first, per the spec.

Sanity check before trusting the table: the nine `tier3_*` rows must **not** all be identical to
`control_singleTarget_0.75`. If they are, Step 2 was skipped and the ladders never reached the runner.

- [ ] **Step 6: Commit the arms and the in-sample result**

```bash
git add src/test/java/io/g3tech/axetrader/backtest/ConfluenceSweepTest.java TODO.md
git commit -m "feat(sweep): stage-1 tiered scale-out arms + hitT1 metric

Nine arms (T3 x ratchet) plus a single-target control on identical data and code.
Reports hitT1% alongside win% because win% is inflated by construction once a
trade can bank a third and scratch the rest."
```

Log the in-sample table in `TODO.md`'s tuning log in the same commit, per the repo's
commit-every-iteration rule.

---

### Task 6: Surface `tiers_filled` / `hit_t1` on the review dashboard

**Files:**
- Create: `dashboard/migrations/0003_tier_fields.sql`
- Modify: `src/main/java/io/g3tech/axetrader/backtest/experiment/DashboardExporter.java`
- Modify: `dashboard/src/schema.ts`, `dashboard/scripts/sql.ts`, `dashboard/frontend/src/types.ts`
- Test: `src/test/java/io/g3tech/axetrader/backtest/experiment/DashboardExporterTest.java`,
  `dashboard/test/push-sql.test.ts`

**Interfaces:**
- Consumes: `TradeResult.tiersFilled()` / `hitT1()` from Task 4.
- Produces: two nullable D1 columns on `trades`.

**Why:** on the phone, "stopped flat after banking two thirds" and "stopped for a full loss" are
completely different trades that currently render identically. Both columns are **nullable** so the
existing run's rows stay valid.

- [ ] **Step 1: Write the migration**

Create `dashboard/migrations/0003_tier_fields.sql`:

```sql
-- Scale-out detail. Nullable: runs exported before the tiered-exit work have no tier data, and a
-- NULL must stay distinguishable from a genuine 0 tiers filled (a full loss).
ALTER TABLE trades ADD COLUMN tiers_filled INTEGER;
ALTER TABLE trades ADD COLUMN hit_t1 INTEGER;
```

- [ ] **Step 2: Write the failing exporter test**

Add to `DashboardExporterTest.java` a case asserting that a `TradeResult` with `tiersFilled = 2`,
`hitT1 = true` exports `"tiers_filled": 2` and `"hit_t1": 1` (SQLite has no boolean; store 0/1).
Follow the assertion style already in that file.

- [ ] **Step 3: Run it and watch it fail**

Run: `./mvnw test -Dtest=DashboardExporterTest`
Expected: FAIL — the keys are absent from the exported JSON.

- [ ] **Step 4: Emit the fields**

In `DashboardExporter.java`, where the per-trade node is built (alongside `net_pnl`), add:

```java
            n.put("tiers_filled", t.tiersFilled());
            n.put("hit_t1", t.hitT1() ? 1 : 0);
```

- [ ] **Step 5: Thread them through the push script and types**

- `dashboard/scripts/sql.ts`: include `tiers_filled` and `hit_t1` in the `trades` INSERT column list
  and values, using the existing `q()` helper so nulls are handled.
- `dashboard/src/schema.ts`: add `tiers_filled: number | null;` and `hit_t1: number | null;` to the
  trade row type. **Nullable — the columns are nullable in SQL.**
- `dashboard/frontend/src/types.ts`: same two fields, same nullability.

- [ ] **Step 6: Verify everything**

```bash
./mvnw test -Dtest=DashboardExporterTest        # expected: PASS
cd dashboard && npm test && npm run typecheck   # expected: api + ui green, typecheck clean
cd dashboard && npm run migrate:local           # applies 0003 locally
```

- [ ] **Step 7: Commit**

```bash
git add dashboard/migrations/0003_tier_fields.sql dashboard/src/schema.ts \
        dashboard/scripts/sql.ts dashboard/frontend/src/types.ts \
        src/main/java/io/g3tech/axetrader/backtest/experiment/DashboardExporter.java \
        src/test/java/io/g3tech/axetrader/backtest/experiment/DashboardExporterTest.java \
        dashboard/test/push-sql.test.ts
git commit -m "feat(dashboard): export tiers_filled and hit_t1

On the phone, 'stopped flat after banking two thirds' and 'stopped for a full
loss' are different trades that currently look identical. Columns are nullable so
the already-exported run stays valid, and so NULL stays distinguishable from a
genuine 0 tiers filled."
```

**Note:** `npm run migrate:remote` is a production write — do not run it without asking the human.

---

### Task 7: Out-of-sample validation against the pre-registered gate

**Files:**
- Modify: `TODO.md` (tuning log)
- No production code changes expected.

**Interfaces:**
- Consumes: the in-sample ranking from Task 5.

- [ ] **Step 1: Fix the in-sample ranking BEFORE looking at OOS**

Write the in-sample ranking into `TODO.md` and commit it. This timestamps the ranking before the
held-out data is consulted, which is what makes the OOS test meaningful rather than a second bite at
the same apple.

- [ ] **Step 2: Run the held-out window**

Run:
```bash
./mvnw test -Dtest=ConfluenceSweepTest -Dsweep=true \
    -Dsweep.from=2026-01-01T00:00:00Z -Dsweep.to=2026-05-02T00:00:00Z
```

- [ ] **Step 3: Apply the gate exactly as written**

For each arm, check all four conditions from the spec:
1. Beats `control_singleTarget_0.75` on net pts/trade, OOS.
2. Max drawdown no worse than the control's, OOS.
3. At least 30 trades OOS.
4. Net-positive in ≥3 of 4 in-sample quarters.

Rank survivors by total net ÷ max drawdown.

**If no arm passes, report that.** Do not relax a threshold, do not add arms and re-run, and do not
re-rank using the OOS numbers. "Scale-out did not fix it" is a real and publishable result, and the
spec commits us to reporting it.

- [ ] **Step 4: Record the outcome and commit**

Append to `TODO.md`: the OOS table, which arms passed each of the four conditions, the ranking of
survivors, and the promotion decision (including "none"). If an arm is promoted, update
`application.yaml`'s `backtest.strategy.exit` block to match it in the same commit.

```bash
git add TODO.md src/main/resources/application.yaml
git commit -m "experiment: tiered scale-out stage 1 -- OOS result and promotion decision"
```

- [ ] **Step 5: Push**

```bash
git push
```

---

## Notes for the implementer

- **Do not edit `BacktestRunnerIntrabarTest`.** It is the regression gate for the refactor. If it
  fails, the implementation diverged from current behaviour.
- **Do not tune the gate to the results.** The success criteria in the spec were fixed before any
  numbers existed. If they turn out to be wrong criteria, say so and stop — that is a conversation
  with the human, not a unilateral edit.
- **`win%` will go up and it will not mean what it looks like.** Report `hitT1%` beside it every
  time. This project has been misled by flattering metrics three times already.
