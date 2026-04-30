package io.g3tech.axetrader.brokers.capital;

import io.g3tech.axetrader.brokers.capital.dto.account.GetAccountsResponse;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesRequest;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.g3tech.axetrader.brokers.capital.Constants.X_SECURITY_TOKEN;
import static io.g3tech.axetrader.brokers.capital.Constants.API_V1_ACCOUNTS;
import static io.g3tech.axetrader.brokers.capital.Constants.API_V1_PRICES;
import static io.g3tech.axetrader.brokers.capital.Constants.CLIENT_SSO_TOKEN;

@Service
public class ApiClient {

    private static final DateTimeFormatter CAPITAL_DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private final RestClient restClient;

    public ApiClient(@Value("${brokers.capital.api.url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public GetAccountsResponse getAccounts(ConversationContext conversationContext) {
        var requestHeaders = buildHttpHeadersForContext(conversationContext);

        return restClient.get()
                .uri(API_V1_ACCOUNTS.value())
                .headers(httpHeaders -> httpHeaders.addAll(requestHeaders))
                .retrieve()
                .body(GetAccountsResponse.class);
    }

    public GetPricesResponse getPrices(ConversationContext conversationContext, GetPricesRequest request) {
        var requestHeaders = buildHttpHeadersForContext(conversationContext);

        return restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .path(API_V1_PRICES.value())
                            .pathSegment(request.epic())
                            .queryParam("resolution", request.resolution());

                    if (request.from() != null) {
                        builder.queryParam("from", CAPITAL_DATE_FORMAT.format(request.from()));
                    }
                    if (request.to() != null) {
                        builder.queryParam("to", CAPITAL_DATE_FORMAT.format(request.to()));
                    }
                    if (request.max() != null) {
                        builder.queryParam("max", request.max());
                    }

                    return builder.build();
                })
                .headers(httpHeaders -> httpHeaders.addAll(requestHeaders))
                .retrieve()
                .body(GetPricesResponse.class);
    }

    private HttpHeaders buildHttpHeadersForContext(ConversationContext conversationContext) {
        var requestHeaders = new HttpHeaders();
        requestHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);

        requestHeaders.set(CLIENT_SSO_TOKEN.value(), conversationContext.clientSecurityToken());
        requestHeaders.set(X_SECURITY_TOKEN.value(), conversationContext.accountSecurityToken());
        return requestHeaders;
    }
}
