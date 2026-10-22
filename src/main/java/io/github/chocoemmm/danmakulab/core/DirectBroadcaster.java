package io.github.chocoemmm.danmakulab.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class DirectBroadcaster {
    private final ConnectionRegistry registry;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final String runId = UUID.randomUUID().toString();

    public DirectBroadcaster(ConnectionRegistry registry, ObjectMapper json, MeterRegistry metrics) {
        this.registry = registry;
        this.json = json;
        this.metrics = metrics;
    }

    public void broadcast(ClientConnection sender, ConnectionRegistry.Membership membership,
                          String clientMessageId, String text, long clientSentAt) throws IOException {
        var room = membership.room();
        synchronized (room) {
            var recipients = registry.snapshot(room);
            long sequence = room.nextSequence();
            var event = new LinkedHashMap<String, Object>();
            event.put("type", "danmaku");
            event.put("runId", runId);
            event.put("roomId", room.id());
            event.put("roomInstanceId", room.instanceId());
            event.put("roomSeq", sequence);
            event.put("clientMessageId", clientMessageId);
            event.put("clientSentAt", clientSentAt);
            event.put("senderId", sender.id());
            event.put("nickname", membership.nickname());
            event.put("text", text);
            var message = encode(event);
            metrics.counter("lab.messages.accepted").increment();
            metrics.counter("lab.deliveries.expected").increment(recipients.size());
            // Acknowledges admission, not delivery. Baseline has no application queue.
            reply(sender, Map.of("type", "send_result", "status", "accepted",
                    "clientMessageId", clientMessageId, "roomSeq", sequence));
            for (var recipient : recipients) {
                if (reply(recipient, message)) {
                    metrics.counter("lab.deliveries.submitted").increment();
                }
            }
        }
    }

    public boolean reply(ClientConnection connection, Map<String, ?> event) throws JsonProcessingException {
        return reply(connection, encode(event));
    }

    private boolean reply(ClientConnection connection, TextMessage message) {
        try {
            connection.send(message);
            return true;
        } catch (IOException | IllegalStateException failure) {
            metrics.counter("lab.writes.failed").increment();
            registry.remove(connection.id());
            connection.closeAfterFailure();
            return false;
        }
    }

    private TextMessage encode(Map<String, ?> event) throws JsonProcessingException {
        return new TextMessage(json.writeValueAsString(event));
    }
}
