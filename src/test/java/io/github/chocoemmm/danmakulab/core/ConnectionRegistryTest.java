package io.github.chocoemmm.danmakulab.core;

import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.protocol.ProtocolException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectionRegistryTest {
    private final ConnectionRegistry registry = new ConnectionRegistry(
            new LabProperties(2, 1, 1024, 200, 5, 50), new SimpleMeterRegistry());

    @Test
    void enforcesConnectionCapAndReclaimsAfterIdempotentRemoval() {
        registry.register(session("a"));
        registry.register(session("b"));
        assertThatThrownBy(() -> registry.register(session("c")))
                .isInstanceOf(ProtocolException.class).hasMessage("connection_limit");
        registry.remove("a");
        registry.remove("a");
        registry.register(session("c"));
        assertThat(registry.connectionCount()).isEqualTo(2);
    }

    @Test
    void failedRoomSwitchPreservesMembershipAndEmptyRoomsAreReclaimed() {
        var a = registry.register(session("a"));
        var b = registry.register(session("b"));
        var first = registry.join(a, "one", "A");
        registry.join(b, "one", "B");
        assertThatThrownBy(() -> registry.join(a, "two", "A"))
                .hasMessage("room_limit");
        assertThat(registry.membership(a)).isEqualTo(first);
        registry.remove("b");
        var second = registry.join(a, "two", "A");
        assertThat(registry.snapshot(first.room())).isEmpty();
        assertThat(registry.snapshot(second.room())).containsExactly(a);
        registry.remove("a");
        assertThat(registry.roomCount()).isZero();
    }

    @Test
    void repeatedJoinPreservesRoomSequenceAndLimiter() {
        var a = registry.register(session("a"));
        var original = registry.join(a, "one", "A");
        var repeated = registry.join(a, "one", "new name");
        assertThat(repeated.room()).isSameAs(original.room());
        assertThat(repeated.nickname()).isEqualTo("new name");
    }

    private WebSocketSession session(String id) {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }
}
