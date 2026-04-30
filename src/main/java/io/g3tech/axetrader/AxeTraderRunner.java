package io.g3tech.axetrader;

import io.g3tech.axetrader.brokers.capital.ApiClient;
import io.g3tech.axetrader.brokers.capital.AuthenticationClient;
import io.g3tech.axetrader.brokers.capital.ConversationContext;
import io.g3tech.axetrader.brokers.capital.WsClient;
import io.g3tech.axetrader.brokers.capital.domain.MarketDataPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class AxeTraderRunner {

    private static final Logger logger = LoggerFactory.getLogger(AxeTraderRunner.class);

    private final ApiClient apiClient;
    private final AuthenticationClient authenticationClient;
    private final WsClient wsClient;
    private final boolean enabled;
    private final MarketDataPreferences marketDataPreferences;

    public AxeTraderRunner(
            ApiClient apiClient,
            AuthenticationClient authenticationClient,
            WsClient wsClient,
            @Value("${axe-trader.enabled:true}") boolean enabled,
            MarketDataPreferences marketDataPreferences
    ) {
        this.apiClient = apiClient;
        this.authenticationClient = authenticationClient;
        this.wsClient = wsClient;
        this.enabled = enabled;
        this.marketDataPreferences = marketDataPreferences;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (!enabled) {
            logger.info("AxeTrader runner is disabled");
            return;
        }

        logger.debug("Application is ready. Starting AxeTrader...");

        ConversationContext conversationContext = authenticationClient.createSession();

        printAccounts(conversationContext);

        try {
            wsClient.connect(conversationContext);
            logger.debug("Connected to websocket");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            wsClient.subscribeOHLCMarketData(marketDataPreferences.epics(), marketDataPreferences.resolutions());
        } catch (IOException e) {
            logger.error("Failed to subscribe to OHLC market data", e);
            throw new RuntimeException(e);
        }

    }

    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void ping() {
        if (!enabled) {
            return;
        }

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
