package com.translation.system.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket消息模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(Include.NON_NULL)
public class WebSocketMessage {
    
    /**
     * 消息类型
     */
    private MessageType type;
    
    /**
     * 消息内容
     */
    private String message;
    
    @JsonIgnore
    private byte[] audioData;
    
    /**
     * 翻译请求配置（仅初始化时使用）
     */
    private TranslationRequest request;
    
    /**
     * 错误代码（仅错误消息使用）
     */
    private String errorCode;
    
    /**
     * 创建错误消息
     */
    public static WebSocketMessage error(String errorMessage) {
        return WebSocketMessage.builder()
                .type(MessageType.ERROR)
                .message(errorMessage)
                .build();
    }
    
    /**
     * 创建带错误代码的错误消息
     */
    public static WebSocketMessage error(String errorMessage, String errorCode) {
        return WebSocketMessage.builder()
                .type(MessageType.ERROR)
                .message(errorMessage)
                .errorCode(errorCode)
                .build();
    }
    
    /**
     * 创建文本结果消息
     */
    public static WebSocketMessage textResult(String text) {
        return WebSocketMessage.builder()
                .type(MessageType.TEXT_RESULT)
                .message(text)
                .build();
    }
    
    /**
     * 创建初始化确认消息
     */
    public static WebSocketMessage initConfirm() {
        return WebSocketMessage.builder()
                .type(MessageType.INIT)
                .message("连接已初始化")
                .build();
    }
    
    public static WebSocketMessage audioResult(byte[] audioData) {
        return WebSocketMessage.builder()
                .type(MessageType.TEXT_RESULT)
                .audioData(audioData)
                .build();
    }
} 