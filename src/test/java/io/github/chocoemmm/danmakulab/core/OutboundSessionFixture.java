package io.github.chocoemmm.danmakulab.core;

import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 兼容旧预算/停机闸门测试的传输替身。原生sendText发起阶段转交给测试设置的sendMessage钩子，
 * 钩子返回后再通知完成；人为阻塞发起阶段只用于制造资源与停机交错，不代表真实异步网络等待。
 * 多慢连接的异步等待行为另由OutboundAsyncTest保留SendHandler及真实TCP压测验证。
 */
final class OutboundSessionFixture {
    private OutboundSessionFixture() { }

    static WebSocketSession bridgedSession() {
        var spring = mock(StandardWebSocketSession.class);
        var nativeSession = mock(Session.class);
        var async = mock(RemoteEndpoint.Async.class);
        when(spring.isOpen()).thenReturn(true);
        when(spring.getNativeSession()).thenReturn(nativeSession);
        when(nativeSession.getAsyncRemote()).thenReturn(async);
        doAnswer(call -> {
            SendHandler completion = call.getArgument(1);
            try {
                spring.sendMessage(new TextMessage(call.<String>getArgument(0)));
                completion.onResult(new SendResult());
            } catch (Exception failure) {
                completion.onResult(new SendResult(failure));
            }
            return null;
        }).when(async).sendText(anyString(), any(SendHandler.class));
        return spring;
    }
}
