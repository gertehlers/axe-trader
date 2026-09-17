package io.g3tech.axetrader.history;

/** Answers whether the broker knows an instrument, so a typo never seeds years of "empty" history. */
@FunctionalInterface
public interface InstrumentCatalog {
    boolean exists(String epic);
}
