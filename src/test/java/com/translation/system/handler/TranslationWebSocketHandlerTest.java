package com.translation.system.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.translation.system.model.AudioFormat;
import com.translation.system.model.MessageType;
import com.translation.system.model.TranslationRequest;
import com.translation.system.model.WebSocketMessage;
import com.translation.system.service.TranslationService;

import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
public class TranslationWebSocketHandlerTest {

    @Mock
    private TranslationService translationService;
    
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    
    @InjectMocks
    private TranslationWebSocketHandler handler;
    
    @Mock
    private WebSocketSession session;
    
    private TranslationRequest request;
    
    @BeforeEach
    public void setup() throws Exception {
        // 设置会话ID
        when(session.getId()).thenReturn("test-session-id");
        
        // 创建请求对象
        request = new TranslationRequest();
        request.setSourceLanguage("zh-CN");
        request.setTargetLanguage("en-US");
        request.setAudioFormat(AudioFormat.WAV);
        request.setSampleRate(16000);
        
        // 允许发送消息
        doNothing().when(session).sendMessage(any());
    }
    
    @Test
    public void testHandleTextMessage_Init() throws Exception {
        // 创建初始化消息
        WebSocketMessage message = WebSocketMessage.builder()
                .type(MessageType.INIT)
                .request(request)
                .build();
        
        // 序列化消息
        String messageJson = objectMapper.writeValueAsString(message);
        
        // 调用处理方法
        handler.handleTextMessage(session, new TextMessage(messageJson));
        
        // 验证发送了确认消息
        verify(session).sendMessage(any(TextMessage.class));
    }
    
    @Test
    public void testHandleTextMessage_Close() throws Exception {
        // 创建关闭消息
        WebSocketMessage message = WebSocketMessage.builder()
                .type(MessageType.CLOSE)
                .build();
        
        // 序列化消息
        String messageJson = objectMapper.writeValueAsString(message);
        
        // 调用处理方法
        handler.handleTextMessage(session, new TextMessage(messageJson));
        
        // 验证关闭会话
        verify(session).close(any());
    }
    
    @Test
    public void testHandleBinaryMessage() throws Exception {
        // 准备会话配置
        WebSocketMessage initMessage = WebSocketMessage.builder()
                .type(MessageType.INIT)
                .request(request)
                .build();
        
        // 序列化消息并初始化会话
        String initMessageJson = objectMapper.writeValueAsString(initMessage);
        handler.handleTextMessage(session, new TextMessage(initMessageJson));
        
        // 准备音频数据
        byte[] audioData = new byte[1024];
        ByteBuffer buffer = ByteBuffer.wrap(audioData);
        
        // 配置转译服务返回模拟音频
        byte[] responseAudio = new byte[2048];
        when(translationService.translateSpeech(any(), any(), any()))
                .thenReturn(Flux.just(responseAudio));
        
        // 调用处理方法
        handler.handleBinaryMessage(session, new BinaryMessage(buffer));
        
        // 验证发送了音频响应
        verify(translationService).translateSpeech(any(), any(), any());
    }
    
    @Test
    public void testHandleBinaryMessage_SessionNotInitialized() throws Exception {
        // 准备音频数据
        byte[] audioData = new byte[1024];
        ByteBuffer buffer = ByteBuffer.wrap(audioData);
        
        // 调用处理方法（未初始化会话）
        handler.handleBinaryMessage(session, new BinaryMessage(buffer));
        
        // 应该发送错误消息
        verify(session).sendMessage(any(TextMessage.class));
    }
} 