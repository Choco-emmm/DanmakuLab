package io.github.chocoemmm.danmakulab.core;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证统一发送入口在多线程调用时，不会同时进入底层 Session.sendMessage。 */
class ClientConnectionTest {
    @Test
    void controlRepliesAndBroadcastsCannotWriteConcurrently() throws Exception {
        var session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        // active 记录同时进入模拟发送方法的线程数，超过 1 就说明出现并发写。
        var active = new AtomicInteger();
        var overlapped = new AtomicBoolean();
        doAnswer(invocation -> {
            if (active.incrementAndGet() != 1) overlapped.set(true);
            // 主动让出一次调度机会，增加竞争被观察到的机会；它不是对所有交错的穷举证明。
            Thread.yield();
            active.decrementAndGet();
            return null;
        }).when(session).sendMessage(any());
        var connection = new ClientConnection(session, 5);
        var start = new CountDownLatch(1);
        // CountDownLatch 是起跑信号：四个任务先等待，主线程统一放行，制造同连接发送竞争。
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int thread = 0; thread < 4; thread++) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 100; i++) connection.send(new TextMessage("message"));
                    return null;
                }));
            }
            start.countDown();
            // Future.get 不只等待完成，也会把工作线程中的异常传回，避免任务失败被测试忽略。
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        assertThat(overlapped).isFalse();
        // 除无重叠外，还核对 4×100 次调用都确实进入了模拟发送方法。
        verify(session, times(400)).sendMessage(any());
    }
}
