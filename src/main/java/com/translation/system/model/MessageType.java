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
     * 错误消息
     */
    ERROR,
    
    /**
     * 关闭连接
     */
    CLOSE
} 