package io.g3tech.axetrader.backtest.discovery.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Objects;

/** Immutable, canonical provenance for one discovery database run. */
public record DiscoveryRun(
        String inputDataSha256,
        String configSha256,
        String featureSchemaVersion,
        String scoreVersion,
        String sourceCommit,
        boolean sourceDirty,
        String instrument,
        int timeframeMinutes,
        Instant windowFrom,
        Instant windowTo) {

    public DiscoveryRun {
        inputDataSha256 = required(inputDataSha256, "inputDataSha256");
        configSha256 = required(configSha256, "configSha256");
        featureSchemaVersion = required(featureSchemaVersion, "featureSchemaVersion");
        scoreVersion = required(scoreVersion, "scoreVersion");
        sourceCommit = required(sourceCommit, "sourceCommit");
        instrument = required(instrument, "instrument");
        Objects.requireNonNull(windowFrom, "windowFrom");
        Objects.requireNonNull(windowTo, "windowTo");
        if (timeframeMinutes <= 0) {
            throw new IllegalArgumentException("timeframeMinutes must be positive");
        }
        if (!windowFrom.isBefore(windowTo)) {
            throw new IllegalArgumentException("data window must be half-open with from before to");
        }
    }

    /** SHA-256 over the ordered, length-delimited run provenance fields. */
    public String runKey() {
        return sha256(canonicalIdentity());
    }

    private String canonicalIdentity() {
        return field(inputDataSha256) + field(configSha256) + field(featureSchemaVersion)
                + field(scoreVersion) + field(sourceCommit) + field(Boolean.toString(sourceDirty))
                + field(instrument) + field(Integer.toString(timeframeMinutes))
                + field(windowFrom.toString()) + field(windowTo.toString());
    }

    private static String field(String value) {
        return value.length() + ":" + value;
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

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
