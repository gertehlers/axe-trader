package io.g3tech.axetrader;

import io.g3tech.axetrader.brokers.capital.ApiClient;
import io.g3tech.axetrader.brokers.capital.AuthenticationClient;
import io.g3tech.axetrader.brokers.capital.ConversationContext;
import io.g3tech.axetrader.brokers.capital.WsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class AxeTraderRunner {

    Logger logger = LoggerFactory.getLogger(AxeTraderRunner.class);

    private final ApiClient apiClient;
    private final AuthenticationClient authenticationClient;
    private final WsClient wsClient;

    public AxeTraderRunner(ApiClient apiClient, AuthenticationClient authenticationClient, WsClient wsClient) {
        this.apiClient = apiClient;
        this.authenticationClient = authenticationClient;
        this.wsClient = wsClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        logger.debug("Application is ready. Starting AxeTrader...");

        ConversationContext conversationContext = authenticationClient.createSession();

        printAccounts(conversationContext);

        try {
            wsClient.connect(conversationContext);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        logger.debug("Connected to websocket");
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void ping() {
        try {
            wsClient.ping();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @EventListener
    public void close(ContextClosedEvent event) {
        logger.debug("Application is closing. Closing AxeTrader. Closed by {}", event.getSource().getClass().getSimpleName());
        try {
            wsClient.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void printAccounts(ConversationContext conversationContext) {
        apiClient.getAccounts(conversationContext).accounts().forEach(accountItem ->
                logger.info("AccountId: {}, AccountName: {}, AccountType: {}", accountItem.accountId(), accountItem.accountName(), accountItem.accountType())
        );
    }
}
