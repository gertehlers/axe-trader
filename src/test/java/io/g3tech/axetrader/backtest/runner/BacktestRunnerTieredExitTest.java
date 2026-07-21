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
