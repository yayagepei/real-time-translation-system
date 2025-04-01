package com.translation.system.service;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.translation.system.model.TranslationRequest;

import org.springframework.beans.factory.annotation.Qualifier;
import reactor.core.publisher.Flux;

/**
 * 翻译服务实现类，协调不同语音服务的使用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationService {

    @Qualifier("microsoftSpeechService")
    private final SpeechService microsoftService;
    
    @Qualifier("openAISpeechService")
    private final SpeechService openAIService;
    
    /**
     * 处理音频翻译请求
     * 
     * @param audioData 音频数据
     * @param request 翻译请求参数
     * @param session WebSocket会话
     * @return 处理结果的Flux
     */
    public Flux<byte[]> translateSpeech(byte[] audioData, TranslationRequest request, WebSocketSession session) {
        log.debug("Processing translation request with {} bytes of audio data", audioData.length);
        
        String sessionId = session.getId();
        
        try {
            // 根据请求配置选择合适的语音服务
            SpeechService selectedService = selectSpeechService(request);
            log.info("Selected service: {}", selectedService.getClass().getSimpleName());
            
            // 语音转文字
            String recognizedText = selectedService.speechToText(audioData, request, session);
            log.info("Session {}: Recognized text: {}", sessionId, recognizedText);
            
            if (recognizedText == null || recognizedText.trim().isEmpty()) {
                log.warn("Session {}: No text recognized from audio", sessionId);
                return Flux.empty();
            }
            
            // 如果模式是speech-to-text，返回空的音频数据流
            if ("speech-to-text".equals(request.getMode())) {
                log.info("Session {}: Speech-to-text mode, skipping text-to-speech", sessionId);
                
                // 返回空Flux，因为我们不需要音频结果
                return Flux.empty();
            }
            
            // 文字转语音
            byte[] synthesizedSpeech = selectedService.textToSpeech(recognizedText, request.getTargetLanguage(), request);
            log.info("Session {}: Synthesized {} bytes of audio", sessionId, synthesizedSpeech.length);
            
            return Flux.just(synthesizedSpeech);
            
        } catch (Exception e) {
            log.error("Session {}: Error in translation process: {}", sessionId, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * 根据请求选择合适的语音服务
     */
    private SpeechService selectSpeechService(TranslationRequest request) {
        // 这里可以根据请求参数决定使用哪个服务
        // 例如，可以基于语言支持、服务可用性等做判断
        
        // 当前简单实现：默认使用Microsoft，除非配置了其他服务
        if ("openai".equalsIgnoreCase(request.getProvider())) {
            return openAIService;
        }
        
        return microsoftService;
    }
} 