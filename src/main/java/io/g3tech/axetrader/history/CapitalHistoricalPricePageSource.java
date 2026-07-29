package io.g3tech.axetrader.history;

import io.g3tech.axetrader.backtest.discovery.DiscoveryWindowPolicy;
import io.g3tech.axetrader.brokers.capital.ApiClient;
import io.g3tech.axetrader.brokers.capital.AuthenticationClient;
import io.g3tech.axetrader.brokers.capital.ConversationContext;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesRequest;
import io.g3tech.axetrader.brokers.capital.dto.prices.PricesItem;
import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/** Capital.com implementation of a bounded, independently auditable history-page fetch. */
@Component
public class CapitalHistoricalPricePageSource implements HistoricalPricePageSource {

    private final AuthenticationClient authenticationClient;
    private final ApiClient apiClient;
    private volatile ConversationContext conversationContext;

    public CapitalHistoricalPricePageSource(AuthenticationClient authenticationClient, ApiClient apiClient) {
        this.authenticationClient = Objects.requireNonNull(authenticationClient, "authenticationClient");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
    }

    @Override
    public ImportedPage fetch(HistoryImportRequest request, Instant fromInclusive, Instant toExclusive, int maxBars) {
        Objects.requireNonNull(request, "request");
        validatePageWindow(request, fromInclusive, toExclusive, maxBars);

        var response = apiClient.getPrices(session(), new GetPricesRequest(
                request.epic(), request.resolution(), fromInclusive, toExclusive, maxBars));
        List<PricesItem> items = response == null || response.prices() == null ? List.of() : response.prices();
        List<HistoricalPrice> prices = items.stream().map(item -> toHistoricalPrice(request, item)).toList();
        return new ImportedPage(fromInclusive, toExclusive, payloadHash(fromInclusive, toExclusive, items), prices);
    }

    private void validatePageWindow(HistoryImportRequest request, Instant fromInclusive, Instant toExclusive, int maxBars) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("page window must be a non-empty half-open interval");
        }
        if (maxBars <= 0) {
            throw new IllegalArgumentException("maxBars must be positive");
        }
        if (fromInclusive.isBefore(request.from()) || toExclusive.isAfter(request.to())) {
            throw new IllegalArgumentException("page window must be contained by the import window");
        }
        if (request.discoveryWindow()) {
            new DiscoveryWindowPolicy().requireDevelopmentWindow(fromInclusive, toExclusive);
        }
    }

    private ConversationContext session() {
        ConversationContext current = conversationContext;
        if (current == null) {
            synchronized (this) {
                current = conversationContext;
                if (current == null) {
                    current = authenticationClient.createSession();
                    conversationContext = Objects.requireNonNull(current, "Capital authentication returned no session");
                }
            }
        }
        return current;
    }

    private static HistoricalPrice toHistoricalPrice(HistoryImportRequest request, PricesItem item) {
        Objects.requireNonNull(item, "Capital price item");
        HistoricalPrice price = new HistoricalPrice();
        price.setEpic(request.epic());
        price.setResolution(request.resolution());
        price.setSnapshotTimeUtc(parseTimestamp(item.snapshotTimeUTC()));
        price.setOpenBid(decimal(item.openPrice().bid(), "open bid"));
        price.setOpenAsk(decimal(item.openPrice().ask(), "open ask"));
        price.setHighBid(decimal(item.highPrice().bid(), "high bid"));
        price.setHighAsk(decimal(item.highPrice().ask(), "high ask"));
        price.setLowBid(decimal(item.lowPrice().bid(), "low bid"));
        price.setLowAsk(decimal(item.lowPrice().ask(), "low ask"));
        price.setCloseBid(decimal(item.closePrice().bid(), "close bid"));
        price.setCloseAsk(decimal(item.closePrice().ask(), "close ask"));
        price.setLastTradedVolume(Math.toIntExact(Objects.requireNonNull(item.lastTradedVolume(), "last traded volume")));
        price.setSource(request.source());
        price.setIngestionTimeUtc(Instant.now());
        return price;
    }

    private static Instant parseTimestamp(String value) {
        Objects.requireNonNull(value, "snapshotTimeUTC");
        return value.endsWith("Z") ? Instant.parse(value) : LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
    }

    private static double decimal(BigDecimal value, String field) {
        return Objects.requireNonNull(value, field).doubleValue();
    }

    private static String payloadHash(Instant fromInclusive, Instant toExclusive, List<PricesItem> items) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        update(digest, fromInclusive.toString());
        update(digest, toExclusive.toString());
        for (PricesItem item : items) {
            update(digest, item.snapshotTimeUTC());
            update(digest, item.openPrice().bid());
            update(digest, item.openPrice().ask());
            update(digest, item.highPrice().bid());
            update(digest, item.highPrice().ask());
            update(digest, item.lowPrice().bid());
            update(digest, item.lowPrice().ask());
            update(digest, item.closePrice().bid());
            update(digest, item.closePrice().ask());
            update(digest, item.lastTradedVolume());
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, Object value) {
        digest.update(Objects.toString(value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '|');
    }
}
