package io.g3tech.axetrader.backtest.runner;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
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

    // ---- 3-tier scale-out (aggressive trail) ----------------------------------------------------
    // Fixture uses ATR = 1.0 so tier/stop multiples read directly as price offsets from ENTRY 100:
    // T1 level 100.75, T2 level 101.5, initial stop 97.0, trail distance 1.5.

    private static final double ATR = 1.0;
    private static final double T1 = 0.75;
    private static final double T2 = 1.5;
    private static final double TRAIL = 1.5;

    @Test
    void scaleOutStopBeforeAnyTierIsFullLoss() {
        // Bar dips to 96 (<= 97) without reaching T1 -> whole position out at the stop.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.2, 96.0, 98.0));

        BacktestRunner.ScaleOutOutcome out = BacktestRunner.scaleOutExit(
                series, Direction.LONG, 0, ENTRY, ATR, scaleOut(0));

        assertThat(out.index()).isEqualTo(1);
        assertThat(out.reason()).isEqualTo(ExitReason.STOP);
        assertThat(out.pnlPerUnit()).isCloseTo(-3.0, within(1e-9)); // 3/3 * -3.0
    }

    @Test
    void scaleOutBarSpanningStopAndT1ResolvesConservativelyToFullStop() {
        // Range 96..101 touches BOTH the stop and T1; adverse-first books the full stop, no tier.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(101.0, 96.0, 100.5));

        BacktestRunner.ScaleOutOutcome out = BacktestRunner.scaleOutExit(
                series, Direction.LONG, 0, ENTRY, ATR, scaleOut(0));

        assertThat(out.reason()).isEqualTo(ExitReason.STOP);
        assertThat(out.pnlPerUnit()).isCloseTo(-3.0, within(1e-9));
    }

    @Test
    void scaleOutT1FilledThenReversesExitsRemainderAtBreakeven() {
        // Bar 1 tags T1 (high 100.8) -> bank 1/3, stop to breakeven. Bar 2 dips to 99 -> the
        // remaining 2/3 exit at breakeven, so the trade is a small NET WIN, not a full-stop loss.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.8, 99.5, 100.2),
                bar(100.3, 99.0, 99.5));

        BacktestRunner.ScaleOutOutcome out = BacktestRunner.scaleOutExit(
                series, Direction.LONG, 0, ENTRY, ATR, scaleOut(0));

        assertThat(out.index()).isEqualTo(2);
        assertThat(out.reason()).isEqualTo(ExitReason.STOP); // stop mechanism, but at breakeven
        assertThat(out.pnlPerUnit()).isCloseTo(0.25, within(1e-9)); // 1/3*0.75 + 2/3*0
    }

    @Test
    void scaleOutFullTrailWinnerBanksAllThreeTranches() {
        // Bar 1 tags T1 and T2 (high 101.6). Bar 2 runs to 103 (peak) lifting the trail to 1.5.
        // Bar 3 pulls back to 101 (<= trail level 101.5) -> final 1/3 out on the trailing stop.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(101.6, 100.0, 101.2),
                bar(103.0, 102.0, 102.5),
                bar(102.0, 101.0, 101.3));

        BacktestRunner.ScaleOutOutcome out = BacktestRunner.scaleOutExit(
                series, Direction.LONG, 0, ENTRY, ATR, scaleOut(0));

        assertThat(out.index()).isEqualTo(3);
        assertThat(out.reason()).isEqualTo(ExitReason.TRAIL);
        // 1/3*0.75 + 1/3*1.5 + 1/3*1.5 = 1.25 -> beats the old 0.75 single-target cap.
        assertThat(out.pnlPerUnit()).isCloseTo(1.25, within(1e-9));
    }

    @Test
    void scaleOutShortFullTrailWinnerMirrorsLong() {
        // SHORT entry 100: T1 level 99.25, T2 98.5, stop 103, trail 1.5. Favorable = down.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.0, 98.4, 98.8),   // tags T1 and T2 (low 98.4)
                bar(98.0, 97.0, 97.5),    // peak progress 3.0 (low 97), trail lifts to level 98.5
                bar(99.0, 98.6, 98.9));   // high 99.0 -> adverse 1.0 <= trail 1.5, final 1/3 out

        BacktestRunner.ScaleOutOutcome out = BacktestRunner.scaleOutExit(
                series, Direction.SHORT, 0, ENTRY, ATR, scaleOut(0));

        assertThat(out.index()).isEqualTo(3);
        assertThat(out.reason()).isEqualTo(ExitReason.TRAIL);
        assertThat(out.pnlPerUnit()).isCloseTo(1.25, within(1e-9));
    }

    @Test
    void scaleOutTimeStopClosesRemainderAtClose() {
        // T1 fills bar 1; bar 2 hits the max-holding-bars limit with 2/3 still open -> close at 100.5.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.8, 99.8, 100.4),
                bar(100.9, 100.1, 100.5));

        BacktestRunner.ScaleOutOutcome out = BacktestRunner.scaleOutExit(
                series, Direction.LONG, 0, ENTRY, ATR, scaleOut(2));

        assertThat(out.index()).isEqualTo(2);
        assertThat(out.reason()).isEqualTo(ExitReason.TIME);
        // 1/3*0.75 + 2/3*0.5 = 0.5833...
        assertThat(out.pnlPerUnit()).isCloseTo(0.75 / 3.0 + 2.0 / 3.0 * 0.5, within(1e-9));
    }

    @Test
    void scaleOutEndOfDataClosesRemainderAtLastClose() {
        // T1 fills; data runs out with 2/3 open -> force close at the last bar's close 100.6.
        BarSeries series = series(
                bar(100, 100, 100),
                bar(100.8, 99.8, 100.4),
                bar(100.9, 100.1, 100.6));

        BacktestRunner.ScaleOutOutcome out = BacktestRunner.scaleOutExit(
                series, Direction.LONG, 0, ENTRY, ATR, scaleOut(0));

        assertThat(out.index()).isEqualTo(2);
        assertThat(out.reason()).isEqualTo(ExitReason.END);
        assertThat(out.pnlPerUnit()).isCloseTo(0.75 / 3.0 + 2.0 / 3.0 * 0.6, within(1e-9));
    }

    /** Scale-out config with the fixture geometry; {@code maxHold} 0 disables the time stop. */
    private static BacktestProperties.Strategy scaleOut(int maxHold) {
        BacktestProperties.Strategy c = new BacktestProperties.Strategy();
        c.setScaleOutEnabled(true);
        c.setStopAtrMultiple(STOP_DIST); // 3.0
        c.setTier1AtrMultiple(T1);
        c.setTier2AtrMultiple(T2);
        c.setTrailAtrMultiple(TRAIL);
        c.setMaxHoldingBars(maxHold);
        return c;
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
