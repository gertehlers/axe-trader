package io.g3tech.axetrader.brokers.capital.dto.ws;

public record Response (Status status, String destination, String correlationId, Object payload) {

    enum Status {
        OK, ERROR
    }
}
