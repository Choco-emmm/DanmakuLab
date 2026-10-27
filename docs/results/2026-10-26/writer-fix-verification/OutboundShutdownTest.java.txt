package io.github.chocoemmm.danmakulab.core;

import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.config.OutboundProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 强制停机的资源归还测试：两个关闭线程都被底层传输卡住时，第三个关闭任务尚在队列。
 * 停机取消未启动关闭任务后，若其写仍在途，名额和预算都必须等完成回调，不能提前复用。
 */
class OutboundShutdownTest {
    @Test
    void forcedShutdownRetiresQueuedClosersButPreservesInFlightBudgetUntilWriterReturns() throws Exception {
        var metrics = new SimpleMeterRegistry();
        var properties = new OutboundProperties("buffered", 8, 100, 1000, 1, 20, 10000, 2);
        var service = new OutboundService(properties, new LabProperties(20, 20, 1024, 200, 5, 50), metrics);
        var closersEntered = new CountDownLatch(2);
        var releaseClosers = new CountDownLatch(1);
        var writerEntered = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var shutdownError = new AtomicReference<Throwable>();
        var first = socket();
        var second = socket();
        var queued = socket();
        // 模拟不响应 Java 中断的网络关闭，迫使第三个关闭任务留在队列中。
        for (var socket : new WebSocketSession[]{first, second}) {
            doAnswer(call -> {
                closersEntered.countDown();
                awaitUninterruptibly(releaseClosers);
                return null;
            }).when(socket).close(any());
        }
        doAnswer(call -> {
            writerEntered.countDown();
            awaitUninterruptibly(releaseWriter);
            return null;
        }).when(queued).sendMessage(any());
        var firstConnection = connect(service, first);
        var secondConnection = connect(service, second);
        var queuedConnection = connect(service, queued);
        // 五字节消息已离开邮箱队列、正在发送，后面的停机不可以提前释放它。
        queuedConnection.send(new TextMessage("hello"), true);
        assertThat(writerEntered.await(2, TimeUnit.SECONDS)).isTrue();
        firstConnection.fail("write_failure");
        secondConnection.fail("write_failure");
        assertThat(closersEntered.await(2, TimeUnit.SECONDS)).isTrue();
        queuedConnection.fail("write_failure");
        var shutdown = new Thread(() -> {
            // 用已设置的中断进入强制清理分支，不让测试等待两轮七秒优雅期限。
            Thread.currentThread().interrupt();
            try { service.shutdown(); }
            catch (Throwable error) { shutdownError.set(error); }
        }, "test-interrupted-outbound-shutdown");
        shutdown.setDaemon(true);
        try {
            shutdown.start();
            shutdown.join(2000);
            assertThat(shutdown.isAlive()).isFalse();
            assertThat(shutdownError.get()).isNull();
            // 两个关闭尚未完成，第三个关闭被取消但写仍在途：三个名额都不能复用。
            assertThat(metrics.get("lab.outbound.slots").gauge().value()).isEqualTo(3);
            assertThat(metrics.get("lab.outbound.close.abandoned").counter().count()).isEqualTo(1);
            assertThat(service.bufferedBytes()).isEqualTo(5);
            assertThat(service.bufferedMessages()).isEqualTo(1);
            verify(queued, never()).close(any());
        } finally {
            // 即使断言失败，也放行所有测试线程，避免污染其他用例。
            releaseClosers.countDown();
            releaseWriter.countDown();
            shutdown.join(2000);
            service.shutdown();
        }
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(metrics.get("lab.outbound.slots").gauge().value()).isZero();
            assertThat(service.bufferedBytes()).isZero();
            assertThat(service.bufferedMessages()).isZero();
        });
        // 重复停机不会重复取消、重复减名额，未启动关闭也不会被转移到当前线程执行。
        assertThat(metrics.get("lab.outbound.close.abandoned").counter().count()).isEqualTo(1);
        verify(first, times(1)).close(any());
        verify(second, times(1)).close(any());
        verify(queued, never()).close(any());
    }

    /** 测试替身忽略中断直到闸门打开；返回时恢复中断标志，保留线程中断语义。 */
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static WebSocketSession socket() {
        return OutboundSessionFixture.bridgedSession();
    }

    /** 用注销回调连接 dispose；真实项目中该回调由 Registry.remove 提供。 */
    private static ClientConnection connect(OutboundService service, WebSocketSession socket) {
        var reference = new AtomicReference<ClientConnection>();
        var connection = service.connection(socket, 5, () -> reference.get().dispose());
        reference.set(connection);
        return connection;
    }
}
