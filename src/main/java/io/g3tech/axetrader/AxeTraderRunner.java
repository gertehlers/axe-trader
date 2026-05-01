package io.g3tech.axetrader;

import io.g3tech.axetrader.brokers.capital.ApiClient;
import io.g3tech.axetrader.brokers.capital.AuthenticationClient;
import io.g3tech.axetrader.brokers.capital.ConversationContext;
import io.g3tech.axetrader.brokers.capital.WsClient;
import io.g3tech.axetrader.brokers.capital.domain.MarketDataPreferences;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesRequest;
import io.g3tech.axetrader.config.AxeTraderMode;
import io.g3tech.axetrader.strategy.backtest.repositories.CapitalHistoricalPriceMapper;
import io.g3tech.axetrader.strategy.backtest.repositories.HistoricalPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

@Service
public class AxeTraderRunner {

    private static final Logger logger = LoggerFactory.getLogger(AxeTraderRunner.class);

    private final ApiClient apiClient;
    private final AuthenticationClient authenticationClient;
    private final WsClient wsClient;
    private final AxeTraderMode mode;
    private final MarketDataPreferences marketDataPreferences;
    private final CapitalHistoricalPriceMapper historicalPriceMapper;
    private final HistoricalPriceRepository historicalPriceRepository;

    public AxeTraderRunner(
            ApiClient apiClient,
            AuthenticationClient authenticationClient,
            WsClient wsClient,
            @Value("${axe-trader.mode:monitor}") String mode,
            MarketDataPreferences marketDataPreferences,
            CapitalHistoricalPriceMapper historicalPriceMapper,
            HistoricalPriceRepository historicalPriceRepository
    ) {
        this.apiClient = apiClient;
        this.authenticationClient = authenticationClient;
        this.wsClient = wsClient;
        this.mode = AxeTraderMode.from(mode);
        this.marketDataPreferences = marketDataPreferences;
        this.historicalPriceMapper = historicalPriceMapper;
        this.historicalPriceRepository = historicalPriceRepository;
    }



    public void loadData() throws InterruptedException {
        logger.info("Loading data");

        var conversationContext = authenticationClient.createSession();

        var startTime = ZonedDateTime.now(ZoneId.from(ZoneOffset.UTC));

        for (var i = 0; i < 500; i++) {
            Thread.sleep(1000);

            var to = startTime.minusSeconds(1).toInstant();

            var getPricesRequest = new GetPricesRequest("US500", "MINUTE", null, to, 1000);

            var apiPrices = apiClient.getPrices(conversationContext, getPricesRequest);
            var historicalPrices = historicalPriceMapper.toHistoricalPrices(apiPrices, getPricesRequest);
            historicalPriceRepository.saveAll(historicalPrices);
            logger.info("Saved {} historical prices with last price at {}", historicalPrices.size(), historicalPrices.getFirst().getSnapshotTimeUtc());

            startTime = ZonedDateTime.of(LocalDateTime.parse(apiPrices.prices().getFirst().snapshotTimeUTC()), ZoneId.from(ZoneOffset.UTC));
        }
//
//

    }


//    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (!mode.isLiveMarketMode()) {
            logger.info("AxeTrader live runner is disabled because mode is {}", mode);
            return;
        }

        if (mode == AxeTraderMode.TRADE) {
            throw new IllegalStateException("TRADE mode is not implemented yet. Use MONITOR mode until order execution and risk controls are built.");
        }

        logger.info("Application is ready. Starting AxeTrader in {} mode.", mode);

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

//    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void ping() {
        if (!mode.isLiveMarketMode()) {
            return;
        }

        try {
            wsClient.ping();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

//    @EventListener
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
