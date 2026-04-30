package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.ConfluenceEntryDetector;
import io.g3tech.axetrader.strategy.backtest.data.HistoricalCandleSource;
import io.g3tech.axetrader.strategy.backtest.data.HistoricalPriceRequest;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class BulkBacktestRunner {

    private final HistoricalCandleSource historicalCandleSource;
    private final ConfluenceEntryDetector entryDetector;

    public BulkBacktestRunner(HistoricalCandleSource historicalCandleSource, ConfluenceEntryDetector entryDetector) {
        this.historicalCandleSource = historicalCandleSource;
        this.entryDetector = entryDetector;
    }

    public BulkBacktestReport run(HistoricalPriceRequest request, BacktestSettings settings) {
        var candles = historicalCandleSource.load(request);
        var result = new ConfluenceBacktester(entryDetector, settings).run(candles);
        var averageRiskMultiple = result.tradesClosed() == 0 ? 0 : result.totalRiskMultiple() / result.tradesClosed();
        var exitReasons = result.trades().stream()
                .collect(Collectors.groupingBy(BacktestTrade::exitReason, Collectors.counting()));

        return new BulkBacktestReport(
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
