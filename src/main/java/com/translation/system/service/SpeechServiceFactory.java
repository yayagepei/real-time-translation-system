package com.translation.system.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.translation.system.model.TranslationRequest;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpeechServiceFactory {
    
    private final List<SpeechService> speechServices;
    private final Map<String, SpeechService> serviceMap = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        speechServices.forEach(service -> {
            serviceMap.put(service.getProviderName().toLowerCase(), service);
            log.info("Registered speech service provider: {}", service.getProviderName());
        });
    }
    
    /**
     * 获取语音服务提供者
     * 
     * @param request 翻译请求
     * @return 语音服务
     */
    public SpeechService getSpeechService(TranslationRequest request) {
        if (request == null || request.getProvider() == null) {
            // 默认使用Microsoft
            return serviceMap.get("microsoft");
        }
        
        SpeechService service = serviceMap.get(request.getProvider().toLowerCase());
        if (service == null) {
            log.warn("Unsupported speech provider: {}, falling back to Microsoft", request.getProvider());
            return serviceMap.get("microsoft");
        }
        
        return service;
    }
} 