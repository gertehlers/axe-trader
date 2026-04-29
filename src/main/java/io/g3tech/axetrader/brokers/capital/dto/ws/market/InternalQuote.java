package io.g3tech.axetrader.brokers.capital.dto.ws.market;

public record InternalQuote(String epic, String product, double bid, double bidQty, double ofr, double ofrQty, long timestamp) {
}
