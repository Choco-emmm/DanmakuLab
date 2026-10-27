package io.github.chocoemmm.danmakulab.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.core.ClientConnection;
import io.github.chocoemmm.danmakulab.core.ConnectionRegistry;
import io.github.chocoemmm.danmakulab.core.DirectBroadcaster;
import io.github.chocoemmm.danmakulab.core.FairRoomDispatcher;
import io.github.chocoemmm.danmakulab.core.Room;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 将调度器明确的拒绝接到真实 JSON 协议入口，确认不会误报 accepted 或关掉正常连接。 */
class RoomAdmissionTest {
    @ParameterizedTest
    @ValueSource(strings = {"room_queue_full", "room_global_budget", "room_scheduler_full", "room_closed", "server_stopping"})
    void returnsCorrelatedRejectionForAdmissionFailure(String reason) throws Exception {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sender");
        var connection = mock(ClientConnection.class);
        when(connection.admitMessage()).thenReturn(true);
        var registry = mock(ConnectionRegistry.class);
        when(registry.find("sender")).thenReturn(connection);
        when(registry.membership(connection)).thenReturn(new ConnectionRegistry.Membership(new Room("room", 50), "A"));
        var broadcaster = mock(DirectBroadcaster.class);
        var dispatcher = mock(FairRoomDispatcher.class);
        when(dispatcher.enabled()).thenReturn(true);
        doThrow(new ProtocolException(reason)).when(dispatcher).submit(any(), any(), anyString(), anyString(), anyLong());
        var metrics = new SimpleMeterRegistry();
        try {
            var handler = new DanmakuHandler(new ObjectMapper(), new LabProperties(2000, 1000, 1024, 200, 5, 50),
                    registry, broadcaster, metrics, dispatcher);
            handler.handleTextMessage(session, new TextMessage("""
                    {"type":"send","clientMessageId":"one","text":"hi","clientSentAt":0}
                    """));
            verify(broadcaster).reply(eq(connection), argThat(event -> "send_result".equals(event.get("type"))
                    && "rejected".equals(event.get("status")) && reason.equals(event.get("reason"))
                    && "one".equals(event.get("clientMessageId"))));
            verify(session, never()).close(any());
            verify(broadcaster, never()).broadcast(any(), any(), anyString(), anyString(), anyLong());
            assertThat(metrics.counter("lab.messages.rejected", "reason", reason).count()).isEqualTo(1);
        } finally { metrics.close(); }
    }
}
