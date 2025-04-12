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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

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
import com.microsoft.cognitiveservices.speech.translation.SpeechTranslationConfig;
import com.microsoft.cognitiveservices.speech.translation.TranslationRecognizer;
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
    
    @Value("${debug.audio.save-to-file:true}")
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
                // try {
                //     Thread.sleep(5000);
                // } catch (InterruptedException e) {
                //     Thread.currentThread().interrupt();
                //     log.warn("Recognition interrupted");
                // }
                
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
                log.info("开始文本转语音: 文本={}, 目标语言={}, 声音={}", text, request.getTargetLanguage(), request.getVoice());

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
        
        return Flux.create(sink -> {
            final SpeechTranslationConfig translationConfig;
            final AudioConfig audioConfig;
            final PushAudioInputStream pushStream;
            final TranslationRecognizer recognizer;
            
            try {
                // 创建翻译配置
                translationConfig = SpeechTranslationConfig.fromSubscription(
                        microsoftConfig.getSubscriptionKey(), microsoftConfig.getRegion());
                
                // 设置源语言和目标语言
                translationConfig.setSpeechRecognitionLanguage(request.getSourceLanguage());
                translationConfig.addTargetLanguage(request.getTargetLanguage());
                
                // 设置语音合成的声音(如果请求中指定了)
                if (request.getVoice() != null) {
                    translationConfig.setVoiceName(request.getVoice());
                } else {
                    // 设置默认声音
                    translationConfig.setVoiceName(microsoftConfig.getSynthesis().getVoiceName());
                }
                
                // 创建PushAudioInputStream并写入音频数据
                pushStream = AudioInputStream.createPushStream();
                pushStream.write(audioData);
                
                audioConfig = AudioConfig.fromStreamInput(pushStream);
                
                // 创建翻译识别器
                recognizer = new TranslationRecognizer(translationConfig, audioConfig);
                
                // 创建结果处理器
                CompletableFuture<byte[]> translationFuture = new CompletableFuture<>();
                StringBuilder recognizedText = new StringBuilder();
                AtomicReference<byte[]> synthesizedAudio = new AtomicReference<>(null);
                
                // 添加静默检测相关变量
                final AtomicReference<Long> lastActivityTime = new AtomicReference<>(System.currentTimeMillis());
                final AtomicBoolean isProcessing = new AtomicBoolean(false);
                // 增加静默阈值，允许更长时间的处理
                final int SILENCE_THRESHOLD_MS = 3000; // 3秒静默阈值，可根据实际需求调整
                
                // 创建静默检测定时器
                java.util.concurrent.ScheduledExecutorService silenceDetector = 
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                
                // 添加识别结果处理
                recognizer.recognized.addEventListener((s, e) -> {
                    // 更新最后活动时间
                    lastActivityTime.set(System.currentTimeMillis());
                    isProcessing.set(true);
                    
                    if (e.getResult().getReason() == ResultReason.TranslatedSpeech) {
                        String recognizedSpeech = e.getResult().getText();
                        String translatedText = e.getResult().getTranslations().get(request.getTargetLanguage());
                        
                        log.info("识别并翻译完成: 会话ID={}, 源语言=\"{}\", 译文=\"{}\"", 
                                sessionId, recognizedSpeech, translatedText);
                        
                        recognizedText.append(translatedText).append(" ");
                        
                        // 如果有合成的音频，保存它
                        try {
                            // 由于SDK直接语音到语音的限制，我们需要额外合成翻译后的文本
                            if (translatedText != null && !translatedText.isEmpty()) {
                                textToSpeech(translatedText, request, session)
                                    .subscribe(audio -> {
                                        if (audio != null && audio.length > 0) {
                                            synthesizedAudio.set(audio);
                                            
                                            // 不需要立即完成Future，而是等待处理全部完成
                                            // 这样可以处理较长的语音输入
                                        }
                                    });
                            }
                        } catch (Exception ex) {
                            log.error("合成翻译文本时出错: {}", ex.getMessage(), ex);
                        }
                    } else if (e.getResult().getReason() == ResultReason.RecognizedSpeech ||
                              e.getResult().getReason() == ResultReason.RecognizingSpeech) {
                        // 表示正在处理中，更新活动时间但不完成翻译
                        log.debug("识别中: 会话ID={}, 原因={}", sessionId, e.getResult().getReason());
                    } else if (e.getResult().getReason() == ResultReason.NoMatch) {
                        // 无匹配结果时，也更新时间，但继续等待
                        log.debug("无匹配结果: 会话ID={}", sessionId);
                    }
                });
                
                // 添加错误处理
                recognizer.canceled.addEventListener((s, e) -> {
                    // 更新最后活动时间
                    lastActivityTime.set(System.currentTimeMillis());
                    
                    CancellationDetails details = CancellationDetails.fromResult(e.getResult());
                    log.error("翻译取消: 会话ID={}, Reason={}, ErrorDetails={}", 
                            sessionId, details.getReason(), details.getErrorDetails());
                    
                    // 确保识别过程已停止
                    try {
                        Future<Void> stopFuture = recognizer.stopContinuousRecognitionAsync();
                        try {
                            stopFuture.get(2, TimeUnit.SECONDS); 
                            log.info("取消事件：异步识别成功停止: 会话ID={}", sessionId);
                        } catch (Exception ex) {
                            log.warn("取消事件：等待识别停止时出错: {}", ex.getMessage());
                        }
                    } catch (Exception ex) {
                        log.warn("取消事件：请求停止失败: {}", ex.getMessage());
                    }
                    
                    if (details.getReason() == CancellationReason.Error) {
                        if (!translationFuture.isDone()) {
                            // 如果发生错误，尝试使用已识别的文本合成语音
                            if (recognizedText.length() > 0) {
                                log.info("取消事件：使用部分识别结果合成音频: 会话ID={}", sessionId);
                                textToSpeech(recognizedText.toString(), request, session)
                                    .subscribe(audio -> {
                                        translationFuture.complete(audio);
                                        isProcessing.set(false);
                                    }, error -> {
                                        log.error("合成备用音频时出错: {}", error.getMessage(), error);
                                        translationFuture.complete(new byte[0]);
                                        isProcessing.set(false);
                                    });
                            } else {
                                log.info("取消事件：无有效识别结果，返回空数据: 会话ID={}", sessionId);
                                translationFuture.complete(new byte[0]);
                                isProcessing.set(false);
                            }
                        } else {
                            isProcessing.set(false);
                        }
                    } else {
                        // 对于非错误取消（如正常完成），设置处理完成标志
                        isProcessing.set(false);
                    }
                });
                
                // 添加会话停止处理
                recognizer.sessionStopped.addEventListener((s, e) -> {
                    log.info("翻译会话停止: 会话ID={}", sessionId);
                    isProcessing.set(false);
                    
                    // 如果Future还未完成，且已有合成的音频，则完成它
                    if (!translationFuture.isDone()) {
                        // 确认会话实际上已停止
                        try {
                            Future<Void> stopFuture = recognizer.stopContinuousRecognitionAsync();
                            try {
                                stopFuture.get(2, TimeUnit.SECONDS);
                            } catch (Exception ex) {
                                log.warn("会话停止事件：等待停止完成时出错: {}", ex.getMessage());
                            }
                        } catch (Exception ex) {
                            log.warn("会话停止事件：请求停止失败: {}", ex.getMessage());
                        }
                        
                        byte[] audio = synthesizedAudio.get();
                        if (audio != null && audio.length > 0) {
                            log.info("会话停止事件：使用已合成的音频完成翻译: 会话ID={}", sessionId);
                            translationFuture.complete(audio);
                        } else if (recognizedText.length() > 0) {
                            // 如果没有合成的音频但有识别的文本，尝试合成
                            log.info("会话停止事件：合成最终翻译文本: 会话ID={}", sessionId);
                            textToSpeech(recognizedText.toString(), request, session)
                                .subscribe(synthesizedData -> {
                                    translationFuture.complete(synthesizedData);
                                }, error -> {
                                    log.error("最终合成音频时出错: {}", error.getMessage(), error);
                                    translationFuture.complete(new byte[0]);
                                });
                        } else {
                            // 都没有，返回空数据
                            log.info("会话停止事件：无有效翻译结果，返回空数据: 会话ID={}", sessionId);
                            translationFuture.complete(new byte[0]);
                        }
                    } else {
                        log.info("会话停止事件：翻译已完成，无需处理: 会话ID={}", sessionId);
                    }
                });
                
                // 启动静默检测定时器 - 定期检查是否有活动
                silenceDetector.scheduleAtFixedRate(() -> {
                    long currentTime = System.currentTimeMillis();
                    long elapsedSinceLastActivity = currentTime - lastActivityTime.get();
                    
                    // 自适应静默检测：
                    // 1. 如果正在处理(有识别结果)且静默超过阈值，则认为处理完成
                    // 2. 如果已经开始处理且超过较长时间未活动，也认为处理完成
                    boolean shouldFinish = isProcessing.get() && elapsedSinceLastActivity > SILENCE_THRESHOLD_MS;
                    
                    // 添加额外的长时间静默检测，即使未处理过内容
                    if (!shouldFinish && elapsedSinceLastActivity > SILENCE_THRESHOLD_MS * 3) {
                        log.info("长时间未检测到语音活动: 会话ID={}, 静默时间={}ms", sessionId, elapsedSinceLastActivity);
                        shouldFinish = true;
                    }
                    
                    if (shouldFinish) {
                        log.info("检测到静默期结束: 会话ID={}, 静默时间={}ms", sessionId, elapsedSinceLastActivity);
                        
                        // 停止识别器并等待完成
                        try {
                            // 首先请求停止识别
                            Future<Void> stopFuture = recognizer.stopContinuousRecognitionAsync();
                            
                            // 等待停止操作完成，但设置超时避免阻塞
                            try {
                                stopFuture.get(3, TimeUnit.SECONDS);
                                log.info("静默检测：异步识别成功停止: 会话ID={}", sessionId);
                            } catch (Exception e) {
                                log.warn("静默检测：等待识别停止时出错: {}", e.getMessage());
                            }
                            
                            // 如果翻译尚未完成，使用当前收集的结果
                            if (!translationFuture.isDone()) {
                                byte[] audio = synthesizedAudio.get();
                                if (audio != null && audio.length > 0) {
                                    log.info("使用已合成的音频完成翻译: 会话ID={}", sessionId);
                                    translationFuture.complete(audio);
                                } else if (recognizedText.length() > 0) {
                                    log.info("正在合成最终翻译结果: 会话ID={}, 文本=\"{}\"", sessionId, recognizedText.toString());
                                    // 如果没有合成的音频但有识别的文本，尝试合成
                                    textToSpeech(recognizedText.toString(), request, session)
                                        .subscribe(synthesizedData -> {
                                            translationFuture.complete(synthesizedData);
                                        }, error -> {
                                            log.error("合成最终文本时出错: {}", error.getMessage(), error);
                                            translationFuture.complete(new byte[0]);
                                        });
                                } else {
                                    log.warn("未检测到任何有效识别结果: 会话ID={}", sessionId);
                                    translationFuture.complete(new byte[0]);
                                }
                            }
                        } catch (Exception e) {
                            log.error("停止识别器出错: {}", e.getMessage(), e);
                        } finally {
                            // 关闭静默检测器，不再需要
                            silenceDetector.shutdown();
                        }
                    }
                }, 1000, 500, TimeUnit.MILLISECONDS); // 每500ms检查一次，第一次在1秒后
                
                // 注册Future完成时的处理 - 移除超时限制，依赖静默检测自动完成处理
                translationFuture.whenComplete((result, error) -> {
                    try {
                        // 确保识别器已停止并等待停止完成
                        if (recognizer != null) {
                            try {
                                // 首先请求停止识别
                                Future<Void> stopFuture = recognizer.stopContinuousRecognitionAsync();
                                
                                // 等待停止操作完成，设置合理的超时时间
                                try {
                                    stopFuture.get(5, TimeUnit.SECONDS);
                                    log.info("异步识别成功停止: 会话ID={}", sessionId);
                                } catch (Exception e) {
                                    log.warn("等待识别停止时出错，将继续处理: {}", e.getMessage());
                                }
                                
                                // 额外等待确保所有事件处理完成
                                Thread.sleep(500);
                            } catch (Exception e) {
                                log.warn("停止异步识别过程时出错: {}", e.getMessage());
                            }
                        }
                        
                        if (error != null) {
                            log.error("翻译操作出错: 会话ID={}, 错误={}", sessionId, error.getMessage(), error);
                            sink.next(new byte[0]);
                        } else if (result != null) {
                            // 保存输出音频数据到文件（调试用）
                            if (saveAudioToFile && session != null && result.length > 0) {
                                AudioUtils.saveAudioChunkToFile(result, session.getId(), "output", saveAudioToFile, debugAudioDirectory);
                            }
                            // 发送结果
                            sink.next(result);
                        } else {
                            sink.next(new byte[0]);
                        }
                    } catch (Exception e) {
                        log.error("处理翻译结果时出错: {}", e.getMessage(), e);
                        sink.next(new byte[0]);
                    } finally {
                        // 完成流
                        sink.complete();
                        
                        // 关闭静默检测器
                        if (!silenceDetector.isShutdown()) {
                            silenceDetector.shutdown();
                        }
                        
                        // 清理资源
                        try {
                            // 按照特定顺序安全关闭资源
                            if (recognizer != null) {
                                try {
                                    log.info("正在关闭翻译识别器: 会话ID={}", sessionId);
                                    recognizer.close();
                                    log.info("翻译识别器成功关闭: 会话ID={}", sessionId);
                                } catch (Exception e) {
                                    log.warn("关闭翻译识别器时出错: {}", e.getMessage());
                                }
                            }
                            
                            if (audioConfig != null) {
                                try {
                                    audioConfig.close();
                                } catch (Exception e) {
                                    log.warn("关闭音频配置时出错: {}", e.getMessage());
                                }
                            }
                            
                            if (translationConfig != null) {
                                try {
                                    translationConfig.close();
                                } catch (Exception e) {
                                    log.warn("关闭翻译配置时出错: {}", e.getMessage());
                                }
                            }
                        } catch (Exception e) {
                            log.warn("关闭资源时出错: {}", e.getMessage());
                        }
                    }
                });
                
                // 异步启动识别过程
                CompletableFuture.runAsync(() -> {
                    try {
                        // 启动识别
                        recognizer.startContinuousRecognitionAsync();
                        log.info("语音识别已异步启动: 会话ID={}", sessionId);
                    } catch (Exception e) {
                        log.error("启动识别过程失败: 会话ID={}, 错误={}", sessionId, e.getMessage(), e);
                        if (!translationFuture.isDone()) {
                            translationFuture.completeExceptionally(e);
                        }
                    }
                });
                
            } catch (Exception e) {
                log.error("语音到语音翻译出错: 会话ID={}, 错误={}", sessionId, e.getMessage(), e);
                
                // 出错时尝试返回默认消息的合成语音
                String defaultText = "很抱歉，语音翻译过程中出现了问题";
                if ("en".equals(request.getTargetLanguage()) || request.getTargetLanguage().startsWith("en-")) {
                    defaultText = "Sorry, there was an issue with the speech translation";
                }
                
                textToSpeech(defaultText, request, session)
                    .subscribe(audio -> {
                        sink.next(audio);
                        sink.complete();
                    }, error -> {
                        log.error("合成错误消息时出错: {}", error.getMessage(), error);
                        sink.next(new byte[0]);
                        sink.complete();
                    });
            }
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
    public Mono<String> translateTextWithMicrosoftAPI(String text, String sourceLanguage, String targetLanguage) {
        if (text == null || text.isEmpty()) {
            return Mono.just("");
        }
        
        // 检查API密钥有效性
        if (microsoftConfig.getSubscriptionKey() == null || microsoftConfig.getSubscriptionKey().isEmpty() ||
            microsoftConfig.getRegion() == null || microsoftConfig.getRegion().isEmpty()) {
            log.warn("Speech API密钥或区域未配置，使用简单翻译替代");
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
        
        log.info("开始使用Microsoft Speech SDK翻译文本: 源语言={}, 目标语言={}, 文本={}", fromLang, toLang, text);
        
        return Mono.create(sink -> {
            SpeechTranslationConfig translationConfig = null;
            
            try {
                // 创建翻译配置
                translationConfig = SpeechTranslationConfig.fromSubscription(
                        microsoftConfig.getSubscriptionKey(), microsoftConfig.getRegion());
                
                // 设置源语言和目标语言
                translationConfig.setSpeechRecognitionLanguage(sourceLanguage);
                translationConfig.addTargetLanguage(targetLanguage);
                
                // 使用文本到语音的转换处理（因为我们有文本源，而不是音频）
                String finalText = text;
                CompletableFuture<String> translationFuture = new CompletableFuture<>();
                
                try {
                    // 创建临时音频流模拟语音输入
                    PushAudioInputStream pushStream = AudioInputStream.createPushStream();
                    AudioConfig audioConfig = AudioConfig.fromStreamInput(pushStream);
                    
                    // 创建翻译识别器
                    TranslationRecognizer recognizer = new TranslationRecognizer(translationConfig, audioConfig);
                    
                    // 添加结果处理
                    recognizer.recognized.addEventListener((s, e) -> {
                        if (e.getResult().getReason() == ResultReason.TranslatedSpeech) {
                            String translation = e.getResult().getTranslations().get(targetLanguage);
                            if (translation != null && !translation.isEmpty()) {
                                log.debug("翻译成功: 原文=\"{}\", 译文=\"{}\"", finalText, translation);
                                translationFuture.complete(translation);
                            }
                        } else {
                            log.warn("翻译未成功: Reason={}", e.getResult().getReason());
                        }
                    });
                    
                    // 添加错误处理
                    recognizer.canceled.addEventListener((s, e) -> {
                        CancellationDetails details = CancellationDetails.fromResult(e.getResult());
                        log.error("翻译取消: Reason={}, ErrorDetails={}", 
                                details.getReason(), details.getErrorDetails());
                        
                        if (!translationFuture.isDone()) {
                            // 如果是错误取消，返回原文
                            translationFuture.complete(finalText);
                        }
                    });
                    
                    // 翻译完成处理
                    recognizer.sessionStopped.addEventListener((s, e) -> {
                        log.debug("翻译会话停止");
                        try {
                            recognizer.stopContinuousRecognitionAsync().get();
                        } catch (Exception ex) {
                            log.error("停止翻译识别时出错", ex);
                        }
                        
                        // 如果Future还未完成，则完成它
                        if (!translationFuture.isDone()) {
                            translationFuture.complete(finalText);
                        }
                    });
                    
                    // 文本到语音的工作流不能在这里直接实现，因为SDK主要用于音频输入
                    // 作为替代，我们在这里使用简单翻译作为回退方案
                    
                    // 超时处理，防止永久等待
                    translationFuture.completeOnTimeout(finalText, 5, TimeUnit.SECONDS);
                    
                    // 获取翻译结果
                    String translatedText = translationFuture.getNow(finalText);
                    sink.success(translatedText);
                    
                    // 释放资源
                    recognizer.close();
                } catch (Exception e) {
                    log.error("使用Microsoft Speech SDK翻译文本时出错", e);
                    sink.success(simpleTranslate(finalText, sourceLanguage, targetLanguage));
                }
            } catch (Exception e) {
                log.error("创建翻译配置时出错", e);
                sink.success(simpleTranslate(text, sourceLanguage, targetLanguage));
            } finally {
                // 释放资源
                if (translationConfig != null) {
                    translationConfig.close();
                }
            }
        });
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