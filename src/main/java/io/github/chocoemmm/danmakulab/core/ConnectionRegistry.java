package io.github.chocoemmm.danmakulab.core;

import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.protocol.ProtocolException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Membership lock only protects in-memory state; never performs socket IO. */
@Component
public class ConnectionRegistry {
    private final LabProperties properties;
    private final Map<String, ClientConnection> connections = new HashMap<>();
    private final Map<String, Membership> memberships = new HashMap<>();
    private final Map<String, Room> rooms = new HashMap<>();
    private final Map<Room, Map<String, ClientConnection>> members = new HashMap<>();

    public ConnectionRegistry(LabProperties properties, MeterRegistry metrics) {
        this.properties = properties;
        metrics.gauge("lab.connections", this, ConnectionRegistry::connectionCount);
        metrics.gauge("lab.rooms", this, ConnectionRegistry::roomCount);
    }

    public synchronized ClientConnection register(WebSocketSession session) {
        if (connections.size() >= properties.maxConnections()) {
            throw new ProtocolException("connection_limit");
        }
        var connection = new ClientConnection(session, properties.connectionMessagesPerSecond());
        connections.put(session.getId(), connection);
        return connection;
    }

    public synchronized ClientConnection find(String id) {
        return connections.get(id);
    }

    public synchronized Membership join(ClientConnection connection, String roomId, String nickname) {
        if (connections.get(connection.id()) != connection) {
            throw new ProtocolException("connection_closed");
        }
        var previous = memberships.get(connection.id());
        if (previous != null && previous.room().id().equals(roomId)) {
            var updated = new Membership(previous.room(), nickname);
            memberships.put(connection.id(), updated);
            return updated;
        }
        var target = rooms.get(roomId);
        if (target == null) {
            boolean replacesLastMember = previous != null && members.get(previous.room()).size() == 1;
            if (rooms.size() >= properties.maxRooms() && !replacesLastMember) {
                throw new ProtocolException("room_limit");
            }
        }
        detach(connection.id());
        // Re-read: detaching the last member can remove the same room.
        target = rooms.computeIfAbsent(roomId, id -> new Room(id, properties.roomMessagesPerSecond()));
        members.computeIfAbsent(target, ignored -> new LinkedHashMap<>()).put(connection.id(), connection);
        var membership = new Membership(target, nickname);
        memberships.put(connection.id(), membership);
        return membership;
    }

    public synchronized Membership membership(ClientConnection connection) {
        var membership = memberships.get(connection.id());
        if (membership == null) {
            throw new ProtocolException("not_joined");
        }
        return membership;
    }

    public synchronized List<ClientConnection> snapshot(Room room) {
        var roomMembers = members.get(room);
        return roomMembers == null ? List.of() : List.copyOf(roomMembers.values());
    }

    public synchronized void remove(String id) {
        connections.remove(id);
        detach(id);
    }

    private void detach(String id) {
        var previous = memberships.remove(id);
        if (previous == null) {
            return;
        }
        var roomMembers = members.get(previous.room());
        roomMembers.remove(id);
        if (roomMembers.isEmpty()) {
            members.remove(previous.room());
            rooms.remove(previous.room().id(), previous.room());
        }
    }

    public synchronized int connectionCount() {
        return connections.size();
    }

    public synchronized int roomCount() {
        return rooms.size();
    }

    public record Membership(Room room, String nickname) {
    }
}
