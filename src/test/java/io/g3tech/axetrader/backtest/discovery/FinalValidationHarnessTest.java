package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.session.HistoricalSessionCalendar;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import io.g3tech.axetrader.backtest.strategy.StrategyFactory;
import io.g3tech.axetrader.strategy.backtest.repositories.HistoricalPriceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

/** The sole command entry point for final validation; it accepts no window properties. */
@SpringBootTest
class FinalValidationHarnessTest {

    @Autowired private HistoricalPriceRepository repository;
    @Autowired private BarSeriesFactory barSeriesFactory;
    @Autowired private BacktestProperties properties;
    @Autowired private ObservableStateExtractor extractor;
    @Autowired private StrategyFactory strategyFactory;

    @Test
    @EnabledIfSystemProperty(named = "finalValidation", matches = "true")
    void finalValidationRequiresFrozenCandidateOnly() {
        if (System.getProperty("finalValidation.from") != null
                || System.getProperty("finalValidation.to") != null) {
            throw new IllegalArgumentException("final validation accepts no window override");
        }
        String candidateId = System.getProperty("finalValidation.candidate");
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("finalValidation.candidate is required");
        }
        new FinalValidationService(DiscoveryRequest.DEFAULT_PERSISTENCE_PATH, (from, to) -> {
            var prices = repository.findByEpicAndSnapshotTimeUtcBetweenOrderBySnapshotTimeUtcAsc(
                    properties.getEpic(), from, to);
            var market = barSeriesFactory.fromPricesWithSides(
                    properties.getEpic(), prices, properties.getTimeframeMinutes());
            var calendar = HistoricalSessionCalendar.fit(
                    prices.stream().map(price -> price.getSnapshotTimeUtc()).toList(), 10);
            var indicators = IndicatorBundle.from(market.mid(), properties.getStrategy());
            var strategies = strategyFactory.build(indicators, properties.getStrategy());
            List<ObservableState> states = new ArrayList<>();
            for (int index = market.mid().getBeginIndex(); index <= market.mid().getEndIndex(); index++) {
                states.addAll(extractor.extract(
                        properties.getEpic(), properties.getTimeframeMinutes(), market, indicators,
                        strategies, properties.getStrategy(), calendar, index).states());
            }
            return new FinalValidationService.ProtectedWindow(market, states);
        }).run(candidateId);
    }
}
