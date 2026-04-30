package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.ConfluenceEntryDetector;
import io.g3tech.axetrader.strategy.IndicatorCalculator;
import io.g3tech.axetrader.strategy.backtest.persistence.SQLiteBacktestStore;
import io.g3tech.axetrader.strategy.backtest.data.HistoricalCandleSource;
import io.g3tech.axetrader.strategy.backtest.data.HistoricalPriceRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class BulkBacktestRunner {

    private final HistoricalCandleSource historicalCandleSource;
    private final ConfluenceEntryDetector entryDetector;
    private final IndicatorCalculator indicatorCalculator;
    private final SQLiteBacktestStore backtestStore;

    public BulkBacktestRunner(
            HistoricalCandleSource historicalCandleSource,
            ConfluenceEntryDetector entryDetector,
            IndicatorCalculator indicatorCalculator,
            SQLiteBacktestStore backtestStore
    ) {
        this.historicalCandleSource = historicalCandleSource;
        this.entryDetector = entryDetector;
        this.indicatorCalculator = indicatorCalculator;
        this.backtestStore = backtestStore;
    }

    public BulkBacktestReport run(HistoricalPriceRequest request, BacktestSettings settings) {
        var candles = historicalCandleSource.load(request);
        return run(request, settings, candles);
    }

    public BacktestSweepReport runSweep(
            HistoricalPriceRequest request,
            int minimumCandles,
            List<Double> targetRiskMultiples,
            List<Double> stopAtrMultiples
    ) {
        var candles = historicalCandleSource.load(request);
        var reports = targetRiskMultiples.stream()
                .flatMap(targetRiskMultiple -> stopAtrMultiples.stream()
                        .map(stopAtrMultiple -> new BacktestSettings(minimumCandles, targetRiskMultiple, stopAtrMultiple)))
                .map(settings -> run(request, settings, candles))
                .toList();

        return new BacktestSweepReport(reports);
    }

    public BulkBacktestReport run(HistoricalPriceRequest request, BacktestSettings settings, List<io.g3tech.axetrader.strategy.Candle> candles) {
        var result = new ConfluenceBacktester(entryDetector, indicatorCalculator, settings).run(candles);
        var averageRiskMultiple = result.tradesClosed() == 0 ? 0 : result.totalRiskMultiple() / result.tradesClosed();
        var exitReasons = result.trades().stream()
                .collect(Collectors.groupingBy(BacktestTrade::exitReason, Collectors.counting()));
        var runId = backtestStore.save(request, settings, result);
        var profileReport = new ProfileReporter().build(result);

        var report = new BulkBacktestReport(
                runId,
                request.epic(),
                request.resolution(),
                settings.targetRiskMultiple(),
                settings.stopAtrMultiple(),
                candles.getFirst().openedAt(),
                candles.getLast().openedAt(),
                result.candlesProcessed(),
                result.signalsDetected(),
                result.tradesClosed(),
                result.winRate(),
                result.resolvedSignals(),
                result.correctSignals(),
                result.incorrectSignals(),
                result.unresolvedSignals(),
                result.accuracy(),
                result.longAccuracy(),
                result.shortAccuracy(),
                result.accuracyByDirection(),
                result.accuracyByVolatilityRegime(),
                result.accuracyByTrendRegime(),
                result.accuracyByScore(),
                result.accuracyByReason(),
                result.forwardMovementByHorizon(),
                profileReport,
                null,
                result.totalRiskMultiple(),
                averageRiskMultiple,
                exitReasons
        );
        return withCandidateGate(report, StrategyCandidateGate.evaluate(report, 0.75, 30));
    }

    private BulkBacktestReport withCandidateGate(BulkBacktestReport report, StrategyCandidateGate candidateGate) {
        return new BulkBacktestReport(
                report.runId(),
                report.epic(),
                report.resolution(),
                report.targetRiskMultiple(),
                report.stopAtrMultiple(),
                report.from(),
                report.to(),
                report.candlesProcessed(),
                report.signalsDetected(),
                report.tradesClosed(),
                report.winRate(),
                report.resolvedSignals(),
                report.correctSignals(),
                report.incorrectSignals(),
                report.unresolvedSignals(),
                report.accuracy(),
                report.longAccuracy(),
                report.shortAccuracy(),
                report.accuracyByDirection(),
                report.accuracyByVolatilityRegime(),
                report.accuracyByTrendRegime(),
                report.accuracyByScore(),
                report.accuracyByReason(),
                report.forwardMovementByHorizon(),
                report.profileReport(),
                candidateGate,
                report.totalRiskMultiple(),
                report.averageRiskMultiple(),
                report.exitReasons()
        );
    }
}
