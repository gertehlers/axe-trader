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
