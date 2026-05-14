# HomeCam v1.0

将旧 Android 手机变为局域网监控摄像头的应用。

## 开发环境要求

- Android Studio Hedgehog (2023.1.1) 或更新版本
- JDK 17
- Android SDK 34

## 构建步骤

1. 用 Android Studio 打开项目
2. 下载 AI 模型文件（见下方说明）放到 `app/src/main/assets/` 目录
3. Sync Gradle 并构建项目

## AI 模型文件

应用需要两个 TFLite 模型文件，放在 `app/src/main/assets/` 下：

### 1. 人物检测模型 - efficientdet_lite0.tflite

从 MediaPipe 模型仓库下载：
```
https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/efficientdet_lite0.tflite
```

### 2. 音频分类模型 - yamnet.tflite

从 TensorFlow Hub 下载：
```
https://tfhub.dev/google/lite-model/yamnet/classification/tflite/1
```

## 功能概览

- 后置摄像头视频采集（640x480 / 1280x720）
- MJPEG 实时流推送到浏览器
- 息屏后台持续运行（前台服务 + WakeLock）
- 本地 AI 人物移动检测（MediaPipe EfficientDet-Lite0）
- 本地 AI 婴儿哭声检测（TFLite YAMNet）
- 事件触发自动录像保存（MP4，环形缓冲）
- 内置 Web 管理页面（实时画面 + 历史视频）
- REST API 接口供未来扩展
- 自动清理旧视频

## 项目结构

```
app/src/main/java/com/homecam/app/
├── HomeCamApp.kt           # Application 类
├── data/                   # Room 数据库层
│   ├── VideoRecord.kt      # 实体类
│   ├── VideoDao.kt          # DAO
│   └── VideoDatabase.kt     # 数据库
├── service/                 # 服务层
│   ├── CameraService.kt     # 前台服务 + CameraX
│   ├── AppSettings.kt       # 配置读取
│   └── ServiceManager.kt    # 服务实例管理
├── stream/                  # 流推送
│   └── MjpegStreamer.kt     # MJPEG 流管理
├── detection/               # AI 检测
│   └── EventDetector.kt     # 视觉+音频检测
├── recorder/                # 录像
│   ├── FrameBuffer.kt       # 环形帧缓冲
│   └── VideoRecorder.kt     # MP4 编码保存
├── web/                     # Web 服务
│   ├── CamWebServer.kt      # NanoHTTPD 服务器
│   └── MjpegInputStream.kt  # MJPEG 输入流
└── ui/                      # 界面
    ├── MainActivity.kt       # 主界面
    └── SettingsActivity.kt   # 设置界面
```

## API 接口

| 路径 | 方法 | 说明 |
|------|------|------|
| `/` | GET | Web 管理页面 |
| `/video` | GET | MJPEG 实时流 |
| `/api/status` | GET | 设备状态 JSON |
| `/api/events` | GET | 最近 20 条事件 |
| `/api/videos` | GET | 所有视频列表 |
| `/videos/{filename}` | GET | 视频文件 |
| `/api/frame.jpg` | GET | 当前帧 JPEG |
