package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.ConfluenceEntryDetector;
import io.g3tech.axetrader.strategy.ConfluenceSettings;
import io.g3tech.axetrader.strategy.IndicatorCalculator;
import io.g3tech.axetrader.strategy.backtest.data.FakeHistoricalCandleSource;
import io.g3tech.axetrader.strategy.backtest.data.HistoricalCandleSource;
import io.g3tech.axetrader.strategy.backtest.data.HistoricalPriceRequest;
import io.g3tech.axetrader.strategy.backtest.persistence.BacktestPersistenceSettings;
import io.g3tech.axetrader.strategy.backtest.persistence.SQLiteBacktestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BulkBacktestRunnerTests {

    @TempDir
    Path tempDir;

    @Test
    void runsBulkBacktestFromHistoricalCandleSource() {
        var detector = new ConfluenceEntryDetector(
                new IndicatorCalculator(),
                new ConfluenceSettings(60, 10, 8, 15, 0.65, 0.35, 1.5, 10)
        );
        HistoricalCandleSource source = new FakeHistoricalCandleSource()::load;
        var store = new SQLiteBacktestStore(new BacktestPersistenceSettings(tempDir.resolve("bulk.sqlite").toString()));
        var runner = new BulkBacktestRunner(source, detector, new IndicatorCalculator(), store);
        var request = new HistoricalPriceRequest(
                "US500",
                "MINUTE_5",
                Instant.parse("2026-04-30T00:00:00Z"),
                Instant.parse("2026-04-30T08:00:00Z"),
                90
        );

        var report = runner.run(request, new BacktestSettings(60, 1.0));

        assertThat(report.epic()).isEqualTo("US500");
        assertThat(report.runId()).isPositive();
        assertThat(report.resolution()).isEqualTo("MINUTE_5");
        assertThat(report.candlesProcessed()).isGreaterThanOrEqualTo(90);
        assertThat(report.signalsDetected()).isGreaterThan(0);
        assertThat(report.tradesClosed()).isGreaterThan(0);
        assertThat(report.resolvedSignals()).isGreaterThan(0);
        assertThat(report.correctSignals()).isGreaterThanOrEqualTo(0);
        assertThat(report.accuracy()).isBetween(0.0, 1.0);
        assertThat(report.accuracyByDirection()).isNotEmpty();
        assertThat(report.accuracyByVolatilityRegime()).isNotEmpty();
        assertThat(report.accuracyByTrendRegime()).isNotEmpty();
        assertThat(report.candidateGate()).isNotNull();
        assertThat(report.candidateGate().requiredAccuracy()).isEqualTo(0.75);
        assertThat(report.candidateGate().candidateStrategy())
                .isEqualTo(report.candidateGate().meetsAccuracyGate()
                        && report.candidateGate().meetsProfitGate()
                        && report.candidateGate().meetsSampleSizeGate());
        assertThat(report.exitReasons()).isNotEmpty();
    }
}
