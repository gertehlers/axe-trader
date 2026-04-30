package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.ConfluenceEntryDetector;
import io.g3tech.axetrader.strategy.backtest.persistence.SQLiteBacktestStore;
import io.g3tech.axetrader.strategy.backtest.data.HistoricalCandleSource;
import io.g3tech.axetrader.strategy.backtest.data.HistoricalPriceRequest;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class BulkBacktestRunner {

    private final HistoricalCandleSource historicalCandleSource;
    private final ConfluenceEntryDetector entryDetector;
    private final SQLiteBacktestStore backtestStore;

    public BulkBacktestRunner(
            HistoricalCandleSource historicalCandleSource,
            ConfluenceEntryDetector entryDetector,
            SQLiteBacktestStore backtestStore
    ) {
        this.historicalCandleSource = historicalCandleSource;
        this.entryDetector = entryDetector;
        this.backtestStore = backtestStore;
    }

    public BulkBacktestReport run(HistoricalPriceRequest request, BacktestSettings settings) {
        var candles = historicalCandleSource.load(request);
        var result = new ConfluenceBacktester(entryDetector, settings).run(candles);
        var averageRiskMultiple = result.tradesClosed() == 0 ? 0 : result.totalRiskMultiple() / result.tradesClosed();
        var exitReasons = result.trades().stream()
                .collect(Collectors.groupingBy(BacktestTrade::exitReason, Collectors.counting()));
        var runId = backtestStore.save(request, result);

        return new BulkBacktestReport(
                runId,
                request.epic(),
                request.resolution(),
                candles.getFirst().openedAt(),
                candles.getLast().openedAt(),
                result.candlesProcessed(),
                result.signalsDetected(),
                result.tradesClosed(),
                result.winRate(),
                result.totalRiskMultiple(),
                averageRiskMultiple,
                exitReasons
        );
    }
}
