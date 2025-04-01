package com.translation.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "pool")
public class PoolConfig {
    private int maxIdle;
    private int minIdle;
    private int maxTotal;
    private long maxWaitMillis;
} 