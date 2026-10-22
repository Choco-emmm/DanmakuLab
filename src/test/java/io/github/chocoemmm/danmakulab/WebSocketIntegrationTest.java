package io.github.chocoemmm.danmakulab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chocoemmm.danmakulab.core.ConnectionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired ConnectionRegistry registry;
    private final List<Peer> peers = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void cleanup() {
        peers.forEach(peer -> peer.socket.abort());
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(registry.connectionCount()).isZero();
            assertThat(registry.roomCount()).isZero();
        });
    }

    @Test
    void tenViewersReceiveExactBroadcastAndOtherRoomReceivesNothing() throws Exception {
        var viewers = new ArrayList<Peer>();
        for (int i = 0; i < 10; i++) viewers.add(join("same", "viewer-" + i));
        var isolated = join("other", "isolated");
        viewers.getFirst().sendMessage("one", "你好，弹幕！");
        var accepted = viewers.getFirst().take("send_result");
        assertThat(accepted.path("status").asText()).isEqualTo("accepted");
        for (var viewer : viewers) {
            var message = viewer.take("danmaku");
            assertThat(message.path("text").asText()).isEqualTo("你好，弹幕！");
            assertThat(message.path("clientMessageId").asText()).isEqualTo("one");
            assertThat(message.path("roomSeq").asLong()).isEqualTo(1);
        }
        assertThat(isolated.inbox.poll(250, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void rejectsUnjoinedAndMalformedMessagesWithoutBreakingHeartbeat() throws Exception {
        var peer = connect();
        peer.sendMessage("before-join", "hello");
        assertThat(peer.take("send_result").path("reason").asText()).isEqualTo("not_joined");
        peer.socket.sendText("{broken", true).get(3, TimeUnit.SECONDS);
        assertThat(peer.take("error").path("reason").asText()).isEqualTo("invalid_json");
        peer.send(Map.of("type", "ping"));
        peer.take("pong");
    }

    @Test
    void switchingRoomsRemovesOldMembershipAndPreservesIsolation() throws Exception {
        var moving = join("old", "moving");
        var old = join("old", "old");
        var target = join("new", "new");
        moving.send(Map.of("type", "join", "roomId", "new", "nickname", "renamed"));
        moving.take("joined");
        old.sendMessage("old-message", "old");
        old.take("send_result");
        old.take("danmaku");
        assertThat(moving.inbox.poll(150, TimeUnit.MILLISECONDS)).isNull();
        target.sendMessage("new-message", "new");
        target.take("send_result");
        target.take("danmaku");
        assertThat(moving.take("danmaku").path("clientMessageId").asText()).isEqualTo("new-message");
    }

    @Test
    void concurrentSendersProduceIdenticalIncreasingSequenceForAllMembers() throws Exception {
        var a = join("concurrent", "A");
        var b = join("concurrent", "B");
        var observer = join("concurrent", "observer");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { a.sendMessage("a", "A"); return null; });
            var second = executor.submit(() -> { b.sendMessage("b", "B"); return null; });
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
        }
        a.take("send_result");
        b.take("send_result");
        for (var peer : List.of(a, b, observer)) {
            assertThat(peer.take("danmaku").path("roomSeq").asLong()).isEqualTo(1);
            assertThat(peer.take("danmaku").path("roomSeq").asLong()).isEqualTo(2);
        }
    }

    @Test
    void rejectsOversizeUtf8EvenWhenCharacterCountFitsContainerLimit() throws Exception {
        var peer = join("utf8", "reader");
        peer.sendMessage("too-big", "汉".repeat(400));
        assertThat(peer.take("error").path("reason").asText()).isEqualTo("frame_too_large");
        peer.send(Map.of("type", "ping"));
        peer.take("pong");
    }

    @Test
    void rejectsInvalidTypesAndEmptyContent() throws Exception {
        var peer = join("validation", "reader");
        peer.send(Map.of("type", "mystery"));
        assertThat(peer.take("error").path("reason").asText()).isEqualTo("unknown_type");
        peer.sendMessage("empty", "   ");
        assertThat(peer.take("send_result").path("reason").asText()).isEqualTo("invalid_text");
        peer.send(Map.of("type", "send", "clientMessageId", "timestamp", "text", "hi", "clientSentAt", "wrong"));
        assertThat(peer.take("send_result").path("reason").asText()).isEqualTo("invalid_client_sent_at");
    }

    @Test
    void gracefulCloseReleasesConnectionAndRoom() throws Exception {
        var peer = join("cleanup", "reader");
        peer.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS);
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(registry.connectionCount()).isZero();
            assertThat(registry.roomCount()).isZero();
        });
    }

    private Peer connect() throws Exception {
        var peer = new Peer();
        peer.socket = http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), peer)
                .get(5, TimeUnit.SECONDS);
        peers.add(peer);
        return peer;
    }

    private Peer join(String room, String nickname) throws Exception {
        var peer = connect();
        peer.send(Map.of("type", "join", "roomId", room, "nickname", nickname));
        assertThat(peer.take("joined").path("roomId").asText()).isEqualTo(room);
        return peer;
    }

    private class Peer implements WebSocket.Listener {
        final BlockingQueue<JsonNode> inbox = new ArrayBlockingQueue<>(128);
        final StringBuilder fragments = new StringBuilder();
        WebSocket socket;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                try {
                    inbox.add(json.readTree(fragments.toString()));
                    fragments.setLength(0);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        void send(Map<String, ?> message) throws Exception {
            socket.sendText(json.writeValueAsString(message), true).get(3, TimeUnit.SECONDS);
        }

        void sendMessage(String id, String content) throws Exception {
            send(Map.of("type", "send", "clientMessageId", id, "text", content, "clientSentAt", System.currentTimeMillis()));
        }

        JsonNode take(String type) throws Exception {
            // Preserve interleaved broadcasts when waiting for a control reply.
            var deferred = new ArrayList<JsonNode>();
            try {
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < end) {
                    var value = inbox.poll(100, TimeUnit.MILLISECONDS);
                    if (value == null) continue;
                    if (value.path("type").asText().equals(type)) return value;
                    deferred.add(value);
                }
                throw new AssertionError("Did not receive " + type + "; other events=" + deferred);
            } finally {
                deferred.forEach(inbox::add);
            }
        }
    }
}
