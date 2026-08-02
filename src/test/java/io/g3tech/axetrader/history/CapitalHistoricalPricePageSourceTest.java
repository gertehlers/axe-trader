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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void sharedPacerAllowsNoBurstAcrossCapitalPageSources() {
        FakeTime time = new FakeTime();
        CapitalRequestPacer pacer = new CapitalRequestPacer(5, time, time);
        SequenceApiClient firstApi = new SequenceApiClient(time, response(priceWithTypedValues("4800.1", "4800.3")));
        SequenceApiClient secondApi = new SequenceApiClient(time,
                response(priceWithTypedValues("4800.1", "4800.3")),
                response(priceWithTypedValues("4800.1", "4800.3")));
        CapitalHistoricalPricePageSource first = source(firstApi, pacer, time, 3, 0);
        CapitalHistoricalPricePageSource second = source(secondApi, pacer, time, 3, 0);

        first.fetch(REQUEST, FROM, TO, 1_000);
        second.fetch(REQUEST, FROM, TO, 1_000);
        second.fetch(REQUEST, FROM, TO, 1_000);

        List<Long> attempts = new ArrayList<>();
        attempts.addAll(firstApi.attemptNanos);
        attempts.addAll(secondApi.attemptNanos);
        attempts.sort(Long::compareTo);
        assertThat(attempts).containsExactly(0L, 200_000_000L, 400_000_000L);
    }

    @Test
    void configuredCapitalRateCannotExceedFiveRequestsPerSecond() {
        FakeTime time = new FakeTime();

        assertThatThrownBy(() -> new CapitalRequestPacer(6, time, time))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 5");
    }

    @Test
    void rateLimitRetryHonorsRetryAfterAndReacquiresAPacerPermit() {
        FakeTime time = new FakeTime();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "2");
        RuntimeException rateLimited = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "limited", headers, new byte[0], null);
        SequenceApiClient api = new SequenceApiClient(time, rateLimited,
                response(priceWithTypedValues("4800.1", "4800.3")));
        CapitalHistoricalPricePageSource source = source(api, new CapitalRequestPacer(5, time, time), time, 3, 0);

        source.fetch(REQUEST, FROM, TO, 1_000);

        assertThat(api.attemptNanos).containsExactly(0L, 2_000_000_000L);
        assertThat(time.sleeps).contains(Duration.ofSeconds(2));
    }

    @Test
    void transientFailureUsesBoundedExponentialBackoffWithJitter() {
        FakeTime time = new FakeTime();
        RuntimeException unavailable = HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "unavailable", HttpHeaders.EMPTY, new byte[0], null);
        SequenceApiClient api = new SequenceApiClient(time, unavailable,
                response(priceWithTypedValues("4800.1", "4800.3")));
        CapitalHistoricalPricePageSource source = source(api, new CapitalRequestPacer(5, time, time), time, 3, 50);

        source.fetch(REQUEST, FROM, TO, 1_000);

        assertThat(api.attemptNanos).containsExactly(0L, 300_000_000L);
        assertThat(time.sleeps).contains(Duration.ofMillis(300));
    }

    @Test
    void retryBudgetIsFiniteAndNonRetryableFailuresAreNotRetried() {
        FakeTime exhaustedTime = new FakeTime();
        RuntimeException transport = new ResourceAccessException("connection reset");
        SequenceApiClient exhaustedApi = new SequenceApiClient(exhaustedTime, transport, transport, transport,
                response(priceWithTypedValues("4800.1", "4800.3")));
        CapitalHistoricalPricePageSource exhausted = source(exhaustedApi,
                new CapitalRequestPacer(5, exhaustedTime, exhaustedTime), exhaustedTime, 3, 0);

        assertThatThrownBy(() -> exhausted.fetch(REQUEST, FROM, TO, 1_000))
                .isSameAs(transport);
        assertThat(exhaustedApi.attemptNanos).hasSize(3);

        FakeTime badRequestTime = new FakeTime();
        RuntimeException badRequest = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "bad request", HttpHeaders.EMPTY, new byte[0], null);
        SequenceApiClient badRequestApi = new SequenceApiClient(badRequestTime, badRequest,
                response(priceWithTypedValues("4800.1", "4800.3")));
        CapitalHistoricalPricePageSource nonRetrying = source(badRequestApi,
                new CapitalRequestPacer(5, badRequestTime, badRequestTime), badRequestTime, 3, 0);

        assertThatThrownBy(() -> nonRetrying.fetch(REQUEST, FROM, TO, 1_000))
                .isSameAs(badRequest);
        assertThat(badRequestApi.attemptNanos).hasSize(1);
    }

    private static CapitalHistoricalPricePageSource source(SequenceApiClient api, CapitalRequestPacer pacer,
                                                            FakeTime time, int attempts, long jitterMillis) {
        return new CapitalHistoricalPricePageSource(new StubAuthenticationClient(), api, pacer, time,
                () -> Duration.ofMillis(jitterMillis), attempts);
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

    private static final class SequenceApiClient extends ApiClient {
        private final FakeTime time;
        private final Queue<Object> outcomes = new ArrayDeque<>();
        private final List<Long> attemptNanos = new ArrayList<>();

        private SequenceApiClient(FakeTime time, Object... outcomes) {
            super("http://localhost");
            this.time = time;
            this.outcomes.addAll(List.of(outcomes));
        }

        @Override
        public GetPricesResponse getPrices(ConversationContext context, GetPricesRequest request) {
            attemptNanos.add(time.nanoTime());
            Object outcome = outcomes.remove();
            if (outcome instanceof RuntimeException failure) {
                throw failure;
            }
            return (GetPricesResponse) outcome;
        }
    }

    private static final class FakeTime implements CapitalRequestPacer.NanoClock, CapitalRequestPacer.Sleeper {
        private long nanos;
        private final List<Duration> sleeps = new ArrayList<>();

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
            nanos += duration.toNanos();
        }
    }
}
