package io.g3tech.axetrader.brokers.capital;

import io.g3tech.axetrader.brokers.capital.dto.account.GetAccountsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

import static io.g3tech.axetrader.brokers.capital.Constants.X_SECURITY_TOKEN;
import static io.g3tech.axetrader.brokers.capital.Constants.API_V1_ACCOUNTS;
import static io.g3tech.axetrader.brokers.capital.Constants.CLIENT_SSO_TOKEN;

@Service
public class ApiClient {

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

    private HttpHeaders buildHttpHeadersForContext(ConversationContext conversationContext) {
        var requestHeaders = new HttpHeaders();
        requestHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);

        requestHeaders.set(CLIENT_SSO_TOKEN.value(), conversationContext.clientSecurityToken());
        requestHeaders.set(X_SECURITY_TOKEN.value(), conversationContext.accountSecurityToken());
        return requestHeaders;
    }
}
