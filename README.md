# HomeCam - 闲置 Android 手机变身局域网监控摄像头

[![Min SDK](https://img.shields.io/badge/minSdk-26-brightgreen)](https://developer.android.com/about/versions/oreo) [![Target SDK](https://img.shields.io/badge/targetSdk-34-blue)](https://developer.android.com/about/versions/14) [![Kotlin](https://img.shields.io/badge/language-Kotlin-purple)](https://kotlinlang.org/) [![License](https://img.shields.io/badge/license-MIT-yellow)](LICENSE)

HomeCam 是一款将闲置 Android 手机转化为局域网监控摄像头的应用。它利用 Camera2 采集视频，通过 MJPEG/RTSP 实时推流，并内置 AI 事件检测（人物移动/跌倒/玩手机/哭声/睡眠检测），支持事件触发自动录像回放。

## 功能特性

- **实时视频流** — 通过 MJPEG over HTTP 在局域网内任意浏览器中查看实时画面
- **多摄像头支持** — 运行时切换前后置/外接摄像头，支持 MIUI 等系统的逻辑多摄组（超广角/长焦），无需重启服务
- **息屏后台运行** — 前台服务 + WakeLock，锁屏后持续采集和推流
- **AI 事件检测**
  - **人物移动检测** — 基于 MediaPipe EfficientDet-Lite0，通过进出状态机判断人物/动物进入和离开
  - 进出状态机：无人状态下检测到人/动物触发“进入”事件，持续 30 秒未检测到触发“离开”事件
  - 识别标签：person / cat / dog / bird
  - **婴儿哭声识别** — 基于 TensorFlow Lite YAMNet，识别婴儿哭声和哭泣声
  - **睡眠检测** — 基于 MediaPipe FaceLandmarker 面部特征点，通过 EAR 闭眼检测判断睡眠状态
  - **跌倒检测** — 基于 MediaPipe Pose Landmarker 躯干角度分析，检测摔倒和恢复站立
  - **玩手机检测** — 基于 EfficientDet 手机识别 + Hand Landmarker 手部位置，双重置信度加权判断
  - **事件类型**：enter（进入）/ leave（离开）/ cry（哭声）/ sleep（睡着）/ wake_up（醒来）/ fall（跌倒）/ get_up（站起）/ phone（玩手机），每种事件均可独立触发报警和录像
- **事件录像** — 检测到事件时自动录制 MP4 视频，环形帧缓冲可回溯事件前数秒
  - **录像开关** — 运行时实时控制是否保存录像
- **检测画框标记开关** — 关闭画框后继续检测和报警，但不标记视频流；画框关闭时检测自动切换到独立线程，不阻塞推流编码
- **摄像头电源控制** — 通过 Web 管理页面远程开关摄像头，关闭时仅停止摄像头以降低功耗，Web 服务器持续运行
- **UDP 自动发现** — 开启 UDP 端口45678，Homecam-TE 可自动扫描发现局域网内的 HomeCam 设备
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
| 视频采集 | Camera2 (YUV_420_888) | Framework |
| HTTP 服务器 | [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | 2.3.1 |
| 视频编码 | MediaCodec + MediaMuxer | Android Framework |
| 人物检测 | [MediaPipe Tasks Vision](https://developers.google.com/mediapipe/solutions/vision/object_detector) | 0.10.8 |
| 姿态检测 | MediaPipe Pose Landmarker (tasks-vision) | 0.10.8 |
| 手势检测 | MediaPipe Hand Landmarker (tasks-vision) | 0.10.8 |
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
3. **face_landmarker.task** — MediaPipe 面部特征点模型（v1.3.1 起需要）
   - 来源：[MediaPipe Model Zoo](https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task)
   - 用于睡眠检测：通过眼部特征点计算 EAR 值判断睁/闭眼状态
4. **pose_landmarker.task** — MediaPipe 姿态关键点模型（v1.5.0 起需要）
   - 来源：[MediaPipe Model Zoo](https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task)
   - 用于跌倒检测：33 个关键点 → 躯干角度分析
5. **hand_landmarker.task** — MediaPipe 手部关键点模型（v1.5.0 起需要）
   - 来源：[MediaPipe Model Zoo](https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task)
   - 用于玩手机检测：21 个手部关键点 → 手部位置评分

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
│   ├── CameraService.kt         # 前台服务 - Camera2 采集 + 帧处理中枢
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
│  ├─ Camera2 YUV_420_888 → 帧采集           │
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

1. Camera2 采集 YUV_420_888 帧 → ARGB 转换
2. 旋转/缩放为 Bitmap，压缩为 JPEG
3. JPEG 帧分两路：
   - `MjpegStreamer` → 浏览器实时显示
   - `FrameBuffer` → 环形缓冲（用于事件前画面回溯）
4. AI 检测（双线程模式）：
   - 画框开启：同线程（cameraExecutor）检测+画框，然后编码推流
   - 画框关闭：复制 Bitmap 交给 `detectExecutor` 后台检测，不阻塞推流编码
5. 检测到事件 → 前后帧拼接 → MediaCodec 编码 → MP4 保存
6. Web 服务器提供 MJPEG 流、JSON API 和视频回放

## Web API 文档

| 路由 | 方法 | 说明 |
|------|------|------|
| `/` | GET | Web 管理界面 |
| `/video` | GET | MJPEG 实时视频流 |
| `/api/status` | GET | 服务器状态（运行状态、IP、端口、检测模式、最新事件、当前摄像头、电源状态等） |
| `/api/events` | GET | 全部事件记录（内存列表，上限 1000 条） |
| `/api/videos` | GET | 所有录像列表（含下载 URL） |
| `/api/frame.jpg` | GET | 单帧 JPEG 快照 |
| `/api/cameras` | GET | 枚举所有摄像头信息（ID、逻辑ID、标签） |
| `/api/camera/switch` | GET | 切换摄像头（参数：cameraId + logicalCameraId） |
| `/api/camera/power` | GET | 摄像头电源控制（参数：action=on|off） |
| `/videos/{filename}` | GET | MP4 录像文件下载/播放 |

## 事件类型

HomeCam 的事件系统基于进出状态机 + 独立检测器（音频/面部），事件类型如下：

| 事件类型 | 触发条件 | 显示文本 | 写入日志 | 触发录像 |
|----------|---------|---------|:---:|:---:|
| `enter` | 无人→有人/动物进入画面 | `有person/cat/dog进入了` | ✓ | ✓ |
| `leave` | 有人/动物离开画面超过 30 秒 | `有person/cat/dog离开了` | ✓ | ✓ |
| `motion` | 每次检测到人/动物（5秒冷却，仅供录像触发） | 不显示 | ✗ | ✓ |
| `cry` | YAMNet 检测到婴儿哭声 | `婴儿哭声` | ✓ | ✓ |
| `sleep` | FaceLandmarker 连续 15 帧闭眼 | `宝宝睡着了` | ✓ | ✓ |
| `wake_up` | 睡眠状态中连续 5 帧睁眼 | `宝宝睡醒了` | ✓ | ✓ |
| `fall` | Pose Landmarker 躯干角度 > 50° 持续约 3 秒 | `检测到有人摔倒` | ✓ | ✓ |
| `get_up` | 摔倒后恢复站立 | `有人站起来了` | ✓ | ✓ |
| `phone` | 手机置信度 + 手部距离评分 > 0.5 | `有人在玩手机（{n}%）` | ✓ | ✓ |

### 进出状态机（人物移动检测）

状态：`EMPTY` ↔ `OCCUPIED`，超时 30 秒

1. EMPTY 状态下检测到人/动物（person/cat/dog/bird）→ 切换为 OCCUPIED，触发 `enter` 事件
2. OCCUPIED 状态下每次检测到人/动物 → 触发 `motion` 事件（5秒冷却），仅用于触发录像
3. OCCUPIED 状态下持续 30 秒未检测到人/动物 → 切换为 EMPTY，触发 `leave` 事件
4. `motion` 事件不写入事件日志，仅用于控制录像保存

`enter` 和 `leave` 事件携带识别标签（label），显示为如 `有person进入了`、`有cat离开了`。

## 配置项

应用内提供完整设置界面（通过主界面右上角齿轮图标进入）：

### 视频设置
- **缩放比例**：0.5x ~ 1.0x（降低分辨率可提升性能）
- **帧率**：15 / 30 FPS

### 网络设置
- **Web 端口**：默认 8080
- **RTSP 流媒体**：开/关，端口 8554
- **MJPEG 推流**：开/关

### 检测设置
- 人物移动检测（开/关）
- 详细检测设置（点击展开）：
  - 检测类别：person / cat / dog / bird
  - 检测频率：每 1~10 帧检测一次
  - 推理后端：CPU / GPU
  - 检测标记（画框开关）：开/关
- 跌倒检测（开/关，需开启人物移动检测）
- 玩手机检测（开/关，需开启人物移动检测）
- 婴儿哭声检测（开/关）
- 睡眠检测（开/关）

### 录像设置
- **保存时长**：事件触发后录制 2~5 秒
- **最大录像数量**：10~100 条
- **最大存储空间**：限制录像占用总大小

## 已知问题

1. **部分设备 Camera2 兼容问题** — 极少数旧款手机的后置摄像头可能无法正常初始化
2. **MIUI 隐藏物理摄像头** — 小米 11 Ultra 等 MIUI 设备会隐藏 `LOGICAL_MULTI_CAMERA` 能力，CameraX 仅检测到主摄和前置。v1.2.2 通过 Camera2 直接探测绕过此限制，超广角和长焦可正常使用
3. **音频检测仅支持 16kHz 采样率** — YAMNet 模型要求固定输入格式
4. **部分设备录像花屏** — 不同 SoC 硬件编码器对 YUV 格式敏感，v1.2.1 已修复（如遇问题请更新）
5. **睡眠检测需要正面人脸** — FaceLandmarker 需要清晰的正脸图像，侧脸或遮挡时无法检测闭眼
6. **跌倒检测需全身可见** — Pose Landmarker 需要画面中可见躯干（肩部和髋部），仅上半身时无法检测
7. **玩手机检测需手机+手同时可见** — 需要在画面中同时识别到手机目标和手部关键点，遮挡严重时可能漏检

## 待开发功能

- RTSP 推流支持（供 NVR/HomeAssistant 集成）
- ONVIF 协议兼容
- 云端存储与远程访问
- 多摄像头管理
- Push Notification 告警推送
- 画面区域裁剪（只检测 ROI）
- 更多 AI 检测模型（如车辆检测、异常声音检测）

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
