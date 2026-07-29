package io.g3tech.axetrader.history;

import io.g3tech.axetrader.brokers.capital.ApiClient;
import io.g3tech.axetrader.brokers.capital.AuthenticationClient;
import io.g3tech.axetrader.brokers.capital.ConversationContext;
import io.g3tech.axetrader.brokers.capital.dto.prices.ClosePrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesResponse;
import io.g3tech.axetrader.brokers.capital.dto.prices.HighPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.LowPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.OpenPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.PricesItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapitalHistoricalPricePageSourceTest {

    @Test
    void preservesBidAskOhlcValuesAndHashesTheReturnedPage() {
        AuthenticationClient authenticationClient = mock(AuthenticationClient.class);
        ApiClient apiClient = mock(ApiClient.class);
        ConversationContext context = new ConversationContext("client", "account", "stream");
        Instant from = Instant.parse("2025-01-20T16:20:00Z");
        Instant to = Instant.parse("2025-01-20T16:23:00Z");
        HistoryImportRequest request = new HistoryImportRequest(
                "US500", "MINUTE", from, to, Path.of("target/history.sqlite"), "capital", false);

        when(authenticationClient.createSession()).thenReturn(context);
        when(apiClient.getPrices(any(), any())).thenReturn(new GetPricesResponse(List.of(price()), null, null));

        CapitalHistoricalPricePageSource source = new CapitalHistoricalPricePageSource(authenticationClient, apiClient);

        ImportedPage page = source.fetch(request, from, to, 1_000);

        assertThat(page.prices().getFirst().getOpenBid()).isEqualTo(6023.1);
        assertThat(page.prices().getFirst().getOpenAsk()).isEqualTo(6023.2);
        assertThat(page.prices().getFirst().getHighBid()).isEqualTo(6024.1);
        assertThat(page.prices().getFirst().getHighAsk()).isEqualTo(6024.2);
        assertThat(page.prices().getFirst().getLowBid()).isEqualTo(6022.1);
        assertThat(page.prices().getFirst().getLowAsk()).isEqualTo(6022.2);
        assertThat(page.prices().getFirst().getCloseBid()).isEqualTo(6023.4);
        assertThat(page.prices().getFirst().getCloseAsk()).isEqualTo(6023.3);
        assertThat(page.payloadHash()).hasSize(64);
        verify(authenticationClient).createSession();
        verify(apiClient).getPrices(context, new io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesRequest(
                "US500", "MINUTE", from, to, 1_000));
    }

    @Test
    void createsTheAuthenticatedSessionLazilyOnlyOnce() {
        AuthenticationClient authenticationClient = mock(AuthenticationClient.class);
        ApiClient apiClient = mock(ApiClient.class);
        ConversationContext context = new ConversationContext("client", "account", "stream");
        Instant from = Instant.parse("2025-01-20T16:20:00Z");
        Instant to = Instant.parse("2025-01-20T16:23:00Z");
        HistoryImportRequest request = new HistoryImportRequest(
                "US500", "MINUTE", from, to, Path.of("target/history.sqlite"), "capital", false);
        when(authenticationClient.createSession()).thenReturn(context);
        when(apiClient.getPrices(any(), any())).thenReturn(new GetPricesResponse(List.of(), null, null));
        CapitalHistoricalPricePageSource source = new CapitalHistoricalPricePageSource(authenticationClient, apiClient);

        source.fetch(request, from, to, 1_000);
        source.fetch(request, from, to, 1_000);

        verify(authenticationClient).createSession();
    }

    private static PricesItem price() {
        return new PricesItem("20/01/2025 16:20:00", "2025-01-20T16:20:00Z",
                new OpenPrice(decimal("6023.1"), decimal("6023.2"), null),
                new ClosePrice(decimal("6023.4"), decimal("6023.3"), null),
                new HighPrice(decimal("6024.1"), decimal("6024.2"), null),
                new LowPrice(decimal("6022.1"), decimal("6022.2"), null), 42L);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
