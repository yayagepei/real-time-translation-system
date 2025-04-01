package com.translation.system.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;

import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.translation.system.config.PoolConfig;
import com.translation.system.config.SpeechConfig.Microsoft;
import com.translation.system.config.SpeechConfig.Microsoft.Recognition;
import com.translation.system.config.SpeechConfig.Microsoft.Synthesis;
import com.translation.system.model.AudioFormat;
import com.translation.system.model.TranslationRequest;
import com.translation.system.service.impl.MicrosoftSpeechService;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
public class MicrosoftSpeechServiceTest {

    @Mock
    private Microsoft microsoftConfig;
    
    @Mock
    private PoolConfig poolConfig;
    
    @Mock
    private GenericObjectPool<SpeechConfig> speechConfigPool;
    
    @InjectMocks
    private MicrosoftSpeechService speechService;
    
    @Mock
    private WebSocketSession session;
    
    private TranslationRequest request;
    
    @BeforeEach
    public void setup() throws Exception {
        // 设置默认的配置
        Recognition recognition = new Recognition();
        recognition.setLanguage("zh-CN");
        
        Synthesis synthesis = new Synthesis();
        synthesis.setLanguage("zh-CN");
        synthesis.setVoiceName("zh-CN-XiaoxiaoNeural");
        
        when(microsoftConfig.getRecognition()).thenReturn(recognition);
        when(microsoftConfig.getSynthesis()).thenReturn(synthesis);
        when(microsoftConfig.getSubscriptionKey()).thenReturn("dummy-key");
        when(microsoftConfig.getRegion()).thenReturn("eastasia");
        
        // 设置池化配置
        when(poolConfig.getMaxTotal()).thenReturn(8);
        when(poolConfig.getMaxIdle()).thenReturn(4);
        when(poolConfig.getMinIdle()).thenReturn(2);
        when(poolConfig.getMaxWaitMillis()).thenReturn(1000L);
        
        // 手动设置speechConfigPool
        ReflectionTestUtils.setField(speechService, "speechConfigPool", speechConfigPool);
        
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
        assert(providerName.equals("microsoft"));
    }
    
    /**
     * 注意：这个测试依赖于Microsoft Speech API，在实际运行时需要有效的订阅密钥。
     * 这里我们只是验证方法调用是否成功，而不测试实际结果。
     */
    @Test
    public void testSpeechToText() throws Exception {
        // 准备测试音频数据
        byte[] audioData = loadTestAudioData();
        
        // 模拟借用对象池
        SpeechConfig mockConfig = mock(SpeechConfig.class);
        when(speechConfigPool.borrowObject()).thenReturn(mockConfig);
        
        // 调用方法
        Flux<String> resultFlux = speechService.speechToText(audioData, request, session);
        
        // 验证结果是否为空
        AtomicReference<String> resultRef = new AtomicReference<>();
        resultFlux.next().subscribe(resultRef::set);
        
        // 由于我们不能实际调用Microsoft API，所以这里我们只验证方法是否成功调用
        Mockito.verify(speechConfigPool).borrowObject();
    }
    
    @Test
    public void testTextToSpeech() throws Exception {
        // 准备测试文本
        String text = "这是一个测试";
        
        // 模拟借用对象池
        SpeechConfig mockConfig = mock(SpeechConfig.class);
        when(speechConfigPool.borrowObject()).thenReturn(mockConfig);
        
        // 调用方法
        Flux<byte[]> resultFlux = speechService.textToSpeech(text, request, session);
        
        // 验证结果是否为空
        AtomicReference<byte[]> resultRef = new AtomicReference<>();
        resultFlux.next().subscribe(resultRef::set);
        
        // 由于我们不能实际调用Microsoft API，所以这里我们只验证方法是否成功调用
        Mockito.verify(speechConfigPool).borrowObject();
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