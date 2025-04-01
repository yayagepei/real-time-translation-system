package com.translation.system.service.impl;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import com.microsoft.cognitiveservices.speech.AudioConfig;
import com.microsoft.cognitiveservices.speech.AudioInputStream;
import com.microsoft.cognitiveservices.speech.AutoDetectSourceLanguageResult;
import com.microsoft.cognitiveservices.speech.CancellationDetails;
import com.microsoft.cognitiveservices.speech.CancellationReason;
import com.microsoft.cognitiveservices.speech.PushAudioInputStream;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat;
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer;
import com.microsoft.cognitiveservices.speech.audio.AudioOutputStream;
import com.microsoft.cognitiveservices.speech.audio.PullAudioOutputStream;
import com.translation.system.config.PoolConfig;
import com.translation.system.config.SpeechConfig.Microsoft;
import com.translation.system.model.TranslationRequest;
import com.translation.system.service.SpeechService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    
    // 会话音频文件映射表
    private final Map<String, String> sessionAudioFileMap = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        // 初始化SpeechConfig对象池
        speechConfigPool = new GenericObjectPool<>(new BasePooledObjectFactory<SpeechConfig>() {
            @Override
            public SpeechConfig create() {
                SpeechConfig config = SpeechConfig.fromSubscription(
                        microsoftConfig.getSubscriptionKey(), 
                        microsoftConfig.getRegion());
                try {
                    // 设置默认识别语言
                    config.setSpeechRecognitionLanguage(microsoftConfig.getRecognition().getLanguage());
                    // 设置默认合成语言
                    config.setSpeechSynthesisLanguage(microsoftConfig.getSynthesis().getLanguage());
                    // 设置默认合成声音
                    config.setSpeechSynthesisVoiceName(microsoftConfig.getSynthesis().getVoiceName());
                    // 设置日志级别 (1.43.0新增)
                    config.setProperty("SPEECH-LogLevel", "1"); // 仅记录错误
                } catch (Exception e) {
                    log.error("Error configuring SpeechConfig", e);
                }
                return config;
            }

            @Override
            public PooledObject<SpeechConfig> wrap(SpeechConfig config) {
                return new DefaultPooledObject<>(config);
            }
            
            @Override
            public void destroyObject(PooledObject<SpeechConfig> p) {
                try {
                    // 1.43.0版本中建议显式关闭资源
                    p.getObject().close();
                } catch (Exception e) {
                    log.warn("Error closing SpeechConfig", e);
                }
            }
        }, poolConfig);
        
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
        
        // 清空会话音频文件映射
        sessionAudioFileMap.clear();
    }

    @Override
    public Flux<String> speechToText(byte[] audioData, TranslationRequest request, WebSocketSession session) {
        return Mono.fromCallable(() -> {
            SpeechConfig speechConfig = null;
            SpeechRecognizer recognizer = null;
            PushAudioInputStream pushStream = null;
            AudioConfig audioConfig = null;
            
            // 保存输入音频数据到文件（调试用）
            if (saveAudioToFile && session != null) {
                saveAudioChunkToFile(audioData, session.getId(), "input");
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
                pushStream.close();
                
                audioConfig = AudioConfig.fromStreamInput(pushStream);
                
                // 创建识别器
                recognizer = new SpeechRecognizer(speechConfig, audioConfig);
                
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
                
                // 添加错误处理 (1.43.0中增强的错误处理)
                recognizer.canceled.addEventListener((s, e) -> {
                    CancellationDetails details = CancellationDetails.fromResult(e.getResult());
                    log.error("CANCELED: Reason={}, ErrorDetails={}", 
                            details.getReason(), details.getErrorDetails());
                    
                    if (details.getReason() == CancellationReason.Error) {
                        future.completeExceptionally(new RuntimeException(
                                "Speech recognition canceled: " + details.getErrorDetails()));
                    } else {
                        // 即使是非错误取消，也确保Future得到完成
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
                
                try {
                    recognizer.stopContinuousRecognitionAsync().get();
                } catch (Exception e) {
                    log.error("Failed to stop speech recognition", e);
                    // 即使停止失败，我们仍然尝试获取已识别的文本
                }
                
                String result = future.getNow("");
                // 如果结果为空，提供一个默认消息
                if (result == null || result.trim().isEmpty()) {
                    log.warn("Recognition produced no result, using fallback message");
                    result = "无法识别语音内容";
                }
                return result;
            } catch (Exception e) {
                log.error("Error in speech-to-text: ", e);
                // 返回错误消息而不是抛出异常，以便系统可以继续运行
                return "语音识别失败: " + e.getMessage();
            } finally {
                // 1.43.0中建议显式关闭资源
                if (recognizer != null) {
                    try {
                        recognizer.close();
                    } catch (Exception e) {
                        log.warn("Failed to close recognizer", e);
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
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(text -> {
            // 将结果切分为句子以便流式处理
            if (text == null || text.isEmpty()) {
                return Flux.empty();
            }
            
            String[] sentences = text.split("[.!?。！？]");
            return Flux.fromArray(sentences)
                       .filter(s -> !s.trim().isEmpty())
                       .map(s -> s.trim() + "。"); // 添加句号
        })
        .onErrorResume(e -> {
            log.error("Error processing speech to text", e);
            return Flux.just("处理语音时发生错误: " + e.getMessage());
        });
    }

    @Override
    public Flux<byte[]> textToSpeech(String text, TranslationRequest request, WebSocketSession session) {
        if (text == null || text.isEmpty()) {
            log.warn("Empty text provided for speech synthesis");
            return Flux.empty();
        }
        
        return Mono.fromCallable(() -> {
            SpeechConfig speechConfig = null;
            SpeechSynthesizer synthesizer = null;
            PullAudioOutputStream stream = null;
            
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
                SpeechSynthesisOutputFormat format;
                try {
                    switch (request.getAudioFormat()) {
                        case MP3:
                            format = SpeechSynthesisOutputFormat.Audio16Khz32KBitRateMonoMp3;
                            break;
                        case OGG:
                            format = SpeechSynthesisOutputFormat.Ogg16Khz16KbpsMonoOpus;
                            break;
                        case WEBM:
                            format = SpeechSynthesisOutputFormat.Webm16Khz16KbpsMonoOpus;
                            break;
                        case WAV:
                        default:
                            format = SpeechSynthesisOutputFormat.Riff16Khz16BitMonoPcm;
                            break;
                    }
                    speechConfig.setSpeechSynthesisOutputFormat(format);
                } catch (Exception e) {
                    log.error("Error setting synthesis output format, using default", e);
                    // 出错时使用默认格式
                    speechConfig.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Riff16Khz16BitMonoPcm);
                }
                
                // 创建合成器
                synthesizer = new SpeechSynthesizer(speechConfig, null);
                
                // 增加错误处理 (1.43.0增强的错误处理)
                synthesizer.SynthesisCanceled.addEventListener((s, e) -> {
                    try {
                        CancellationDetails details = CancellationDetails.fromResult(e.getResult());
                        log.error("Synthesis canceled: Reason={}, ErrorDetails={}", 
                                details.getReason(), details.getErrorDetails());
                    } catch (Exception ex) {
                        log.error("Error getting cancellation details", ex);
                    }
                });
                
                // 限制文本长度，防止过长导致合成失败
                final int MAX_TEXT_LENGTH = 1000;
                String processedText = text.length() > MAX_TEXT_LENGTH ? 
                        text.substring(0, MAX_TEXT_LENGTH) + "..." : text;
                
                var result = synthesizer.SpeakTextAsync(processedText).get();
                
                if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                    // 获取音频数据
                    AudioOutputStream audioOutputStream = AudioOutputStream.fromResult(result);
                    stream = PullAudioOutputStream.fromStreamOutput(audioOutputStream);
                    
                    // 读取所有音频数据
                    byte[] audioData = new byte[stream.getAvailableBytes()];
                    stream.read(audioData);
                    
                    // 保存输出音频数据到文件（调试用）
                    if (saveAudioToFile && session != null) {
                        saveAudioChunkToFile(audioData, session.getId(), "output");
                    }
                    
                    return audioData;
                } else if (result.getReason() == ResultReason.Canceled) {
                    try {
                        CancellationDetails details = CancellationDetails.fromResult(result);
                        log.error("Speech synthesis canceled: {}", details.getErrorDetails());
                        // 返回空数据而不是抛出异常
                        return new byte[0];
                    } catch (Exception e) {
                        log.error("Error getting synthesis cancellation details", e);
                        return new byte[0];
                    }
                } else {
                    log.error("Speech synthesis failed: {}", result.getReason());
                    return new byte[0];
                }
            } catch (Exception e) {
                log.error("Error in text-to-speech: ", e);
                // 返回空数据而不是抛出异常
                return new byte[0];
            } finally {
                // 1.43.0中建议显式关闭资源
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (Exception e) {
                        log.warn("Failed to close audio stream", e);
                    }
                }
                if (synthesizer != null) {
                    try {
                        synthesizer.close();
                    } catch (Exception e) {
                        log.warn("Failed to close synthesizer", e);
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
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(audioData -> {
            // 检查音频数据是否为空
            if (audioData == null || audioData.length == 0) {
                log.warn("No audio data generated");
                return Flux.empty();
            }
            
            // 如果音频数据较大，可以分块处理
            int chunkSize = 8192; // 8KB chunks
            int chunks = (audioData.length + chunkSize - 1) / chunkSize;
            
            return Flux.range(0, chunks)
                      .map(i -> {
                          int start = i * chunkSize;
                          int end = Math.min(start + chunkSize, audioData.length);
                          byte[] chunk = new byte[end - start];
                          System.arraycopy(audioData, start, chunk, 0, chunk.length);
                          return chunk;
                      });
        })
        .onErrorResume(e -> {
            log.error("Error processing text to speech", e);
            // 返回空流而不是抛出异常
            return Flux.empty();
        });
    }
    
    /**
     * 保存音频数据块到文件
     * 
     * @param audioData 音频数据
     * @param sessionId 会话ID
     * @param type 类型（输入/输出）
     */
    private void saveAudioChunkToFile(byte[] audioData, String sessionId, String type) {
        if (!saveAudioToFile || audioData == null || audioData.length == 0) {
            return;
        }
        
        try {
            // 为会话获取音频文件路径，如果不存在则创建
            String sessionFilePath = sessionAudioFileMap.computeIfAbsent(sessionId, id -> {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                return Paths.get(debugAudioDirectory, String.format("%s_%s", timestamp, id)).toString();
            });
            
            // 创建不同的文件名用于输入和输出
            String fileName = String.format("%s_%s_%d.bin", sessionFilePath, type, System.currentTimeMillis());
            
            try (FileOutputStream fos = new FileOutputStream(fileName)) {
                fos.write(audioData);
                log.debug("Saved {} audio chunk to file: {}", type, fileName);
            }
        } catch (IOException e) {
            log.error("Failed to save audio data to file", e);
        }
    }

    @Override
    public String getProviderName() {
        return "microsoft";
    }
} 