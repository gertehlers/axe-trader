package io.g3tech.axetrader.strategy.backtest.data;

import io.g3tech.axetrader.brokers.capital.ApiClient;
import io.g3tech.axetrader.brokers.capital.AuthenticationClient;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesRequest;
import io.g3tech.axetrader.strategy.Candle;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CapitalHistoricalCandleSource implements HistoricalCandleSource {

    private final AuthenticationClient authenticationClient;
    private final ApiClient apiClient;
    private final CapitalPriceCandleMapper candleMapper;

    public CapitalHistoricalCandleSource(
            AuthenticationClient authenticationClient,
            ApiClient apiClient,
            CapitalPriceCandleMapper candleMapper
    ) {
        this.authenticationClient = authenticationClient;
        this.apiClient = apiClient;
        this.candleMapper = candleMapper;
    }

    @Override
    public List<Candle> load(HistoricalPriceRequest request) {
        var conversationContext = authenticationClient.createSession();
        var pricesRequest = new GetPricesRequest(
                request.epic(),
                request.resolution(),
                request.from(),
                request.to(),
                request.max()
        );

        return candleMapper.toCandles(apiClient.getPrices(conversationContext, pricesRequest));
    }
}
