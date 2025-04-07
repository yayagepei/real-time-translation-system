package com.translation.system.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.translation.system.config.PoolConfig;
import com.translation.system.config.SpeechConfig.Microsoft;
import com.translation.system.config.SpeechConfig.Microsoft.Recognition;
import com.translation.system.config.SpeechConfig.Microsoft.Synthesis;
import com.translation.system.model.AudioFormat;
import com.translation.system.model.TranslationRequest;
import com.translation.system.service.impl.MicrosoftSpeechService;
import com.translation.system.util.AudioUtils;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@Slf4j
public class MicrosoftSpeechServiceTest {

    private Microsoft microsoftConfig;
    private PoolConfig poolConfig;
    private GenericObjectPool<SpeechConfig> speechConfigPool;
    private MicrosoftSpeechService speechService;
    private WebSocketSession session;
    private TranslationRequest request;
    
    // 这里使用微软API的密钥和区域，请在运行前替换为有效的值
    private static final String API_KEY = "G4rDKnpeQZ55GLXNkLsydemAdJERS8bhnWfgvM9JgkkwuOTWNI1RJQQJ99BCAC3pKaRXJ3w3AAAYACOGctDB";
    private static final String REGION = "eastasia";
    
    @BeforeEach
    public void setup() throws Exception {
        // 创建真实配置而不是模拟
        microsoftConfig = new Microsoft();
        microsoftConfig.setSubscriptionKey(API_KEY);
        microsoftConfig.setRegion(REGION);
        
        // 设置默认的配置
        Recognition recognition = new Recognition();
        recognition.setLanguage("zh-CN");
        
        Synthesis synthesis = new Synthesis();
        synthesis.setLanguage("zh-CN");
        synthesis.setVoiceName("zh-CN-XiaoxiaoNeural");
        
        microsoftConfig.setRecognition(recognition);
        microsoftConfig.setSynthesis(synthesis);
        
        // 设置池化配置
        poolConfig = new PoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(2);
        poolConfig.setMaxWaitMillis(1000L);
        
        // 创建服务实例并初始化
        speechService = new MicrosoftSpeechService(microsoftConfig, poolConfig);
        speechService.init(); // 调用init方法初始化服务
        
        // 创建请求对象
        request = new TranslationRequest();
        request.setSourceLanguage("zh-CN");
        request.setTargetLanguage("en-US");
        request.setAudioFormat(AudioFormat.WAV);
        request.setSampleRate(16000);
        
        // 设置保存音频路径，便于调试
        System.setProperty("debug.audio.save-to-file", "true");
        System.setProperty("debug.audio.directory", "./target/debug-audio");
    }
    
    // @Test
    public void testGetProviderName() {
        String providerName = speechService.getProviderName();
        assertNotNull(providerName);
        assertTrue(providerName.equals("microsoft"));
    }
    
    /**
     * 实际调用Microsoft语音识别API
     */
    // @Test
    public void testSpeechToText() throws Exception {
        // 准备测试音频数据
        byte[] audioData = loadTestAudioData();
        
        if (audioData == null || audioData.length == 0) {
            log.warn("测试音频文件不存在或为空，将使用文本到语音先生成一段测试音频");
            // 先使用文本转语音生成一段测试音频
            String testText = "这是一个测试音频，用于验证语音识别功能";
            log.info("尝试生成测试音频，文本: {}", testText);
            
            // 创建专门用于合成的请求
            TranslationRequest synthesisRequest = new TranslationRequest();
            synthesisRequest.setSourceLanguage("zh-CN");
            synthesisRequest.setTargetLanguage("en-US"); // 保持相同语言，避免翻译
            synthesisRequest.setAudioFormat(AudioFormat.WAV);
            synthesisRequest.setSampleRate(16000);
            
            // 生成音频数据
            Flux<byte[]> audioFlux = speechService.textToSpeech(testText, synthesisRequest, null);
            audioData = audioFlux.blockFirst();
            
            if (audioData == null || audioData.length == 0) {
                log.error("无法生成测试音频数据，跳过测试");
                return;
            }
            
            log.info("成功生成测试音频数据，大小: {} 字节", audioData.length);
            // 保存生成的音频数据供后续使用
            saveTestAudioOutput(audioData);
        }
        
        log.info("开始调用Microsoft语音识别API，音频数据大小: {} 字节", audioData.length);
        
        // 调用方法，实际访问微软API
        Flux<String> resultFlux = speechService.speechToText(audioData, request, session);
        
        // 使用StepVerifier验证结果
        StepVerifier.create(resultFlux)
            .consumeNextWith(text -> {
                log.info("识别结果: {}", text);
                assertNotNull(text, "识别结果不应为空");
            })
            .verifyComplete();
    }
    
    // @Test
    public void testTextToSpeech() throws Exception {
        // 准备测试文本
        String text = "这是一个测试，我们正在使用微软的语音合成服务";
        
        log.info("开始调用Microsoft语音合成API，文本: {}", text);
        
        // 调用方法，实际访问微软API
        Flux<byte[]> resultFlux = speechService.textToSpeech(text, request, session);
        
        // 使用StepVerifier验证结果
        StepVerifier.create(resultFlux)
            .consumeNextWith(audioBytes -> {
                log.info("合成音频数据大小: {} 字节", audioBytes.length);
                assertTrue(audioBytes.length > 0, "合成的音频数据不应为空");
                
                // 保存音频数据到文件进行验证
                saveTestAudioOutput(audioBytes);
            })
            .verifyComplete();
    }
    
    /**
     * 测试语音翻译语音功能
     */
    // @Test
    public void testTranslateSpeechToSpeech() throws Exception {
        // 准备测试音频数据
        byte[] audioData = loadTestAudioData();
        
        if (audioData == null || audioData.length == 0) {
            log.error("测试音频文件不存在或为空");
            return;
        }
        
        log.info("======== 开始测试语音翻译语音功能 ========");
        log.info("输入音频数据大小: {} 字节", audioData.length);
        
        // 设置翻译请求
        TranslationRequest translationRequest = new TranslationRequest();
        translationRequest.setSourceLanguage("zh-CN"); // 源语言：中文
        translationRequest.setTargetLanguage("en-US"); // 目标语言：英文
        translationRequest.setAudioFormat(AudioFormat.WAV);
        translationRequest.setVoice("en-US-JennyNeural"); // 使用英文女声
        
        // 检查翻译请求参数
        log.info("翻译请求参数: 源语言={}, 目标语言={}, 声音={}, 音频格式={}",
                translationRequest.getSourceLanguage(), 
                translationRequest.getTargetLanguage(),
                translationRequest.getVoice(),
                translationRequest.getAudioFormat());
        
        // 调用方法，进行语音翻译语音测试
        log.info("调用translateSpeechToSpeech方法...");
        Flux<byte[]> resultFlux = speechService.translateSpeechToSpeech(audioData, translationRequest, null);
        
        // 使用StepVerifier验证结果
        log.info("开始验证结果...");
        StepVerifier.create(resultFlux)
            .consumeNextWith(translatedAudio -> {
                log.info("翻译后的音频数据大小: {} 字节", translatedAudio.length);
                assertTrue(translatedAudio.length > 0, "翻译后的音频数据不应为空");
                
                // 保存翻译后的音频数据到文件进行验证
                try {
                    // 保存到特定目录以区分
                    String outputPath = "target/translated-audio_" + System.currentTimeMillis() + ".wav";
                    java.nio.file.Files.write(java.nio.file.Paths.get(outputPath), translatedAudio);
                    log.info("已保存翻译后的音频到: {}", outputPath);
                } catch (Exception e) {
                    log.error("保存翻译音频失败", e);
                }
            })
            .verifyComplete();
        
        log.info("======== 语音翻译语音测试完成 ========");
    }
    
    private byte[] loadTestAudioData() throws IOException {
        // 从测试资源加载测试音频文件
        try (InputStream is = getClass().getResourceAsStream("/test-audio.wav")) {
            if (is == null) {
                log.warn("测试音频文件不存在，请在测试资源目录中添加test-audio.wav文件");
                // 如果测试文件不存在，返回一个空的音频数据
                return new byte[0];
            }
            byte[] data = is.readAllBytes();
            if (data.length < 100) { // 判断文件是否有实际内容
                log.warn("测试音频文件内容过少，可能是空文件");
                return new byte[0];
            }
            return data;
        }
    }
    
    private void saveTestAudioOutput(byte[] audioData) {
        try {
            // 保存到测试资源目录，方便后续测试使用
            String resourceFile = AudioUtils.saveAudioChunkToFile(
                    audioData, 
                    "test-session", 
                    "test-output", 
                    true, 
                    "src/test/resources");
            
            if (resourceFile != null) {
                log.info("已保存测试音频到: {}", resourceFile);
            }
            
            // 同时保存到target目录
            String outputFile = AudioUtils.saveAudioChunkToFile(
                    audioData, 
                    "test-session", 
                    "test-output", 
                    true, 
                    "target");
            
            if (outputFile != null) {
                log.info("已保存合成音频到: {}", outputFile);
            }
        } catch (Exception e) {
            log.error("保存测试音频输出失败", e);
        }
    }
} 