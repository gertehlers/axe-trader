package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.backtest.data.HistoricalPriceRequest;
import io.g3tech.axetrader.config.AxeTraderMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Component
public class BacktestCommandRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(BacktestCommandRunner.class);

    private final BulkBacktestRunner bulkBacktestRunner;
    private final AxeTraderMode mode;
    private final boolean enabled;
    private final String epic;
    private final String resolution;
    private final Instant from;
    private final Instant to;
    private final int max;
    private final int minimumCandles;
    private final double targetRiskMultiple;
    private final List<Double> targetRiskMultiples;
    private final List<Double> stopAtrMultiples;

    public BacktestCommandRunner(
            BulkBacktestRunner bulkBacktestRunner,
            @Value("${axe-trader.mode:monitor}") String mode,
            @Value("${axe-trader.backtest.enabled:false}") boolean enabled,
            @Value("${axe-trader.backtest.epic:US500}") String epic,
            @Value("${axe-trader.backtest.resolution:MINUTE_5}") String resolution,
            @Value("${axe-trader.backtest.from:2026-04-30T00:00:00Z}") Instant from,
            @Value("${axe-trader.backtest.to:2026-04-30T08:00:00Z}") Instant to,
            @Value("${axe-trader.backtest.max:120}") int max,
            @Value("${axe-trader.backtest.minimum-candles:60}") int minimumCandles,
            @Value("${axe-trader.backtest.target-risk-multiple:2.0}") double targetRiskMultiple,
            @Value("${axe-trader.backtest.sweep.target-risk-multiples:}") String targetRiskMultiples,
            @Value("${axe-trader.backtest.sweep.stop-atr-multiples:}") String stopAtrMultiples
    ) {
        this.bulkBacktestRunner = bulkBacktestRunner;
        this.mode = AxeTraderMode.from(mode);
        this.enabled = enabled;
        this.epic = epic;
        this.resolution = resolution;
        this.from = from;
        this.to = to;
        this.max = max;
        this.minimumCandles = minimumCandles;
        this.targetRiskMultiple = targetRiskMultiple;
        this.targetRiskMultiples = parseDoubles(targetRiskMultiples, List.of(targetRiskMultiple));
        this.stopAtrMultiples = parseDoubles(stopAtrMultiples, List.of(1.5));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mode != AxeTraderMode.BACKTEST) {
            logger.info("Backtest runner is disabled because mode is {}", mode);
            return;
        }

        if (!enabled) {
            logger.info("Backtest runner is disabled by axe-trader.backtest.enabled=false");
            return;
        }

        var request = new HistoricalPriceRequest(epic, resolution, from, to, max);
        var sweepReport = bulkBacktestRunner.runSweep(request, minimumCandles, targetRiskMultiples, stopAtrMultiples);

        sweepReport.reports().forEach(this::logReport);
        logger.info("""
                Backtest sweep summary:
                  runs={}
                  bestByTotalR=runId={}, targetR={}, stopAtr={}, totalR={}, accuracy={}
                  bestByAccuracy=runId={}, targetR={}, stopAtr={}, totalR={}, accuracy={}
                """,
                sweepReport.reports().size(),
                sweepReport.bestByTotalRiskMultiple().runId(),
                sweepReport.bestByTotalRiskMultiple().targetRiskMultiple(),
                sweepReport.bestByTotalRiskMultiple().stopAtrMultiple(),
                sweepReport.bestByTotalRiskMultiple().totalRiskMultiple(),
                sweepReport.bestByTotalRiskMultiple().accuracy(),
                sweepReport.bestByAccuracy().runId(),
                sweepReport.bestByAccuracy().targetRiskMultiple(),
                sweepReport.bestByAccuracy().stopAtrMultiple(),
                sweepReport.bestByAccuracy().totalRiskMultiple(),
                sweepReport.bestByAccuracy().accuracy()
        );
    }

    private void logReport(BulkBacktestReport report) {
        logger.info("""
                Backtest report:
                  runId={}
                  epic={}
                  resolution={}
                  targetR={}
                  stopAtr={}
                  from={}
                  to={}
                  candlesProcessed={}
                  signalsDetected={}
                  tradesClosed={}
                  winRate={}
                  resolvedSignals={}
                  correctSignals={}
                  incorrectSignals={}
                  unresolvedSignals={}
                  accuracy={}
                  longAccuracy={}
                  shortAccuracy={}
                  accuracyByDirection={}
                  accuracyByVolatilityRegime={}
                  accuracyByTrendRegime={}
                  accuracyByScore={}
                  forwardMovementByHorizon={}
                  strongestSignalTypes={}
                  weakestSignalTypes={}
                  candidateGate={}
                  totalR={}
                  averageR={}
                  exitReasons={}
                """,
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
                report.forwardMovementByHorizon(),
                report.profileReport().strongestSignalTypes(),
                report.profileReport().weakestSignalTypes(),
                report.candidateGate(),
                report.totalRiskMultiple(),
                report.averageRiskMultiple(),
                report.exitReasons()
        );
    }

    private List<Double> parseDoubles(String value, List<Double> fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(Double::parseDouble)
                .toList();
    }
}
