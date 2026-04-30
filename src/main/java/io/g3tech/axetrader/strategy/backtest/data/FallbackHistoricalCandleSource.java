package io.g3tech.axetrader.strategy.backtest.data;

import io.g3tech.axetrader.strategy.Candle;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Primary
public class FallbackHistoricalCandleSource implements HistoricalCandleSource {

    private static final Logger logger = LoggerFactory.getLogger(FallbackHistoricalCandleSource.class);

    private final CapitalHistoricalCandleSource capitalHistoricalCandleSource;
    private final FakeHistoricalCandleSource fakeHistoricalCandleSource;
    private final HistoricalDataSourceMode mode;

    public FallbackHistoricalCandleSource(
            CapitalHistoricalCandleSource capitalHistoricalCandleSource,
            FakeHistoricalCandleSource fakeHistoricalCandleSource,
            @Value("${axe-trader.backtest.data-source:fallback}") String mode
    ) {
        this.capitalHistoricalCandleSource = capitalHistoricalCandleSource;
        this.fakeHistoricalCandleSource = fakeHistoricalCandleSource;
        this.mode = HistoricalDataSourceMode.from(mode);
    }

    @Override
    public List<Candle> load(HistoricalPriceRequest request) {
        if (mode == HistoricalDataSourceMode.CAPITAL) {
            return loadCapitalOnly(request);
        }

        if (mode == HistoricalDataSourceMode.FAKE) {
            logger.info("Loading fake historical candles because backtest data source mode is FAKE");
            return fakeHistoricalCandleSource.load(request);
        }

        try {
            var candles = capitalHistoricalCandleSource.load(request);
            if (!candles.isEmpty()) {
                logger.info("Loaded {} historical candles from Capital.com", candles.size());
                return candles;
            }

            logger.warn("Capital.com returned no historical candles. Falling back to fake data.");
        } catch (RuntimeException e) {
            logger.warn("Failed to load Capital.com historical candles. Falling back to fake data: {}", e.getMessage());
        }

        return fakeHistoricalCandleSource.load(request);
    }

    private List<Candle> loadCapitalOnly(HistoricalPriceRequest request) {
        var candles = capitalHistoricalCandleSource.load(request);
        if (candles.isEmpty()) {
            throw new IllegalStateException("Capital.com returned no historical candles for " + request);
        }

        logger.info("Loaded {} historical candles from Capital.com", candles.size());
        return candles;
    }
}
