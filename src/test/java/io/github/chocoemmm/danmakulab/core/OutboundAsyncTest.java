package io.github.chocoemmm.danmakulab.core;

import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.config.OutboundProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 多慢连接饥饿的回归入口：发送池只有一个线程，两个连接的网络完成回调一直不返回，
 * 第三个正常连接仍必须写出。测试不靠放宽控制期限或增加线程来变绿。
 */
class OutboundAsyncTest {
    @Test
    void morePendingSocketsThanWorkersMustNotStarveHealthyConnection() throws Exception {
        var metrics = new SimpleMeterRegistry();
        var service = new OutboundService(new OutboundProperties("buffered", 8, 1024, 4096, 1, 20, 1000, 2),
                new LabProperties(20, 20, 1024, 200, 5, 50), metrics);
        var releaseOldBlockingWrite = new CountDownLatch(1);
        var slowOne = socket(false);
        var slowTwo = socket(false);
        var healthy = socket(true);
        // 旧路径调用 Spring 同步发送时，会占住唯一的发送线程。
        // 新路径应调用原生异步端点，保留回调就能模拟网络仍在等待，而不用占一条Java线程。
        for (var slow : new StandardWebSocketSession[]{slowOne, slowTwo}) {
            doAnswer(call -> { releaseOldBlockingWrite.await(3, TimeUnit.SECONDS); return null; })
                    .when(slow).sendMessage(any());
        }
        try {
            connect(service, slowOne).send(new TextMessage("slow-one"), true);
            connect(service, slowTwo).send(new TextMessage("slow-two"), true);
            connect(service, healthy).send(new TextMessage("pong"));
            await().atMost(600, TimeUnit.MILLISECONDS).untilAsserted(() ->
                    verify(healthy.getNativeSession().getAsyncRemote()).sendText(eq("pong"), any(SendHandler.class)));
            verify(slowOne, never()).sendMessage(any());
            verify(slowTwo, never()).sendMessage(any());
        } finally {
            releaseOldBlockingWrite.countDown();
            service.shutdown();
        }
    }

    /** 原生会话替身：快连接立即完成，慢连接先保留回调；真实容器由网络事件触发回调。 */
    private static StandardWebSocketSession socket(boolean immediate) {
        var spring = mock(StandardWebSocketSession.class);
        var nativeSession = mock(Session.class);
        var async = mock(RemoteEndpoint.Async.class);
        when(spring.isOpen()).thenReturn(true);
        when(spring.getNativeSession()).thenReturn(nativeSession);
        when(spring.getNativeSession(Session.class)).thenReturn(nativeSession);
        when(nativeSession.getAsyncRemote()).thenReturn(async);
        var pending = new ConcurrentLinkedQueue<SendHandler>();
        doAnswer(call -> {
            if (immediate) call.<SendHandler>getArgument(1).onResult(new SendResult());
            else pending.add(call.getArgument(1));
            return null;
        }).when(async).sendText(anyString(), any(SendHandler.class));
        // 测试清理也模拟容器关闭后的失败通知，不能让保留的回调/预算成为测试遗留物。
        try {
            doAnswer(call -> {
                SendHandler handler;
                while ((handler = pending.poll()) != null) handler.onResult(new SendResult(new IOException("closed")));
                return null;
            }).when(spring).close(any());
        } catch (IOException impossible) { throw new AssertionError(impossible); }
        return spring;
    }

    @Test
    void inFlightKeepsBudgetAndSlotAfterLogicalAndPhysicalCloseUntilCallback() throws Exception {
        var metrics = new SimpleMeterRegistry(); var service = service(metrics);
        var session = socket(false);
        var asyncRemote = session.getNativeSession().getAsyncRemote();
        var callback = new AtomicReference<SendHandler>();
        doAnswer(call -> { callback.set(call.getArgument(1)); return null; })
                .when(asyncRemote).sendText(anyString(), any(SendHandler.class));
        var connection = connect(service, session);
        try {
            connection.send(new TextMessage("1234"), true);
            await().until(() -> callback.get() != null);
            connection.fail("test_close");
            await().untilAsserted(() -> verify(session).close(any()));
            // 上面的close没有持有本测试自行捕获的回调：关闭先完成，写仍在途。
            assertThat(service.bufferedBytes()).isEqualTo(4);
            assertThat(metrics.get("lab.outbound.slots").gauge().value()).isEqualTo(1);
            callback.get().onResult(new SendResult(new IOException("network completed with failure")));
            await().untilAsserted(() -> {
                assertThat(service.bufferedBytes()).isZero();
                assertThat(metrics.get("lab.outbound.slots").gauge().value()).isZero();
                assertThat(metrics.get("lab.outbound.writes.inflight").gauge().value()).isZero();
            });
        } finally {
            if (callback.get() != null) callback.get().onResult(new SendResult(new IOException("cleanup")));
            service.shutdown();
        }
    }

    @Test
    void inlineDuplicateCallbackAndFollowingThrowAreHandledExactlyOnce() throws Exception {
        // 容器先成功通知、随后错误重复通知甚至抛异常，也只能算一次成功，不能再扣预算或断连。
        var metrics = new SimpleMeterRegistry(); var service = service(metrics); var session = socket(true);
        var asyncRemote = session.getNativeSession().getAsyncRemote();
        doAnswer(call -> {
            SendHandler callback = call.getArgument(1);
            callback.onResult(new SendResult());
            callback.onResult(new SendResult(new IOException("duplicate failure")));
            throw new IllegalStateException("throw after callback");
        }).when(asyncRemote).sendText(anyString(), any(SendHandler.class));
        try {
            connect(service, session).send(new TextMessage("hello"), true);
            await().untilAsserted(() -> assertThat(metrics.counter("lab.deliveries.submitted").count()).isEqualTo(1));
            assertThat(service.bufferedBytes()).isZero();
            assertThat(metrics.counter("lab.writes.failed").count()).isZero();
            assertThat(metrics.get("lab.outbound.writes.inflight").gauge().value()).isZero();
        } finally { service.shutdown(); }
    }

    @Test
    void delayedFailureCancelsQueuedMessagesWithoutStartingASecondWrite() throws Exception {
        // 第一条仍在传输时第二条排队；第一条超时后第二条必须取消，不能继续发到已失效连接。
        var metrics = new SimpleMeterRegistry(); var service = service(metrics); var session = socket(false);
        var asyncRemote = session.getNativeSession().getAsyncRemote();
        var callback = new AtomicReference<SendHandler>();
        doAnswer(call -> { callback.set(call.getArgument(1)); return null; })
                .when(asyncRemote).sendText(anyString(), any(SendHandler.class));
        try {
            var connection = connect(service, session);
            connection.send(new TextMessage("first"), true);
            await().until(() -> callback.get() != null);
            connection.send(new TextMessage("queued"), true);
            callback.get().onResult(new SendResult(new IOException("timeout")));
            await().untilAsserted(() -> {
                assertThat(service.bufferedBytes()).isZero();
                assertThat(metrics.get("lab.outbound.slots").gauge().value()).isZero();
            });
            verify(session.getNativeSession().getAsyncRemote(), times(1)).sendText(anyString(), any(SendHandler.class));
            assertThat(metrics.counter("lab.writes.failed").count()).isEqualTo(1);
            assertThat(metrics.counter("lab.outbound.discarded", "reason", "connection_closed").count()).isEqualTo(1);
        } finally { service.shutdown(); }
    }

    @Test
    void unsupportedTransportFailsWithoutLeakingConnectionSlot() {
        // 不支持原生异步端点时明确拒绝，不能偷偷同步发送，也不能泄漏已预留的准入名额。
        var metrics = new SimpleMeterRegistry(); var service = service(metrics);
        try {
            assertThatThrownBy(() -> service.connection(mock(WebSocketSession.class), 5, () -> { }))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("native Jakarta");
            assertThat(metrics.get("lab.outbound.slots").gauge().value()).isZero();
        } finally { service.shutdown(); }
    }

    @Test
    void fastInlineCompletionKeepsSingleWriterAcrossManyBatches() throws Exception {
        // 一千次立即完成仍要按批次推进；回调里递归发下一条会使调用深度或同时提交数大于一。
        var metrics = new SimpleMeterRegistry(); var service = service(metrics); var session = socket(true);
        var asyncRemote = session.getNativeSession().getAsyncRemote();
        var depth = new java.util.concurrent.atomic.AtomicInteger();
        var maximum = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            maximum.accumulateAndGet(depth.incrementAndGet(), Math::max);
            try { call.<SendHandler>getArgument(1).onResult(new SendResult()); }
            finally { depth.decrementAndGet(); }
            return null;
        }).when(asyncRemote).sendText(anyString(), any(SendHandler.class));
        try {
            var connection = connect(service, session);
            for (int i = 0; i < 1000; i++) connection.send(new TextMessage("hello"), true);
            await().untilAsserted(() -> assertThat(metrics.counter("lab.deliveries.submitted").count()).isEqualTo(1000));
            assertThat(maximum).hasValue(1);
            assertThat(service.bufferedBytes()).isZero();
            verify(session.getNativeSession().getAsyncRemote()).setSendTimeout(5_000L);
        } finally { service.shutdown(); }
    }

    private static OutboundService service(SimpleMeterRegistry metrics) {
        return new OutboundService(new OutboundProperties("buffered", 2048, 65536, 134217728, 1, 20, 10000, 2),
                new LabProperties(20, 20, 1024, 200, 5, 50), metrics);
    }

    /** 完成事件延迟到达时，后面的弹幕与pong都要等前一条；成功后必须能重新激活邮箱。 */
    @Test
    void delayedSuccessReactivatesMailboxAndPreservesControlAndDanmakuOrder() throws Exception {
        var metrics = new SimpleMeterRegistry(); var service = service(metrics); var session = socket(false);
        var remote = session.getNativeSession().getAsyncRemote();
        var pending = new ConcurrentLinkedQueue<SendHandler>();
        var payloads = new java.util.concurrent.CopyOnWriteArrayList<String>();
        doAnswer(call -> {
            payloads.add(call.getArgument(0)); pending.add(call.getArgument(1)); return null;
        }).when(remote).sendText(anyString(), any(SendHandler.class));
        try {
            var connection = connect(service, session);
            connection.send(new TextMessage("first"), true);
            await().until(() -> pending.size() == 1);
            connection.send(new TextMessage("second"), true);
            connection.send(new TextMessage("pong"));
            assertThat(payloads).containsExactly("first");
            assertThat(service.bufferedMessages()).isEqualTo(3);
            // 模拟三个先后到达的网络完成事件，每次只允许下一条交给容器。
            pending.remove().onResult(new SendResult());
            await().until(() -> pending.size() == 1);
            assertThat(payloads).containsExactly("first", "second");
            pending.remove().onResult(new SendResult());
            await().until(() -> pending.size() == 1);
            assertThat(payloads).containsExactly("first", "second", "pong");
            pending.remove().onResult(new SendResult());
            assertThat(service.bufferedBytes()).isZero();
            assertThat(metrics.counter("lab.deliveries.submitted").count()).isEqualTo(2);
            assertThat(metrics.get("lab.outbound.writes.inflight").gauge().value()).isZero();
        } finally {
            SendHandler callback;
            while ((callback = pending.poll()) != null) callback.onResult(new SendResult(new IOException("cleanup")));
            service.shutdown();
        }
    }

    private static ClientConnection connect(OutboundService service, StandardWebSocketSession session) {
        var ref = new AtomicReference<ClientConnection>();
        var connection = service.connection(session, 5, () -> ref.get().dispose());
        ref.set(connection);
        return connection;
    }
}
