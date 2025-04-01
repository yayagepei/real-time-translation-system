package com.translation.system.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import com.translation.system.handler.TranslationWebSocketHandler;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSocket
@EnableScheduling
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
    
    private final TranslationWebSocketHandler translationWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(translationWebSocketHandler, "/api/translation")
                .setAllowedOrigins("*"); // 生产环境应限制允许的来源
    }
    
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // 设置最大文本消息大小
        container.setMaxTextMessageBufferSize(8192);
        // 设置最大二进制消息大小 (10MB)
        container.setMaxBinaryMessageBufferSize(10 * 1024 * 1024);
        // 设置异步发送超时时间
        container.setAsyncSendTimeout(30000L);
        // 设置回话空闲超时时间
        container.setMaxSessionIdleTimeout(600000L);
        return container;
    }
} 