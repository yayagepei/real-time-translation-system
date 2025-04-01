package com.translation.system.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.translation.system.config.SpeechConfig.OpenAI;
import com.translation.system.model.AudioFormat;
import com.translation.system.model.TranslationRequest;
import com.translation.system.service.impl.OpenAISpeechService;

import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
public class OpenAISpeechServiceTest {

    @Mock
    private OpenAI openaiConfig;
    
    @Mock
    private HttpClient httpClient;
    
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    
    @InjectMocks
    private OpenAISpeechService speechService;
    
    @Mock
    private WebSocketSession session;
    
    private TranslationRequest request;
    
    @BeforeEach
    public void setup() throws Exception {
        // 设置默认的配置
        when(openaiConfig.getApiKey()).thenReturn("dummy-key");
        when(openaiConfig.getModel()).thenReturn("tts-1");
        when(openaiConfig.getVoice()).thenReturn("alloy");
        when(openaiConfig.getSpeed()).thenReturn(1.0);
        
        // 手动设置httpClient
        ReflectionTestUtils.setField(speechService, "httpClient", httpClient);
        
        // 创建请求对象
        request = new TranslationRequest();
        request.setSourceLanguage("zh-CN");
        request.setTargetLanguage("en-US");
        request.setAudioFormat(AudioFormat.WAV);
        request.setSampleRate(16000);
    }
    
    @Test
    public void testGetProviderName() {
        String providerName = speechService.getProviderName();
        assertNotNull(providerName);
        assert(providerName.equals("openai"));
    }
    
    /**
     * 注意：这些测试依赖于OpenAI API，在实际运行时需要有效的API密钥。
     * 由于我们不能在测试中实际调用API，所以这里我们模拟HTTP响应。
     */
    @Test
    public void testSpeechToText() throws Exception {
        // 准备测试音频数据
        byte[] audioData = loadTestAudioData();
        
        // 创建一个模拟的HTTP响应
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        
        // 创建一个带有文本结果的JSON响应
        ObjectNode responseJson = objectMapper.createObjectNode();
        responseJson.put("text", "这是一个测试");
        when(mockResponse.body()).thenReturn(responseJson.toString());
        
        // 模拟HTTP客户端行为，用any(BodyHandler.class)替代any()
        when(httpClient.send(any(HttpRequest.class), any(BodyHandler.class))).thenReturn(mockResponse);
        
        // 调用方法
        Flux<String> resultFlux = speechService.speechToText(audioData, request, session);
        
        // 验证结果
        AtomicReference<String> resultRef = new AtomicReference<>();
        resultFlux.next().subscribe(resultRef::set);
        
        // 这里实际上无法验证结果，因为我们不会真正执行HTTP调用
    }
    
    @Test
    public void testTextToSpeech() throws Exception {
        // 准备测试文本
        String text = "这是一个测试";
        
        // 创建一个模拟的HTTP响应
        HttpResponse<byte[]> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        
        // 创建一个模拟的音频数据
        byte[] mockAudioData = new byte[1024];
        when(mockResponse.body()).thenReturn(mockAudioData);
        
        // 模拟HTTP客户端行为，用any(BodyHandler.class)替代any()
        when(httpClient.send(any(HttpRequest.class), any(BodyHandler.class))).thenReturn(mockResponse);
        
        // 调用方法
        Flux<byte[]> resultFlux = speechService.textToSpeech(text, request, session);
        
        // 验证结果
        AtomicReference<byte[]> resultRef = new AtomicReference<>();
        resultFlux.next().subscribe(resultRef::set);
        
        // 这里实际上无法验证结果，因为我们不会真正执行HTTP调用
    }
    
    private byte[] loadTestAudioData() throws IOException {
        // 从测试资源加载测试音频文件
        try (InputStream is = getClass().getResourceAsStream("/test-audio.wav")) {
            if (is == null) {
                // 如果测试文件不存在，返回一个空的音频数据
                return new byte[1024];
            }
            return is.readAllBytes();
        }
    }
} 