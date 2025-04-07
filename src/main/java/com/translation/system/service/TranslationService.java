package com.translation.system.service;

import org.springframework.web.socket.WebSocketSession;
import com.translation.system.model.TranslationRequest;
import reactor.core.publisher.Flux;

/**
 * 翻译服务接口
 */
public interface TranslationService {
    
    /**
     * 处理音频翻译请求
     * 
     * @param audioData 音频数据
     * @param request 翻译请求参数
     * @param session WebSocket会话
     * @return 处理结果的Flux
     */
    Flux<byte[]> translateSpeech(byte[] audioData, TranslationRequest request, WebSocketSession session);
} 