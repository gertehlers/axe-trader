package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.ConfluenceEntryDetector;
import io.g3tech.axetrader.strategy.ConfluenceSettings;
import io.g3tech.axetrader.strategy.IndicatorCalculator;
import io.g3tech.axetrader.strategy.backtest.data.FakeHistoricalCandleSource;
import io.g3tech.axetrader.strategy.backtest.data.HistoricalCandleSource;
import io.g3tech.axetrader.strategy.backtest.data.HistoricalPriceRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BulkBacktestRunnerTests {

    @Test
    void runsBulkBacktestFromHistoricalCandleSource() {
        var detector = new ConfluenceEntryDetector(
                new IndicatorCalculator(),
                new ConfluenceSettings(60, 10, 8, 15, 0.65, 0.35, 1.5, 10)
        );
        HistoricalCandleSource source = new FakeHistoricalCandleSource()::load;
        var runner = new BulkBacktestRunner(source, detector);
        var request = new HistoricalPriceRequest(
                "US500",
                "MINUTE_5",
                Instant.parse("2026-04-30T00:00:00Z"),
                Instant.parse("2026-04-30T08:00:00Z"),
                90
        );

        var report = runner.run(request, new BacktestSettings(60, 1.0));

        assertThat(report.epic()).isEqualTo("US500");
        assertThat(report.resolution()).isEqualTo("MINUTE_5");
        assertThat(report.candlesProcessed()).isGreaterThanOrEqualTo(90);
        assertThat(report.signalsDetected()).isGreaterThan(0);
        assertThat(report.tradesClosed()).isGreaterThan(0);
        assertThat(report.exitReasons()).isNotEmpty();
    }
}
