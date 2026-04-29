package io.g3tech.axetrader.brokers.capital;

import io.g3tech.axetrader.brokers.capital.dto.ws.Request;
import io.g3tech.axetrader.brokers.capital.dto.ws.Response;
import io.g3tech.axetrader.brokers.capital.dto.ws.market.MarketDataSubscribe;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class WsClient {

    Logger logger = LoggerFactory.getLogger(WsClient.class);

    private final ObjectMapper objectMapper;
    private final WebSocketClient webSocketClient;

    private ConversationContext conversationContext;
    private WebSocketSession session;
    private URI wsUrl;

    public WsClient() {
        this.objectMapper = new ObjectMapper();
        this.webSocketClient = new StandardWebSocketClient();
    }

    public synchronized void connect(ConversationContext conversationContext) throws IOException {
        this.conversationContext = conversationContext;
        this.wsUrl = URI.create(conversationContext.streamingUrl().concat(Constants.CONNECT.value()));
        this.session = openSession();
    }

    public void ping() throws IOException {
        send("ping", null);
    }

    public void subscribeOHLCMarketData(String epic, String resolution) throws IOException {
        subscribeOHLCMarketData(List.of(epic), List.of(resolution));
    }

    public void subscribeOHLCMarketData(List<String> epics, List<String> resolutions) throws IOException {
        send("OHLCMarketData.subscribe", new MarketDataSubscribe(epics, resolutions));
    }

    private synchronized WebSocketSession getSession() throws IOException {
        if (session == null || !session.isOpen()) {
            session = openSession();
        }

        return session;
    }

    private WebSocketSession openSession() throws IOException {
        if (wsUrl == null) {
            throw new IllegalStateException("Call connect(conversationContext) before using the websocket client");
        }

        logger.info("Connecting websocket to {}", wsUrl);

        try {
            return webSocketClient.execute(new ClientHandler(), new WebSocketHttpHeaders(), wsUrl).get(15, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            var connectException = new ConnectException("Interrupted while connecting to websocket: %s".formatted(e.getMessage()));
            connectException.initCause(e);
            throw connectException;
        } catch (ExecutionException | TimeoutException e) {
            var connectException = new ConnectException("Failed to connect to websocket: " + e.getMessage());
            connectException.initCause(e);
            throw connectException;
        }
    }

    private void send(String destination, Object payload) throws IOException {
        var request = new Request(
                destination,
                UUID.randomUUID().toString(),
                conversationContext.clientSecurityToken(),
                conversationContext.accountSecurityToken(),
                payload
        );

        var message = objectMapper.writeValueAsString(request);
        logger.debug("Sending websocket message to {}", destination);

        getSession().sendMessage(new TextMessage(message));
    }

    public synchronized void close() throws IOException {
        if (session != null && session.isOpen()) {
            session.close();
        }
    }

    private class ClientHandler extends TextWebSocketHandler {

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            logger.info("Websocket connected: {}", session.getRemoteAddress());
        }

        @Override
        protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
            var response = objectMapper.readValue(message.getPayload(), Response.class);

            if ("ping".equals(response.destination())) {
                logger.debug("Received websocket ping response: {} {}", response.status(), response.payload());
                return;
            }

            logger.info("Received websocket message: destination={}, correlationId={}, status={}", response.destination(), response.correlationId(), response.status());
            logger.debug("Websocket payload: {}", response.payload());
        }

        @Override
        public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
            logger.warn("Websocket transport error", exception);
        }

        @Override
        public void afterConnectionClosed(@NonNull WebSocketSession session, CloseStatus status) {
            logger.info("Websocket connection closed: {}", session.getRemoteAddress());
            logger.info("Websocket closed: code={}, reason={}", status.getCode(), status.getReason());
        }
    }
}
