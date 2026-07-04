package io.g3tech.axetrader.backtest.runner;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the intrabar stop/target bracket ({@link BacktestRunner#intrabarExit}). This is the
 * money-critical exit model — it decides win vs. loss and the fill price — so the tie-break and the
 * fill-at-level behavior are pinned here directly, without Spring or the history DB.
 *
 * <p>Fixture: LONG entry at 100 with a 3.0-point stop (level 97) and a 0.75-point target (level
 * 100.75), mirroring the promoted US500 geometry (wide stop / tight target).
 */
class BacktestRunnerIntrabarTest {

    private static final double ENTRY = 100.0;
    private static final double STOP_DIST = 3.0;    // stop level 97.00
    private static final double TARGET_DIST = 0.75; // target level 100.75

    @Test
    void targetTouchedFillsAtTargetLevel() {
        // Bar 1 reaches up to 101 (>= 100.75) without dipping to the stop.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(101.0, 99.5, 100.5));

        BacktestRunner.ExitOutcome out = BacktestRunner.intrabarExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, TARGET_DIST, 0);

        assertThat(out.index()).isEqualTo(1);
        assertThat(out.reason()).isEqualTo(ExitReason.TARGET);
        assertThat(out.price()).isCloseTo(100.75, within(1e-9));
    }

    @Test
    void stopTouchedFillsAtStopLevel() {
        // Bar 1 dips to 96 (<= 97) without reaching the target.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.5, 96.0, 98.0));

        BacktestRunner.ExitOutcome out = BacktestRunner.intrabarExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, TARGET_DIST, 0);

        assertThat(out.index()).isEqualTo(1);
        assertThat(out.reason()).isEqualTo(ExitReason.STOP);
        assertThat(out.price()).isCloseTo(97.0, within(1e-9));
    }

    @Test
    void barSpanningBothLevelsResolvesConservativelyToStop() {
        // Bar 1 range 96..101 touches BOTH levels; OHLC can't reveal order, so we assume the stop.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(101.0, 96.0, 100.5));

        BacktestRunner.ExitOutcome out = BacktestRunner.intrabarExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, TARGET_DIST, 0);

        assertThat(out.reason()).isEqualTo(ExitReason.STOP);
        assertThat(out.price()).isCloseTo(97.0, within(1e-9));
    }

    @Test
    void neitherLevelTouchedForceClosesAtLastBarClose() {
        // Price drifts inside the bracket the whole time -> END at the last close.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.4, 98.5, 99.0),
                bar(100.6, 98.0, 99.5));

        BacktestRunner.ExitOutcome out = BacktestRunner.intrabarExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, TARGET_DIST, 0);

        assertThat(out.index()).isEqualTo(2);
        assertThat(out.reason()).isEqualTo(ExitReason.END);
        assertThat(out.price()).isCloseTo(99.5, within(1e-9));
    }

    @Test
    void timeStopFiresAtCloseWhenNeitherLevelHit() {
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.4, 98.5, 99.0),
                bar(100.6, 98.0, 99.7),
                bar(100.5, 98.2, 99.3));

        BacktestRunner.ExitOutcome out = BacktestRunner.intrabarExit(
                series, Direction.LONG, 0, ENTRY, STOP_DIST, TARGET_DIST, 2);

        assertThat(out.index()).isEqualTo(2); // entryIndex + maxHoldingBars
        assertThat(out.reason()).isEqualTo(ExitReason.TIME);
        assertThat(out.price()).isCloseTo(99.7, within(1e-9));
    }

    @Test
    void shortTargetTouchedFillsAtTargetLevel() {
        // SHORT entry at 100: stop level 103, target level 99.25. Bar dips to 99 (<= 99.25).
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.5, 99.0, 99.5));

        BacktestRunner.ExitOutcome out = BacktestRunner.intrabarExit(
                series, Direction.SHORT, 0, ENTRY, STOP_DIST, TARGET_DIST, 0);

        assertThat(out.index()).isEqualTo(1);
        assertThat(out.reason()).isEqualTo(ExitReason.TARGET);
        assertThat(out.price()).isCloseTo(99.25, within(1e-9));
    }

    private static BarSeries series(double[]... bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName("intrabar-test").build();
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
