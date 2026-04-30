package io.g3tech.axetrader.strategy.backtest.data;

import io.g3tech.axetrader.brokers.capital.ApiClient;
import io.g3tech.axetrader.brokers.capital.AuthenticationClient;
import io.g3tech.axetrader.brokers.capital.ConversationContext;
import io.g3tech.axetrader.brokers.capital.domain.CapitalUserConfig;
import io.g3tech.axetrader.brokers.capital.dto.prices.ClosePrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesRequest;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesResponse;
import io.g3tech.axetrader.brokers.capital.dto.prices.HighPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.LowPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.OpenPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.PricesItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapitalHistoricalCandleSourceTests {

    @Test
    void loadsHistoricalCandlesInChunksAndDeduplicatesBoundaryCandles() {
        var apiClient = new RecordingApiClient();
        var source = new CapitalHistoricalCandleSource(
                new FixedAuthenticationClient(),
                apiClient,
                new CapitalPriceCandleMapper(),
                2
        );
        var request = new HistoricalPriceRequest(
                "US500",
                "MINUTE_5",
                Instant.parse("2026-04-30T00:00:00Z"),
                Instant.parse("2026-04-30T00:25:00Z"),
                1000
        );

        var candles = source.load(request);

        assertThat(apiClient.requests).hasSize(3);
        assertThat(apiClient.requests.get(0).from()).isEqualTo(Instant.parse("2026-04-30T00:00:00Z"));
        assertThat(apiClient.requests.get(0).to()).isEqualTo(Instant.parse("2026-04-30T00:10:00Z"));
        assertThat(apiClient.requests.get(1).from()).isEqualTo(Instant.parse("2026-04-30T00:10:00Z"));
        assertThat(apiClient.requests.get(1).to()).isEqualTo(Instant.parse("2026-04-30T00:20:00Z"));
        assertThat(apiClient.requests.get(2).from()).isEqualTo(Instant.parse("2026-04-30T00:20:00Z"));
        assertThat(apiClient.requests.get(2).to()).isEqualTo(Instant.parse("2026-04-30T00:25:00Z"));
        assertThat(apiClient.requests).allSatisfy(chunk -> assertThat(chunk.max()).isEqualTo(2));
        assertThat(candles).extracting(candle -> candle.openedAt().toString())
                .containsExactly(
                        "2026-04-30T00:00:00Z",
                        "2026-04-30T00:10:00Z",
                        "2026-04-30T00:20:00Z",
                        "2026-04-30T00:25:00Z"
                );
    }

    @Test
    void rejectsChunksLargerThanCapitalAllows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CapitalHistoricalCandleSource(
                        new FixedAuthenticationClient(),
                        new RecordingApiClient(),
                        new CapitalPriceCandleMapper(),
                        1001
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 1000");
    }

    private static class FixedAuthenticationClient extends AuthenticationClient {

        FixedAuthenticationClient() {
            super("https://example.test", new CapitalUserConfig("user", "password", "key"));
        }

        @Override
        public ConversationContext createSession() {
            return new ConversationContext("cst", "xst", "https://stream.example.test");
        }
    }

    private static class RecordingApiClient extends ApiClient {

        private final List<GetPricesRequest> requests = new ArrayList<>();

        RecordingApiClient() {
            super("https://example.test");
        }

        @Override
        public GetPricesResponse getPrices(ConversationContext conversationContext, GetPricesRequest request) {
            requests.add(request);
            return new GetPricesResponse(List.of(
                    price(request.from().toString().replace("Z", "")),
                    price(request.to().toString().replace("Z", ""))
            ), null, null);
        }
    }

    private static PricesItem price(String timestamp) {
        var value = new BigDecimal("100");
        return new PricesItem(
                timestamp,
                timestamp,
                new OpenPrice(value, value, null),
                new ClosePrice(value, value, null),
                new HighPrice(value, value, null),
                new LowPrice(value, value, null),
                1_000L
        );
    }
}
