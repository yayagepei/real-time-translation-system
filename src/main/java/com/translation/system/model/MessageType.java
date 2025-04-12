package com.translation.system.model;

/**
 * WebSocket消息类型枚举
 */
public enum MessageType {
    /**
     * 初始化请求/响应
     */
    INIT,
    
    /**
     * 文本结果
     */
    TEXT_RESULT,
    
    /**
     * 翻译结果
     */
    TRANSLATION,
    
    /**
     * 错误消息
     */
    ERROR,
    
    /**
     * 关闭连接
     */
    CLOSE,
    
    /**
     * 心跳请求
     */
    PING,
    
    /**
     * 心跳响应
     */
    PONG,
    
    /**
     * 文件上传
     */
    FILE_UPLOAD,
    
    /**
     * 文件上传进度
     */
    FILE_UPLOAD_PROGRESS,
    
    /**
     * 音频结果
     */
    AUDIO_RESULT
} 