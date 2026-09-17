package io.g3tech.axetrader.history;

import io.g3tech.axetrader.brokers.capital.ApiClient;
import io.g3tech.axetrader.brokers.capital.AuthenticationClient;
import io.g3tech.axetrader.brokers.capital.ConversationContext;
import io.g3tech.axetrader.brokers.capital.domain.CapitalUserConfig;
import io.g3tech.axetrader.brokers.capital.dto.market.details.GetMarketDetailsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;

class CapitalInstrumentCatalogTest {

    @Test
    void knowsAnEpicWithMarketDetails() {
        var source = new CapitalHistoricalPricePageSource(new Auth(), new MarketsApi(null));
        assertThat(source.exists("OIL_BRENT")).isTrue();
    }

    @Test
    void doesNotKnowAnEpicCapitalAnswersNotFoundFor() {
        var notFound = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "error.not-found.epic",
                HttpHeaders.EMPTY, new byte[0], null);
        var source = new CapitalHistoricalPricePageSource(new Auth(), new MarketsApi(notFound));
        assertThat(source.exists("UKOIL")).isFalse();
    }

    private static final class Auth extends AuthenticationClient {
        Auth() {
            super("http://localhost", new CapitalUserConfig("login", "password", "api-key"));
        }

        @Override
        public ConversationContext createSession() {
            return new ConversationContext("client-token", "account-token", "wss://streaming.example");
        }
    }

    private static final class MarketsApi extends ApiClient {
        private final RuntimeException failure;

        MarketsApi(RuntimeException failure) {
            super("http://localhost");
            this.failure = failure;
        }

        @Override
        public GetMarketDetailsResponse getMarketDetails(ConversationContext context, String epic) {
            if (failure != null) {
                throw failure;
            }
            return new GetMarketDetailsResponse(null, null, null);
        }
    }
}
