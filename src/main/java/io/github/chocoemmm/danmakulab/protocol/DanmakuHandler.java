package io.github.chocoemmm.danmakulab.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.core.ClientConnection;
import io.github.chocoemmm.danmakulab.core.ConnectionRegistry;
import io.github.chocoemmm.danmakulab.core.DirectBroadcaster;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.regex.Pattern;

@Component
public class DanmakuHandler extends TextWebSocketHandler {
    private static final Pattern ROOM_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private final ObjectMapper json;
    private final LabProperties properties;
    private final ConnectionRegistry registry;
    private final DirectBroadcaster broadcaster;
    private final MeterRegistry metrics;

    public DanmakuHandler(ObjectMapper json, LabProperties properties, ConnectionRegistry registry,
                          DirectBroadcaster broadcaster, MeterRegistry metrics) {
        this.json = json;
        this.properties = properties;
        this.registry = registry;
        this.broadcaster = broadcaster;
        this.metrics = metrics;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Tomcat's blocking send timeout; baseline deliberately sends on the receiving thread.
        session.getAttributes().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 5_000L);
        try {
            registry.register(session);
        } catch (ProtocolException rejected) {
            metrics.counter("lab.connections.rejected").increment();
            session.close(new CloseStatus(1013, rejected.getMessage()));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var connection = registry.find(session.getId());
        if (connection == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String clientMessageId = "";
        boolean isSend = false;
        try {
            if (message.asBytes().length > properties.maxFrameBytes()) {
                throw new ProtocolException("frame_too_large");
            }
            var root = json.readTree(message.getPayload());
            if (root == null || !root.isObject()) {
                throw new ProtocolException("invalid_message");
            }
            String type = text(root, "type", 32);
            isSend = type.equals("send");
            if (isSend && root.path("clientMessageId").isTextual()
                    && root.path("clientMessageId").textValue().length() <= 64) {
                clientMessageId = root.path("clientMessageId").textValue();
            }
            switch (type) {
                case "join" -> {
                    String roomId = text(root, "roomId", 64);
                    if (!ROOM_ID.matcher(roomId).matches()) {
                        throw new ProtocolException("invalid_room_id");
                    }
                    var membership = registry.join(connection, roomId, text(root, "nickname", 32));
                    broadcaster.reply(connection, Map.of("type", "joined", "roomId", roomId,
                            "roomInstanceId", membership.room().instanceId(), "connectionId", connection.id()));
                }
                case "send" -> send(connection, root);
                case "ping" -> broadcaster.reply(connection, Map.of("type", "pong"));
                default -> throw new ProtocolException("unknown_type");
            }
        } catch (JsonProcessingException invalidJson) {
            reject(connection, false, "", "invalid_json");
        } catch (ProtocolException rejected) {
            reject(connection, isSend, clientMessageId, rejected.getMessage());
        }
    }

    private void send(ClientConnection connection, JsonNode root) throws Exception {
        metrics.counter("lab.messages.input").increment();
        String id = text(root, "clientMessageId", 64);
        String content = text(root, "text", properties.maxTextLength());
        var timestamp = root.path("clientSentAt");
        if (!timestamp.isIntegralNumber() || !timestamp.canConvertToLong() || timestamp.longValue() < 0) {
            throw new ProtocolException("invalid_client_sent_at");
        }
        var membership = registry.membership(connection);
        if (!connection.admitMessage()) {
            throw new ProtocolException("connection_rate_limit");
        }
        if (!membership.room().admitMessage()) {
            throw new ProtocolException("room_rate_limit");
        }
        broadcaster.broadcast(connection, membership, id, content, timestamp.longValue());
    }

    private String text(JsonNode root, String field, int maxLength) {
        var node = root.path(field);
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > maxLength) {
            throw new ProtocolException("invalid_" + field);
        }
        return node.textValue();
    }

    private void reject(ClientConnection connection, boolean isSend, String id, String reason) throws Exception {
        metrics.counter("lab.messages.rejected", "reason", reason).increment();
        if (isSend) {
            broadcaster.reply(connection, Map.of("type", "send_result", "status", "rejected",
                    "clientMessageId", id, "reason", reason));
        } else {
            broadcaster.reply(connection, Map.of("type", "error", "reason", reason));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        registry.remove(session.getId());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }
}
