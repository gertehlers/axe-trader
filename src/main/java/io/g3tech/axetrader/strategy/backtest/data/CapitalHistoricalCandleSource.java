package io.g3tech.axetrader.strategy.backtest.data;

import io.g3tech.axetrader.brokers.capital.ApiClient;
import io.g3tech.axetrader.brokers.capital.AuthenticationClient;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesRequest;
import io.g3tech.axetrader.strategy.Candle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class CapitalHistoricalCandleSource implements HistoricalCandleSource {

    private static final int CAPITAL_MAX_CANDLES_PER_REQUEST = 1000;

    private final AuthenticationClient authenticationClient;
    private final ApiClient apiClient;
    private final CapitalPriceCandleMapper candleMapper;
    private final int chunkSize;

    public CapitalHistoricalCandleSource(
            AuthenticationClient authenticationClient,
            ApiClient apiClient,
            CapitalPriceCandleMapper candleMapper,
            @Value("${axe-trader.backtest.chunk-size:1000}") int chunkSize
    ) {
        this.authenticationClient = authenticationClient;
        this.apiClient = apiClient;
        this.candleMapper = candleMapper;
        if (chunkSize <= 0 || chunkSize > CAPITAL_MAX_CANDLES_PER_REQUEST) {
            throw new IllegalArgumentException("Capital backtest chunk size must be between 1 and 1000");
        }
        this.chunkSize = chunkSize;
    }

    @Override
    public List<Candle> load(HistoricalPriceRequest request) {
        var conversationContext = authenticationClient.createSession();
        if (request.from() == null || request.to() == null) {
            var pricesRequest = new GetPricesRequest(
                    request.epic(),
                    request.resolution(),
                    request.from(),
                    request.to(),
                    Math.min(request.max(), chunkSize)
            );

            return candleMapper.toCandles(apiClient.getPrices(conversationContext, pricesRequest));
        }

        var resolution = CapitalResolution.from(request.resolution());
        var chunkDuration = resolution.duration().multipliedBy(chunkSize);
        var cursor = request.from();
        var candles = new java.util.ArrayList<Candle>();

        while (cursor.isBefore(request.to())) {
            var chunkTo = cursor.plus(chunkDuration);
            if (chunkTo.isAfter(request.to())) {
                chunkTo = request.to();
            }

            var pricesRequest = new GetPricesRequest(
                    request.epic(),
                    request.resolution(),
                    cursor,
                    chunkTo,
                    chunkSize
            );

            candles.addAll(candleMapper.toCandles(apiClient.getPrices(conversationContext, pricesRequest)));

            cursor = chunkTo;
        }

        return merge(candles);
    }

    public static List<Candle> merge(List<Candle> candles) {
        return candles.stream()
                .collect(Collectors.toMap(
                        Candle::openedAt,
                        Function.identity(),
                        (first, duplicate) -> duplicate,
                        java.util.LinkedHashMap::new
                ))
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

}
