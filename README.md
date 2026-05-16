# HomeCam - 闲置 Android 手机变身局域网监控摄像头

[![Min SDK](https://img.shields.io/badge/minSdk-26-brightgreen)](https://developer.android.com/about/versions/oreo) [![Target SDK](https://img.shields.io/badge/targetSdk-34-blue)](https://developer.android.com/about/versions/14) [![Kotlin](https://img.shields.io/badge/language-Kotlin-purple)](https://kotlinlang.org/) [![License](https://img.shields.io/badge/license-MIT-yellow)](LICENSE)

HomeCam 是一款将闲置 Android 手机转化为局域网监控摄像头的应用。它利用 CameraX 采集视频，通过 MJPEG 实时推流到浏览器，并内置 AI 事件检测（人物移动检测、婴儿哭声识别），支持事件触发自动录像回放。

## 功能特性

- **实时视频流** — 通过 MJPEG over HTTP 在局域网内任意浏览器中查看实时画面
- **多摄像头支持** — 运行时切换前后置/外接摄像头，支持 MIUI 等系统的逻辑多摄组（超广角/长焦），无需重启服务
- **息屏后台运行** — 前台服务 + WakeLock，锁屏后持续采集和推流
- **AI 事件检测**
  - **人物移动检测** — 基于 MediaPipe EfficientDet-Lite0，检测画面中的人物
  - **婴儿哭声识别** — 基于 TensorFlow Lite YAMNet，识别婴儿哭声和哭泣声
  - **危险检测** — 检测到人物时触发告警
- **事件录像** — 检测到事件时自动录制 MP4 视频，环形帧缓冲可回溯事件前数秒
  - **录像开关** — 运行时实时控制是否保存录像
- **内置 Web 管理界面** — 暗色主题 Web UI，支持实时画面、事件历史、视频回放和下载
- **RESTful API** — 提供 JSON 接口供扩展集成

## 截图

| Web 实时画面 | 事件历史 |
|:---:|:---:|
| ![Live](screenshots/live.jpg) | ![History](screenshots/history.jpg) |

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 1.9.22 |
| 最低 SDK | Android 8.0 (API 26) | - |
| 目标 SDK | Android 14 (API 34) | - |
| 视频采集 | CameraX + Camera2 (多摄) | 1.3.1 |
| HTTP 服务器 | [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | 2.3.1 |
| 视频编码 | MediaCodec + MediaMuxer | Android Framework |
| 人物检测 | [MediaPipe Tasks Vision](https://developers.google.com/mediapipe/solutions/vision/object_detector) | 0.10.8 |
| 音频分类 | [TensorFlow Lite Task Audio](https://www.tensorflow.org/lite/inference_with_metadata/task_library/audio_classifier) | 0.4.4 |
| 数据库 | [Room](https://developer.android.com/training/data-storage/room) | 2.6.1 |
| JSON | Gson | 2.10.1 |
| 构建工具 | Gradle + AGP | 8.2.2 |

## 快速开始

### 前置条件

- Android Studio Hedgehog (2023.1.1) 或更高版本
- Android SDK 34
- JDK 17
- 一台 Android 8.0+ 设备（推荐有较好的摄像头和麦克风）

### 构建与安装

```bash
# 克隆仓库
git clone https://github.com/yourusername/homecam.git
cd homecam


# 使用 Gradle 构建
./gradlew assembleDebug

# 或直接在 Android Studio 中打开项目，点击 Run
```

### AI 模型下载

应用需要以下模型文件放置在 `app/src/main/assets/` 目录下：

1. **efficientdet_lite0.tflite** — MediaPipe 物体检测模型（v1.2.0 起已包含在仓库中）
   - 来源：[MediaPipe Model Zoo](https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/latest/efficientdet_lite0.tflite)
2. **yamnet.tflite** — YAMNet 音频分类模型（已包含在仓库中）
   - 来源：[TensorFlow Hub](https://tfhub.dev/google/lite-model/yamnet/classification/tflite/1)

### 使用方式

1. 安装并启动应用，授予相机、麦克风和通知权限
2. 点击 **启动摄像头** 按钮
3. 应用会在后台开始采集视频，并启动 Web 服务器
4. 在同一个局域网内的电脑或平板上，打开浏览器访问应用显示的地址（例如 `http://192.168.1.100:8080`）
5. 在 Web 界面中查看实时画面、事件历史，播放或下载录像

## 项目结构

```
app/src/main/java/com/homecam/app/
├── HomeCamApp.kt                # Application 类，初始化 Room 数据库
├── ui/                          # 界面层
│   ├── MainActivity.kt          # 主界面（状态显示/启停控制）
│   └── SettingsActivity.kt      # 设置界面
├── service/                     # 核心服务层
│   ├── CameraService.kt         # 前台服务 - CameraX 采集 + 帧处理中枢
│   ├── AppSettings.kt           # 配置读取器（SharedPreferences）
│   └── ServiceManager.kt        # 服务实例全局引用
├── stream/
│   └── MjpegStreamer.kt         # MJPEG 多客户端分发
├── detection/
│   └── EventDetector.kt         # AI 事件检测（MediaPipe + TFLite）
├── recorder/
│   ├── FrameBuffer.kt           # 环形帧缓冲（事件前画面回溯）
│   └── VideoRecorder.kt         # JPEG→H.264→MP4 编码保存
├── web/
│   ├── CamWebServer.kt          # NanoHTTPD HTTP 服务器 + REST API
│   └── MjpegInputStream.kt      # MJPEG 流输入适配器
└── data/
    ├── VideoRecord.kt           # Room 实体
    ├── VideoDao.kt              # Room DAO
    └── VideoDatabase.kt         # Room 数据库单例

app/src/main/assets/
├── web/                         # Web 前端（index.html, style.css, app.js）
└── models/                      # AI 模型文件
    └── README.md
```

## 架构设计

应用采用三层架构：

```
┌─────────────────────────────────────────────┐
│              UI 层 (Android)                 │
│  MainActivity / SettingsActivity             │
└──────────────────┬──────────────────────────┘
                   │ Intent / SharedPrefs
┌──────────────────┴──────────────────────────┐
│             服务层 (Foreground Service)       │
│  CameraService                              │
│  ├─ CameraX ImageAnalysis → 帧采集           │
│  ├─ processFrame() → 帧处理中枢               │
│  │   ├→ MjpegStreamer.pushFrame() (推流)    │
│  │   ├→ FrameBuffer.addFrame() (缓冲)       │
│  │   ├→ EventDetector.analyzeFrame() (检测)  │
│  │   └→ 事件触发 → VideoRecorder (录像)      │
│  ├─ EventDetector (MediaPipe + TFLite)      │
│  └─ CamWebServer (NanoHTTPD + REST API)     │
└──────────────────┬──────────────────────────┘
                   │ Room / File I/O
┌──────────────────┴──────────────────────────┐
│            数据与存储层                        │
│  Room Database  │  File System (MP4)         │
└─────────────────────────────────────────────┘
```

### 数据流

1. CameraX 采集帧 → `ImageAnalysis.Analyzer`
2. 帧解码为 Bitmap，旋转/缩放后压缩为 JPEG
3. JPEG 帧分三路：
   - `MjpegStreamer` → 浏览器实时显示
   - `FrameBuffer` → 环形缓冲（用于事件前画面回溯）
   - `EventDetector` → AI 检测
4. 检测到事件 → 前后帧拼接 → MediaCodec 编码 → MP4 保存
5. Web 服务器提供 MJPEG 流、JSON API 和视频回放

## Web API 文档

| 路由 | 方法 | 说明 |
|------|------|------|
| `/` | GET | Web 管理界面 |
| `/video` | GET | MJPEG 实时视频流 |
| `/api/status` | GET | 服务器状态（运行时间、帧率、事件计数等） |
| `/api/events` | GET | 最近 20 条事件记录 |
| `/api/videos` | GET | 所有录像列表（含下载 URL） |
| `/api/frame.jpg` | GET | 单帧 JPEG 快照 |
| `/videos/{filename}` | GET | MP4 录像文件下载/播放 |

## 配置项

应用内提供完整设置界面（通过主界面右上角齿轮图标进入）：

### 视频设置
- **缩放比例**：0.5x ~ 1.0x（降低分辨率可提升性能）
- **帧率**：15 / 30 FPS

### 网络设置
- **Web 端口**：默认 8080

### 检测设置
- 人物移动检测（开/关）
- 婴儿哭声检测（开/关）
- 危险检测（开/关）

### 录像设置
- **保存时长**：事件触发后录制 2~5 秒
- **最大录像数量**：10~100 条
- **最大存储空间**：限制录像占用总大小

## 已知问题

1. **部分设备 CameraX 兼容问题** — 极少数旧款手机的后置摄像头可能无法正常初始化
2. **MIUI 隐藏物理摄像头** — 小米 11 Ultra 等 MIUI 设备会隐藏 `LOGICAL_MULTI_CAMERA` 能力，CameraX 仅检测到主摄和前置。v1.2.2 通过 Camera2 直接探测绕过此限制，超广角和长焦可正常使用
2. **音频检测仅支持 16kHz 采样率** — YAMNet 模型要求固定输入格式
3. **部分设备录像花屏** — 不同 SoC 硬件编码器对 YUV 格式敏感，v1.2.1 已修复（如遇问题请更新）

## 待开发功能

- RTSP 推流支持（供 NVR/HomeAssistant 集成）
- ONVIF 协议兼容
- 云端存储与远程访问
- 多摄像头管理
- Push Notification 告警推送
- 画面区域裁剪（只检测 ROI）
- 更多 AI 检测模型（如宠物检测、车辆检测）

## 构建说明

### Release 构建

```bash
./gradlew assembleRelease
```

Release 构建会启用 ProGuard 代码混淆。如需签名发布，在 `app/build.gradle.kts` 中配置 signingConfigs。

### 要求

- Gradle 8.5
- Android Gradle Plugin 8.2.2
- Kotlin 1.9.22

## 贡献

欢迎提交 Issue 和 Pull Request。在提交 PR 前请确保：

1. 在您的设备上测试通过
2. 代码风格与现有代码保持一致
3. 更新相关文档

## 许可证

[MIT License](LICENSE)

---

**免责声明**：本应用仅供个人安防监控使用。请遵守当地法律法规，未经他人同意不得用于偷拍等非法用途。
