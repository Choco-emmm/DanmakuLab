package io.github.chocoemmm.danmakulab.core;

import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.config.OutboundProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 用可控阻塞的 Session 逼出真实并发交错；不依赖“网络碰巧慢”验证预算和单写者。
 * CountDownLatch 是闸门：测试明确知道发送线程何时进入、何时允许返回。
 */
class OutboundMailboxTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ArrayList<OutboundService> services = new ArrayList<>();
    private final ArrayList<CountDownLatch> gates = new ArrayList<>();

    @AfterEach
    void cleanup() {
        gates.forEach(CountDownLatch::countDown);
        services.forEach(OutboundService::shutdown);
    }

    private OutboundService service(int messages, long bytes, long global, int workers, int capacity, int age) {
        var properties = new OutboundProperties("buffered", messages, bytes, global, workers, capacity, age, 2);
        var service = new OutboundService(properties, new LabProperties(20, 20, 1024, 200, 5, 50), metrics);
        services.add(service);
        return service;
    }

    private ClientConnection connect(OutboundService service, WebSocketSession session) {
        var ref = new AtomicReference<ClientConnection>();
        var connection = service.connection(session, 5, () -> ref.get().dispose());
        ref.set(connection);
        return connection;
    }

    private WebSocketSession session() {
        var session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private CountDownLatch gate() {
        var gate = new CountDownLatch(1);
        gates.add(gate);
        return gate;
    }

    @Test
    void slowWriteDoesNotBlockAnotherConnectionAndNeverWritesOneSessionConcurrently() throws Exception {
        var service = service(8, 1024, 4096, 2, 20, 10000);
        var entered = gate();
        var release = gate();
        var slow = session();
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        doAnswer(call -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            active.decrementAndGet();
            return null;
        }).when(slow).sendMessage(any());
        var slowConnection = connect(service, slow);
        slowConnection.send(new TextMessage("first"), true);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        // 慢发送仍未放行，继续把弹幕和控制消息放进同一邮箱。
        slowConnection.send(new TextMessage("second"), true);
        slowConnection.send(new TextMessage("pong"));
        var fast = session();
        var delivered = gate();
        doAnswer(call -> { delivered.countDown(); return null; }).when(fast).sendMessage(any());
        connect(service, fast).send(new TextMessage("normal"), true);
        assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(release.getCount()).isEqualTo(1);
        release.countDown();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> verify(slow, times(3)).sendMessage(any()));
        assertThat(maximum).hasValue(1);
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(service.bufferedBytes()).isZero());
    }

    @Test
    void utf8BudgetIncludesInFlightAndDropsOnlyOldQueuedDanmaku() throws Exception {
        // 每个“弹”为 3 字节；上限 9 字节可放三个，含正在写的一个。
        var service = service(3, 9, 9, 1, 20, 10000);
        var entered = gate();
        var release = gate();
        var socket = session();
        var payloads = Collections.synchronizedList(new ArrayList<String>());
        doAnswer(call -> {
            TextMessage message = call.getArgument(0);
            payloads.add(message.getPayload());
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(socket).sendMessage(any());
        var connection = connect(service, socket);
        connection.send(new TextMessage("弹"), true);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        connection.send(new TextMessage("旧"), true);
        connection.send(new TextMessage("p"));
        connection.send(new TextMessage("新"), true);
        // 淘汰旧弹幕后占 3+1+3=7 字节，正在发送的“弹”没有被提前释放。
        assertThat(service.bufferedBytes()).isEqualTo(7);
        assertThat(service.bufferedMessages()).isEqualTo(3);
        assertThat(metrics.get("lab.outbound.dropped").tag("reason", "overflow").counter().count()).isEqualTo(1);
        release.countDown();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(service.bufferedBytes()).isZero());
        assertThat(payloads).containsExactly("弹", "p", "新");
    }

    @Test
    void globalBudgetFailureAndRepeatedCleanupDoNotDoubleRelease() throws Exception {
        var service = service(8, 100, 4, 1, 20, 10000);
        var entered = gate();
        var release = gate();
        var socket = session();
        doAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(socket).sendMessage(any());
        var first = connect(service, socket);
        first.send(new TextMessage("1234"), true);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        var second = connect(service, session());
        assertThatThrownBy(() -> second.send(new TextMessage("x"))).hasMessageContaining("global_budget");
        second.dispose();
        first.dispose();
        first.dispose();
        assertThat(service.bufferedBytes()).isEqualTo(4);
        release.countDown();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(service.bufferedBytes()).isZero();
            assertThat(service.bufferedMessages()).isZero();
        });
    }

    @Test
    void controlCannotBeSilentlyDroppedWhenMailboxIsFull() throws Exception {
        var service = service(1, 100, 100, 1, 20, 10000);
        var entered = gate();
        var release = gate();
        var socket = session();
        doAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(socket).sendMessage(any());
        var connection = connect(service, socket);
        connection.send(new TextMessage("joined"));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> connection.send(new TextMessage("pong"))).hasMessageContaining("mailbox_full");
        assertThat(metrics.get("lab.outbound.closed").tag("reason", "mailbox_full").counter().count()).isEqualTo(1);
        connection.fail("write_failure");
        assertThat(service.bufferedMessages()).isEqualTo(1);
    }

    @Test
    void expiredQueuedDanmakuIsRemovedWhileInFlightStillOwnsBudget() throws Exception {
        var service = service(8, 100, 100, 1, 20, 30);
        var entered = gate();
        var release = gate();
        var socket = session();
        doAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(socket).sendMessage(any());
        var connection = connect(service, socket);
        connection.send(new TextMessage("inflight"), true);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        connection.send(new TextMessage("expired"), true);
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(service.bufferedMessages()).isEqualTo(1);
            assertThat(metrics.get("lab.outbound.dropped").tag("reason", "expired").counter().count()).isEqualTo(1);
        });
        assertThat(service.bufferedBytes()).isEqualTo(8);
    }

    @Test
    void executorRejectionCancelsQueuedBudgetWithoutCallerNetworkWrite() throws Exception {
        var service = service(8, 100, 1000, 1, 1, 10000);
        var entered = gate();
        var release = gate();
        var blocking = session();
        doAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(blocking).sendMessage(any());
        connect(service, blocking).send(new TextMessage("a"), true);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        connect(service, session()).send(new TextMessage("b"), true);
        var rejectedSocket = session();
        var rejected = connect(service, rejectedSocket);
        assertThatThrownBy(() -> rejected.send(new TextMessage("c"), true)).hasMessageContaining("executor rejected");
        verify(rejectedSocket, never()).sendMessage(any());
        assertThat(service.bufferedBytes()).isEqualTo(2);
    }

    @Test
    void failedWriteReclaimsQueuedAndInFlightExactlyOnce() throws Exception {
        var service = service(8, 100, 100, 1, 20, 10000);
        var entered = gate();
        var release = gate();
        var socket = session();
        doAnswer(call -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            throw new IOException("simulated network failure");
        }).when(socket).sendMessage(any());
        var connection = connect(service, socket);
        connection.send(new TextMessage("first"), true);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        connection.send(new TextMessage("queued"), true);
        release.countDown();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(service.bufferedBytes()).isZero();
            assertThat(service.bufferedMessages()).isZero();
            verify(socket, times(1)).close(any());
        });
        connection.dispose();
        connection.fail("write_failure");
        assertThat(service.bufferedBytes()).isZero();
        assertThat(metrics.get("lab.writes.failed").counter().count()).isEqualTo(1);
    }

    @Test
    void staleControlClosesConnectionInsteadOfDroppingItsReply() throws Exception {
        var service = service(8, 100, 100, 1, 20, 30);
        var entered = gate();
        var release = gate();
        var socket = session();
        doAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(socket).sendMessage(any());
        var connection = connect(service, socket);
        connection.send(new TextMessage("inflight"), true);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        connection.send(new TextMessage("pong"));
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(metrics.get("lab.outbound.closed").tag("reason", "control_expired").counter().count()).isEqualTo(1);
            verify(socket).close(any());
        });
        // 控制回复随断连作废，仍在途的 8 字节只有发送返回才释放。
        assertThat(service.bufferedBytes()).isEqualTo(8);
    }

    @Test
    void oversizedUtf8EntryCannotReserveBeyondConnectionLimit() throws Exception {
        var service = service(8, 5, 100, 1, 20, 10000);
        var socket = session();
        var connection = connect(service, socket);
        // 两个汉字是 6 UTF-8 字节，超过本用例 5 字节上限，不能先入队再发现超限。
        assertThatThrownBy(() -> connection.send(new TextMessage("弹幕"), true)).hasMessageContaining("message_too_large");
        assertThat(service.bufferedBytes()).isZero();
        assertThat(service.bufferedMessages()).isZero();
        verify(socket, never()).sendMessage(any());
    }

    @Test
    void closingConnectionsKeepAdmissionSlotsUntilPhysicalCloseCompletes() throws Exception {
        var service = service(8, 100, 1000, 1, 20, 10000);
        var release = gate();
        // 本测试服务容量为 20。关闭请求已注销逻辑关系，但底层关闭刻意卡住。
        for (int i = 0; i < 20; i++) {
            var socket = session();
            doAnswer(call -> { release.await(5, TimeUnit.SECONDS); return null; }).when(socket).close(any());
            connect(service, socket).fail("write_failure");
        }
        assertThat(metrics.get("lab.outbound.slots").gauge().value()).isEqualTo(20);
        assertThatThrownBy(() -> connect(service, session())).hasMessage("connection_limit");
        release.countDown();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(metrics.get("lab.outbound.slots").gauge().value()).isZero());
        var reused = connect(service, session());
        reused.dispose();
        assertThat(metrics.get("lab.outbound.slots").gauge().value()).isZero();
    }

    @Test
    void shutdownWaitsForAnAlreadyStartedFailureToSubmitItsCloseTask() throws Exception {
        var service = service(8, 100, 1000, 1, 20, 10000);
        var invalidationEntered = gate();
        var allowInvalidation = gate();
        var connectionRef = new AtomicReference<ClientConnection>();
        var failureError = new AtomicReference<Throwable>();
        var shutdownError = new AtomicReference<Throwable>();
        var socket = session();
        // 刻意停在“已决定关闭、尚未提交关闭任务”之间，模拟注销回调碰到并发调度。
        // 原实现此时让 shutdown 跳过本连接并关闭线程池，随后第一次关闭提交就会被拒绝。
        var connection = service.connection(socket, 5, () -> {
            invalidationEntered.countDown();
            try {
                if (!allowInvalidation.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("等待释放注销回调超时");
                }
                connectionRef.get().dispose();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        connectionRef.set(connection);
        var failing = new Thread(() -> {
            try { connection.fail("write_failure"); }
            catch (Throwable error) { failureError.set(error); }
        }, "test-first-failure");
        var shuttingDown = new Thread(() -> {
            try { service.shutdown(); }
            catch (Throwable error) { shutdownError.set(error); }
        }, "test-concurrent-shutdown");
        // 守护线程是测试失败时的最后兜底；正常路径仍要求 join 成功，不能让后台线程污染下一例。
        failing.setDaemon(true);
        shuttingDown.setDaemon(true);
        try {
            failing.start();
            assertThat(invalidationEntered.await(2, TimeUnit.SECONDS)).isTrue();
            shuttingDown.start();
            // 不靠任意 sleep 猜测交错：旧实现已经结束停机；修复后应在生命周期锁上等待。
            await().atMost(2, TimeUnit.SECONDS).until(() ->
                    !shuttingDown.isAlive() || shuttingDown.getState() == Thread.State.BLOCKED);
        } finally {
            // 即使上面的断言失败也必须放行，再有限等待两个线程退出，避免测试把线程锁死。
            allowInvalidation.countDown();
            failing.join(3000);
            if (shuttingDown.getState() != Thread.State.NEW) shuttingDown.join(3000);
            if (failing.isAlive()) failing.interrupt();
            if (shuttingDown.isAlive()) shuttingDown.interrupt();
        }
        assertThat(failing.isAlive()).isFalse();
        assertThat(shuttingDown.isAlive()).isFalse();
        assertThat(failureError.get()).isNull();
        assertThat(shutdownError.get()).isNull();
        verify(socket, times(1)).close(any());
        assertThat(metrics.get("lab.outbound.slots").gauge().value()).isZero();
    }
}
