# 实时翻译系统

这是一个基于Java Spring Boot的实时语音翻译系统，支持多种语言间的语音识别、翻译和语音合成。

## 功能特点

- 支持多种语言的实时语音识别（中文、英语、日语等）
- 基于WebSocket的双向通信，支持低延迟流式传输
- 多种服务提供商支持（Microsoft Azure, OpenAI）
- 自定义语音合成（多种声音选择）
- 文件上传翻译功能
- 简洁直观的Web界面

## 系统要求

- Java 17 或更高版本
- Maven 3.6 或更高版本
- 支持WebSocket的现代浏览器
- 麦克风（用于语音输入）

## 安装和运行

### 方法一：使用脚本运行

#### Windows

1. 确保已安装Java 17+
2. 双击`run.bat`脚本

#### Linux/macOS

1. 确保已安装Java 17+
2. 打开终端，进入项目目录
3. 添加执行权限：`chmod +x run.sh`
4. 运行脚本：`./run.sh`

### 方法二：手动构建和运行

1. 克隆仓库：`git clone <仓库URL>`
2. 进入项目目录：`cd real-time-translation-system`
3. 构建项目：`mvn clean package`
4. 运行应用：`java -jar target/system-0.0.1-SNAPSHOT.jar`

## 配置

编辑`src/main/resources/application.properties`文件来配置API密钥和其他设置：

```properties
# 服务器配置
server.port=8080

# Microsoft Azure Speech服务配置
speech.microsoft.subscription-key=your_subscription_key
speech.microsoft.region=your_region
speech.microsoft.recognition.language=zh-CN
speech.microsoft.synthesis.language=zh-CN
speech.microsoft.synthesis.voice-name=zh-CN-XiaoxiaoNeural

# OpenAI服务配置 
speech.openai.api-key=your_openai_api_key
speech.openai.model=tts-1
speech.openai.voice=alloy
speech.openai.speed=1.0

# 对象池配置
pool.max-idle=10
pool.min-idle=2
pool.max-total=20
pool.max-wait-millis=30000

# 调试选项
debug.audio.save-to-file=false
debug.audio.directory=./debug-audio
```

## 使用方法

1. 启动应用后，在浏览器中打开 `http://localhost:8080`
2. 点击"连接服务器"按钮
3. 选择源语言和目标语言
4. 点击麦克风按钮开始录音
5. 说话，系统将识别您的语音并进行翻译
6. 翻译结果将以文本形式显示，也可以点击播放按钮听取翻译后的语音

## 开发者指南

### 项目结构

```
├── src/main/java/com/translation/system/
│   ├── config/            # 配置类
│   ├── controller/        # REST控制器
│   ├── handler/           # WebSocket处理器
│   ├── model/             # 数据模型
│   ├── service/           # 服务接口和实现
│   └── RealTimeTranslationSystemApplication.java  # 主应用类
├── src/main/resources/
│   ├── static/            # 静态资源（HTML, CSS, JS）
│   └── application.properties  # 应用配置
├── pom.xml                # Maven配置
└── README.md              # 项目说明
```

### API说明

#### WebSocket API

连接端点：`/ws/speech` 或 `/sockjs/speech`（使用SockJS）

##### 客户端发送的消息类型

- `AUDIO_DATA`：发送音频数据进行语音识别和翻译
  ```json
  {
    "type": "AUDIO_DATA",
    "audio": "base64编码的音频数据",
    "request": {
      "sourceLanguage": "zh-CN",
      "targetLanguage": "en-US",
      "provider": "microsoft",
      "voice": "en-US-AriaNeural",
      "mode": "speech-to-speech"
    }
  }
  ```

##### 服务器发送的消息类型

- `TEXT_RESULT`：语音识别结果
  ```json
  {
    "type": "TEXT_RESULT",
    "text": "识别出的文本"
  }
  ```

- `TRANSLATION`：翻译结果
  ```json
  {
    "type": "TRANSLATION",
    "text": "翻译后的文本"
  }
  ```

- `AUDIO_RESULT`：语音合成结果
  ```json
  {
    "type": "AUDIO_RESULT",
    "audio": "base64编码的音频数据"
  }
  ```

- `ERROR`：错误信息
  ```json
  {
    "type": "ERROR",
    "text": "错误信息",
    "code": 500
  }
  ```

#### REST API

- `GET /api/health`：健康检查
  - 响应：`{"status": "UP", "services": {"microsoft": true, "openai": false}}`

- `POST /api/translation/file`：文件上传翻译
  - 参数：
    - `file`：音频文件
    - `sourceLanguage`：源语言
    - `targetLanguage`：目标语言
    - `provider`：服务提供商
  - 响应：`{"text": "翻译结果"}`

## 许可证

[MIT License](LICENSE)

## 联系方式

如有问题或建议，请提交Issue或联系项目维护者。

---
如需帮助，欢迎提交问题或参与项目贡献。 