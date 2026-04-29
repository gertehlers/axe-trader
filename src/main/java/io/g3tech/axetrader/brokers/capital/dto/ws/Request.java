package io.g3tech.axetrader.brokers.capital.dto.ws;

public record Request(String destination, String correlationId, String cst, String securityToken, Object payload) {
}
