package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * Identifies the rows a discovery run consumed, by logical content rather than by file bytes.
 *
 * <p>Hashing {@code data/axe-trader.sqlite} itself is not reproducible: merely opening a SQLite
 * database can rewrite its header, so two runs over identical data record different hashes. This
 * digest covers the row count, the window bounds, and every OHLC tuple in order, so it changes
 * when — and only when — the data actually changes.
 */
public final class DiscoveryInputDigest {

    private DiscoveryInputDigest() {
    }

    public static String of(List<HistoricalPrice> prices) {
        Objects.requireNonNull(prices, "prices");
        if (prices.isEmpty()) {
            throw new IllegalArgumentException("Cannot digest a window with no rows");
        }

        StringBuilder content = new StringBuilder();
        field(content, Integer.toString(prices.size()));
        field(content, prices.getFirst().getSnapshotTimeUtc().toString());
        field(content, prices.getLast().getSnapshotTimeUtc().toString());
        for (HistoricalPrice price : prices) {
            field(content, price.getSnapshotTimeUtc().toString());
            field(content, Double.toString(price.getOpenBid()));
            field(content, Double.toString(price.getOpenAsk()));
            field(content, Double.toString(price.getHighBid()));
            field(content, Double.toString(price.getHighAsk()));
            field(content, Double.toString(price.getLowBid()));
            field(content, Double.toString(price.getLowAsk()));
            field(content, Double.toString(price.getCloseBid()));
            field(content, Double.toString(price.getCloseAsk()));
            field(content, Integer.toString(price.getLastTradedVolume()));
        }
        return sha256(content.toString());
    }

    /** Length-delimited so no combination of values can be confused with a different one. */
    private static void field(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
