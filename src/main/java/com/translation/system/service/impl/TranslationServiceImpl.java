package com.translation.system.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import com.translation.system.model.TranslationRequest;
import com.translation.system.service.SpeechService;
import com.translation.system.service.SpeechServiceFactory;
import com.translation.system.service.TranslationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationServiceImpl implements TranslationService {

    private final SpeechServiceFactory speechServiceFactory;
    
    @Override
    public Flux<byte[]> translateSpeech(byte[] audioData, TranslationRequest request, WebSocketSession session) {
        // 获取指定的语音服务
        SpeechService speechService = speechServiceFactory.getSpeechService(request);
        
        String sessionId = session != null ? session.getId() : "unknown";
        log.info("开始语音翻译处理: 会话ID={}, 音频数据大小={}字节, 源语言={}, 目标语言={}, 服务提供商={}", 
                sessionId, audioData.length, request.getSourceLanguage(), 
                request.getTargetLanguage(), speechService.getProviderName());
        
        // 根据请求模式决定调用哪个方法
        if ("speech-to-text".equals(request.getMode())) {
            // 仅执行语音转文本
            return speechService.speechToText(audioData, request, session)
                    .doOnNext(text -> log.info("语音识别结果: 会话ID={}, 识别文本=\"{}\"", sessionId, text))
                    .map(text -> text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .doOnComplete(() -> log.info("语音转文本完成: 会话ID={}", sessionId))
                    .doOnError(e -> log.error("语音转文本错误: 会话ID={}, 错误={}", sessionId, e.getMessage(), e));
        } else {
            // 默认执行语音转语音翻译
            return speechService.translateSpeechToSpeech(audioData, request, session)
                    .doOnComplete(() -> log.info("语音翻译完成: 会话ID={}", sessionId))
                    .doOnError(e -> log.error("语音翻译错误: 会话ID={}, 错误={}", sessionId, e.getMessage(), e));
        }
    }
} 