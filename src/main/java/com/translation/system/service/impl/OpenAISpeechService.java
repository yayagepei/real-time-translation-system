package com.translation.system.service.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAISpeechService implements SpeechService {

    private final OpenAI openaiConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient httpClient;
    
    private static final String WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String TTS_API_URL = "https://api.openai.com/v1/audio/speech";
    
    @PostConstruct
    public void init() {
        // 创建HTTP Client，设置超时时间
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Flux<String> speechToText(byte[] audioData, TranslationRequest request, WebSocketSession session) {
        return Mono.fromCallable(() -> {
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
                HttpRequest request_ = HttpRequest.newBuilder()
                        .uri(URI.create(WHISPER_API_URL))
                        .header("Authorization", "Bearer " + openaiConfig.getApiKey())
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody.toByteArray()))
                        .build();
                
                // 发送请求
                HttpResponse<String> response = httpClient.send(request_, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    // 解析响应
                    JsonNode jsonNode = objectMapper.readTree(response.body());
                    String text = jsonNode.get("text").asText();
                    return text;
                } else {
                    log.error("Error from OpenAI Whisper API: {}", response.body());
                    throw new RuntimeException("Failed to transcribe audio: " + response.statusCode());
                }
            } catch (Exception e) {
                log.error("Error in speech-to-text: ", e);
                throw new RuntimeException("Speech recognition failed", e);
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(text -> {
            // 将结果切分为句子以便流式处理
            String[] sentences = text.split("[.!?。！？]");
            return Flux.fromArray(sentences)
                       .filter(s -> !s.trim().isEmpty())
                       .map(s -> s.trim() + "。"); // 添加句号
        });
    }

    @Override
    public Flux<byte[]> textToSpeech(String text, TranslationRequest request, WebSocketSession session) {
        return Mono.fromCallable(() -> {
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
                requestBody.put("response_format", responseFormat);
                
                // 构建请求
                HttpRequest request_ = HttpRequest.newBuilder()
                        .uri(URI.create(TTS_API_URL))
                        .header("Authorization", "Bearer " + openaiConfig.getApiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                        .build();
                
                // 发送请求
                HttpResponse<byte[]> response = httpClient.send(request_, HttpResponse.BodyHandlers.ofByteArray());
                
                if (response.statusCode() == 200) {
                    return response.body();
                } else {
                    String responseBody = new String(response.body(), StandardCharsets.UTF_8);
                    log.error("Error from OpenAI TTS API: {}", responseBody);
                    throw new RuntimeException("Failed to synthesize speech: " + response.statusCode());
                }
            } catch (Exception e) {
                log.error("Error in text-to-speech: ", e);
                throw new RuntimeException("Speech synthesis failed", e);
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(audioData -> {
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
        });
    }

    @Override
    public String getProviderName() {
        return "openai";
    }
} 