package com.translation.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * 语音服务配置
 */
@Configuration
public class SpeechConfig {

    /**
     * Microsoft Azure Speech服务配置
     */
    @Data
    @Configuration
    @ConfigurationProperties(prefix = "speech.microsoft")
    public static class Microsoft {
        // Azure Speech API Key
        private String subscriptionKey;
        // Azure Speech 区域
        private String region;
        
        // Azure 翻译API密钥
        private String translatorKey;
        // Azure 翻译API区域/位置
        private String translatorRegion;
        
        // 语音识别配置
        private Recognition recognition = new Recognition();
        // 语音合成配置
        private Synthesis synthesis = new Synthesis();
        
        @Data
        public static class Recognition {
            // 默认识别语言
            private String language = "zh-CN";
        }
        
        @Data
        public static class Synthesis {
            // 默认合成语言
            private String language = "zh-CN";
            // 默认合成声音
            private String voiceName = "zh-CN-XiaoxiaoNeural";
        }
    }
    
    /**
     * OpenAI 语音服务配置
     */
    @Data
    @Configuration
    @ConfigurationProperties(prefix = "speech.openai")
    public static class OpenAI {
        // OpenAI API Key
        private String apiKey;
        // TTS模型
        private String model = "tts-1";
        // 默认声音
        private String voice = "alloy";
        // 默认语速
        private double speed = 1.0;
    }
} 