package com.translation.system.service;

import org.springframework.web.socket.WebSocketSession;

import com.translation.system.model.TranslationRequest;
import reactor.core.publisher.Flux;

/**
 * 语音服务接口
 */
public interface SpeechService {
    
    /**
     * 语音转文字
     * 
     * @param audioData 音频数据
     * @param sourceLanguage 源语言
     * @return 识别出的文字
     */
    Flux<String> speechToText(byte[] audioData, String sourceLanguage);
    
    /**
     * 语音转文字（带请求和会话支持）
     * 
     * @param audioData 音频数据
     * @param request 翻译请求配置
     * @param session WebSocket会话
     * @return 识别出的文字
     */
    Flux<String> speechToText(byte[] audioData, TranslationRequest request, WebSocketSession session);
    
    /**
     * 文字转语音
     * 
     * @param text 需要转换的文字
     * @param targetLanguage 目标语言
     * @return 合成的语音数据
     */
    Flux<byte[]> textToSpeech(String text, String targetLanguage);
    
    /**
     * 文字转语音（带请求配置支持）
     * 
     * @param text 需要转换的文字
     * @param targetLanguage 目标语言
     * @param request 翻译请求配置
     * @return 合成的语音数据
     */
    Flux<byte[]> textToSpeech(String text, String targetLanguage, TranslationRequest request);
    
    /**
     * 文字转语音（带请求和会话支持）
     * 
     * @param text 需要转换的文字
     * @param request 翻译请求配置
     * @param session WebSocket会话
     * @return 合成的语音数据
     */
    Flux<byte[]> textToSpeech(String text, TranslationRequest request, WebSocketSession session);
    
    /**
     * 检查服务可用性
     * 
     * @return 服务是否可用
     */
    boolean isAvailable();
    
    /**
     * 获取服务提供者名称
     * 
     * @return 服务提供者名称
     */
    String getProviderName();
    
    /**
     * 语音翻译语音 - 将输入语音翻译成目标语言的语音
     * 
     * @param audioData 输入的音频数据
     * @param sourceLanguage 源语言
     * @param targetLanguage 目标语言
     * @return 翻译后的语音数据流
     */
    Flux<byte[]> translateSpeechToSpeech(byte[] audioData, String sourceLanguage, String targetLanguage);
    
    /**
     * 语音翻译语音 - 将输入语音翻译成目标语言的语音（带请求配置）
     * 
     * @param audioData 输入的音频数据
     * @param request 翻译请求配置
     * @param session WebSocket会话
     * @return 翻译后的语音数据流
     */
    Flux<byte[]> translateSpeechToSpeech(byte[] audioData, TranslationRequest request, WebSocketSession session);
} 