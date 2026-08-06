package io.g3tech.axetrader.history;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

public final class PriceValidator {

    public Set<PriceValidationFailure> validate(ImportedPrice price) {
        EnumSet<PriceValidationFailure> failures = EnumSet.noneOf(PriceValidationFailure.class);
        if (price == null) {
            failures.add(PriceValidationFailure.TIMESTAMP_MISSING);
            return failures;
        }
        if (price.timestamp() == null) {
            failures.add(PriceValidationFailure.TIMESTAMP_MISSING);
        }
        validatePair(price.openBid(), price.openAsk(), PriceValidationFailure.OPEN_BID_NOT_POSITIVE,
                PriceValidationFailure.OPEN_ASK_NOT_POSITIVE, PriceValidationFailure.OPEN_BID_ABOVE_ASK, failures);
        validatePair(price.highBid(), price.highAsk(), PriceValidationFailure.HIGH_BID_NOT_POSITIVE,
                PriceValidationFailure.HIGH_ASK_NOT_POSITIVE, PriceValidationFailure.HIGH_BID_ABOVE_ASK, failures);
        validatePair(price.lowBid(), price.lowAsk(), PriceValidationFailure.LOW_BID_NOT_POSITIVE,
                PriceValidationFailure.LOW_ASK_NOT_POSITIVE, PriceValidationFailure.LOW_BID_ABOVE_ASK, failures);
        validatePair(price.closeBid(), price.closeAsk(), PriceValidationFailure.CLOSE_BID_NOT_POSITIVE,
                PriceValidationFailure.CLOSE_ASK_NOT_POSITIVE, PriceValidationFailure.CLOSE_BID_ABOVE_ASK, failures);
        if (price.lastTradedVolume() == null) {
            failures.add(PriceValidationFailure.LAST_TRADED_VOLUME_MISSING);
        } else if (price.lastTradedVolume() < 0) {
            failures.add(PriceValidationFailure.LAST_TRADED_VOLUME_NEGATIVE);
        }
        return failures;
    }

    private static void validatePair(BigDecimal bid, BigDecimal ask, PriceValidationFailure bidNotPositive,
                                     PriceValidationFailure askNotPositive, PriceValidationFailure bidAboveAsk,
                                     Set<PriceValidationFailure> failures) {
        if (!isPositive(bid)) {
            failures.add(bidNotPositive);
        }
        if (!isPositive(ask)) {
            failures.add(askNotPositive);
        }
        if (bid != null && ask != null && bid.compareTo(ask) > 0) {
            failures.add(bidAboveAsk);
        }
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
