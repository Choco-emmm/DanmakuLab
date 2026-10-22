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

class ClientConnectionTest {
    @Test
    void controlRepliesAndBroadcastsCannotWriteConcurrently() throws Exception {
        var session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        var active = new AtomicInteger();
        var overlapped = new AtomicBoolean();
        doAnswer(invocation -> {
            if (active.incrementAndGet() != 1) overlapped.set(true);
            Thread.yield();
            active.decrementAndGet();
            return null;
        }).when(session).sendMessage(any());
        var connection = new ClientConnection(session, 5);
        var start = new CountDownLatch(1);
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
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        assertThat(overlapped).isFalse();
        verify(session, times(400)).sendMessage(any());
    }
}
