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

class TieredExitEngineTest {

    @Test
    void longStopWinsAnIntrabarTieWithoutBankingATier() {
        TieredExitEngine.Outcome outcome = TieredExitEngine.tieredExit(
                series(bar(100, 100, 100), bar(102, 96, 100)), Direction.LONG, 0, 100, 3,
                List.of(new TieredExitEngine.TierLevel(1, .75)), Ratchet.NONE, 0);

        assertThat(outcome.fills()).singleElement().satisfies(fill -> {
            assertThat(fill.reason()).isEqualTo(ExitReason.STOP);
            assertThat(fill.price()).isCloseTo(97, within(1e-9));
        });
    }

    @Test
    void shortTierFillsAtTheDownwardLevel() {
        TieredExitEngine.Outcome outcome = TieredExitEngine.tieredExit(
                series(bar(100, 100, 100), bar(100.2, 98.5, 99)), Direction.SHORT, 0, 100, 3,
                List.of(new TieredExitEngine.TierLevel(1, 1.5)), Ratchet.NONE, 0);

        assertThat(outcome.fills()).singleElement().satisfies(fill -> {
            assertThat(fill.reason()).isEqualTo(ExitReason.TARGET);
            assertThat(fill.price()).isCloseTo(98.5, within(1e-9));
        });
    }

    @Test
    void multipleTiersFillInOrderAndWeightTheExit() {
        TieredExitEngine.Outcome outcome = TieredExitEngine.tieredExit(
                series(bar(100, 100, 100), bar(102, 99.5, 101.8), bar(103.2, 100, 103)), Direction.LONG, 0, 100, 3,
                List.of(new TieredExitEngine.TierLevel(.5, .75), new TieredExitEngine.TierLevel(.5, 3)),
                Ratchet.NONE, 0);

        assertThat(outcome.tiersFilled()).isEqualTo(2);
        assertThat(outcome.weightedPrice()).isCloseTo(101.875, within(1e-9));
    }

    @Test
    void ratchetOnlyProtectsFollowingBars() {
        TieredExitEngine.Outcome outcome = TieredExitEngine.tieredExit(
                series(bar(100, 100, 100), bar(100.8, 99.5, 100.5), bar(100.5, 99.8, 100)), Direction.LONG, 0, 100, 3,
                List.of(new TieredExitEngine.TierLevel(.5, .75), new TieredExitEngine.TierLevel(.5, 3)),
                Ratchet.BREAKEVEN_AFTER_T1, 0);

        assertThat(outcome.fills()).last().satisfies(fill -> {
            assertThat(fill.index()).isEqualTo(2);
            assertThat(fill.reason()).isEqualTo(ExitReason.STOP);
            assertThat(fill.price()).isCloseTo(100, within(1e-9));
        });
    }

    @Test
    void timeStopClosesTheUnfilledRemainderAtTheClose() {
        TieredExitEngine.Outcome outcome = TieredExitEngine.tieredExit(
                series(bar(100, 100, 100), bar(100.8, 99.5, 100.5), bar(100.7, 99.5, 100.2)), Direction.LONG, 0, 100, 3,
                List.of(new TieredExitEngine.TierLevel(.5, .75), new TieredExitEngine.TierLevel(.5, 3)),
                Ratchet.NONE, 2);

        assertThat(outcome.fills()).last().satisfies(fill -> {
            assertThat(fill.reason()).isEqualTo(ExitReason.TIME);
            assertThat(fill.price()).isCloseTo(100.2, within(1e-9));
        });
    }

    private static BarSeries series(double[]... bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName("tier-engine").build();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < bars.length; i++) {
            double[] bar = bars[i];
            series.barBuilder().timePeriod(Duration.ofMinutes(5)).endTime(start.plus(Duration.ofMinutes(5L * (i + 1))))
                    .openPrice(bar[2]).highPrice(bar[0]).lowPrice(bar[1]).closePrice(bar[2]).volume(1).add();
        }
        return series;
    }

    private static double[] bar(double high, double low, double close) {
        return new double[] {high, low, close};
    }
}
