package com.translation.system.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import com.translation.system.model.AudioFormat;
import com.translation.system.model.TranslationRequest;
import com.translation.system.service.impl.TranslationServiceImpl;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
public class TranslationServiceTest {

    @Mock
    private SpeechServiceFactory speechServiceFactory;
    
    @InjectMocks
    private TranslationServiceImpl translationService;
    
    @Mock
    private WebSocketSession session;
    
    private TranslationRequest request;
    private SpeechService speechService;
    
    @BeforeEach
    public void setup() {
        // 创建请求对象
        request = new TranslationRequest();
        request.setSourceLanguage("zh-CN");
        request.setTargetLanguage("en-US");
        request.setAudioFormat(AudioFormat.WAV);
        request.setSampleRate(16000);
        
        // 创建一个模拟的语音服务
        speechService = mock(SpeechService.class);
        
        // 配置工厂返回模拟的语音服务
        when(speechServiceFactory.getSpeechService(any())).thenReturn(speechService);
    }
    
    @Test
    public void testTranslateSpeech() {
        // 准备测试数据
        byte[] audioData = new byte[1024];
        String recognizedText = "这是一个测试";
        byte[] synthesizedAudio = new byte[2048];
        
        // 配置语音识别返回模拟文本
        when(speechService.speechToText(any(), any(), any()))
                .thenReturn(Flux.just(recognizedText));
        
        // 配置文本合成返回模拟音频
        when(speechService.textToSpeech(any(String.class), any(TranslationRequest.class), any(WebSocketSession.class)))
                .thenReturn(Flux.just(synthesizedAudio));
        
        // 执行测试
        Flux<byte[]> resultFlux = translationService.translateSpeech(audioData, request, session);
        
        // 验证结果
        StepVerifier.create(resultFlux)
                .expectNext(synthesizedAudio)
                .verifyComplete();
    }
} 