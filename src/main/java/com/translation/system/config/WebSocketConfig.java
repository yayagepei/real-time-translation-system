package com.translation.system.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import org.springframework.web.socket.sockjs.transport.handler.SockJsWebSocketHandler;
import org.springframework.web.socket.sockjs.transport.TransportHandlingSockJsService;

import com.translation.system.handler.TranslationWebSocketHandler;

@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {
    
    private final TranslationWebSocketHandler translationWebSocketHandler;
    
    public WebSocketConfig(TranslationWebSocketHandler translationWebSocketHandler) {
        this.translationWebSocketHandler = translationWebSocketHandler;
    }
    
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(translationWebSocketHandler, "/ws/speech")
                .setAllowedOriginPatterns("*") // 使用模式匹配代替通配符
                .setHandshakeHandler(new DefaultHandshakeHandler())
                .addInterceptors(new HttpSessionHandshakeInterceptor());
                
        // 添加SockJS支持
        registry.addHandler(translationWebSocketHandler, "/sockjs/speech")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
    
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8192);
        container.setMaxBinaryMessageBufferSize(1024 * 1024); // 1MB
        container.setMaxSessionIdleTimeout(60000L);
        container.setAsyncSendTimeout(5000L);
        return container;
    }
} 