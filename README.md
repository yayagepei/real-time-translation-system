# 实时语音转译系统

这是一个基于SpringBoot的实时语音转译系统，支持Microsoft Speech和OpenAI语音服务。系统通过WebSocket提供实时的语音转译功能，支持流式处理以减少延迟。

## 功能特点

- 支持多种语音服务提供商（Microsoft Speech Service和OpenAI）
- 使用WebSocket进行实时通信
- 流式处理音频数据，减少延迟
- 支持多种音频格式（WAV、MP3、OGG等）
- 支持多种语言的转换
- 支持自定义语音、语速等参数
- 对象池化技术减少资源消耗
- 异步响应式处理提高系统性能

## 系统架构

系统主要由以下部分组成：

1. **WebSocket服务**：处理客户端连接和消息传递
2. **语音服务接口**：统一的语音处理接口
3. **语音服务实现**：
   - Microsoft Speech Service实现
   - OpenAI语音服务实现
4. **转译服务**：协调语音识别和合成流程
5. **前端界面**：用于测试和演示的Web界面

## 技术栈

- **后端**：
  - Java 17
  - Spring Boot 3.2.3
  - Spring WebSocket
  - Project Reactor（响应式编程）
  - Apache Commons Pool（对象池）
  - Microsoft Cognitive Services Speech SDK
  - OpenAI API

- **前端**：
  - HTML/CSS/JavaScript
  - Web Audio API
  - MediaRecorder API
  - WebSocket API

## 快速开始

### 系统要求

- JDK 17+
- Maven 3.6+
- Microsoft Speech Service订阅（可选）
- OpenAI API密钥（可选）

### 环境变量设置

使用前需要设置以下环境变量（或在application.yml中修改）：

```
# Microsoft Speech Service
MICROSOFT_SPEECH_KEY=your-microsoft-speech-key
MICROSOFT_SPEECH_REGION=eastasia

# OpenAI
OPENAI_API_KEY=your-openai-api-key
```

### 构建和运行

```bash
mvn clean package
java -jar target/system-0.0.1-SNAPSHOT.jar
```

访问 http://localhost:8080 即可打开测试界面。

## 使用方法

1. 在测试界面选择服务提供商、语言、音频格式等参数
2. 点击"连接服务器"按钮建立WebSocket连接
3. 点击"开始录音"按钮开始录制音频
4. 系统会自动将录制的音频发送到服务器进行识别和转译
5. 转译后的音频会自动播放，同时在日志区域显示相关信息

## 扩展性

系统设计支持轻松添加新的语音服务提供商：

1. 实现`SpeechService`接口
2. 在`application.yml`中添加相应配置
3. 服务会自动注册到系统中

## 测试

系统包含了完整的单元测试和集成测试，使用以下命令运行测试：

```bash
mvn test
```

## 注意事项

- 实际使用需要有效的Microsoft Speech Service订阅或OpenAI API密钥
- WebSocket连接需要稳定的网络环境
- 录音功能需要浏览器支持MediaRecorder API 

## 编码说明

本项目中的所有文件均采用UTF-8编码格式。在开发过程中，请确保：

1. 所有Java源代码文件使用UTF-8编码保存
2. 所有配置文件（如application.yml）使用UTF-8编码
3. 所有静态资源文件（如HTML、CSS、JavaScript）使用UTF-8编码

### IDE设置

#### IntelliJ IDEA
- 打开"File" > "Settings" > "Editor" > "File Encodings"
- 将"Global Encoding"和"Project Encoding"设置为"UTF-8"
- 将"Default encoding for properties files"设置为"UTF-8"

#### Eclipse
- 打开"Window" > "Preferences" > "General" > "Workspace"
- 将"Text file encoding"设置为"UTF-8"
- 打开"Window" > "Preferences" > "General" > "Content Types"
- 选择"Text"，并在底部的"Default encoding"中输入"UTF-8"并点击"Update"

#### VS Code
- 打开"File" > "Preferences" > "Settings"
- 搜索"encoding"
- 将"Files: Encoding"设置为"UTF-8" 