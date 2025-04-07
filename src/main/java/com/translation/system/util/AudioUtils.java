package com.translation.system.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 音频工具类，提供音频数据处理的通用方法
 */
@Slf4j
@Component
public class AudioUtils {
    
    // 会话音频文件映射表，用于跟踪不同会话的音频文件路径
    private static final Map<String, String> SESSION_AUDIO_FILE_MAP = new ConcurrentHashMap<>();
    
    /**
     * 保存音频数据块到文件
     * 
     * @param audioData 音频数据
     * @param sessionId 会话ID
     * @param type 类型（输入/输出）
     * @param saveEnabled 是否启用保存功能
     * @param directoryPath 音频保存目录
     * @return 保存的文件路径，如果保存失败则返回null
     */
    public static String saveAudioChunkToFile(
            byte[] audioData, 
            String sessionId, 
            String type, 
            boolean saveEnabled, 
            String directoryPath) {
        
        if (!saveEnabled || audioData == null || audioData.length == 0) {
            return null;
        }
        
        try {
            // 确保目录存在
            Path directory = Paths.get(directoryPath);
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
                log.info("Created audio directory: {}", directoryPath);
            }
            
            // 为会话获取音频文件路径，如果不存在则创建
            String sessionFilePath = SESSION_AUDIO_FILE_MAP.computeIfAbsent(sessionId, id -> {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                return Paths.get(directoryPath, String.format("%s_%s", timestamp, id)).toString();
            });
            
            // 创建不同的文件名用于输入和输出
            String fileName = String.format("%s_%s_%d.bin", sessionFilePath, type, System.currentTimeMillis());
            
            // 保存音频数据到文件
            try (FileOutputStream fos = new FileOutputStream(fileName)) {
                fos.write(audioData);
                log.debug("Saved {} audio chunk to file: {}", type, fileName);
                return fileName;
            }
        } catch (IOException e) {
            log.error("Failed to save audio data to file: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 清理指定会话的音频文件记录
     * 
     * @param sessionId 会话ID
     */
    public static void cleanupSessionAudioFiles(String sessionId) {
        SESSION_AUDIO_FILE_MAP.remove(sessionId);
    }
    
    /**
     * 清理所有会话的音频文件记录
     */
    public static void cleanupAllSessionAudioFiles() {
        SESSION_AUDIO_FILE_MAP.clear();
    }
} 