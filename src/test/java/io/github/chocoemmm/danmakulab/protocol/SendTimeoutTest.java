package io.github.chocoemmm.danmakulab.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.core.ConnectionRegistry;
import io.github.chocoemmm.danmakulab.core.DirectBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 超时配置回归：Tomcat 读取原生 Session 用户属性，不能只修改 Spring 的业务属性。 */
class SendTimeoutTest {
    @Test
    void blockingTimeoutReachesNativeContainerSession() throws Exception {
        var attributes = new HashMap<String, Object>();
        var nativeProperties = new HashMap<String, Object>();
        var nativeSession = mock(Session.class);
        when(nativeSession.getUserProperties()).thenReturn(nativeProperties);
        var springSession = mock(StandardWebSocketSession.class);
        when(springSession.getAttributes()).thenReturn(attributes);
        when(springSession.getNativeSession()).thenReturn(nativeSession);
        var properties = new LabProperties(2000, 1000, 1024, 200, 5, 50);
        var metrics = new SimpleMeterRegistry();
        var handler = new DanmakuHandler(new ObjectMapper(), properties,
                mock(ConnectionRegistry.class), mock(DirectBroadcaster.class), metrics);

        handler.afterConnectionEstablished(springSession);

        // 两张 Map 故意独立，只有原生属性里存在 Long 类型值，容器才有机会使用它。
        assertThat(nativeProperties).containsEntry("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 5_000L);
    }
}
