package io.g3tech.axetrader.strategy.backtest.persistence;

import io.g3tech.axetrader.strategy.Candle;
import io.g3tech.axetrader.strategy.ConfluenceEntryDetector;
import io.g3tech.axetrader.strategy.ConfluenceSettings;
import io.g3tech.axetrader.strategy.Direction;
import io.g3tech.axetrader.strategy.IndicatorCalculator;
import io.g3tech.axetrader.strategy.backtest.BacktestSettings;
import io.g3tech.axetrader.strategy.backtest.ConfluenceBacktester;
import io.g3tech.axetrader.strategy.backtest.data.HistoricalPriceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SQLiteBacktestStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void savesBacktestRunSignalsAndTrades() throws Exception {
        var databasePath = tempDir.resolve("backtests.sqlite");
        var store = new SQLiteBacktestStore(new BacktestPersistenceSettings(databasePath.toString()));
        var detector = new ConfluenceEntryDetector(
                new IndicatorCalculator(),
                new ConfluenceSettings(60, 10, 8, 15, 0.65, 0.35, 1.5, 10)
        );
        var candles = trendingCandles();
        var settings = new BacktestSettings(60, 1.0);
        var result = new ConfluenceBacktester(detector, settings).run(candles);
        var request = new HistoricalPriceRequest(
                "US500",
                "MINUTE_5",
                candles.getFirst().openedAt(),
                candles.getLast().openedAt(),
                candles.size()
        );

        var runId = store.save(request, settings, result);

        assertThat(runId).isPositive();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            assertThat(count(connection, "backtest_run")).isEqualTo(1);
            assertThat(count(connection, "backtest_signal")).isEqualTo(result.signalsDetected());
            assertThat(count(connection, "backtest_trade")).isEqualTo(result.tradesClosed());
            assertThat(count(connection, "backtest_signal_evaluation")).isEqualTo(result.signalEvaluations().size());
        }
    }

    private int count(java.sql.Connection connection, String tableName) throws Exception {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery("select count(*) from " + tableName)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private List<Candle> trendingCandles() {
        var candles = new ArrayList<Candle>();
        for (int i = 0; i < 70; i++) {
            var open = 100 + (i * 0.35);
            var close = open + 0.22;
            var high = Math.max(open, close) + 0.25;
            var low = Math.min(open, close) - 0.25;
            candles.add(candle(i, open, high, low, close));
        }

        candles.set(68, candle(68, 124.00, 124.30, 123.60, 124.05));
        candles.set(69, candle(69, 124.10, 126.20, 123.95, 126.00));
        candles.add(candle(70, 126.00, 130.00, 125.90, 129.00));
        return candles;
    }

    private Candle candle(int index, double open, double high, double low, double close) {
        return new Candle(
                Instant.parse("2026-04-30T00:00:00Z").plusSeconds(index * 300L),
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                1_000L
        );
    }
}
