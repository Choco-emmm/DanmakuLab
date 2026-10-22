package io.github.chocoemmm.danmakulab.config;

import io.github.chocoemmm.danmakulab.protocol.DanmakuHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
    private final DanmakuHandler handler;

    public WebSocketConfiguration(DanmakuHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Default same-origin policy. The demo page is served by this application.
        registry.addHandler(handler, "/ws");
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer(LabProperties properties) {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.maxFrameBytes());
        container.setMaxBinaryMessageBufferSize(properties.maxFrameBytes());
        container.setMaxSessionIdleTimeout(60_000L);
        container.setAsyncSendTimeout(5_000L);
        return container;
    }
}
