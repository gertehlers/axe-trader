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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Service
public class CapitalHistoricalPricePageSource implements HistoricalPricePageSource {

    private final AuthenticationClient authenticationClient;
    private final ApiClient apiClient;
    private final CapitalRequestPacer pacer;
    private final CapitalSessionPacer sessionPacer;
    private final CapitalRequestPacer.Sleeper sleeper;
    private final Supplier<Duration> jitter;
    private final Supplier<Instant> wallClock;
    private final int maxAttempts;
    private volatile ConversationContext conversationContext;

    public CapitalHistoricalPricePageSource(AuthenticationClient authenticationClient, ApiClient apiClient) {
        this(authenticationClient, apiClient, new CapitalRequestPacer(5), new CapitalSessionPacer(), systemSleeper(),
                () -> Duration.ofMillis(ThreadLocalRandom.current().nextLong(101)), 4, Instant::now);
    }

    @Autowired
    public CapitalHistoricalPricePageSource(AuthenticationClient authenticationClient, ApiClient apiClient,
                                            CapitalRequestPacer pacer, CapitalSessionPacer sessionPacer) {
        this(authenticationClient, apiClient, pacer, sessionPacer, systemSleeper(),
                () -> Duration.ofMillis(ThreadLocalRandom.current().nextLong(101)), 4, Instant::now);
    }

    CapitalHistoricalPricePageSource(AuthenticationClient authenticationClient, ApiClient apiClient,
                                     CapitalRequestPacer pacer, CapitalRequestPacer.Sleeper sleeper,
                                     Supplier<Duration> jitter, int maxAttempts) {
        this(authenticationClient, apiClient, pacer, new CapitalSessionPacer(), sleeper, jitter, maxAttempts,
                Instant::now);
    }

    CapitalHistoricalPricePageSource(AuthenticationClient authenticationClient, ApiClient apiClient,
                                     CapitalRequestPacer pacer, CapitalSessionPacer sessionPacer,
                                     CapitalRequestPacer.Sleeper sleeper, Supplier<Duration> jitter,
                                     int maxAttempts, Supplier<Instant> wallClock) {
        this.authenticationClient = Objects.requireNonNull(authenticationClient, "authenticationClient");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.pacer = Objects.requireNonNull(pacer, "pacer");
        this.sessionPacer = Objects.requireNonNull(sessionPacer, "sessionPacer");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    @Override
    public ImportedPage fetch(HistoryImportRequest request, Instant fromInclusive, Instant toExclusive, int maxBars) {
        Objects.requireNonNull(request, "request");
        validatePage(request, fromInclusive, toExclusive, maxBars);

        final io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesResponse response =
                fetchWithRetry(request, fromInclusive, toExclusive, maxBars);
        if (response == null) {
            return new ImportedPage(fromInclusive, toExclusive, List.of(),
                    hashPage(fromInclusive, toExclusive, List.of()));
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

    private io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesResponse fetchWithRetry(
            HistoryImportRequest request, Instant fromInclusive, Instant toExclusive, int maxBars) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ConversationContext context = authenticatedContext();
            pacer.acquire();
            try {
                return Objects.requireNonNull(apiClient.getPrices(
                                context, new GetPricesRequest(request.epic(), request.resolution(),
                                        fromInclusive, toExclusive, maxBars)),
                        "Capital prices response was empty");
            } catch (HttpClientErrorException.NotFound ignored) {
                return null;
            } catch (RuntimeException failure) {
                if (!retryable(failure) || attempt == maxAttempts) {
                    throw failure;
                }
                lastFailure = failure;
                Duration delay = retryDelay(failure, attempt);
                if (delay.compareTo(Duration.ofMinutes(10)) >= 0) {
                    invalidateConversationContext();
                }
                sleeper.sleep(delay);
            }
        }
        throw lastFailure == null ? new IllegalStateException("Capital retry loop made no attempt") : lastFailure;
    }

    private Duration retryDelay(RuntimeException failure, int failedAttempt) {
        if (failure instanceof HttpClientErrorException.TooManyRequests rateLimited) {
            Duration retryAfter = retryAfter(rateLimited.getResponseHeaders());
            if (retryAfter != null) {
                return retryAfter;
            }
        }
        long exponentialMillis = Math.min(5_000L, 250L << Math.min(failedAttempt - 1, 4));
        Duration boundedJitter = min(nonNegative(jitter.get()), Duration.ofMillis(100));
        return Duration.ofMillis(exponentialMillis).plus(boundedJitter);
    }

    private static boolean retryable(RuntimeException failure) {
        if (failure instanceof ResourceAccessException transport) {
            return hasTransientTransportCause(transport);
        }
        return failure instanceof HttpStatusCodeException http
                && (http.getStatusCode().value() == 429 || http.getStatusCode().is5xxServerError());
    }

    private static boolean hasTransientTransportCause(Throwable failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof ConnectException
                    || cause instanceof SocketException) {
                return true;
            }
        }
        return false;
    }

    private Duration retryAfter(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return nonNegative(Duration.ofSeconds(Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return nonNegative(Duration.between(wallClock.get(), retryAt));
            } catch (DateTimeParseException malformed) {
                return null;
            }
        }
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static Duration nonNegative(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return Duration.ZERO;
        }
        return duration;
    }

    private void invalidateConversationContext() {
        synchronized (this) {
            conversationContext = null;
        }
    }

    private static CapitalRequestPacer.Sleeper systemSleeper() {
        return duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during Capital retry backoff", exception);
            }
        };
    }

    private ConversationContext authenticatedContext() {
        var cached = conversationContext;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (conversationContext == null) {
                conversationContext = createSessionWithRetry();
            }
            return conversationContext;
        }
    }

    private ConversationContext createSessionWithRetry() {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            sessionPacer.acquire();
            try {
                return Objects.requireNonNull(authenticationClient.createSession(),
                        "Capital authentication returned no conversation context");
            } catch (RuntimeException failure) {
                if (!retryable(failure) || attempt == maxAttempts) {
                    throw failure;
                }
                lastFailure = failure;
                sleeper.sleep(retryDelay(failure, attempt));
            }
        }
        throw lastFailure == null ? new IllegalStateException("Capital session retry loop made no attempt") : lastFailure;
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
