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

/**
 * 真实网络集成测试：启动内嵌 Tomcat，用 JDK WebSocket 客户端验证完整收发链路。
 * RANDOM_PORT 随机选择端口，避免与本机演示用的 8080 冲突。
 * 测试检查客户端收到的内容，不把服务端调用 send 当作交付完成。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired ConnectionRegistry registry;
    private final List<Peer> peers = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    /** 每个场景之后回收客户端，并等待服务端回调清理，避免上个测试的成员污染下个场景。 */
    @AfterEach
    void cleanup() {
        peers.forEach(peer -> peer.socket.abort());
        // 清理回调是异步到达的，用有限轮询等待条件成立，而不假设关闭后立刻可见。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(registry.connectionCount()).isZero();
            assertThat(registry.roomCount()).isZero();
        });
    }

    /** 10 位观众都收到同一条弹幕，另一个房间的探针在观测窗口内没有收到消息。 */
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

    /** 未加入和非法 JSON 返回稳定错误；错误后连接仍能正常回应心跳。 */
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

    /** 换房确认后发起的新广播只到目标房间，不再把该连接算作旧房间成员。 */
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

    /** 两个发送者并发输入，三个观众看到的房间序号都必须按 1、2 递增。 */
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

    /** 常用汉字在 UTF-8 下占三个字节，验证按字节拦截而非只数 Java 字符。 */
    @Test
    void rejectsOversizeUtf8EvenWhenCharacterCountFitsContainerLimit() throws Exception {
        var peer = join("utf8", "reader");
        peer.sendMessage("too-big", "汉".repeat(400));
        assertThat(peer.take("error").path("reason").asText()).isEqualTo("frame_too_large");
        peer.send(Map.of("type", "ping"));
        peer.take("pong");
    }

    /** 未知类型、空白正文、错误类型的时间戳，分别返回对应原因码。 */
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

    /** 完整关闭握手后，验证连接表与房间表都能回到零，而不仅是客户端认为已关闭。 */
    @Test
    void gracefulCloseReleasesConnectionAndRoom() throws Exception {
        var peer = join("cleanup", "reader");
        peer.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS);
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(registry.connectionCount()).isZero();
            assertThat(registry.roomCount()).isZero();
        });
    }

    /** 创建一个真实客户端；get 带超时，连接失败会使测试明确失败而非无限等待。 */
    private Peer connect() throws Exception {
        var peer = new Peer();
        peer.socket = http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), peer)
                .get(5, TimeUnit.SECONDS);
        peers.add(peer);
        return peer;
    }

    /** 连接后等待 joined 回应再返回，调用方可据此建立稳定成员的测试窗口。 */
    private Peer join(String room, String nickname) throws Exception {
        var peer = connect();
        peer.send(Map.of("type", "join", "roomId", room, "nickname", nickname));
        assertThat(peer.take("joined").path("roomId").asText()).isEqualTo(room);
        return peer;
    }

    /** 把异步 WebSocket 回调转换为测试线程可轮询的收件箱，便于断言消息内容。 */
    private class Peer implements WebSocket.Listener {
        // 收件箱有界；本场景消息很少，溢出直接报错，避免工具本身无限积压。
        final BlockingQueue<JsonNode> inbox = new ArrayBlockingQueue<>(128);
        final StringBuilder fragments = new StringBuilder();
        WebSocket socket;

        @Override
        public void onOpen(WebSocket webSocket) {
            // JDK Listener 需要显式申请继续接收回调；这里每次申请一个。
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // 一条消息可能分片到达；last=true 才表示文本完整，可以解析为一个 JSON 对象。
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

        /** 把协议对象编码后发送；等待发送调用完成，不把它当成观众收到的确认。 */
        void send(Map<String, ?> message) throws Exception {
            socket.sendText(json.writeValueAsString(message), true).get(3, TimeUnit.SECONDS);
        }

        /** 构造合法 send 请求的便捷入口。 */
        void sendMessage(String id, String content) throws Exception {
            send(Map.of("type", "send", "clientMessageId", id, "text", content, "clientSentAt", System.currentTimeMillis()));
        }

        JsonNode take(String type) throws Exception {
            // 控制响应与广播可能交错。等待指定类型时暂存其他消息，最后放回收件箱，
            // 保证等待 send_result 的操作不会悄悄丢掉随后还需要断言的 danmaku。
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
