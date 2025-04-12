package com.translation.system.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
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
import com.translation.system.service.SpeechService;
import com.translation.system.service.TranslationService;
import com.translation.system.service.impl.MicrosoftSpeechService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@Component
@RequiredArgsConstructor
public class TranslationWebSocketHandler extends AbstractWebSocketHandler {

    private final TranslationService translationService;
    private final SpeechService microsoftSpeechService;
    private final SpeechService openAISpeechService;
    private final ObjectMapper objectMapper;
    
    // 存储会话对应的请求配置
    private final Map<String, TranslationRequest> sessionConfigs = new ConcurrentHashMap<>();
    
    // 会话活跃时间记录
    private final Map<String, Long> sessionLastActiveTime = new ConcurrentHashMap<>();
    
    // 会话活跃超时时间（毫秒）
    private static final long SESSION_TIMEOUT = 10 * 60 * 1000; // 10分钟
    
    // 心跳间隔时间（毫秒）
    private static final long HEARTBEAT_INTERVAL = 30 * 1000; // 30秒
    
    // 存储所有WebSocket会话
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        log.info("WebSocket连接已建立: 会话ID={}, 远程地址={}, 本地地址={}", 
                sessionId, session.getRemoteAddress(), session.getLocalAddress());
        sessionLastActiveTime.put(sessionId, System.currentTimeMillis());
        activeSessions.put(sessionId, session); // 保存会话引用
        
        // 发送欢迎消息
        try {
            sendTextMessage(session, WebSocketMessage.builder()
                    .type(MessageType.TEXT_RESULT)
                    .message("连接已建立，请发送初始化配置")
                    .build());
            log.debug("发送欢迎消息: 会话ID={}", sessionId);
        } catch (Exception e) {
            log.error("发送欢迎消息失败: 会话ID={}, 错误={}", sessionId, e.getMessage(), e);
        }
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        String payload = message.getPayload();
        updateSessionActivity(sessionId);
        
        log.debug("收到文本消息: 会话ID={}, 消息长度={}", sessionId, payload.length());
        
        try {
            WebSocketMessage webSocketMessage = objectMapper.readValue(payload, WebSocketMessage.class);
            
            if (webSocketMessage.getType() == MessageType.INIT) {
                // 保存会话配置
                TranslationRequest request = webSocketMessage.getRequest();
                
                // 验证请求配置
                if (request == null) {
                    log.warn("初始化失败-无效配置: 会话ID={}", sessionId);
                    sendErrorMessage(session, "初始化失败：请提供有效的翻译请求配置");
                    return;
                }
                
                sessionConfigs.put(sessionId, request);
                log.info("会话初始化成功: 会话ID={}, 配置={}, 源语言={}, 目标语言={}, 提供商={}", 
                        sessionId, request, request.getSourceLanguage(), 
                        request.getTargetLanguage(), request.getProvider());
                
                // 发送确认
                sendTextMessage(session, WebSocketMessage.builder()
                        .type(MessageType.INIT)
                        .message("连接已初始化")
                        .build());
                log.debug("发送初始化确认: 会话ID={}", sessionId);
            } else if (webSocketMessage.getType() == MessageType.CLOSE) {
                log.info("客户端请求关闭连接: 会话ID={}", sessionId);
                closeSession(session, CloseStatus.NORMAL);
            } else if (webSocketMessage.getType() == MessageType.PING) {
                // 处理心跳请求，返回PONG响应
                log.debug("收到心跳请求: 会话ID={}", sessionId);
                sendTextMessage(session, WebSocketMessage.builder()
                        .type(MessageType.PONG)
                        .message("pong")
                        .build());
            } else if (webSocketMessage.getType() == MessageType.FILE_UPLOAD) {
                // 处理文件上传请求
                handleFileUpload(session, webSocketMessage);
            } else {
                // 处理其他类型的文本消息
                log.debug("收到其他类型文本消息: 会话ID={}, 消息类型={}", sessionId, webSocketMessage.getType());
                sendTextMessage(session, WebSocketMessage.builder()
                        .type(MessageType.TEXT_RESULT)
                        .message("收到消息：" + webSocketMessage.getMessage())
                        .build());
            }
        } catch (Exception e) {
            log.error("处理文本消息失败: 会话ID={}, 错误类型={}, 错误信息={}", 
                    sessionId, e.getClass().getName(), e.getMessage(), e);
            sendErrorMessage(session, "处理消息时发生错误: " + e.getMessage());
        }
    }
    
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        String sessionId = session.getId();
        long startProcessingTime = System.currentTimeMillis();
        updateSessionActivity(sessionId);
        
        try {
            // 获取音频数据
            byte[] audioData = message.getPayload().array();
            log.info("收到音频数据: 会话ID={}, 数据大小={}KB, 远程地址={}, 接收时间={}", 
                    sessionId, audioData.length / 1024.0, session.getRemoteAddress(), 
                    new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()));
            
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
            } else if (audioData.length < 1000) {
                log.warn("音频数据可能过小: 会话ID={}, 数据大小={}字节", sessionId, audioData.length);
            } else if (audioData.length > 500000) {
                log.warn("音频数据较大: 会话ID={}, 数据大小={}KB", sessionId, audioData.length / 1024.0);
            }
            
            log.info("开始处理音频转译: 会话ID={}, 源语言={}, 目标语言={}, 提供商={}, 数据大小={}KB", 
                    sessionId, request.getSourceLanguage(), request.getTargetLanguage(), 
                    request.getProvider(), audioData.length / 1024.0);
            
            // 处理音频转译
            long beforeCallService = System.currentTimeMillis();
            Flux<byte[]> resultFlux = translationService.translateSpeech(audioData, request, session);
            long afterCallService = System.currentTimeMillis();
            log.info("转译服务调用完成: 会话ID={}, 调用耗时={}毫秒", 
                    sessionId, (afterCallService - beforeCallService));
            
            // 设置最大处理时间，防止处理时间过长
            final long maxProcessingTime = 60000; // 最长等待60秒
            final long startTime = System.currentTimeMillis();
            
            // 记录处理开始时间
            log.debug("开始处理转译结果流: 会话ID={}, 开始时间={}", 
                    sessionId, new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()));
            
            // 订阅结果流，并将结果发送回客户端
            resultFlux.subscribe(
                data -> {
                    try {
                        long currentTime = System.currentTimeMillis();
                        long elapsed = currentTime - startTime;
                        
                        // 检查处理是否超时
                        if (elapsed > maxProcessingTime) {
                            log.warn("处理时间过长，跳过后续处理: 会话ID={}, 已用时间={}毫秒", 
                                    sessionId, elapsed);
                            return;
                        }
                        
                        // 检查会话是否仍然打开
                        if (!session.isOpen()) {
                            log.warn("会话已关闭，停止处理: 会话ID={}, 处理已进行={}毫秒", 
                                    sessionId, elapsed);
                            return;
                        }
                        
                        // 检查数据有效性
                        if (data != null && data.length > 0) {
                            log.info("发送合成的音频数据: 会话ID={}, 数据大小={}KB, 处理耗时={}毫秒", 
                                    sessionId, data.length / 1024.0, elapsed);
                            
                            // 记录发送时间
                            long beforeSend = System.currentTimeMillis();
                            session.sendMessage(new BinaryMessage(data));
                            long afterSend = System.currentTimeMillis();
                            
                            if (afterSend - beforeSend > 100) {
                                log.warn("发送音频数据耗时较长: 会话ID={}, 发送耗时={}毫秒", 
                                        sessionId, (afterSend - beforeSend));
                            } else {
                                log.debug("音频数据发送完成: 会话ID={}, 发送耗时={}毫秒", 
                                        sessionId, (afterSend - beforeSend));
                            }
                        } else {
                            log.warn("合成的音频数据无效: 会话ID={}, 数据={}", 
                                    sessionId, data == null ? "null" : "空数组");
                        }
                    } catch (IOException e) {
                        log.error("发送语音数据失败: 会话ID={}, 错误类型={}, 错误信息={}", 
                                sessionId, e.getClass().getName(), e.getMessage(), e);
                    }
                },
                error -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.error("转译处理错误: 会话ID={}, 错误类型={}, 错误信息={}, 处理时间={}毫秒", 
                            sessionId, error.getClass().getName(), error.getMessage(), elapsed, error);
                    sendErrorMessage(session, "转译处理发生错误: " + error.getMessage());
                },
                () -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    long totalProcessing = System.currentTimeMillis() - startProcessingTime;
                    log.info("转译处理完成: 会话ID={}, Flux处理耗时={}毫秒, 总处理耗时={}毫秒", 
                            sessionId, elapsed, totalProcessing);
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
        activeSessions.remove(sessionId); // 移除会话引用
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
            String messageJson = objectMapper.writeValueAsString(message);
            
            // 检查消息大小，如果超过阈值则使用分块发送
            byte[] messageBytes = messageJson.getBytes(StandardCharsets.UTF_8);
            if (messageBytes.length > 1024 * 1024) { // 如果消息超过1MB
                sendLargeTextMessage(session, message, messageJson);
            } else {
                session.sendMessage(new TextMessage(messageJson));
            }
        }
    }
    
    /**
     * 发送大型文本消息（分块发送）
     * 将大型消息分成多个小块发送，防止WebSocket消息大小限制导致的连接关闭
     */
    private void sendLargeTextMessage(WebSocketSession session, WebSocketMessage originalMessage, String messageJson) throws IOException {
        if (session == null || !session.isOpen()) {
            return;
        }
        
        log.debug("消息过大，使用分块发送: 会话ID={}, 消息大小={}字节", session.getId(), messageJson.getBytes(StandardCharsets.UTF_8).length);
        
        // 分割消息内容为多个块
        List<String> chunks = splitTextIntoChunks(messageJson, 500 * 1024); // 每块限制在500KB
        int totalChunks = chunks.size();
        
        for (int i = 0; i < totalChunks; i++) {
            // 构建分块消息
            WebSocketMessage chunkMessage = WebSocketMessage.builder()
                    .type(originalMessage.getType())
                    .message(chunks.get(i))
                    .isChunked(true)
                    .chunkIndex(i)
                    .totalChunks(totalChunks)
                    .isChunkedComplete(i == totalChunks - 1)
                    .build();
            
            String chunkJson = objectMapper.writeValueAsString(chunkMessage);
            session.sendMessage(new TextMessage(chunkJson));
            
            // 添加小延迟，防止网络拥堵
            try {
                if (i < totalChunks - 1) {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("分块发送消息中断: 会话ID={}", session.getId());
                break;
            }
        }
        
        log.debug("分块消息发送完成: 会话ID={}, 总块数={}", session.getId(), totalChunks);
    }
    
    /**
     * 将文本按字节大小分割成多个块
     */
    private List<String> splitTextIntoChunks(String text, int maxChunkSizeBytes) {
        List<String> chunks = new ArrayList<>();
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        int textLength = textBytes.length;
        
        if (textLength <= maxChunkSizeBytes) {
            chunks.add(text);
            return chunks;
        }
        
        int startIndex = 0;
        while (startIndex < text.length()) {
            int endIndex = startIndex;
            int byteCount = 0;
            
            while (endIndex < text.length() && byteCount < maxChunkSizeBytes) {
                char c = text.charAt(endIndex);
                // 计算字符的UTF-8编码字节数
                int charBytes = 0;
                if (c <= 0x7F) {
                    charBytes = 1;
                } else if (c <= 0x7FF) {
                    charBytes = 2;
                } else if (Character.isHighSurrogate(c) && endIndex + 1 < text.length() 
                        && Character.isLowSurrogate(text.charAt(endIndex + 1))) {
                    charBytes = 4;
                    endIndex++; // 跳过低代理项
                } else {
                    charBytes = 3;
                }
                
                if (byteCount + charBytes > maxChunkSizeBytes) {
                    break;
                }
                
                byteCount += charBytes;
                endIndex++;
            }
            
            if (endIndex > startIndex) {
                chunks.add(text.substring(startIndex, endIndex));
                startIndex = endIndex;
            } else {
                // 防止无限循环
                startIndex++;
            }
        }
        
        return chunks;
    }
    
    /**
     * 更新会话活跃时间
     */
    private void updateSessionActivity(String sessionId) {
        long now = System.currentTimeMillis();
        Long previous = sessionLastActiveTime.put(sessionId, now);
        if (previous != null) {
            long inactiveTime = now - previous;
            // 记录较长不活跃时间
            if (inactiveTime > 10000) { // 10秒
                log.debug("会话不活跃时间较长: 会话ID={}, 不活跃时间={}毫秒", sessionId, inactiveTime);
            }
        }
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
     * 定时检查并清理超时的会话
     */
    @Scheduled(fixedRate = 60000) // 每分钟执行一次
    public void cleanupInactiveSessions() {
        log.debug("开始检查不活跃会话, 当前会话数量: {}", sessionLastActiveTime.size());
        long currentTime = System.currentTimeMillis();
        int cleanedCount = 0;
        
        for (Map.Entry<String, Long> entry : sessionLastActiveTime.entrySet()) {
            String sessionId = entry.getKey();
            long lastActiveTime = entry.getValue();
            long inactiveTime = currentTime - lastActiveTime;
            
            // 记录所有会话的不活跃时间
            log.debug("会话活跃状态: 会话ID={}, 最后活跃时间={}, 不活跃时长={}秒", 
                    sessionId, lastActiveTime, inactiveTime / 1000);
            
            if (inactiveTime > SESSION_TIMEOUT) {
                log.info("清理超时会话: 会话ID={}, 最后活跃时间={}, 超时时间={}秒", 
                        sessionId, lastActiveTime, SESSION_TIMEOUT / 1000);
                
                WebSocketSession session = activeSessions.get(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        closeSession(session, CloseStatus.NORMAL.withReason("会话超时"));
                        cleanedCount++;
                    } catch (Exception e) {
                        log.error("关闭超时会话失败: 会话ID={}, 错误={}", sessionId, e.getMessage(), e);
                    }
                }
                
                    // 移除会话记录
                    sessionConfigs.remove(sessionId);
                    sessionLastActiveTime.remove(sessionId);
                activeSessions.remove(sessionId);
            }
        }
        
        if (cleanedCount > 0) {
            log.info("已清理 {} 个超时会话", cleanedCount);
        }
        
        log.debug("会话清理完成, 剩余会话数量: {}", sessionLastActiveTime.size());
    }
    
    /**
     * 定时发送心跳消息，保持连接活跃
     */
    @Scheduled(fixedRate = HEARTBEAT_INTERVAL)
    public void sendHeartbeats() {
        if (activeSessions.isEmpty()) {
            return;
        }
        
        log.debug("开始发送心跳消息, 当前活跃会话数: {}", activeSessions.size());
        long currentTime = System.currentTimeMillis();
        int sentCount = 0;
        
        for (Map.Entry<String, WebSocketSession> entry : activeSessions.entrySet()) {
            String sessionId = entry.getKey();
            WebSocketSession session = entry.getValue();
            
            // 检查会话是否打开
            if (session != null && session.isOpen()) {
                try {
                    // 创建并发送PING消息
                    WebSocketMessage pingMessage = WebSocketMessage.builder()
                            .type(MessageType.PING)
                            .message("ping")
                            .timestamp(currentTime)
                            .build();
                    
                    sendTextMessage(session, pingMessage);
                    sentCount++;
                    
                    log.debug("已发送心跳消息: 会话ID={}", sessionId);
                } catch (IOException e) {
                    log.warn("发送心跳消息失败: 会话ID={}, 错误={}", sessionId, e.getMessage());
                    
                    // 尝试关闭可能已断开的连接
                    try {
                        closeSession(session, CloseStatus.SESSION_NOT_RELIABLE);
                        activeSessions.remove(sessionId);
                        sessionLastActiveTime.remove(sessionId);
                        sessionConfigs.remove(sessionId);
                    } catch (Exception ex) {
                        log.error("关闭不可靠会话失败: 会话ID={}, 错误={}", sessionId, ex.getMessage());
                    }
                }
            } else {
                // 移除已关闭的会话
                activeSessions.remove(sessionId);
                sessionLastActiveTime.remove(sessionId);
                sessionConfigs.remove(sessionId);
                log.debug("移除已关闭会话: 会话ID={}", sessionId);
            }
        }
        
        log.debug("心跳消息发送完成, 已发送: {}, 总会话数: {}", sentCount, activeSessions.size());
    }
    
    /**
     * 根据会话ID获取WebSocketSession实例
     */
    private WebSocketSession getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }
    
    /**
     * 处理通过WebSocket上传的文件
     */
    private void handleFileUpload(WebSocketSession session, WebSocketMessage message) {
        String sessionId = session.getId();
        TranslationRequest request = message.getRequest();
        String base64Audio = message.getAudio();
        
        if (base64Audio == null || base64Audio.isEmpty()) {
            log.warn("文件上传失败: 会话ID={}, 原因=音频数据为空", sessionId);
            sendErrorMessage(session, "音频数据为空");
            return;
        }
        
        if (request == null) {
            log.warn("文件上传失败: 会话ID={}, 原因=翻译请求配置为空", sessionId);
            sendErrorMessage(session, "翻译请求配置为空");
            return;
        }
        
        try {
            log.info("开始处理WebSocket文件上传: 会话ID={}, 源语言={}, 目标语言={}, 提供商={}, 数据大小={}KB", 
                    sessionId, request.getSourceLanguage(), request.getTargetLanguage(), 
                    request.getProvider(), base64Audio.length() / 1000);
            
            // 发送进度消息 - 开始处理
            sendProgressMessage(session, 10, "正在解码音频数据...");
            
            // 解码Base64音频数据
            byte[] audioData;
            try {
                audioData = Base64.getDecoder().decode(base64Audio);
                log.debug("音频数据解码成功: 会话ID={}, 数据大小={}KB", sessionId, audioData.length / 1024.0);
            } catch (IllegalArgumentException e) {
                log.error("音频数据Base64解码失败: 会话ID={}, 错误={}", sessionId, e.getMessage(), e);
                sendErrorMessage(session, "音频数据解码失败: " + e.getMessage());
                return;
            }
            
            // 发送进度消息 - 开始转录
            sendProgressMessage(session, 30, "正在进行语音识别和翻译...");
            
            // 选择服务提供商
            SpeechService speechService;
            if ("openai".equalsIgnoreCase(request.getProvider())) {
                speechService = openAISpeechService;
            } else {
                speechService = microsoftSpeechService;
            }
            
            // 检查请求模式，如果是speech-to-speech，就进行完整的语音转语音翻译
            if ("speech-to-speech".equals(request.getMode())) {
                // 直接调用语音转语音翻译
                log.info("开始进行语音转语音翻译: 会话ID={}, 源语言={}, 目标语言={}", 
                        sessionId, request.getSourceLanguage(), request.getTargetLanguage());
                
                speechService.translateSpeechToSpeech(audioData, request, session)
                    .doOnNext(translatedAudioData -> {
                        try {
                            log.info("语音转语音翻译完成: 会话ID={}, 音频数据大小={}KB", 
                                    sessionId, translatedAudioData.length / 1024.0);
                            
                            // 发送进度消息 - 语音合成完成
                            sendProgressMessage(session, 90, "语音翻译完成，准备播放...");
                            
                            // 编码音频数据为Base64
                            String base64TranslatedAudio = Base64.getEncoder().encodeToString(translatedAudioData);
                            
                            // 发送音频数据
                            WebSocketMessage audioMessage = WebSocketMessage.builder()
                                    .type(MessageType.AUDIO_RESULT)
                                    .audio(base64TranslatedAudio)
                                    .isFileUpload(true)
                                    .build();
                            
                            sendTextMessage(session, audioMessage);
                            
                            // 发送处理完成消息
                            sendProgressMessage(session, 100, "处理完成", true);
                        } catch (Exception e) {
                            log.error("发送翻译后的音频数据失败: 会话ID={}, 错误={}", sessionId, e.getMessage(), e);
                            sendErrorMessage(session, "发送翻译后的音频数据失败: " + e.getMessage());
                        }
                    })
                    .doOnError(error -> {
                        log.error("语音转语音翻译错误: 会话ID={}, 错误={}", sessionId, error.getMessage(), error);
                        sendErrorMessage(session, "语音转语音翻译错误: " + error.getMessage());
                    })
                    .subscribe();
            } else {
                // 原有的语音转文字处理逻辑
                speechService.speechToText(audioData, request.getSourceLanguage())
                    .doOnNext(text -> {
                        try {
                            log.debug("语音识别结果: 会话ID={}, 文本={}", sessionId, text);
                            
                            // 发送文本结果，标记为文件上传结果
                            WebSocketMessage resultMessage = WebSocketMessage.builder()
                                    .type(MessageType.TEXT_RESULT)
                                    .message(text)
                                    .isFileUpload(true)
                                    .build();
                            
                            sendTextMessage(session, resultMessage);
                            
                            // 发送进度消息 - 语音识别完成
                            sendProgressMessage(session, 60, "语音识别完成，开始翻译...");
                            
                            // 如果源语言和目标语言不同，且目标语言不为auto，则进行翻译
                            if (!request.getSourceLanguage().equals(request.getTargetLanguage()) && 
                                !"auto".equals(request.getSourceLanguage())) {
                                
                                // 使用MicrosoftSpeechService的翻译功能
                                log.info("开始翻译文本: 会话ID={}, 源语言={}, 目标语言={}, 文本=\"{}\"", 
                                        sessionId, request.getSourceLanguage(), request.getTargetLanguage(), text);
                                
                                // 获取具体的MicrosoftSpeechService实例
                                MicrosoftSpeechService microsoftService = (MicrosoftSpeechService) microsoftSpeechService;
                                
                                // 翻译文本
                                microsoftService.translateTextWithMicrosoftAPI(text, 
                                        request.getSourceLanguage(), 
                                        request.getTargetLanguage())
                                    .subscribe(translatedText -> {
                                        try {
                                            log.info("翻译完成: 会话ID={}, 原文=\"{}\", 译文=\"{}\"", 
                                                    sessionId, text, translatedText);
                                            
                                            // 发送翻译进度消息
                                            sendProgressMessage(session, 90, "翻译已完成");
                                            
                                            // 发送翻译结果 - 检查消息长度是否过大，如果过大则分块发送
                                            sendLargeTextMessage(session, translatedText, MessageType.TRANSLATION, true);
                                            
                                            // 发送处理完成消息
                                            sendProgressMessage(session, 100, "处理完成", true);
                                        } catch (Exception e) {
                                            log.error("发送翻译结果失败: 会话ID={}, 错误={}", sessionId, e.getMessage(), e);
                                            sendErrorMessage(session, "发送翻译结果失败: " + e.getMessage());
                                        }
                                    }, error -> {
                                        log.error("翻译过程出错: 会话ID={}, 错误={}", sessionId, error.getMessage(), error);
                                        sendErrorMessage(session, "翻译过程出错: " + error.getMessage());
                                        
                                        // 即使翻译失败也发送原文
                                        try {
                                            WebSocketMessage translationMessage = WebSocketMessage.builder()
                                                    .type(MessageType.TRANSLATION)
                                                    .message(text) // 使用原文
                                                    .isFileUpload(true)
                                                    .build();
                                            
                                            sendTextMessage(session, translationMessage);
                                            sendProgressMessage(session, 100, "处理完成（翻译失败，显示原文）", true);
                                        } catch (Exception e) {
                                            log.error("发送原文失败: 会话ID={}, 错误={}", sessionId, e.getMessage(), e);
                                        }
                                    });
                            } else {
                                // 源语言和目标语言相同，不需要翻译
                                log.info("源语言和目标语言相同，不需要翻译: {}={}", 
                                        request.getSourceLanguage(), request.getTargetLanguage());
                                sendProgressMessage(session, 100, "处理完成（无需翻译）", true);
                            }
                            
                        } catch (Exception e) {
                            log.error("处理语音识别结果失败: 会话ID={}, 错误={}", sessionId, e.getMessage(), e);
                            sendErrorMessage(session, "处理语音识别结果失败: " + e.getMessage());
                        }
                    })
                    .doOnError(error -> {
                        log.error("语音识别错误: 会话ID={}, 错误={}", sessionId, error.getMessage(), error);
                        sendErrorMessage(session, "语音识别错误: " + error.getMessage());
                    })
                    .subscribe();
            }
                
        } catch (Exception e) {
            log.error("处理文件上传失败: 会话ID={}, 错误={}", sessionId, e.getMessage(), e);
            sendErrorMessage(session, "处理文件上传失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送处理进度消息
     */
    private void sendProgressMessage(WebSocketSession session, int progress, String status) {
        sendProgressMessage(session, progress, status, false);
    }
    
    /**
     * 发送处理进度消息
     * 
     * @param session WebSocket会话
     * @param progress 进度百分比
     * @param status 状态描述
     * @param isComplete 是否处理完成
     */
    private void sendProgressMessage(WebSocketSession session, int progress, String status, boolean isComplete) {
        try {
            WebSocketMessage progressMessage = WebSocketMessage.builder()
                    .type(MessageType.FILE_UPLOAD_PROGRESS)
                    .progress(progress)
                    .status(status)
                    .isComplete(isComplete)
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            sendTextMessage(session, progressMessage);
        } catch (Exception e) {
            log.error("发送进度消息失败: 会话ID={}, 错误={}", session.getId(), e.getMessage(), e);
        }
    }
    
    /**
     * 发送大型文本消息的重载方法
     * 
     * @param session WebSocket会话
     * @param content 消息内容
     * @param messageType 消息类型
     * @param isFileUpload 是否为文件上传结果
     */
    private void sendLargeTextMessage(WebSocketSession session, String content, MessageType messageType, boolean isFileUpload) throws IOException {
        if (session == null || !session.isOpen() || content == null) {
            return;
        }
        
        // 创建消息对象
        WebSocketMessage message = WebSocketMessage.builder()
                .type(messageType)
                .message(content)
                .isFileUpload(isFileUpload)
                .build();
        
        String messageJson = objectMapper.writeValueAsString(message);
        
        // 检查消息大小，如果超过阈值则使用分块发送
        byte[] messageBytes = messageJson.getBytes(StandardCharsets.UTF_8);
        if (messageBytes.length > 1024 * 1024) { // 如果消息超过1MB
            sendLargeTextMessage(session, message, messageJson);
        } else {
            session.sendMessage(new TextMessage(messageJson));
        }
    }
} 