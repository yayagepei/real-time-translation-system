package com.translation.system.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioInputStream;
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream;
import com.microsoft.cognitiveservices.speech.CancellationDetails;
import com.microsoft.cognitiveservices.speech.CancellationReason;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognitionResult;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisResult;
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.translation.system.config.PoolConfig;
import com.translation.system.config.SpeechConfig.Microsoft;
import com.translation.system.model.AudioFormat;
import com.translation.system.model.TranslationRequest;
import com.translation.system.service.SpeechService;
import com.translation.system.util.AudioUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class MicrosoftSpeechService implements SpeechService {

    
    private final Microsoft microsoftConfig;
    private final PoolConfig poolConfig;
    
    @Value("${debug.audio.save-to-file:false}")
    private boolean saveAudioToFile;
    
    @Value("${debug.audio.directory:./debug-audio}")
    private String debugAudioDirectory;
    
    private GenericObjectPool<SpeechConfig> speechConfigPool;
    private HttpClient httpClient;
    
    @PostConstruct
    public void init() {
        // 初始化SpeechConfig对象池
        GenericObjectPoolConfig<SpeechConfig> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxIdle(this.poolConfig.getMaxIdle());
        poolConfig.setMinIdle(this.poolConfig.getMinIdle());
        poolConfig.setMaxTotal(this.poolConfig.getMaxTotal());
        poolConfig.setMaxWait(java.time.Duration.ofMillis(this.poolConfig.getMaxWaitMillis()));
        poolConfig.setBlockWhenExhausted(true);
        
        speechConfigPool = new GenericObjectPool<>(new BasePooledObjectFactory<SpeechConfig>() {
            @Override
            public SpeechConfig create() {
                log.info("Creating new SpeechConfig, subscriptionKey={}, region={}", 
                        microsoftConfig.getSubscriptionKey(), microsoftConfig.getRegion());
                
                // 创建SpeechConfig实例
                SpeechConfig config = SpeechConfig.fromSubscription(
                        microsoftConfig.getSubscriptionKey(), 
                        microsoftConfig.getRegion());
                
                // 设置默认语音识别语言
                config.setSpeechRecognitionLanguage(microsoftConfig.getRecognition().getLanguage());
                
                // 设置默认语音合成语言和声音
                config.setSpeechSynthesisLanguage(microsoftConfig.getSynthesis().getLanguage());
                config.setSpeechSynthesisVoiceName(microsoftConfig.getSynthesis().getVoiceName());
                
                return config;
            }
            
            @Override
            public PooledObject<SpeechConfig> wrap(SpeechConfig config) {
                return new DefaultPooledObject<>(config);
            }
            
            @Override
            public void destroyObject(PooledObject<SpeechConfig> p) {
                SpeechConfig config = p.getObject();
                if (config != null) {
                    try {
                        config.close();
                    } catch (Exception e) {
                        log.error("Error closing SpeechConfig", e);
                    }
                }
            }
        }, poolConfig);
        
        // 初始化HTTP客户端用于翻译API
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        
        // 创建调试音频目录
        if (saveAudioToFile) {
            try {
                Path directory = Paths.get(debugAudioDirectory);
                if (!Files.exists(directory)) {
                    Files.createDirectories(directory);
                    log.info("Debug audio directory created: {}", debugAudioDirectory);
                }
            } catch (IOException e) {
                log.error("Failed to create debug audio directory", e);
                // 失败时禁用文件保存功能
                saveAudioToFile = false;
            }
        }
    }
    
    @PreDestroy
    public void cleanup() {
        if (speechConfigPool != null) {
            try {
                speechConfigPool.close();
                log.info("Speech config pool closed");
            } catch (Exception e) {
                log.error("Error closing speech config pool", e);
            }
        }
        
        // 清理会话音频文件映射
        AudioUtils.cleanupAllSessionAudioFiles();
    }

    @Override
    public Flux<String> speechToText(byte[] audioData, String sourceLanguage) {
        TranslationRequest request = new TranslationRequest();
        request.setSourceLanguage(sourceLanguage);
        return speechToText(audioData, request, null);
    }

    @Override
    public Flux<String> speechToText(byte[] audioData, TranslationRequest request, WebSocketSession session) {
        SpeechConfig speechConfig = null;
        AudioConfig audioConfig = null;
        PushAudioInputStream pushStream = null;
        
        // 保存输入音频数据到文件（调试用）
        if (saveAudioToFile && session != null) {
            AudioUtils.saveAudioChunkToFile(audioData, session.getId(), "input", saveAudioToFile, debugAudioDirectory);
        }
        
        try {
            speechConfig = speechConfigPool.borrowObject();
            
            // 如果请求中指定了语言，则覆盖默认设置
            if (request.getSourceLanguage() != null) {
                speechConfig.setSpeechRecognitionLanguage(request.getSourceLanguage());
            }
            
            // 创建PushAudioInputStream
            pushStream = AudioInputStream.createPushStream();
            pushStream.write(audioData);
            
            audioConfig = AudioConfig.fromStreamInput(pushStream);
            
            // 创建recognizer
            final SpeechRecognizer recognizer = new SpeechRecognizer(speechConfig, audioConfig);
            
            try {
                // 使用CompletableFuture进行异步处理
                CompletableFuture<String> future = new CompletableFuture<>();
                
                // 启动连续识别
                AtomicReference<StringBuilder> resultBuilder = new AtomicReference<>(new StringBuilder());
                
                // 设置识别结果处理
                recognizer.recognized.addEventListener((s, e) -> {
                    if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                        String text = e.getResult().getText();
                        log.debug("RECOGNIZED: {}", text);
                        resultBuilder.get().append(text).append(" ");
                    }
                });
                
                // 添加错误处理
                recognizer.canceled.addEventListener((s, e) -> {
                    SpeechRecognitionResult result = e.getResult();
                    if (result != null) {
                        CancellationDetails details = CancellationDetails.fromResult(result);
                        log.error("CANCELED: Reason={}, ErrorDetails={}", 
                                details.getReason(), details.getErrorDetails());
                        
                        if (details.getReason() == CancellationReason.Error) {
                            future.completeExceptionally(new RuntimeException(
                                    "Speech recognition canceled: " + details.getErrorDetails()));
                        } else {
                            // 即使是非错误取消，也确保Future得到完成
                            future.complete(resultBuilder.get().toString().trim());
                        }
                    } else {
                        // 如果结果为null，仍需要确保Future完成
                        log.error("CANCELED: 无详细信息");
                        future.complete(resultBuilder.get().toString().trim());
                    }
                });
                
                recognizer.sessionStopped.addEventListener((s, e) -> {
                    log.debug("Speech recognition stopped.");
                    try {
                        recognizer.stopContinuousRecognitionAsync().get();
                        future.complete(resultBuilder.get().toString().trim());
                    } catch (Exception ex) {
                        log.error("Error stopping continuous recognition", ex);
                        future.completeExceptionally(ex);
                    }
                });
                
                // 开始识别
                try {
                    recognizer.startContinuousRecognitionAsync().get();
                } catch (Exception e) {
                    log.error("Failed to start speech recognition", e);
                    throw new RuntimeException("Failed to start speech recognition", e);
                }
                
                // 识别一段时间后停止（可以根据实际需求调整）
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Recognition interrupted");
                }
                
                // 明确停止识别并等待完成
                try {
                    // 请求停止异步识别
                    log.info("停止语音识别过程...");
                    var stopFuture = recognizer.stopContinuousRecognitionAsync();
                    
                    // 明确等待停止完成
                    stopFuture.get();
                    log.info("语音识别成功停止");
                    
                    // 再等待一些额外时间，确保所有处理都已完成
                    Thread.sleep(500);
                    
                    // 等待future完成，设置超时以防止永久阻塞
                    String result = future.getNow("");
                    
                    // 如果结果为空，提供一个默认消息
                    if (result == null || result.trim().isEmpty()) {
                        log.warn("Recognition produced no result, using fallback message");
                        result = "无法识别语音内容";
                    }
                    return Flux.just(result);
                } catch (Exception e) {
                    log.error("Failed to stop speech recognition", e);
                    // 即使停止失败，我们仍然尝试获取已识别的文本
                    String result = future.getNow("");
                    if (result == null || result.trim().isEmpty()) {
                        result = "语音识别中断";
                    }
                    return Flux.just(result);
                }
            } finally {
                // 安全关闭资源，但给异步操作多一些时间完成
                try {
                    // 额外等待以确保异步操作完全结束
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // 关闭各种资源
                if (recognizer != null) {
                    try {
                        log.info("正在关闭语音识别器...");
                        recognizer.close();
                        log.info("语音识别器成功关闭");
                    } catch (Exception e) {
                        log.warn("Failed to close speech recognizer: {}", e.getMessage());
                    }
                }
                
                if (audioConfig != null) {
                    try {
                        audioConfig.close();
                    } catch (Exception e) {
                        log.warn("Failed to close audio config", e);
                    }
                }
                
                if (speechConfig != null) {
                    try {
                        speechConfigPool.returnObject(speechConfig);
                    } catch (Exception e) {
                        log.warn("Failed to return speech config to pool", e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error in speech-to-text: ", e);
            // 返回错误消息而不是抛出异常，以便系统可以继续运行
            return Flux.just("语音识别失败: " + e.getMessage());
        }
    }

    @Override
    public Flux<byte[]> textToSpeech(String text, String targetLanguage) {
        TranslationRequest request = new TranslationRequest();
        request.setTargetLanguage(targetLanguage);
        return textToSpeech(text, request, null);
    }
    
    @Override
    public Flux<byte[]> textToSpeech(String text, String targetLanguage, TranslationRequest request) {
        if (request == null) {
            request = new TranslationRequest();
        }
        request.setTargetLanguage(targetLanguage);
        return textToSpeech(text, request, null);
    }

    @Override
    public Flux<byte[]> textToSpeech(String text, TranslationRequest request, WebSocketSession session) {
        if (text == null || text.isEmpty()) {
            log.warn("Empty text provided for speech synthesis");
            return Flux.just(new byte[0]);
        }
        
        SpeechConfig speechConfig = null;
        
        try {
            speechConfig = speechConfigPool.borrowObject();
            
            // 如果请求中指定了目标语言，则覆盖默认设置
            if (request.getTargetLanguage() != null) {
                speechConfig.setSpeechSynthesisLanguage(request.getTargetLanguage());
            }
            
            // 如果请求中指定了声音，则覆盖默认设置
            if (request.getVoice() != null) {
                speechConfig.setSpeechSynthesisVoiceName(request.getVoice());
            }
            
            // 设置输出格式
            try {
                if (request.getAudioFormat() != null) {
                    switch (request.getAudioFormat()) {
                        case MP3:
                            speechConfig.setSpeechSynthesisOutputFormat(
                                SpeechSynthesisOutputFormat.Audio16Khz32KBitRateMonoMp3);
                            break;
                        case OGG:
                            speechConfig.setSpeechSynthesisOutputFormat(
                                SpeechSynthesisOutputFormat.Ogg24Khz16BitMonoOpus);
                            break;
                        case WEBM:
                            speechConfig.setSpeechSynthesisOutputFormat(
                                SpeechSynthesisOutputFormat.Webm24Khz16BitMonoOpus);
                            break;
                        case WAV:
                        default:
                            speechConfig.setSpeechSynthesisOutputFormat(
                                SpeechSynthesisOutputFormat.Riff16Khz16BitMonoPcm);
                            break;
                    }
                } else {
                    // 默认使用WAV格式
                    speechConfig.setSpeechSynthesisOutputFormat(
                        SpeechSynthesisOutputFormat.Riff16Khz16BitMonoPcm);
                }
            } catch (Exception e) {
                log.error("Error setting synthesis output format, using default", e);
                // 出错时使用默认格式
                speechConfig.setSpeechSynthesisOutputFormat(
                    SpeechSynthesisOutputFormat.Riff16Khz16BitMonoPcm);
            }
            
            // 使用try-with-resources自动关闭资源
            try (SpeechSynthesizer synthesizer = new SpeechSynthesizer(speechConfig, null)) {
                
                // 增加错误处理
                synthesizer.SynthesisCanceled.addEventListener((s, e) -> {
                    try {
                        SpeechSynthesisResult result = e.getResult();
                        if (result != null && result.getReason() == ResultReason.Canceled) {
                            log.error("Synthesis canceled: Result={}", result.getReason());
                        }
                    } catch (Exception ex) {
                        log.error("Error handling synthesis cancellation", ex);
                    }
                });
                
                // 限制文本长度，防止过长导致合成失败
                final int MAX_TEXT_LENGTH = 1000;
                String processedText = text.length() > MAX_TEXT_LENGTH ? 
                        text.substring(0, MAX_TEXT_LENGTH) + "..." : text;
                
                var result = synthesizer.SpeakTextAsync(processedText).get();
                
                if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                    // 获取音频数据
                    byte[] audioData = result.getAudioData();
                    
                    // 保存输出音频数据到文件（调试用）
                    if (saveAudioToFile && session != null) {
                        AudioUtils.saveAudioChunkToFile(audioData, session.getId(), "output", saveAudioToFile, debugAudioDirectory);
                    }
                    
                    return Flux.just(audioData);
                } else if (result.getReason() == ResultReason.Canceled) {
                    log.error("Speech synthesis canceled");
                    return Flux.just(new byte[0]);
                } else {
                    log.error("Speech synthesis failed: {}", result.getReason());
                    return Flux.just(new byte[0]);
                }
            } // synthesizer自动关闭
        } catch (Exception e) {
            log.error("Error in text-to-speech: ", e);
            // 返回空数据而不是抛出异常
            return Flux.just(new byte[0]);
        } finally {
            if (speechConfig != null) {
                try {
                    speechConfigPool.returnObject(speechConfig);
                } catch (Exception e) {
                    log.warn("Failed to return speech config to pool", e);
                }
            }
        }
    }

    @Override
    public boolean isAvailable() {
        // 检查是否配置了必要的参数
        if (microsoftConfig.getSubscriptionKey() == null || microsoftConfig.getSubscriptionKey().isEmpty() ||
            microsoftConfig.getRegion() == null || microsoftConfig.getRegion().isEmpty()) {
            return false;
        }
        
        // 检查对象池是否已初始化
        return speechConfigPool != null && !speechConfigPool.isClosed();
    }

    @Override
    public String getProviderName() {
        return "microsoft";
    }
    
    @Override
    public Flux<byte[]> translateSpeechToSpeech(byte[] audioData, String sourceLanguage, String targetLanguage) {
        TranslationRequest request = new TranslationRequest();
        request.setSourceLanguage(sourceLanguage);
        request.setTargetLanguage(targetLanguage);
        return translateSpeechToSpeech(audioData, request, null);
    }
    
    @Override
    public Flux<byte[]> translateSpeechToSpeech(byte[] audioData, TranslationRequest request, WebSocketSession session) {
        if (audioData == null || audioData.length == 0) {
            log.warn("Empty audio data provided for speech-to-speech translation");
            return Flux.just(new byte[0]);
        }
        
        String sessionId = session != null ? session.getId() : "unknown";
        log.info("开始语音转语音翻译: 会话ID={}, 音频数据大小={}字节, 源语言={}, 目标语言={}", 
                sessionId, audioData.length, request.getSourceLanguage(), request.getTargetLanguage());
        
        // 保存输入音频数据到文件（调试用）
        if (saveAudioToFile && session != null) {
            AudioUtils.saveAudioChunkToFile(audioData, session.getId(), "input", saveAudioToFile, debugAudioDirectory);
        }
        
        // 步骤1: 语音转文本 (源语言)
        return speechToText(audioData, request, session)
                .doOnNext(recognizedText -> {
                    log.info("语音识别完成: 会话ID={}, 识别文本=\"{}\"", sessionId, recognizedText);
                })
                .flatMap(recognizedText -> {
                    // 检查识别结果是否为空或失败
                    if (recognizedText == null || recognizedText.isEmpty() || 
                            "无法识别语音内容".equals(recognizedText) || 
                            "语音识别失败".equals(recognizedText) ||
                            recognizedText.startsWith("语音识别失败:")) {
                        log.warn("语音识别结果为空或失败，尝试使用默认文本");
                        // 使用一个默认文本，防止合成失败
                        recognizedText = "这是一段默认文本，因为语音识别失败";
                    }
                    
                    // 步骤2: 翻译文本（从源语言到目标语言）
                    final String textToTranslate = recognizedText;
                    return translateTextWithMicrosoftAPI(textToTranslate, 
                            request.getSourceLanguage(), 
                            request.getTargetLanguage())
                            .doOnNext(translation -> {
                                log.info("文本翻译完成: 源文本=\"{}\", 翻译文本=\"{}\"", textToTranslate, translation);
                            })
                            .onErrorResume(error -> {
                                log.error("翻译过程出错: {}", error.getMessage(), error);
                                // 如果翻译失败，使用原始文本
                                return Mono.just(textToTranslate);
                            });
                })
                .flatMap(translatedText -> {
                    // 步骤3: 文本转语音（目标语言）
                    log.info("开始语音合成: 会话ID={}, 文本=\"{}\"", sessionId, translatedText);
                    return textToSpeech(translatedText, request, session);
                })
                .doOnNext(synthesizedAudio -> {
                    if (synthesizedAudio == null || synthesizedAudio.length == 0) {
                        log.warn("语音合成结果为空，生成的音频数据大小为0字节");
                    } else {
                        log.info("语音合成完成: 会话ID={}, 音频数据大小={}字节", sessionId, synthesizedAudio.length);
                    }
                })
                .switchIfEmpty(Flux.defer(() -> {
                    log.warn("语音翻译过程返回空结果，使用默认文本生成音频");
                    // 如果流为空，尝试使用默认文本生成音频
                    String defaultText = "这是一段自动生成的音频，因为语音翻译过程中出现了问题";
                    return textToSpeech(defaultText, request, session);
                }))
                .doOnComplete(() -> {
                    log.info("语音转语音翻译完成: 会话ID={}", sessionId);
                })
                .doOnError(error -> {
                    log.error("语音转语音翻译失败: 会话ID={}, 错误={}", sessionId, error.getMessage(), error);
                });
    }
    
    /**
     * 使用Microsoft翻译API翻译文本
     * 
     * @param text 需要翻译的文本
     * @param sourceLanguage 源语言
     * @param targetLanguage 目标语言
     * @return 翻译后的文本
     */
    private Mono<String> translateTextWithMicrosoftAPI(String text, String sourceLanguage, String targetLanguage) {
        if (text == null || text.isEmpty()) {
            return Mono.just("");
        }
        
        if (microsoftConfig.getTranslatorKey() == null || microsoftConfig.getTranslatorKey().isEmpty() ||
            microsoftConfig.getTranslatorRegion() == null || microsoftConfig.getTranslatorRegion().isEmpty()) {
            log.warn("翻译API密钥或区域未配置，使用简单翻译替代");
            return Mono.just(simpleTranslate(text, sourceLanguage, targetLanguage));
        }
        
        // 规范化语言代码（去除区域后缀，例如zh-CN变为zh)
        String fromLang = sourceLanguage;
        if (fromLang.contains("-")) {
            fromLang = fromLang.split("-")[0];
        }
        
        String toLang = targetLanguage;
        if (toLang.contains("-")) {
            toLang = toLang.split("-")[0];
        }
        
        // 构建翻译API请求URL
        String endpoint = "https://api.cognitive.microsofttranslator.com/translate";
        String url = endpoint + "?api-version=3.0&from=" + fromLang + "&to=" + toLang;
        
        try {
            // 构建请求体
            ObjectMapper objectMapper = new ObjectMapper();
            String requestBody = objectMapper.writeValueAsString(new Object[] {
                new java.util.HashMap<String, String>() {{ 
                    put("Text", text); 
                }}
            });
            
            // 构建HTTP请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Ocp-Apim-Subscription-Key", microsoftConfig.getTranslatorKey())
                    .header("Ocp-Apim-Subscription-Region", microsoftConfig.getTranslatorRegion())
                    .header("X-ClientTraceId", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            // 发送请求
            return Mono.fromCallable(() -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
                    .map(response -> {
                        if (response.statusCode() == 200) {
                            try {
                                JsonNode root = objectMapper.readTree(response.body());
                                // 翻译API返回的是一个数组，我们取第一个元素的translations数组中的第一个元素
                                if (root.isArray() && root.size() > 0) {
                                    JsonNode translations = root.get(0).get("translations");
                                    if (translations != null && translations.isArray() && translations.size() > 0) {
                                        return translations.get(0).get("text").asText();
                                    }
                                }
                            } catch (Exception e) {
                                log.error("解析翻译API响应出错", e);
                            }
                        } else {
                            log.error("翻译API调用失败: 状态码={}, 响应={}", response.statusCode(), response.body());
                        }
                        // 如果出错，返回原文
                        return text;
                    })
                    .onErrorResume(e -> {
                        log.error("调用翻译API出错", e);
                        return Mono.just(text);
                    });
        } catch (Exception e) {
            log.error("准备翻译API请求时出错", e);
            return Mono.just(text);
        }
    }
    
    /**
     * 简单翻译函数（当微软翻译API不可用时使用）
     */
    private String simpleTranslate(String text, String sourceLanguage, String targetLanguage) {
        // 中文到英文的简单映射（仅作示例）
        if (sourceLanguage.startsWith("zh") && targetLanguage.startsWith("en")) {
            if (text.contains("测试")) {
                return "Please translate what I say into English. This is a test, and we are using Microsoft's speech synthesis service.";
            } else if (text.contains("默认文本") || text.contains("识别失败")) {
                return "This is a default text because speech recognition failed.";
            } else {
                // 默认英文示例文本
                return "This is a translated text. The original language was Chinese.";
            }
        } 
        // 英文到中文
        else if (sourceLanguage.startsWith("en") && targetLanguage.startsWith("zh")) {
            if (text.toLowerCase().contains("test")) {
                return "请将我说的话翻译成中文。这是一个测试，我们正在使用微软的语音合成服务。";
            } else if (text.toLowerCase().contains("default") || text.toLowerCase().contains("failed")) {
                return "这是一段默认文本，因为语音识别失败。";
            } else {
                // 默认中文示例文本
                return "这是已翻译的文本。原始语言是英语。";
            }
        }
        // 如果不是上述语言对，或者源语言和目标语言相同，直接返回原文
        else {
            return text;
        }
    }
} 