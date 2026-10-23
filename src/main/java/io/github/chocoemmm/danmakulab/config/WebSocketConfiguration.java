package io.github.chocoemmm.danmakulab.config;

import io.github.chocoemmm.danmakulab.protocol.DanmakuHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 把 HTTP 路径 /ws 与业务处理器连接起来，并设置底层 WebSocket 容器的接收和超时参数。
 * EnableWebSocket 开启 Spring 的原生 WebSocket 支持；这里没有引入 STOMP 消息代理。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
    private final DanmakuHandler handler;

    public WebSocketConfiguration(DanmakuHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 浏览器连接 /ws 后，连接建立、消息到达和断开等事件交由 DanmakuHandler 处理。
        // 沿用默认同源策略：演示页和 WebSocket 由同一个服务提供，无需放开任意来源。
        registry.addHandler(handler, "/ws");
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer(LabProperties properties) {
        // Bean 交给 Spring 管理，负责定制底层 Servlet WebSocket 容器。
        var container = new ServletServerContainerFactoryBean();
        // 容器接收缓冲是第一道边界；业务处理器仍会额外核对 UTF-8 实际字节数。
        // 文本长度与字节数不是同一概念，例如一个常用汉字通常占三个 UTF-8 字节。
        container.setMaxTextMessageBufferSize(properties.maxFrameBytes());
        container.setMaxBinaryMessageBufferSize(properties.maxFrameBytes());
        // 60 秒没有通信时由容器关闭空闲连接；浏览器正常情况下每 15 秒发送应用心跳。
        container.setMaxSessionIdleTimeout(60_000L);
        // 这是容器异步发送的超时配置；当前同步广播另在连接建立时设置阻塞发送超时。
        container.setAsyncSendTimeout(5_000L);
        return container;
    }
}
