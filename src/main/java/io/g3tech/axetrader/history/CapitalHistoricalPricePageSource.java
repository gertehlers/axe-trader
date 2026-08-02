package io.g3tech.axetrader.history;

import io.g3tech.axetrader.brokers.capital.ApiClient;
import io.g3tech.axetrader.brokers.capital.AuthenticationClient;
import io.g3tech.axetrader.brokers.capital.ConversationContext;
import io.g3tech.axetrader.brokers.capital.dto.prices.ClosePrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesRequest;
import io.g3tech.axetrader.brokers.capital.dto.prices.HighPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.LowPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.OpenPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.PricesItem;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

@Service
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
        validatePage(request, fromInclusive, toExclusive, maxBars);

        final io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesResponse response;
        try {
            response = Objects.requireNonNull(apiClient.getPrices(
                    authenticatedContext(),
                    new GetPricesRequest(request.epic(), request.resolution(), fromInclusive, toExclusive, maxBars)),
                    "Capital prices response was empty");
        } catch (HttpClientErrorException.NotFound ignored) {
            return new ImportedPage(fromInclusive, toExclusive, List.of(), hashPage(fromInclusive, toExclusive, List.of()));
        }
        var returnedPrices = response.prices() == null ? List.<PricesItem>of() : response.prices();
        var inPagePrices = returnedPrices.stream().filter(price -> {
            Instant timestamp = parseUtcTimestamp(price.snapshotTimeUTC());
            if (timestamp.isAfter(toExclusive)) {
                throw new IllegalStateException("Capital returned a timestamp after the requested page");
            }
            return timestamp.isBefore(toExclusive);
        }).toList();
        var importedPrices = inPagePrices.stream().map(CapitalHistoricalPricePageSource::map).toList();

        return new ImportedPage(fromInclusive, toExclusive, importedPrices, hashPage(fromInclusive, toExclusive, inPagePrices));
    }

    private ConversationContext authenticatedContext() {
        var cached = conversationContext;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (conversationContext == null) {
                conversationContext = authenticationClient.createSession();
            }
            return conversationContext;
        }
    }

    private static void validatePage(HistoryImportRequest request, Instant fromInclusive, Instant toExclusive, int maxBars) {
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
        if (fromInclusive.isBefore(request.from()) || toExclusive.isAfter(request.to())) {
            throw new IllegalArgumentException("page bounds must be within the import window");
        }
        if (maxBars < 1 || maxBars > 1_000) {
            throw new IllegalArgumentException("maxBars must be between 1 and 1000");
        }
    }

    private static ImportedPrice map(PricesItem price) {
        return new ImportedPrice(
                parseUtcTimestamp(price.snapshotTimeUTC()),
                bid(price.openPrice()), ask(price.openPrice()),
                bid(price.highPrice()), ask(price.highPrice()),
                bid(price.lowPrice()), ask(price.lowPrice()),
                bid(price.closePrice()), ask(price.closePrice()),
                price.lastTradedVolume());
    }

    private static Instant parseUtcTimestamp(String timestamp) {
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(timestamp).toInstant(ZoneOffset.UTC);
        }
    }

    private static BigDecimal bid(OpenPrice price) {
        return price == null ? null : price.bid();
    }

    private static BigDecimal ask(OpenPrice price) {
        return price == null ? null : price.ask();
    }

    private static BigDecimal bid(HighPrice price) {
        return price == null ? null : price.bid();
    }

    private static BigDecimal ask(HighPrice price) {
        return price == null ? null : price.ask();
    }

    private static BigDecimal bid(LowPrice price) {
        return price == null ? null : price.bid();
    }

    private static BigDecimal ask(LowPrice price) {
        return price == null ? null : price.ask();
    }

    private static BigDecimal bid(ClosePrice price) {
        return price == null ? null : price.bid();
    }

    private static BigDecimal ask(ClosePrice price) {
        return price == null ? null : price.ask();
    }

    private static String hashPage(Instant fromInclusive, Instant toExclusive, List<PricesItem> prices) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, fromInclusive.toString());
        append(canonical, toExclusive.toString());
        for (PricesItem price : prices) {
            append(canonical, price.snapshotTime());
            append(canonical, price.snapshotTimeUTC());
            append(canonical, price.openPrice() == null ? null : price.openPrice().bid());
            append(canonical, price.openPrice() == null ? null : price.openPrice().ask());
            append(canonical, price.openPrice() == null ? null : price.openPrice().lastTraded());
            append(canonical, price.closePrice() == null ? null : price.closePrice().bid());
            append(canonical, price.closePrice() == null ? null : price.closePrice().ask());
            append(canonical, price.closePrice() == null ? null : price.closePrice().lastTraded());
            append(canonical, price.highPrice() == null ? null : price.highPrice().bid());
            append(canonical, price.highPrice() == null ? null : price.highPrice().ask());
            append(canonical, price.highPrice() == null ? null : price.highPrice().lastTraded());
            append(canonical, price.lowPrice() == null ? null : price.lowPrice().bid());
            append(canonical, price.lowPrice() == null ? null : price.lowPrice().ask());
            append(canonical, price.lowPrice() == null ? null : price.lowPrice().lastTraded());
            append(canonical, price.lastTradedVolume());
        }

        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void append(StringBuilder canonical, Object value) {
        String text = value == null ? "<null>" : value.toString();
        canonical.append(text.length()).append(':').append(text).append('\n');
    }
}
