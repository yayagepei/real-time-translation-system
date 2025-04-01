package com.translation.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "speech")
public class SpeechConfig {

    private Microsoft microsoft;
    private OpenAI openai;

    @Data
    public static class Microsoft {
        private String subscriptionKey;
        private String region;
        private Recognition recognition;
        private Synthesis synthesis;

        @Data
        public static class Recognition {
            private String language;
        }

        @Data
        public static class Synthesis {
            private String voiceName;
            private String language;
        }
    }

    @Data
    public static class OpenAI {
        private String apiKey;
        private String model;
        private String voice;
        private Double speed;
    }
} 