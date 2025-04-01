package com.translation.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.nio.charset.StandardCharsets;

@SpringBootApplication
@EnableConfigurationProperties
public class RealTimeTranslationSystemApplication {

    public static void main(String[] args) {
        // 设置JVM默认编码为UTF-8
        System.setProperty("file.encoding", StandardCharsets.UTF_8.name());
        SpringApplication.run(RealTimeTranslationSystemApplication.class, args);
    }
} 