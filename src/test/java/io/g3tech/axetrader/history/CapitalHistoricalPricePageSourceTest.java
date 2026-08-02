package io.g3tech.axetrader.history;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapitalHistoricalPricePageSourceTest {

    private static final Instant FROM = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2024-01-01T00:02:00Z");
    private static final HistoryImportRequest REQUEST = new HistoryImportRequest(
            "US500", "MINUTE", FROM, TO, Path.of("build", "history-stage.sqlite"), "capital");

    @Test
    void preservesCapitalBidAskValuesAndTheRequestedPageBounds() {
        StubAuthenticationClient authentication = new StubAuthenticationClient();
        StubApiClient api = new StubApiClient(response(priceWithTypedValues("4800.1", "4800.3")));
        CapitalHistoricalPricePageSource source = new CapitalHistoricalPricePageSource(authentication, api);

        ImportedPage page = source.fetch(REQUEST, FROM, TO, 1_000);

        assertThat(authentication.createSessionCalls).isEqualTo(1);
        assertThat(api.request).isEqualTo(new GetPricesRequest("US500", "MINUTE", FROM, TO, 1_000));
        assertThat(page.requestedFrom()).isEqualTo(FROM);
        assertThat(page.requestedTo()).isEqualTo(TO);
        assertThat(page.prices()).singleElement().satisfies(imported -> {
            assertThat(imported.timestamp()).isEqualTo(Instant.parse("2024-01-01T00:01:00Z"));
            assertThat(imported.openBid()).isEqualByComparingTo("4799.1");
            assertThat(imported.openAsk()).isEqualByComparingTo("4799.3");
            assertThat(imported.highBid()).isEqualByComparingTo("4801.1");
            assertThat(imported.highAsk()).isEqualByComparingTo("4801.3");
            assertThat(imported.lowBid()).isEqualByComparingTo("4798.1");
            assertThat(imported.lowAsk()).isEqualByComparingTo("4798.3");
            assertThat(imported.closeBid()).isEqualByComparingTo("4800.1");
            assertThat(imported.closeAsk()).isEqualByComparingTo("4800.3");
            assertThat(imported.lastTradedVolume()).isEqualTo(123L);
        });
        assertThat(page.payloadHash()).hasSize(64);
    }

    @Test
    void hashesReturnedPriceFieldsRatherThanOnlyTheRequestedBounds() {
        CapitalHistoricalPricePageSource first = new CapitalHistoricalPricePageSource(
                new StubAuthenticationClient(), new StubApiClient(response(priceWithTypedValues("4800.1", "4800.3"))));
        CapitalHistoricalPricePageSource changed = new CapitalHistoricalPricePageSource(
                new StubAuthenticationClient(), new StubApiClient(response(priceWithTypedValues("4800.2", "4800.3"))));

        assertThat(first.fetch(REQUEST, FROM, TO, 1_000).payloadHash())
                .isNotEqualTo(changed.fetch(REQUEST, FROM, TO, 1_000).payloadHash());
    }

    @Test
    void translatesCapitalsInclusiveToTimestampToTheInternalHalfOpenPage() {
        CapitalHistoricalPricePageSource source = new CapitalHistoricalPricePageSource(
                new StubAuthenticationClient(), new StubApiClient(response(
                priceAt("2024-01-01T00:01:00Z", "4800.1", "4800.3"),
                priceAt("2024-01-01T00:02:00Z", "4800.2", "4800.4"))));

        ImportedPage page = source.fetch(REQUEST, FROM, TO, 1_000);

        assertThat(page.prices()).extracting(ImportedPrice::timestamp)
                .containsExactly(Instant.parse("2024-01-01T00:01:00Z"));
    }

    @Test
    void authenticatesOnlyWhenTheFirstPageIsFetched() {
        StubAuthenticationClient authentication = new StubAuthenticationClient();
        CapitalHistoricalPricePageSource source = new CapitalHistoricalPricePageSource(
                authentication, new StubApiClient(response(priceWithTypedValues("4800.1", "4800.3"))));

        assertThat(authentication.createSessionCalls).isZero();

        source.fetch(REQUEST, FROM, TO, 1_000);
        source.fetch(REQUEST, FROM, TO, 1_000);

        assertThat(authentication.createSessionCalls).isEqualTo(1);
    }

    @Test
    void translatesCapitalNotFoundForAClosedWindowToAnEmptyPage() {
        CapitalHistoricalPricePageSource source = new CapitalHistoricalPricePageSource(
                new StubAuthenticationClient(), new StubApiClient(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "closed", HttpHeaders.EMPTY, new byte[0], null)));

        ImportedPage page = source.fetch(REQUEST, FROM, TO, 1_000);

        assertThat(page.prices()).isEmpty();
    }

    private static GetPricesResponse response(PricesItem... prices) {
        return new GetPricesResponse(List.of(prices), null, null);
    }

    private static OpenPrice openValues(String bid, String ask) {
        return new OpenPrice(new BigDecimal(bid), new BigDecimal(ask), null);
    }

    private static ClosePrice closeValues(String bid, String ask) {
        return new ClosePrice(new BigDecimal(bid), new BigDecimal(ask), null);
    }

    private static HighPrice highValues(String bid, String ask) {
        return new HighPrice(new BigDecimal(bid), new BigDecimal(ask), null);
    }

    private static LowPrice lowValues(String bid, String ask) {
        return new LowPrice(new BigDecimal(bid), new BigDecimal(ask), null);
    }

    private static PricesItem priceWithTypedValues(String closeBid, String closeAsk) {
        return priceAt("2024-01-01T00:01:00Z", closeBid, closeAsk);
    }

    private static PricesItem priceAt(String timestamp, String closeBid, String closeAsk) {
        return new PricesItem(
                timestamp.replace('T', ' ').replace("Z", ""), timestamp,
                openValues("4799.1", "4799.3"), closeValues(closeBid, closeAsk),
                highValues("4801.1", "4801.3"), lowValues("4798.1", "4798.3"), 123L);
    }

    private static final class StubAuthenticationClient extends AuthenticationClient {
        private int createSessionCalls;

        private StubAuthenticationClient() {
            super("http://localhost", new CapitalUserConfig("login", "password", "api-key"));
        }

        @Override
        public ConversationContext createSession() {
            createSessionCalls++;
            return new ConversationContext("client-token", "account-token", "wss://streaming.example");
        }
    }

    private static final class StubApiClient extends ApiClient {
        private final GetPricesResponse response;
        private final RuntimeException failure;
        private GetPricesRequest request;

        private StubApiClient(GetPricesResponse response) {
            super("http://localhost");
            this.response = response;
            this.failure = null;
        }

        private StubApiClient(RuntimeException failure) {
            super("http://localhost");
            this.response = null;
            this.failure = failure;
        }

        @Override
        public GetPricesResponse getPrices(ConversationContext context, GetPricesRequest request) {
            this.request = request;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
