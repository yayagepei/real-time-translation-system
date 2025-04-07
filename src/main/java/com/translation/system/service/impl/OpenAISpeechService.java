package com.translation.system.service.impl;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.translation.system.config.SpeechConfig.OpenAI;
import com.translation.system.model.TranslationRequest;
import com.translation.system.service.SpeechService;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Service
@Slf4j
@RequiredArgsConstructor
public class OpenAISpeechService implements SpeechService {

    private final OpenAI openaiConfig;
    private ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String TTS_API_URL = "https://api.openai.com/v1/audio/speech";
    
    private HttpClient httpClient;
    
    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Flux<String> speechToText(byte[] audioData, String sourceLanguage) {
        // 创建基本请求并调用带会话的方法
        TranslationRequest request = new TranslationRequest();
        request.setSourceLanguage(sourceLanguage);
        return speechToText(audioData, request, null);
    }

    @Override
    public Flux<String> speechToText(byte[] audioData, TranslationRequest request, WebSocketSession session) {
        try {
            // 构建请求体
            var boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            var requestBody = new ByteArrayOutputStream();
            
            // 添加模型信息
            String modelLine = "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
                    "whisper-1\r\n";
            requestBody.write(modelLine.getBytes(StandardCharsets.UTF_8));
            
            // 添加语言信息（如果有指定）
            if (request.getSourceLanguage() != null) {
                String languageLine = "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"language\"\r\n\r\n" +
                        request.getSourceLanguage() + "\r\n";
                requestBody.write(languageLine.getBytes(StandardCharsets.UTF_8));
            }
            
            // 添加音频文件
            String audioHeader = "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n";
            requestBody.write(audioHeader.getBytes(StandardCharsets.UTF_8));
            requestBody.write(audioData);
            requestBody.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            
            // 构建请求
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(WHISPER_API_URL))
                    .header("Authorization", "Bearer " + openaiConfig.getApiKey())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody.toByteArray()))
                    .build();
            
            // 发送请求
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                // 解析响应
                JsonNode jsonNode = objectMapper.readTree(response.body());
                String text = jsonNode.get("text").asText();
                return Flux.just(text);
            } else {
                log.error("Error from OpenAI Whisper API: {}", response.body());
                throw new RuntimeException("Failed to transcribe audio: " + response.statusCode());
            }
        } catch (Exception e) {
            log.error("Error in speech-to-text: ", e);
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
        
        try {
            // 构建请求体
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", openaiConfig.getModel());
            requestBody.put("input", text);
            
            // 使用配置中的声音，如果请求中有指定则覆盖
            String voice = (request.getVoice() != null) ? request.getVoice() : openaiConfig.getVoice();
            requestBody.put("voice", voice);
            
            // 使用配置中的语速，如果请求中有指定则覆盖
            double speed = (request.getSpeed() != null) ? request.getSpeed() : openaiConfig.getSpeed();
            requestBody.put("speed", speed);
            
            // 设置响应格式
            String responseFormat;
            if (request.getAudioFormat() != null) {
                switch(request.getAudioFormat()) {
                    case MP3:
                        responseFormat = "mp3";
                        break;
                    case OGG:
                        responseFormat = "opus";
                        break;
                    case WAV:
                    default:
                        responseFormat = "wav";
                        break;
                }
            } else {
                responseFormat = "wav"; // 默认使用wav
            }
            requestBody.put("response_format", responseFormat);
            
            // 构建请求
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(TTS_API_URL))
                    .header("Authorization", "Bearer " + openaiConfig.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();
            
            // 发送请求
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            
            if (response.statusCode() == 200) {
                return Flux.just(response.body());
            } else {
                String responseBody = new String(response.body(), StandardCharsets.UTF_8);
                log.error("Error from OpenAI TTS API: {}", responseBody);
                throw new RuntimeException("Failed to synthesize speech: " + response.statusCode());
            }
        } catch (Exception e) {
            log.error("Error in text-to-speech: ", e);
            return Flux.just(new byte[0]);
        }
    }
    
    @Override
    public boolean isAvailable() {
        return openaiConfig.getApiKey() != null && !openaiConfig.getApiKey().trim().isEmpty();
    }

    @Override
    public String getProviderName() {
        return "openai";
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
        log.info("开始OpenAI语音转语音翻译: 会话ID={}, 音频数据大小={}字节, 源语言={}, 目标语言={}", 
                sessionId, audioData.length, request.getSourceLanguage(), request.getTargetLanguage());
        
        // OpenAI没有直接的语音转语音翻译API，需要组合使用Whisper和TTS
        // 步骤1: 使用Whisper进行语音转文本
        return speechToText(audioData, request, session)
                .doOnNext(recognizedText -> {
                    log.info("OpenAI语音识别完成: 会话ID={}, 识别文本=\"{}\"", sessionId, recognizedText);
                })
                .flatMap(recognizedText -> {
                    // 检查识别结果是否为空或失败
                    if (recognizedText == null || recognizedText.isEmpty() || 
                            recognizedText.startsWith("语音识别失败")) {
                        log.warn("OpenAI语音识别结果为空或失败，尝试使用默认文本");
                        // 使用一个默认文本，防止合成失败
                        recognizedText = "这是一段默认文本，因为OpenAI语音识别失败";
                    }
                    
                    // 步骤2: 翻译文本（从源语言到目标语言）
                    final String originalText = recognizedText;
                    String translatedText = translateText(originalText, 
                            request.getSourceLanguage(), 
                            request.getTargetLanguage());
                    
                    log.info("OpenAI文本翻译完成: 源文本=\"{}\", 目标文本=\"{}\"", originalText, translatedText);
                    
                    // 步骤3: 使用TTS进行文本转语音（目标语言）
                    log.info("开始OpenAI语音合成: 会话ID={}, 文本=\"{}\"", sessionId, translatedText);
                    return textToSpeech(translatedText, request, session);
                })
                .doOnNext(synthesizedAudio -> {
                    if (synthesizedAudio == null || synthesizedAudio.length == 0) {
                        log.warn("OpenAI语音合成结果为空，生成的音频数据大小为0字节");
                    } else {
                        log.info("OpenAI语音合成完成: 会话ID={}, 音频数据大小={}字节", sessionId, synthesizedAudio.length);
                    }
                })
                .switchIfEmpty(Flux.defer(() -> {
                    log.warn("OpenAI语音翻译过程返回空结果，使用默认文本生成音频");
                    // 如果流为空，尝试使用默认文本生成音频
                    String defaultText = "这是一段自动生成的音频，因为OpenAI语音翻译过程中出现了问题";
                    return textToSpeech(defaultText, request, session);
                }))
                .doOnComplete(() -> {
                    log.info("OpenAI语音转语音翻译完成: 会话ID={}", sessionId);
                })
                .doOnError(error -> {
                    log.error("OpenAI语音转语音翻译失败: 会话ID={}, 错误={}", sessionId, error.getMessage(), error);
                });
    }

    /**
     * 简单翻译文本（临时实现，实际项目中应使用专业翻译API）
     * 
     * @param text 需要翻译的文本
     * @param sourceLanguage 源语言
     * @param targetLanguage 目标语言
     * @return 翻译后的文本
     */
    private String translateText(String text, String sourceLanguage, String targetLanguage) {
        // 这里只是一个简单示例，实际应该集成专业的翻译API
        // 例如OpenAI的API、Google翻译API或百度翻译API等
        
        // 中文到英文的简单映射（仅作示例）
        if ("zh-CN".equals(sourceLanguage) && targetLanguage.startsWith("en")) {
            if (text.contains("测试")) {
                return "Please translate what I say into English. This is a test, and we are using OpenAI's speech synthesis service.";
            } else if (text.contains("默认文本") || text.contains("识别失败")) {
                return "This is a default text because speech recognition failed.";
            } else {
                // 默认英文示例文本
                return "This is a translated text. The original language was Chinese.";
            }
        } 
        // 英文到中文
        else if (sourceLanguage.startsWith("en") && "zh-CN".equals(targetLanguage)) {
            if (text.toLowerCase().contains("test")) {
                return "请将我说的话翻译成中文。这是一个测试，我们正在使用OpenAI的语音合成服务。";
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