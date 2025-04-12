package com.translation.system.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 翻译请求模型，包含各种配置选项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslationRequest {
    
    /**
     * 源语言，例如 "zh-CN", "en-US", "auto" 表示自动检测
     */
    private String sourceLanguage;
    
    /**
     * 目标语言，例如 "zh-CN", "en-US"
     */
    private String targetLanguage;
    
    /**
     * 服务提供者，例如 "microsoft", "openai"
     */
    @Builder.Default
    private String provider = "microsoft";
    
    /**
     * 是否保留原始声音特征（例如音调、语速等）
     */
    @Builder.Default
    private boolean preserveVoiceCharacteristics = false;
    
    /**
     * 音频质量，可选值："low", "medium", "high"
     */
    @Builder.Default
    private String audioQuality = "medium";
    
    /**
     * 语音模型，可选值："basic", "enhanced"
     */
    @Builder.Default
    private String speechModel = "enhanced";
    
    /**
     * 翻译模式："speech-to-speech"（语音到语音）, "speech-to-text"（语音到文本）
     */
    @Builder.Default
    private String mode = "speech-to-speech";
    
    /**
     * 音频格式，默认为WAV
     */
    @Builder.Default
    private AudioFormat audioFormat = AudioFormat.WAV;
    
    /**
     * 声音ID，用于文本到语音转换
     */
    private String voiceId;
    
    /**
     * 声音名称，用于文本到语音转换（与voiceId二选一使用）
     */
    private String voice;
    
    /**
     * 自定义配置选项
     */
    private CustomOptions customOptions;
    
    /**
     * 会话ID，用于标识会话
     */
    private String sessionId;
    
    /**
     * 语速
     */
    private Double speed;
    
    /**
     * 采样率（Hz）
     */
    private Integer sampleRate;
    
    /**
     * 是否返回音频数据
     * 当值为true时，服务会返回转换后的音频数据
     */
    @Builder.Default
    private Boolean returnAudio = false;
    
    /**
     * 自定义配置选项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomOptions {
        /**
         * 是否启用消除背景噪音
         */
        @Builder.Default
        private boolean noiseReduction = true;
        
        /**
         * 是否启用断句检测
         */
        @Builder.Default
        private boolean usePunctuation = true;
        
        /**
         * 是否启用说话者分离
         */
        @Builder.Default
        private boolean speakerSeparation = false;
        
        /**
         * 语速调整因子，范围0.5-2.0
         */
        @Builder.Default
        private double speedFactor = 1.0;
        
        /**
         * 音调调整因子，范围0.5-2.0
         */
        @Builder.Default
        private double pitchFactor = 1.0;
    }
} 