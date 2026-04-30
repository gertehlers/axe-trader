package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.backtest.data.HistoricalPriceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class BacktestCommandRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(BacktestCommandRunner.class);

    private final BulkBacktestRunner bulkBacktestRunner;
    private final boolean enabled;
    private final String epic;
    private final String resolution;
    private final Instant from;
    private final Instant to;
    private final int max;
    private final int minimumCandles;
    private final double targetRiskMultiple;

    public BacktestCommandRunner(
            BulkBacktestRunner bulkBacktestRunner,
            @Value("${axe-trader.backtest.enabled:false}") boolean enabled,
            @Value("${axe-trader.backtest.epic:US500}") String epic,
            @Value("${axe-trader.backtest.resolution:MINUTE_5}") String resolution,
            @Value("${axe-trader.backtest.from:2026-04-30T00:00:00Z}") Instant from,
            @Value("${axe-trader.backtest.to:2026-04-30T08:00:00Z}") Instant to,
            @Value("${axe-trader.backtest.max:120}") int max,
            @Value("${axe-trader.backtest.minimum-candles:60}") int minimumCandles,
            @Value("${axe-trader.backtest.target-risk-multiple:2.0}") double targetRiskMultiple
    ) {
        this.bulkBacktestRunner = bulkBacktestRunner;
        this.enabled = enabled;
        this.epic = epic;
        this.resolution = resolution;
        this.from = from;
        this.to = to;
        this.max = max;
        this.minimumCandles = minimumCandles;
        this.targetRiskMultiple = targetRiskMultiple;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        var request = new HistoricalPriceRequest(epic, resolution, from, to, max);
        var settings = new BacktestSettings(minimumCandles, targetRiskMultiple);
        var report = bulkBacktestRunner.run(request, settings);

        logger.info("""
                Backtest report:
                  epic={}
                  resolution={}
                  from={}
                  to={}
                  candlesProcessed={}
                  signalsDetected={}
                  tradesClosed={}
                  winRate={}
                  totalR={}
                  averageR={}
                  exitReasons={}
                """,
                report.epic(),
                report.resolution(),
                report.from(),
                report.to(),
                report.candlesProcessed(),
                report.signalsDetected(),
                report.tradesClosed(),
                report.winRate(),
                report.totalRiskMultiple(),
                report.averageRiskMultiple(),
                report.exitReasons()
        );
    }
}
