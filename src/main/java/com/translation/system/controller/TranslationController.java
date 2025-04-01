package com.translation.system.controller;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.translation.system.model.TranslationRequest;
import com.translation.system.service.SpeechService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 翻译系统REST控制器
 * 提供文件上传翻译和系统状态检查功能
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TranslationController {

    private final SpeechService microsoftSpeechService;
    private final SpeechService openAISpeechService;
    
    /**
     * 服务健康状态检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("microsoftService", microsoftSpeechService.isAvailable() ? "UP" : "DOWN");
        status.put("openaiService", openAISpeechService.isAvailable() ? "UP" : "DOWN");
        status.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * 文件上传音频转文字接口
     */
    @PostMapping(value = "/speech-to-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> speechToText(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sourceLanguage", defaultValue = "auto") String sourceLanguage,
            @RequestParam(value = "provider", defaultValue = "microsoft") String provider) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            byte[] audioData = file.getBytes();
            
            String text;
            if ("openai".equalsIgnoreCase(provider)) {
                text = openAISpeechService.speechToText(audioData, sourceLanguage);
            } else {
                text = microsoftSpeechService.speechToText(audioData, sourceLanguage);
            }
            
            response.put("success", true);
            response.put("text", text);
            response.put("provider", provider);
            response.put("sourceLanguage", sourceLanguage);
            
        } catch (IOException e) {
            log.error("Error reading audio file", e);
            response.put("success", false);
            response.put("error", "无法读取音频文件: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("Error processing speech-to-text", e);
            response.put("success", false);
            response.put("error", "处理语音转文字时出错: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 获取系统信息
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getSystemInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", "实时翻译系统");
        info.put("version", "1.0.0");
        info.put("description", "基于Microsoft和OpenAI服务的实时语音翻译系统");
        info.put("supportedLanguages", getSupportedLanguages());
        
        return ResponseEntity.ok(info);
    }
    
    /**
     * 获取支持的语言列表
     */
    private Map<String, String> getSupportedLanguages() {
        Map<String, String> languages = new HashMap<>();
        languages.put("zh-CN", "简体中文");
        languages.put("en-US", "英语(美国)");
        languages.put("ja-JP", "日语");
        languages.put("ko-KR", "韩语");
        languages.put("fr-FR", "法语");
        languages.put("de-DE", "德语");
        languages.put("es-ES", "西班牙语");
        languages.put("ru-RU", "俄语");
        // 可以根据实际支持添加更多
        return languages;
    }
} 