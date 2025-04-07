package com.translation.system.handler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.translation.system.model.MessageType;
import com.translation.system.model.TranslationRequest;
import com.translation.system.model.WebSocketMessage;
import com.translation.system.service.TranslationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@Component
@RequiredArgsConstructor
public class TranslationWebSocketHandler extends AbstractWebSocketHandler {

    private final TranslationService translationService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 存储会话对应的请求配置
    private final Map<String, TranslationRequest> sessionConfigs = new ConcurrentHashMap<>();
    
    // 会话活跃时间记录
    private final Map<String, Long> sessionLastActiveTime = new ConcurrentHashMap<>();
    
    // 会话活跃超时时间（毫秒）
    private static final long SESSION_TIMEOUT = 5 * 60 * 1000; // 5分钟
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        log.info("WebSocket connection established: {}", sessionId);
        sessionLastActiveTime.put(sessionId, System.currentTimeMillis());
        
        // 发送欢迎消息
        try {
            sendTextMessage(session, WebSocketMessage.builder()
                    .type(MessageType.TEXT_RESULT)
                    .message("连接已建立，请发送初始化配置")
                    .build());
        } catch (Exception e) {
            log.error("Error sending welcome message to session {}: {}", sessionId, e.getMessage());
        }
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        updateSessionActivity(sessionId);
        
        try {
            WebSocketMessage webSocketMessage = objectMapper.readValue(message.getPayload(), WebSocketMessage.class);
            
            if (webSocketMessage.getType() == MessageType.INIT) {
                // 保存会话配置
                TranslationRequest request = webSocketMessage.getRequest();
                
                // 验证请求配置
                if (request == null) {
                    sendErrorMessage(session, "初始化失败：请提供有效的翻译请求配置");
                    return;
                }
                
                sessionConfigs.put(sessionId, request);
                log.info("Initialized session {} with config: {}", sessionId, request);
                
                // 发送确认
                sendTextMessage(session, WebSocketMessage.builder()
                        .type(MessageType.INIT)
                        .message("连接已初始化")
                        .build());
            } else if (webSocketMessage.getType() == MessageType.CLOSE) {
                log.info("Client requested connection close: {}", sessionId);
                closeSession(session, CloseStatus.NORMAL);
            } else {
                // 处理其他类型的文本消息
                log.debug("Received text message from session {}: {}", sessionId, webSocketMessage.getType());
                sendTextMessage(session, WebSocketMessage.builder()
                        .type(MessageType.TEXT_RESULT)
                        .message("收到消息：" + webSocketMessage.getMessage())
                        .build());
            }
        } catch (Exception e) {
            log.error("Error handling text message from session {}: {}", sessionId, e.getMessage());
            sendErrorMessage(session, "处理消息时发生错误: " + e.getMessage());
        }
    }
    
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        String sessionId = session.getId();
        updateSessionActivity(sessionId);
        
        try {
            // 获取音频数据
            byte[] audioData = message.getPayload().array();
            log.info("收到语音数据: 会话ID={}, 数据大小={}字节, 远程地址={}", 
                    sessionId, audioData.length, session.getRemoteAddress());
            
            // 获取会话配置
            TranslationRequest request = sessionConfigs.get(sessionId);
            if (request == null) {
                log.warn("会话未初始化: 会话ID={}", sessionId);
                sendErrorMessage(session, "会话未初始化，请先发送初始化配置");
                return;
            }
            
            // 音频数据大小检查
            if (audioData.length == 0) {
                log.warn("收到空的音频数据: 会话ID={}", sessionId);
                sendErrorMessage(session, "收到空的音频数据");
                return;
            }
            
            log.info("开始处理语音转译: 会话ID={}, 源语言={}, 目标语言={}, 服务提供商={}", 
                    sessionId, request.getSourceLanguage(), request.getTargetLanguage(), request.getProvider());
            
            // 处理音频转译
            Flux<byte[]> resultFlux = translationService.translateSpeech(audioData, request, session);
            log.info("转译服务返回Flux: 会话ID={}", sessionId);
            
            // 设置最大处理时间，防止处理时间过长
            final long maxProcessingTime = 60000; // 最长等待60秒
            final long startTime = System.currentTimeMillis();
            
            // 订阅结果流，并将结果发送回客户端
            resultFlux.subscribe(
                data -> {
                    try {
                        // 检查处理是否超时
                        if (System.currentTimeMillis() - startTime > maxProcessingTime) {
                            log.warn("处理时间过长: 会话ID={}, 已用时间={}毫秒", 
                                    sessionId, (System.currentTimeMillis() - startTime));
                            return;
                        }
                        
                        // 检查会话是否仍然打开
                        if (!session.isOpen()) {
                            log.warn("会话已关闭，停止处理: 会话ID={}", sessionId);
                            return;
                        }
                        
                        // 检查数据有效性
                        if (data != null && data.length > 0) {
                            log.info("发送合成的语音数据: 会话ID={}, 数据大小={}字节", 
                                    sessionId, data.length);
                            session.sendMessage(new BinaryMessage(data));
                        } else {
                            log.warn("合成的语音数据为空: 会话ID={}", sessionId);
                        }
                    } catch (IOException e) {
                        log.error("发送语音数据失败: 会话ID={}, 错误={}", sessionId, e.getMessage(), e);
                    }
                },
                error -> {
                    log.error("转译处理错误: 会话ID={}, 错误={}", sessionId, error.getMessage(), error);
                    sendErrorMessage(session, "转译处理发生错误: " + error.getMessage());
                },
                () -> {
                    log.info("转译处理完成: 会话ID={}, 总耗时={}毫秒", 
                            sessionId, (System.currentTimeMillis() - startTime));
                    try {
                        sendTextMessage(session, WebSocketMessage.builder()
                                .type(MessageType.TEXT_RESULT)
                                .message("处理完成")
                                .build());
                    } catch (Exception e) {
                        log.error("发送完成消息失败: 会话ID={}, 错误={}", sessionId, e.getMessage(), e);
                    }
                }
            );
            
        } catch (Exception e) {
            log.error("处理二进制消息异常: 会话ID={}, 错误类型={}, 错误信息={}", 
                    sessionId, e.getClass().getSimpleName(), e.getMessage(), e);
            sendErrorMessage(session, "处理音频数据时发生错误: " + e.getMessage());
        }
    }
    
    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) throws Exception {
        // 更新会话活跃时间
        updateSessionActivity(session.getId());
    }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        log.info("WebSocket connection closed: {} with status: {}", sessionId, status);
        
        // 清理会话资源
        sessionConfigs.remove(sessionId);
        sessionLastActiveTime.remove(sessionId);
    }
    
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String sessionId = session.getId();
        log.error("Transport error for session {}: {}", sessionId, exception.getMessage());
        
        // 尝试发送错误消息
        try {
            if (session.isOpen()) {
                sendErrorMessage(session, "连接传输错误: " + exception.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to send transport error message to session {}", sessionId, e);
        }
        
        // 关闭会话
        closeSession(session, CloseStatus.SERVER_ERROR);
    }
    
    /**
     * 发送错误消息
     */
    private void sendErrorMessage(WebSocketSession session, String errorMessage) {
        try {
            if (session != null && session.isOpen()) {
                String message = objectMapper.writeValueAsString(
                        WebSocketMessage.builder()
                            .type(MessageType.ERROR)
                            .message(errorMessage)
                            .build());
                session.sendMessage(new TextMessage(message));
            }
        } catch (IOException e) {
            log.error("Error sending error message to session {}: {}", 
                    session != null ? session.getId() : "null", e.getMessage());
        }
    }
    
    /**
     * 发送文本消息
     */
    private void sendTextMessage(WebSocketSession session, WebSocketMessage message) throws IOException {
        if (session != null && session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        }
    }
    
    /**
     * 更新会话活跃时间
     */
    private void updateSessionActivity(String sessionId) {
        sessionLastActiveTime.put(sessionId, System.currentTimeMillis());
    }
    
    /**
     * 关闭会话
     */
    private void closeSession(WebSocketSession session, CloseStatus status) {
        if (session != null && session.isOpen()) {
            try {
                session.close(status);
            } catch (IOException e) {
                log.error("Error closing session {}: {}", session.getId(), e.getMessage());
            }
        }
    }
    
    /**
     * 定时检查并关闭不活跃的会话
     * 每分钟执行一次
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupInactiveSessions() {
        long now = System.currentTimeMillis();
        
        sessionLastActiveTime.forEach((sessionId, lastActiveTime) -> {
            if (now - lastActiveTime > SESSION_TIMEOUT) {
                log.info("Closing inactive session: {} (last active: {} ms ago)",
                        sessionId, now - lastActiveTime);
                
                // 查找对应的WebSocketSession并关闭
                try {
                    WebSocketSession session = findSessionById(sessionId);
                    if (session != null && session.isOpen()) {
                        session.close(CloseStatus.NORMAL.withReason("Session timeout"));
                    }
                } catch (Exception e) {
                    log.error("Error closing inactive session {}: {}", sessionId, e.getMessage());
                } finally {
                    // 移除会话记录
                    sessionConfigs.remove(sessionId);
                    sessionLastActiveTime.remove(sessionId);
                }
            }
        });
    }
    
    /**
     * 根据ID查找会话
     * 注意：实际项目中会有更好的方式来管理会话，这里仅为示例
     */
    private WebSocketSession findSessionById(String sessionId) {
        // 通常会通过一个会话存储库来实现
        // 这里假设我们无法直接获取会话对象
        return null;
    }
} 