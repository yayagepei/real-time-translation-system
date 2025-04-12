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
     * 时间戳（主要用于心跳消息）
     */
    private Long timestamp;
    
    /**
     * 翻译请求配置（仅初始化时使用）
     */
    private TranslationRequest request;
    
    /**
     * 音频Base64编码（用于音频数据和文件上传）
     */
    private String audio;
    
    /**
     * 进度百分比（用于文件上传进度）
     */
    private Integer progress;
    
    /**
     * 处理状态描述（用于文件上传进度）
     */
    private String status;
    
    /**
     * 处理是否完成（用于文件上传进度）
     */
    private Boolean isComplete;
    
    /**
     * 是否为文件上传结果
     */
    @Builder.Default
    private boolean isFileUpload = false;
    
    /**
     * 是否为分块消息
     */
    @Builder.Default
    private boolean isChunked = false;
    
    /**
     * 当前块索引（从0开始）
     */
    private Integer chunkIndex;
    
    /**
     * 总块数
     */
    private Integer totalChunks;
    
    /**
     * 分块传输是否完成标记
     */
    @Builder.Default
    private boolean isChunkedComplete = false;
    
    /**
     * 文件名（用于文件上传）
     */
    private String filename;
    
    /**
     * 文件类型（用于文件上传）
     */
    private String fileType;
    
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
    
    /**
     * 创建心跳响应消息
     */
    public static WebSocketMessage pong() {
        return WebSocketMessage.builder()
                .type(MessageType.PONG)
                .message("pong")
                .timestamp(System.currentTimeMillis())
                .build();
    }
} 