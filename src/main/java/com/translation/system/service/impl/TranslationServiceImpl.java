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
        
        log.debug("Using speech service provider: {}", speechService.getProviderName());
        
        // 语音识别 -> 将识别结果合成为目标语言的语音
        return speechService.speechToText(audioData, request, session)
                .flatMap(text -> {
                    log.debug("Recognized text: {}", text);
                    // 在实际应用中，可能需要调用翻译API将文本从源语言翻译为目标语言
                    // 这里简化处理，直接使用识别的文本进行语音合成
                    return speechService.textToSpeech(text, request, session);
                });
    }
} 