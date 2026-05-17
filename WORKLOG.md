# HomeCam v1.0 工作记录与技术文档

**最后更新**：2026-05-18  
**版本**：1.4.0  
**状态**：v1.4.0 新增 UDP 自动发现服务，Homecam-TE 终端可通过 UDP 广播自动发现局域网内的 HomeCam 设备

---

## 目录

1. [项目概述](#1-项目概述)
2. [架构设计](#2-架构设计)
3. [模块详细文档](#3-模块详细文档)
4. [数据流与调用链](#4-数据流与调用链)
5. [API 接口文档](#5-api-接口文档)
6. [数据库设计](#6-数据库设计)
7. [配置项说明](#7-配置项说明)
8. [AI 模型说明](#8-ai-模型说明)
9. [已知问题与待修复](#9-已知问题与待修复)
10. [待开发功能清单](#10-待开发功能清单)
11. [构建与部署](#11-构建与部署)
12. [代码风格与约定](#12-代码风格与约定)

---

## 1. 项目概述

### 1.1 定位

HomeCam 是一款将闲置 Android 手机转化为局域网监控摄像头的应用。核心功能：

- 后置摄像头视频采集 + MJPEG 实时流推送
- 息屏后台持续运行（前台服务 + WakeLock）
- 本地 AI 事件检测（人物移动 / 婴儿哭声 / 危险检测）
- 事件触发自动录像（MP4，环形缓冲回溯）
- 内置 Web 管理页面（实时画面 + 历史视频回放）
- RESTful API 接口供未来扩展

### 1.2 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 1.9.22 |
| 最低 SDK | Android 8.0 (API 26) | - |
| 目标 SDK | Android 14 (API 34) | - |
| 视频采集 | CameraX | 1.3.1 |
| HTTP 服务器 | NanoHTTPD | 2.3.1 |
| 视频编码 | MediaCodec + MediaMuxer | Android Framework |
| 人物检测 | MediaPipe Tasks Vision | 0.10.8 |
| 音频分类 | TensorFlow Lite Task Audio | 0.4.4 |
| 数据库 | Room | 2.6.1 |
| 配置存储 | SharedPreferences (PreferenceManager) | - |
| JSON 序列化 | Gson | 2.10.1 |
| 注解处理 | KSP | 1.9.22-1.0.17 |
| 构建工具 | Gradle + AGP | 8.2.2 |

### 1.3 目录结构

```
app/src/main/java/com/homecam/app/
├── HomeCamApp.kt                # Application 类，初始化 Room 数据库
├── data/                        # 数据持久化层
│   ├── VideoRecord.kt           # Room Entity - 视频记录
│   ├── VideoDao.kt              # Room DAO - 数据库操作
│   └── VideoDatabase.kt         # Room Database - 单例
├── service/                     # 核心服务层
│   ├── CameraService.kt         # 前台服务 - CameraX 采集 + 帧处理
│   ├── AppSettings.kt           # 配置读取器
│   └── ServiceManager.kt        # 服务实例全局访问
├── stream/                      # 流推送
│   └── MjpegStreamer.kt         # MJPEG 多客户端分发
├── detection/                   # AI 事件检测
│   └── EventDetector.kt         # 视觉+音频检测协调器
├── recorder/                    # 录像存储
│   ├── FrameBuffer.kt           # 环形帧缓冲
│   └── VideoRecorder.kt         # JPEG→H.264→MP4 编码保存
├── web/                         # Web 服务
│   ├── CamWebServer.kt          # NanoHTTPD 路由处理
│   └── MjpegInputStream.kt      # PipedStream 实现 MJPEG 流
└── ui/                          # 界面层
    ├── MainActivity.kt          # 主界面
    └── SettingsActivity.kt      # 设置界面

app/src/main/assets/
├── web/                         # Web 前端资源
│   ├── index.html
│   ├── style.css
│   └── app.js
└── models/                      # AI 模型文件（需手动下载）
    └── README.md

app/src/main/res/
├── layout/                      # 布局 XML
├── drawable/                    # 矢量图标
├── mipmap-anydpi-v26/           # 自适应启动图标
└── values/                      # 字符串/颜色/主题
```

---

## 2. 架构设计

### 2.1 分层架构

```
┌──────────────────────────────────────────────────────────────┐
│                       UI 层 (Android)                        │
│  MainActivity (状态显示/启停控制)  SettingsActivity (配置)     │
└──────────────────────┬───────────────────────────────────────┘
                       │ Intent / SharedPreferences
┌──────────────────────┴───────────────────────────────────────┐
│                    服务层 (Foreground Service)                 │
│  CameraService ────────────────────────────────────────────  │
│  ├─ CameraX ImageAnalysis (帧采集)                           │
│  ├─ processFrame() (帧处理中枢)                               │
│  │   ├→ MjpegStreamer.pushFrame() (推流)                    │
│  │   ├→ FrameBuffer.addFrame() (缓冲)                       │
│  │   ├→ EventDetector.analyzeFrame() (检测)                 │
│  │   └→ postEventFrames 收集 (录像)                          │
│  ├─ EventDetector (AI 检测协调)                              │
│  │   ├─ ObjectDetector (MediaPipe - 视觉)                   │
│  │   └─ AudioClassifier (TFLite - 音频)                     │
│  ├─ VideoRecorder (录像保存)                                 │
│  └─ CamWebServer (Web 服务)                                  │
│      ├─ 静态资源 (/index.html, /style.css, /app.js)          │
│      ├─ MJPEG 流 (/video → MjpegInputStream → Streamer)      │
│      └─ REST API (/api/*)                                    │
└──────────────────────┬───────────────────────────────────────┘
                       │ Room / File I/O
┌──────────────────────┴───────────────────────────────────────┐
│                     数据与存储层                               │
│  Room Database (VideoRecord)  │  File System (MP4/内部存储)   │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 关键设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 视频采集 | CameraX ImageAnalysis | 兼容性好，支持后台采集，无需 Surface |
| 流推送 | MJPEG over HTTP | 实现简单，浏览器原生支持 `<img>` 标签显示 |
| 录像方式 | JPEG帧→MediaCodec→MP4 | 避免实时编码的开销，利用环形缓冲回溯事件前画面 |
| 事件检测 | 每 N 帧检测一次 | 平衡准确性和性能，低端设备不会过载 |
| Web 服务器 | NanoHTTPD | 轻量级，易于嵌入 Android |
| 服务架构 | 单一前台服务 | 简化管理，所有功能在同一进程 |
| 服务实例 | ServiceManager 静态引用 | 让 WebServer 能访问 Service 内部组件 |

### 2.3 服务生命周期

```
用户点击"启动监控"
  → MainActivity.startCameraService()
    → startForegroundService(intent)
      → CameraService.onStartCommand()
        → startForeground() (通知)
        → ServiceManager.instance = this
        → acquireWakeLock()
        → initCamera()
        → initDetectors()
        → startWebServer()

用户点击"停止监控"
  → MainActivity.stopCameraService()
    → startService(ACTION_STOP)
      → CameraService: stopSelf()
        → onDestroy()
          → ServiceManager.instance = null
          → cameraProvider.unbindAll()
          → eventDetector.release()
          → streamer.clear()
          → webServer.stop()
          → wakeLock.release()
          → cameraExecutor.shutdownNow()
```

---

## 3. 模块详细文档

### 3.1 CameraService — 核心服务

**文件**: `service/CameraService.kt`  
**基类**: `LifecycleService`  
**用途**: 前台服务，管理整个监控流程

#### 成员变量

| 变量 | 类型 | 可见性 | 说明 |
|------|------|--------|------|
| `isRunning` | `AtomicBoolean` | companion, public | 静态运行标志，供 UI 和 WebServer 读取 |
| `latestEventType` | `String?` | companion, public | 最近事件类型 ("motion"/"cry"/"danger") |
| `latestEventTime` | `Long` | companion, public | 最近事件时间戳 (ms) |
| `latestEventLabel` | `String` | companion, public | 最近事件的识别标签 (enter/leave 时为 person/cat/dog 等) |
| `streamer` | `MjpegStreamer` | public | MJPEG 流推送器 |
| `frameBuffer` | `FrameBuffer` | public lateinit | 环形帧缓冲 |
| `videoRecorder` | `VideoRecorder` | public lateinit | 录像保存器 |
| `eventDetector` | `EventDetector` | public lateinit | 事件检测器 |
| `isRecordingEvent` | `Boolean` | private | 当前是否正在录制事件视频 |
| `postEventFrames` | `MutableList` | private | 事件后帧收集列表 |

#### 帧处理流程 (processFrame)

```
ImageProxy (RGBA_8888)
  → Bitmap (处理行步幅/padding)
  → 旋转 (根据传感器旋转角度)
  → 缩放 (到用户设置的分辨率)
  → JPEG 压缩 (quality=75)
  → streamer.pushFrame(jpegData)     [推流]
  → frameBuffer.addFrame(ts, jpeg)   [缓冲]
  → eventDetector.analyzeFrame(bmp)  [检测]
  → postEventFrames.add(ts, jpeg)    [事件录像] (如果 isRecordingEvent)
  → scaledBitmap.recycle()           [释放]
```

#### 事件录像流程

```
EventDetector 回调 onEventDetected(eventType)
  → 检查 isRecordingEvent (防止重入)
  → 从 frameBuffer 获取 preFrames (前N秒)
  → 设置 isRecordingEvent = true
  → 设置 postEventEndTime = now + N秒
  → 后续帧继续收集到 postEventFrames
  → 当 timestamp >= postEventEndTime:
      → saveEventVideo()
      → videoRecorder.saveEventVideo(type, preFrames, postFrames)
```

#### WakeLock 管理策略

当前实现：`PARTIAL_WAKE_LOCK`，24 小时超时。  
**注意**：超时后服务可能被系统杀死。应改为无超时获取 + `onDestroy()` 释放。

---

### 3.2 MjpegStreamer — 流推送

**文件**: `stream/MjpegStreamer.kt`

#### 架构

```
pushFrame(jpegData)
  ├→ clients: CopyOnWriteArrayList<OutputStream>  [直接客户端]
  │   └→ 写入 MJPEG multipart 包
  └→ frameListeners: CopyOnWriteArrayList<FrameListener>  [监听器]
      └→ onFrame(jpegData) 回调
```

#### MJPEG 数据包格式

```
--boundary\r\n
Content-Type: image/jpeg\r\n
Content-Length: {size}\r\n
\r\n
{JPEG 二进制数据}
\r\n
```

#### 当前实际使用路径

- **CamWebServer** 通过 `MjpegInputStream`（注册为 FrameListener）获取帧数据
- `clients` 列表当前未被 CamWebServer 使用（属于预留接口）
- 两条路径会各自独立构建 MJPEG 头部

#### FrameListener 扩展接口

```kotlin
interface FrameListener {
    fun onFrame(jpegData: ByteArray)
}
```

用于：
- 当前：`MjpegInputStream` 接收帧数据
- 预留：未来 WebSocket 帧推送

---

### 3.3 FrameBuffer — 环形帧缓冲

**文件**: `recorder/FrameBuffer.kt`

#### 设计

- 容量：`fps * 10`（15fps = 150帧，30fps = 300帧）
- 存储：`ArrayDeque<Frame>`，所有方法 `@Synchronized`
- 每帧数据：`Pair<Long, ByteArray>`（时间戳 + JPEG 字节）

#### 内存估算

| 分辨率 | JPEG 大小(约) | 150帧内存 | 300帧内存 |
|--------|--------------|----------|----------|
| 640x480 | ~30-50 KB | ~4.5-7.5 MB | ~9-15 MB |
| 1280x720 | ~60-100 KB | ~9-15 MB | ~18-30 MB |

#### 关键方法

- `addFrame(timestamp, jpeg)` — 尾部添加，超容量移除头部
- `getFramesInRange(startMs, endMs)` — 线性过滤，返回时间范围内的帧
- `getLatestFrame()` — 返回最后一帧（供 WebServer 单帧接口和事件检测使用）

---

### 3.4 VideoRecorder — 录像保存

**文件**: `recorder/VideoRecorder.kt`

#### 编码流程

```
List<Pair<Long, ByteArray>> (JPEG 帧)
  → 逐帧: JPEG → Bitmap → bitmapToYuv() → YUV420
  → MediaCodec (H.264/AVC 编码)
  → MediaMuxer (封装 MP4)
  → 保存到 Movies/HomeCam/
  → 插入 Room 数据库
  → 执行自动清理
```

#### 编码参数（当前硬编码）

| 参数 | 值 | 说明 |
|------|-----|------|
| 编解码器 | H.264/AVC | MediaCodec |
| 比特率 | 1 Mbps | 固定 |
| 帧率 | 15 fps | 固定（不随设置变化） |
| 颜色格式 | YUV420SemiPlanar (NV12) | 显式指定，跨设备兼容 |
| I帧间隔 | 5 秒 | 固定 |

#### 文件命名规则

```
HomeCam_{类型缩写}_{yyyyMMdd_HHmmss}.mp4

类型缩写:
  MOT = motion (人物移动)
  CRY = cry (婴儿哭声)
  DNG = danger (危险检测)
  EVT = 其他
```

示例：`HomeCam_MOT_20260512_102315.mp4`

#### 自动清理策略

每次保存新视频后触发：
- 条件1：视频总数 > `max_video_count`（默认50）
- 条件2：总文件大小 > `max_storage_mb`（默认200MB）
- 执行：删除最早的记录 + 对应文件，直到两个条件均满足

#### bitmapToYuv 手动转换

```kotlin
ARGB → YUV420 (BT.601 标准)
Y  = (66R + 129G + 25B + 128) >> 8 + 16
U  = (-38R - 74G + 112B + 128) >> 8 + 128
V  = (112R - 94G - 18B + 128) >> 8 + 128
```

每帧分配：`IntArray(width*height)` + `ByteArray(width*height*3/2)`  
640x480: ~1.2MB + 460KB ≈ 1.7MB/帧 → 高 GC 压力

---

### 3.5 EventDetector — 事件检测

**文件**: `detection/EventDetector.kt`

#### 视觉检测 (MediaPipe)

| 参数 | 值 |
|------|-----|
| 模型 | `efficientdet_lite0.tflite` |
| 运行模式 | `RunningMode.IMAGE` |
| 置信度阈值 | 0.5 |
| 最大检测数 | 3 |
| 检测目标 | "person" 类别 + 动物 (cat/dog/bird) |
| 检测频率 | 每 3-5 帧执行一次检测 |
| 进出超时 | 30000 ms (30秒未检测到触发"离开事件") |

进出状态机（v1.3.2）：
1. 每隔 N 帧执行一次检测
2. 如果 `isProcessing = true`（上一帧还在处理），跳过
3. 检测到 "person" / "cat" / "dog" / "bird" 且 score > 0.5 → 触发进出状态机
4. 状态机触发逻辑：
   - **EMPTY → OCCUPIED**：无人状态下突然检测到人/动物 → 触发 `"enter"` 事件，带识别标签 (person/cat/dog)
   - **保持 OCCUPIED**：每次检测到人/动物 → 触发 `"motion"` 事件（5秒冷却），仅用于触发录像保存
   - **OCCUPIED → EMPTY**：持续 30 秒未检测到人/动物 → 触发 `"leave"` 事件，带识别标签
5. "motion" 事件不写入事件日志，仅控制录像保存

#### 音频检测 (TFLite)

| 参数 | 值 |
|------|-----|
| 模型 | `yamnet.tflite` |
| 采样率 | 16000 Hz (模型要求) |
| 声道 | 单声道 |
| 置信度阈值 | 0.3 |
| 触发标签 | "Crying", "Crying, sobbing", "Baby cry, infant cry", "Sobbing" |
| 冷却时间 | 5000 ms |

音频线程流程：
```
AudioRecord (16kHz, MONO, PCM_16BIT)
  → 循环读取 short[] 音频缓冲
  → load 到 TensorAudio
  → AudioClassifier.classify()
  → 检查 cryLabels 中任一得分 > 0.3
  → 触发回调
```

#### "危险检测" 说明

当前 v1.0 中，danger 检测与 motion 检测逻辑完全相同（都检测 "person"）。  
未来应扩展为：检测婴儿在特定区域（如床边、楼梯）等场景。

---

### 3.6 CamWebServer — Web 服务

**文件**: `web/CamWebServer.kt`

#### 线程模型

NanoHTTPD 默认使用 daemon 线程池处理请求。`serve()` 方法在 worker 线程执行。  
数据库查询使用 `runBlocking` 阻塞当前 worker 线程。

#### MjpegInputStream 实现原理

```
MjpegStreamer.pushFrame()
  → FrameListener.onFrame(jpegData)
    → MjpegInputStream.pipeOutput.write(MJPEG头 + JPEG数据)
      → PipedOutputStream → PipedInputStream (64KB 缓冲)
        → NanoHTTPD 从 pipeInput 读取 → HTTP 响应
```

关键：PipedStream 缓冲区仅 64KB，高分辨率 JPEG 可能超限导致阻塞。

#### 视频文件服务

使用 `FileInputStream` + `newChunkedResponse` 提供视频文件。  
**限制**：不支持 Range 请求，浏览器无法 seek。

---

### 3.7 AppSettings — 配置系统

**文件**: `service/AppSettings.kt`

所有配置通过 `PreferenceManager.getDefaultSharedPreferences()` 读取。  
配置键与 SettingsActivity 中 Preference 的 key 一一对应。

#### SharedPreferences 键映射

| key | 类型 | 默认值 | 对应 UI |
|-----|------|--------|---------|
| `resolution` | String | "640x480" | ListPreference |
| `fps` | String | "15" | ListPreference |
| `web_port` | String | "8080" | EditTextPreference |
| `motion_detection` | Boolean | true | SwitchPreferenceCompat |
| `cry_detection` | Boolean | false | SwitchPreferenceCompat |
| `danger_detection` | Boolean | false | SwitchPreferenceCompat |
| `save_duration` | Int | 3 | SeekBarPreference |
| `max_video_count` | Int | 50 | SeekBarPreference |
| `max_storage_mb` | Int | 200 | **无 UI 控件** |

#### 注意事项

- 设置修改后不会立即生效，需停止再启动服务
- `web_port` 无输入校验（非数字、负数、特权端口均可输入）
- `max_storage_mb` 仅在代码中使用默认值 200，用户无法修改

---

### 3.8 UI 层

#### MainActivity

- 状态显示：运行/停止（绿色/灰色指示灯）
- IP 地址显示：`http://{ip}:{port}`（仅运行时可见）
- 启停按钮：启动/停止 CameraService
- 最新事件摘要
- 权限请求：CAMERA, RECORD_AUDIO, WRITE_EXTERNAL_STORAGE (≤API28), POST_NOTIFICATIONS (≥API33)

#### SettingsActivity

使用 `PreferenceFragmentCompat`，所有 Preference 在代码中动态创建（非 XML）。

分类：
- **视频**：分辨率、帧率
- **网络**：Web 服务端口
- **智能检测**：人物移动开关、哭声检测开关、危险检测开关
- **录像**：保存时长(2-5秒)、最大视频数(10-100)

---

### 3.9 Web 前端

#### 页面结构

两个标签页：实时 / 历史

- **实时页**：`<img src="/video">` MJPEG 流 + 状态条
- **历史页**：视频列表 + 内嵌播放器

#### 自动重连

MJPEG 流断开后 3 秒自动重连：
```javascript
liveStream.src = '/video?t=' + Date.now();
```

#### 状态轮询

每 5 秒请求 `/api/status`，更新事件信息。  
历史标签激活时每 15 秒刷新视频列表。

#### 深色/浅色主题

CSS 自定义属性 + `@media (prefers-color-scheme: light)` 自动适配。

---

## 4. 数据流与调用链

### 4.1 实时视频流路径

```
CameraX 后置摄像头
  → ImageAnalysis.Analyzer (cameraExecutor 线程)
    → processFrame()
      → RGBA buffer → Bitmap → 旋转 → 缩放 → JPEG 压缩
      → MjpegStreamer.pushFrame(jpegData)
        → FrameListener.onFrame()
          → MjpegInputStream.pipeOutput.write()
            → NanoHTTPD 读取 → HTTP Chunked Response
              → 浏览器 <img> 显示
```

### 4.2 事件检测到录像保存

```
processFrame()
  → eventDetector.analyzeFrame(bitmap)
    → ObjectDetector.detect() [视觉]
    或 AudioClassifier.classify() [音频]
      → onEventDetected("motion"/"cry"/"danger")
        → CameraService.onEventDetected()
          → 从 FrameBuffer 获取 preFrames
          → 设置 isRecordingEvent = true
          → 后续帧收集到 postEventFrames
          → 时间到达 postEventEndTime:
            → VideoRecorder.saveEventVideo()
              → [IO 线程] MediaCodec 编码 → MediaMuxer MP4
              → 保存文件 → Room 插入记录
              → 自动清理旧视频
```

### 4.3 Web 请求处理

```
浏览器请求 → NanoHTTPD (worker 线程)
  → CamWebServer.serve()
    → 路由匹配
      → /          : assets/web/index.html
      → /video     : MjpegInputStream (长连接)
      → /api/status: 内存状态 → JSON
      → /api/videos: runBlocking { dao.getAll() } → JSON
      → /videos/x  : FileInputStream → chunked response
      → /api/frame : frameBuffer.getLatestFrame() → JPEG
```

---

## 5. API 接口文档

### 5.1 GET /api/status

设备运行状态。

**响应示例**：
```json
{
  "running": true,
  "ip": "192.168.1.105",
  "port": 8080,
  "url": "http://192.168.1.105:8080",
  "detection_modes": {
    "motion": true,
    "cry": false,
    "danger": false
  },
  "latest_event": "motion",
  "latest_event_time": 1715485395000
}
```

### 5.2 GET /api/events

最近 20 条事件记录。

**响应示例**：
```json
[
  {
    "fileName": "HomeCam_MOT_20260512_102315.mp4",
    "timestamp": 1715485395000,
    "eventType": "motion",
    "durationSec": 6,
    "fileSize": 1048576
  }
]
```

### 5.3 GET /api/videos

所有保存视频列表（含下载 URL）。

**响应示例**：
```json
[
  {
    "fileName": "HomeCam_MOT_20260512_102315.mp4",
    "timestamp": 1715485395000,
    "eventType": "motion",
    "durationSec": 6,
    "fileSize": 1048576,
    "url": "/videos/HomeCam_MOT_20260512_102315.mp4"
  }
]
```

### 5.4 GET /videos/{filename}

返回 MP4 视频文件。Content-Type: `video/mp4`。

### 5.5 GET /api/frame.jpg

返回当前最新一帧 JPEG 图像。Content-Type: `image/jpeg`。

### 5.6 GET /video

MJPEG 实时流。Content-Type: `multipart/x-mixed-replace; boundary=--boundary`。

### 5.7 预留接口 (未实现)

| 路径 | 说明 |
|------|------|
| `/ws/live` | WebSocket 实时帧推送 (P2) |
| `/api/detection/config` | 检测配置修改 (未规划) |
| `/api/videos/{filename}` DELETE | 删除视频 (未规划) |

---

## 6. 数据库设计

### 6.1 表结构：video_records

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| fileName | TEXT | PRIMARY KEY | 文件名，格式 `HomeCam_{TYPE}_{yyyyMMdd_HHmmss}.mp4` |
| timestamp | INTEGER | - | 事件时间戳 (毫秒) |
| eventType | TEXT | - | 事件类型: "motion" / "cry" / "enter" / "leave" / "sleep" / "wake_up" |
| durationSec | INTEGER | - | 视频时长(秒) |
| fileSize | INTEGER | - | 文件大小(字节) |

### 6.2 索引

当前无额外索引。如果查询性能成为问题，可在 `timestamp` 上添加索引。

### 6.3 数据库版本

版本 1，`exportSchema = false`。

**迁移注意**：如果未来修改表结构，需要：
1. 设置 `exportSchema = true`
2. 实现 `Migration` 对象
3. 在 `Room.databaseBuilder` 中添加 `.addMigration()`

---

## 7. 配置项说明

| 配置项 | SharedPrefs Key | 类型 | 默认值 | 范围 | 生效时机 |
|--------|-----------------|------|--------|------|----------|
| 分辨率 | `resolution` | String | "640x480" | "640x480" / "1280x720" | 重启服务 |
| 帧率 | `fps` | String | "15" | "15" / "30" | 重启服务 |
| Web 端口 | `web_port` | String | "8080" | 1-65535 | 重启服务 |
| 人物移动检测 | `motion_detection` | Boolean | true | - | 重启服务 |
| 婴儿哭声检测 | `cry_detection` | Boolean | false | - | 重启服务 |
| 宝宝危险检测 | `danger_detection` | Boolean | false | - | 重启服务 |
| 保存时长(前后) | `save_duration` | Int | 3 | 2-5 秒 | 下次事件触发 |
| 最大视频数 | `max_video_count` | Int | 50 | 10-100 | 下次保存后清理 |
| 最大存储空间 | `max_storage_mb` | Int | 200 | - (无UI) | 下次保存后清理 |
| 检测间隔帧数 | (计算属性) | Int | 3或5 | fps>=30时为5 | 实时 |

---

## 8. AI 模型说明

### 8.1 人物检测 — EfficientDet-Lite0

| 属性 | 值 |
|------|-----|
| 文件名 | `efficientdet_lite0.tflite` |
| 放置位置 | `app/src/main/assets/` |
| 来源 | MediaPipe 模型仓库 |
| 下载地址 | `https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/efficientdet_lite0.tflite` |
| 输入 | RGB 图像 (320x320) |
| 输出 | 边界框 + 类别 + 置信度 |
| 量化 | int8 |
| 推理频率 | 每 3-5 帧一次 |

### 8.2 音频分类 — YAMNet

| 属性 | 值 |
|------|-----|
| 文件名 | `yamnet.tflite` |
| 放置位置 | `app/src/main/assets/` |
| 来源 | TensorFlow Hub |
| 下载地址 | `https://tfhub.dev/google/lite-model/yamnet/classification/tflite/1` |
| 输入 | 单声道 PCM, 16kHz, 0.975 秒 |
| 输出 | 521 个音频事件类别得分 |
| 推理频率 | 约每 2 秒一次 |

### 8.3 模型文件清单

| 模型文件 | 大小 | 状态 |
|----------|------|------|
| `efficientdet_lite0.tflite` | ~14 MB | ✅ v1.2.0 已包含在 `assets/` 中 |
| `yamnet.tflite` | ~4 MB | ✅ 已包含在 `assets/` 中 |

如果模型文件缺失，`initVisualDetector()` / `initAudioDetector()` 会捕获异常并打印堆栈，对应功能不可用但不会崩溃。

---

## 9. 已知问题与待修复

### 9.1 线程安全问题 (P0)

| 位置 | 问题 | 风险 | 状态 |
|------|------|------|------|
| `CameraService.latestEventType` | 非 volatile/plain var，多线程读写 | UI 可能读到旧值 | ✅ v1.1.0 已修复 (`@Volatile`) |
| `CameraService.latestEventTime` | 同上 | 同上 | ✅ v1.1.0 已修复 (`@Volatile`) |
| `CameraService.isRecordingEvent` | 非 volatile，camera executor 与 event detector 回调可能并发 | 可能丢失事件 | ✅ v1.1.0 已修复 (`@Volatile`) |
| `EventDetector.isProcessing` | 非 volatile，但实际运行在单线程 executor | 风险较低 | ✅ v1.1.0 已修复 (`@Volatile`) |
| `EventDetector.frameCounter` | 非 volatile，同上 | 风险较低 | ✅ v1.2.0 已修复 (`@Volatile`) |
| `CameraService.onEventDetected()` | 两个 `synchronized` 块分别检查状态和操作列表，中间有时间窗口 | 事件帧丢失 | ✅ v1.2.0 已修复（合并为一个原子块） |
| `CameraService.currentEventType` | 非 volatile，不同线程读写 | `finishEventVideo()` 可能读到 null 跳过保存 | ✅ v1.2.0 已修复 (`@Volatile`)

### 9.2 内存与性能问题 (P1)

| 位置 | 问题 | 修复方案 |
|------|------|----------|
| `VideoRecorder.bitmapToYuv()` | 每帧分配 ~1.7MB，高 GC 压力 | 预分配缓冲区复用 |
| `MjpegInputStream` 管道缓冲 | 64KB 可能不足，高分辨率帧会阻塞帧处理线程 | 增大到 256KB-512KB |
| `CamWebServer.serveAsset()` | 每次请求重新读取整个文件到内存 | 添加 LRU 缓存 |
| `VideoRecorder` 编码参数硬编码 | 15fps/1Mbps 不随设置变化 | 从 AppSettings 读取 |

### 9.3 功能缺陷 (P1)

| 位置 | 问题 | 修复方案 |
|------|------|----------|
| `VideoRecorder` 编码失败 | 临时 .mp4 文件未删除，留下垃圾文件 | 在 `encodeFramesToMp4` 失败时 `outputFile.delete()` |
| `VideoRecorder` 清理竞争 | 两个事件同时保存可能并发清理 | 添加同步锁 |
| `CameraService` WakeLock | 24 小时超时，超时后服务可能被杀 | 改为无超时获取 |
| 视频文件不支持 Range 请求 | 浏览器无法 seek 视频进度 | 实现 HTTP Range 头解析 |
| 设置修改不即时生效 | 需要重启服务 | 添加设置变更监听，动态重配置 |

### 9.4 安全问题 (P2)

| 问题 | 说明 | 修复方案 |
|------|------|----------|
| Web 服务无认证 | 局域网内任何人可访问摄像头 | 添加简单 PIN 码认证 |
| 无 HTTPS | MJPEG 和 API 均为明文 HTTP | 局域网场景风险较低，暂可接受 |
| 端口无校验 | 用户可输入特权端口(<1024)或不合法值 | 添加输入校验 |
| Web 前端 XSS | `innerHTML` 渲染视频列表 | 改用 DOM API 或转义 |

### 9.5 代码重复 (P2)

| 位置 | 说明 |
|------|------|
| `getLocalIpAddress()` | `MainActivity` 和 `CamWebServer` 中有完全相同的实现 |
| MJPEG 帧封装 | `MjpegStreamer.pushFrame()` 和 `MjpegInputStream` 各自独立构建 MJPEG multipart 头 |

---

## 10. 待开发功能清单

### 10.1 需求规格中尚未实现的功能

| 需求编号 | 功能 | 优先级 | 状态 | 说明 |
|----------|------|--------|------|------|
| F-BG-04 | 息屏后停止本机预览渲染 | P2 | 未实现 | 当前无 Preview UseCase，已隐式满足 |
| F-EVENT-04 | 保存时长前后各 N 秒 | P1 | 已实现 | 通过环形缓冲+后帧收集 |
| F-HISTORY-04 | 下载视频到查看设备 | P1 | 已实现 | Web 端已有下载按钮 |
| F-EXT-01 | RESTful API | P1 | 已实现 | /api/status, /api/events, /api/videos |
| F-EXT-02 | WebSocket 服务端 | P2 | 未实现 | StreamManager 已预留 FrameListener |
| F-EXT-03 | 模块化支持 RTSP/FLV | P2 | 部分完成 | 代码分层已做好，但未抽象流协议接口 |

### 10.2 优化与增强 (按优先级排序)

1. **[P0] 修复线程安全问题** — latestEventType/latestEventTime 加 @Volatile
2. **[P0] 增加 MjpegInputStream 管道缓冲** — 64KB → 256KB+
3. **[P0] 修复 WakeLock 超时** — 移除 24h 限制
4. **[P1] 提取公共工具类** — getLocalIpAddress() 去重
5. **[P1] 添加 max_storage_mb UI 控件** — 设置界面增加 SeekBarPreference
6. **[P1] 端口输入校验** — 限制 1024-65535 范围
7. **[P1] 视频文件 Range 请求** — 支持浏览器进度条拖动
8. **[P1] 预分配 YUV 缓冲** — 降低 VideoRecorder GC 压力
9. **[P1] 编码参数动态化** — 从 AppSettings 读取帧率和比特率
10. **[P2] 编码失败清理临时文件** — 添加 outputFile.delete()
11. **[P2] 静态资源缓存** — WebServer 缓存 asset 字节数组
12. **[P2] Web 端简单认证** — PIN 码或密码保护
13. **[P2] WebSocket 帧推送** — 利用 FrameListener 扩展点
14. **[P2] 二维码生成** — ZXing 生成包含访问地址的二维码
15. **[P2] 前置摄像头选项** — 添加到设置界面

---

## 11. 构建与部署

### 11.1 环境要求

- Android Studio Hedgehog (2023.1.1) 或更新
- JDK 17
- Android SDK 34
- Gradle 8.5（项目已配置 wrapper）

### 11.2 构建步骤

```bash
# 1. 下载 AI 模型到 assets 目录
# efficientdet_lite0.tflite → app/src/main/assets/
# yamnet.tflite → app/src/main/assets/

# 2. 用 Android Studio 打开项目或命令行构建
./gradlew assembleDebug

# 3. APK 输出位置
# app/build/outputs/apk/debug/app-debug.apk
```

### 11.3 模型文件获取

**人物检测模型**：
```bash
curl -o app/src/main/assets/efficientdet_lite0.tflite \
  https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/efficientdet_lite0.tflite
```

**音频分类模型**：
从 TensorFlow Hub 下载 yamnet.tflite，放入 `app/src/main/assets/`

### 11.4 安装与运行

```bash
# 安装到设备
adb install app-debug.apk

# 或通过 Android Studio 直接运行
```

首次启动需要授予：相机权限、麦克风权限（如果开启哭声检测）、存储权限（Android 9 以下）。

### 11.5 验证检查清单

- [ ] 应用启动后显示权限请求
- [ ] 授予权限后点击"启动监控"显示运行状态
- [ ] IP 地址正确显示
- [ ] 同一局域网浏览器访问 `http://{ip}:8080` 可看到 Web 页面
- [ ] Web 页面实时标签显示 MJPEG 视频流
- [ ] 开启人物检测后，走动触发事件
- [ ] 事件触发后录像保存到 Movies/HomeCam/
- [ ] Web 历史标签可播放和下载视频
- [ ] 息屏后监控继续运行
- [ ] 通知栏显示不可取消的监控通知
- [ ] 长时间运行（>1小时）无崩溃

---

## 12. 代码风格与约定

### 12.1 命名约定

- 类：PascalCase (`CameraService`, `VideoRecorder`)
- 方法/变量：camelCase (`processFrame`, `isRecordingEvent`)
- 常量：UPPER_SNAKE_CASE (`CHANNEL_ID`, `ACTION_START`)
- 包：全小写 (`com.homecam.app.service`)
- SharedPrefs key：snake_case (`motion_detection`, `web_port`)
- 布局 ID：snake_case (`toggle_button`, `status_text`)

### 12.2 依赖版本管理

在 `app/build.gradle.kts` 中使用局部变量管理版本号：
```kotlin
val cameraxVersion = "1.3.1"
implementation("androidx.camera:camera-core:$cameraxVersion")
```

### 12.3 协程使用

- 作用域：`CoroutineScope(SupervisorJob() + Dispatchers.Default)` 在 Service 中
- IO 操作：使用 `Dispatchers.IO`
- Room 查询：DAO 方法均为 `suspend`，需在协程或 `runBlocking` 中调用
- **注意**：`CamWebServer` 中使用 `runBlocking` 阻塞 NanoHTTPD worker 线程

### 12.4 资源管理

- Bitmap：每帧处理完毕后调用 `recycle()`
- ImageProxy：在 `finally` 块中调用 `close()`
- CameraProvider：Service 销毁时 `unbindAll()`
- WakeLock：Service 销毁时 `release()`
- AudioRecord：停止检测时 `stop()` + `release()`

---

## 13. 版本记录

### v1.1.0 (2026-05-13)

#### 新增功能

1. **多摄像头选择** — 主界面增加摄像头切换按钮，点击弹出选择对话框，可选取设备上所有可用摄像头（后置多个、前置等），运行时切换无需重启服务
   - `CameraService`: 使用 `CameraSelector.Builder.addCameraFilter()` + `availableCameraInfos` 精确绑定指定摄像头
   - `MainActivity`: 通过 `Camera2 CameraManager` 枚举所有摄像头并弹出选择列表
   - `AppSettings`: 新增 `getCameraIndex/setCameraIndex` 存储摄像头索引

2. **缩放系数替代固定分辨率** — 保留原始画面比例，不再因固定分辨率导致变形
   - 设置中"分辨率"改为"画面缩放"三档（1X / 0.75X / 0.5X）
   - CameraService.processFrame(): 宽高同比例缩放，保持长宽比
   - AppSettings: 新增 getScaleFactor() 方法

#### 修复

- 权限检查：POST_NOTIFICATIONS 改为可选，不再因拒绝通知权限而阻止服务启动
- UI 同步：通过 BroadcastReceiver 实时监听服务状态变化并刷新界面
- 默认缩放系数改为 0.75X，兼顾性能与画质

#### 技术调整

- 版本号：versionCode = 2, versionName = "1.1.0"
- 移除旧设置项 resolution，新增 scale_factor (key: "scale_factor", 默认 "0.75")
- 移除旧设置项 camera_lens，新增 camera_index (key: "camera_index", 默认 0)
### v1.2.0 (2026-05-14)

#### 修复

1. **AI 人物检测模型缺失** — EfficientDet-Lite0 模型文件已下载到 `assets/` 目录
   - 此前模型文件未包含在仓库中，导致视觉检测静默失败（`initVisualDetector()` 捕获异常后不报错）
   - 现在已从 MediaPipe 官方仓库下载 float32 版本模型

2. **`onEventDetected()` 竞争条件修复** — 修复了 `synchronized` 块拆分导致的帧丢失问题
   - 此前两个独立的 `synchronized(postEventFrames)` 块之间存在时间窗口：第一个块设置 `isRecordingEvent = true` 后，`processFrame()` 可能开始向 `postEventFrames` 添加帧，然后第二个块又执行 `clear()` 清空，导致这些帧丢失
   - 现在所有操作合并到一个 `synchronized` 块中，原子性完成

3. **`currentEventType` 线程安全** — 添加 `@Volatile` 注解
   - 该字段在 `onEventDetected()`（事件回调线程）中写入，在 `finishEventVideo()`（摄像头线程）中读取
   - 缺少 volatile 可能导致 `finishEventVideo()` 读到过期的 null 值，跳过录像保存

4. **`EventDetector.frameCounter` 线程安全** — 添加 `@Volatile` 注解

5. **`detect()` 空安全防护** — 添加 `results == null` 检查，防止潜在的 NPE

#### 新增

- EfficientDet-Lite0 模型文件 `efficientdet_lite0.tflite`（14MB）包含在 `assets/` 目录

#### 技术调整

- 版本号：versionCode = 3, versionName = "1.2.0"

### v1.2.1 (2026-05-14)

#### 修复

1. **录像花屏问题** — 修复不同 SoC（Qualcomm、MediaTek）硬件编码器对 YUV 数据格式的歧义
   - 此前使用 `COLOR_FormatYUV420Flexible` 让编码器自行推断格式，但不同厂商的硬件编码器对"灵活"格式的默认解析不同（部分按 NV21、部分按 NV12），导致 UV 通道错位 → 花屏
   - 改为显式指定 `COLOR_FormatYUV420SemiPlanar` (NV12)，与 `bitmapToYuv()` 实际输出的 UV 顺序（U在前V在后）精确匹配
   - 所有符合 Android CDD 的硬件编码器均支持该格式

2. **帧时间戳修正** — 录像 PTS 从绝对 Unix 时间戳改为相对首帧偏移
   - 此前使用 `System.currentTimeMillis() * 1000` 作为 PTS，数值极大（约 1.7e12 µs），部分播放器解码异常
   - 改为 `(timestamp - firstFrameTimestamp) * 1000`，确保 PTS 从 0 开始递增

#### 技术调整

- 版本号：versionCode = 4, versionName = "1.2.1"


### v1.2.2 (2026-05-15)

#### 新增

1. **支持逻辑多摄组切换（MIUI 隐藏摄像头）** — 解决小米 11 Ultra 等 MIUI 设备上 `LOGICAL_MULTI_CAMERA` 被系统隐藏，CameraX 只能看到主摄和前置，无法选择超广角和长焦的问题
   - Camera2 API 直接枚举摄像头 ID 0-5，主动探测被 MIUI 隐藏的物理摄像头
   - 通过焦距判定镜头类型（广角/长焦），自动标注摄像头标签
   - 直接 `openCamera(physicalId)` 绕过逻辑多摄 HAL 限制，而非通过 `setPhysicalCameraId()`
   - `ImageFormat.YUV_420_888` 替代 `PixelFormat.RGBA_8888`（MIUI HAL 不支持 RGBA 直接输出）
   - 手动 YUV→ARGB 转换（BT.601 色彩空间），纯软件渲染
   - 帧丢弃机制（AtomicInteger 计数 ≥2 时跳过），防止后台线程队列堆积导致 OOM
   - 切换摄像头时 500ms 延迟关闭旧 Camera2 会话，避免资源冲突

#### 修复

1. **Camera2 会话配置失败** — MIUI 隐藏 `LOGICAL_MULTI_CAMERA` 能力导致 `setPhysicalCameraId()` 调用失败
   - 直接打开物理摄像头 ID，绕过逻辑多摄能力检查

2. **RGBA_8888 格式不支持** — 小米 11 Ultra 的 Camera HAL 不原生支持 `PixelFormat.RGBA_8888`
   - 改用 `ImageFormat.YUV_420_888`（所有 Camera HAL 强制支持的格式）
   - 软件实现 YUV420→ARGB8888 像素格式转换

3. **ImageReader maxImages 缓冲耗尽崩溃** — 图片关闭在后台线程执行，主线程 3 帧后触发 `IllegalStateException`
   - `image.close()` 在 OnImageAvailableListener 中同步执行，确保帧缓冲区及时释放

4. **主线程 ANR** — 1280x720 分辨率 YUV→ARGB 转换（921,600 像素浮点运算）阻塞主线程
   - 主线程仅做 ByteBuffer→ByteArray 快速拷贝
   - YUV→ARGB 转换移至 `cameraExecutor` 后台线程

5. **首次启动监控主页面不刷新** — 点击启动后 CameraService 和 Web 服务成功运行，但主界面状态未更新为运行中
   - 原因：`startCameraService()` 调用后立即执行 `updateUI()`，此时服务尚未初始化完成，`isRunning` 仍为 `false`
   - 在 `startCameraService()` 和 `stopCameraService()` 末尾添加 `Handler.postDelayed({ updateUI() }, 500)`，确保服务状态就绪后刷新界面

6. **通知栏点击返回导致 Activity 多开** — 从通知栏点击 "HomeCam 监控中" 返回应用后，相当于新开了一层窗口，需要多次返回才能回到桌面
   - 原因：通知的 `PendingIntent` 未设置 `Intent.FLAG_ACTIVITY_CLEAR_TOP` 和 `Intent.FLAG_ACTIVITY_SINGLE_TOP`，每次点击都创建新的 `MainActivity` 实例
   - 在 `startForeground()` 和 `updateNotification()` 的通知 `Intent` 上添加 `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP` 标志，复用已有 Activity 实例

#### 技术调整

- 版本号：versionCode = 5, versionName = "1.2.2"
- CameraService：重构 `initCamera2()` / `createCamera2Session()`，移除 `setPhysicalCameraId()`
- CameraService：新增 `closeCamera2()`、`yuv420ToArgbBytes()`、`camera2PendingFrames` 帧计数器
- CameraService：重命名 `processCamera2Frame(image: Image)` → `processCamera2Pixels(pixels: IntArray, width: Int, height: Int)`
- MainActivity：`enumerateCameras()` 新增重复摄像头去重（相同 facing + 焦距签名）和 `actualLogicalId` 修正
- MainActivity：`startCameraService()` / `stopCameraService()` 增加 500ms 延迟 `updateUI()` 确保界面刷新
- CameraService：通知 `PendingIntent` 添加 `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP` 防止 Activity 多开

---

### v1.3.0 (2026-05-16)

#### 新增

1. **Web端摄像头切换** — 在 Web 管理页面可直接查看摄像头列表并切换摄像头，无需在手机端操作
   - 新增 `GET /api/cameras` 端点，返回枚举到的所有摄像头信息（ID、逻辑ID、标签）
   - 新增 `GET /api/camera/switch` 端点，接收 `cameraId` + `logicalCameraId` 查询参数，写入设置并发送切换指令
   - 增强 `/api/status` 返回 `current_camera_id` / `current_logical_camera_id` 字段

2. **Web端摄像头电源开关** — 在 Web 管理页面可直接控制摄像头的打开和关闭，关闭时 Web 服务器保持运行，仅关闭摄像头以降低功耗
   - 新增 `GET /api/camera/power?action=on|off` 端点，控制摄像头电源
   - 电源关闭时：`closeCamera2()` + `cameraProvider?.unbindAll()` + `streamer.clear()`
   - 电源打开时：调用 `initCamera()` 重新初始化摄像头
   - 新增 `cameraPoweredOn: AtomicBoolean` 标志，服务启动时受该标志控制
   - 增强 `/api/status` 返回 `camera_powered` 字段

3. **Web前端摄像头电源按钮** — 直播标签页摄像头选择器旁新增电源控制按钮
   - 打开状态：绿色 ⚡ 图标
   - 关闭状态：红色 ⚠ 图标带脉冲呼吸动画
   - 关闭摄像头时 MJPEG 流断开，显示"摄像头已关闭"
   - 打开时自动重新连接 MJPEG 视频流
   - 每 5 秒状态轮询同步电源状态

#### Web API 通讯协议

所有 Web 控制接口采用 **GET + URL 查询参数** 格式，与 NanoHTTPD 的 `session.parms` 自动解析配合使用。

##### 摄像头切换协议

```
请求: GET /api/camera/switch?cameraId={cameraId}&logicalCameraId={logicalCameraId}

参数:
  - cameraId: string, 必填, 目标物理摄像头 ID (如 "1", "2")
  - logicalCameraId: string, 可选, 默认为 cameraId 的值
    逻辑摄像头 ID (如 "0"), 用于 CameraX 绑定和 Camera2 多摄组关联

处理流程:
  1. 通过 CameraUtils.enumerateCameras() 校验 cameraId 是否有效
  2. 写入 AppSettings (cameraIndex / cameraId / logicalCameraId)
  3. 如服务正在运行: 发送 ACTION_SWITCH_CAMERA Intent 触发运行时切换
  4. 如服务未运行: 仅保存设置, 下次启动生效

响应:
  {
    "success": true,
    "cameraId": "1",
    "switching": true   // true=已切换, false=仅保存
  }

错误响应:
  {"success": false, "error": "Missing cameraId"}
  {"success": false, "error": "Invalid cameraId: xxx"}
```

##### 摄像头电源控制协议

```
请求: GET /api/camera/power?action={on|off}

参数:
  - action: string, 必填, "on" 或 "off"

电源关闭 (action=off):
  1. cameraPoweredOn = false
  2. closeCamera2() — 关闭 Camera2 设备/会话/ImageReader
  3. cameraProvider?.unbindAll() — 解绑 CameraX UseCase
  4. streamer.clear() — 清空 MJPEG 流, 前端显示断开
  5. Web 服务器保持运行, 所有 API 正常响应

电源打开 (action=on):
  1. cameraPoweredOn = true
  2. initCamera() — 重新初始化摄像头 (自动选择上次设置的摄像头)
  3. MJPEG 流恢复推送
  4. 前端自动重连视频流

响应:
  {
    "success": true,
    "power": true   // true=已打开, false=已关闭
  }

错误响应:
  {"success": false, "error": "Missing or invalid action (must be 'on' or 'off')"}
```

#### 技术调整

- 版本号：versionCode = 6, versionName = "1.3.0"
- 新增 `service/CameraUtils.kt`，将 `CameraInfo` 和 `enumerateCameras()` 从 `MainActivity` 提取为共享工具类
- `MainActivity.kt`：删除私有枚举方法，改用 `CameraUtils.enumerateCameras()`
- `web/CamWebServer.kt`：新增 `/api/cameras`、`/api/camera/switch`、`/api/camera/power` 路由
- `service/CameraService.kt`：新增 `ACTION_CAMERA_POWER`、`cameraPoweredOn` 标志、`initCamera()` 受标志守卫
- `assets/web/index.html`：新增摄像头选择器 UI 和电源按钮
- `assets/web/style.css`：新增选择器样式和电源按钮样式（脉冲动画）
- `assets/web/app.js`：新增摄像头列表加载、切换逻辑、电源控制、流重连和状态同步

---


### v1.3.1 (2026-05-16)

#### 新增

- **AI 睡眠检测**：基于 MediaPipe FaceLandmarker 面部特征点模型，通过 Eye Aspect Ratio (EAR) 计算睁闭眼状态
  - 连续 15 帧闭眼（约 3 秒，15fps+每3帧检测）→ 触发「宝宝睡着了」事件
  - 睡眠状态中连续 5 帧睁眼 → 触发「宝宝睡醒了」事件
  - 事件间隔冷却 10 秒，防止重复触发
  - 状态机：AWAKE ↔ SLEEPING，滞回逻辑避免状态抖动
- **模型文件**：需下载 `face_landmarker.task` 放置于 `app/src/main/assets/` 目录
  - 下载地址：`https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task`

#### 变更

- **UI 文字更改**：
  - 「智能检测」→「AI检测」
  - 「宝宝危险检测」→「睡眠检测」
  - 添加「宝宝睡着了」「宝宝睡醒了」事件显示文字
- **设置项**：`danger_detection` → `sleep_detection`（SharedPreferences key 变更）
- **API `/api/status`**：检测模式字段 `danger` → `sleep`

#### 修复

- **音频检测 buffer**：YAMNet 音频循环改为完整读取 15600 采样点后再分类，修复之前每次仅读取 ~1600 样本导致识别不准的问题
- **日志统一**：`EventDetector.kt` 中 `e.printStackTrace()` → `Log.e(TAG, ...)`

#### 技术调整

- `detection/EventDetector.kt`：
  - 新增 `FaceLandmarker` 初始化和闭眼检测方法（`initSleepDetector()`、`analyzeSleep()`、`onFaceLandmarkResult()`）
  - 状态机：`SleepState` 枚举（AWAKE/SLEEPING）
  - EAR 计算公式：`(|p2-p6| + |p3-p5|) / (2 * |p1-p4|)`，闭眼阈值 0.22
  - 右眼特征点索引：`[33, 159, 158, 133, 153, 145]`
  - 左眼特征点索引：`[362, 386, 385, 263, 374, 380]`
- `service/CameraService.kt`：
  - 帧处理路径（CameraX + Camera2）添加 `analyzeSleep()` 调用
  - `initDetectors()` 添加 `initSleepDetector()` 调用
  - Notification 标签添加 `wake_up` 事件
- `service/AppSettings.kt`：`isDangerDetectionEnabled()` → `isSleepDetectionEnabled()`
- `ui/SettingsActivity.kt`：`danger_detection` → `sleep_detection`
- `web/CamWebServer.kt`：API 状态响应字段更新
- `assets/web/app.js`：事件类型标签和图标添加 sleep/wake_up
- `res/values/strings.xml`：新增 `pref_sleep_detection`、`event_sleep`、`event_wake_up` 字符串
- 版本号保持不变（versionCode = 6, versionName = "1.3.0"，未发布）

---


### v1.3.2 (2026-05-16)

#### 修复

- **手机端事件不更新**：`onEventDetected()` 未发送 `ACTION_STATE_CHANGED` 广播，MainActivity 收不到通知。修复：事件触发后立即发送广播触发 `updateUI()`
- **`/api/events` 返回空**：接口读取 Room 录像数据库，未录像时无数据。修复：改为从独立内存列表 `CameraService.eventHistory` 返回
- **事件数据丢失**：`latestEventType/latestEventTime` 受 `recordingEnabled` 守卫，关闭录像时事件不被记录。修复：事件追踪与录像分离，始终记录

#### 变更

- **手机端事件日志**：新增 20 行事件日志窗口（MaterialCardView + TextView），显示最新 100 条事件
- **Web 端事件日志**：历史视频列表改为显示最新 10 条
- **`CameraService.eventHistory`**：新增 `EventRecord` 数据类，内存维护事件列表（上限 1000 条）
- **`/api/events`**：返回所有事件记录（无数量限制）
### v1.3.2 (2026-05-17) - 进出状态机

#### 新增

1. **人物移动检测状态机** — 从简单的"motion"触发改为基于进出状态机的事件推送
   - 无人状态(EMPTY) → 检测到人/动物 → 触发 `"enter"` 事件，记录识别标签（如 "person" / "cat" / "dog"）
   - 有人状态(OCCUPIED) → 持续 30 秒未检测到人/动物 → 触发 `"leave"` 事件，记录识别标签
   - "motion" 事件仍在每次检测时触发（带 5 秒冷却），但**仅用于触发录像保存**，不再写入事件日志

2. **EventRecord 新增 label 字段** — 用于存储 "enter"/"leave" 事件的识别标签
   - 手机端显示：`有person进入了` / `有cat离开了`
   - Web 端显示：`有person进入了` / `有cat离开了`

3. **字符串资源更新** — 新增 `event_enter` / `event_leave` 字符串

#### 事件类型完整列表

| 事件类型 | 触发条件 | 显示文本 | 是否写入日志 | 是否触发录像 |
|----------|---------|---------|:---:|:---:|
| `enter` | 无人→有人/动物进入画面 | `有%1\$s进入了` | ✓ | ✓ |
| `leave` | 有人/动物离开画面超过 30 秒 | `有%1\$s离开了` | ✓ | ✓ |
| `motion` | 每次检测到人/动物（5秒冷却） | 不显示 | ✗ | ✓ |
| `cry` | YAMNet 检测到婴儿哭声 | `婴儿哭声` | ✓ | ✓ |
| `sleep` | FaceLandmarker 闭眼检测连续 5 帧 → 睡着 | `宝宝睡着了` | ✓ | ✓ |
| `wake_up` | 睡眠状态中连续 5 帧睁眼 → 醒来 | `宝宝睡醒了` | ✓ | ✓ |

#### 技术调整

- `detection/EventDetector.kt`：
  - 新增 `OccupancyState` 枚举（EMPTY/OCCUPIED）
  - 新增 `occupancyState` / `lastOccupiedTime` / `currentOccupantLabel` / `occupancyTimeoutMs` 字段
  - `analyzeFrame()` 新增进出状态机逻辑：EMPTY→OCCUPIED触发"enter"，OCCUPIED→EMPTY触发"leave"，保留"motion"用于录像
- `service/CameraService.kt`：
  - `EventRecord` 新增 `val label: String = ""` 字段
  - 新增 `latestEventLabel` 字段
  - `onEventDetected()` 过滤掉 "motion"，不写入 eventHistory
  - `updateNotification()` 新增 enter/leave 显示支持
- `web/CamWebServer.kt`：`/api/events` 返回 `label` 字段，`/api/status` 返回 `latest_event_label`
- `ui/MainActivity.kt`：新增 enter/leave 事件类型映射，显示带标签的提示信息
- `assets/web/app.js`：新增 enter/leave 事件显示支持

---

### v1.4.0 (2026-05-18) - UDP 自动发现

#### 新增

1. **UDP 自动发现服务** — 在 CamWebServer 中新增 UDP 监听线程
   - 监听端口：45678 (UDP)
   - 协议格式：请求 `HOMECAM_DISCOVER` → 响应 `HOMECAM_RESPONSE|{ 设备名 }|{ IP }|{ 端口 }|{ 设备 ID }`
   - 设备名：`HomeCam-{ ANDROID_ID 后 6 位 }`
   - 设备 ID：`Settings.Secure.ANDROID_ID`

2. **UDP 生命周期管理** — 服务启动时启动监听，销毁时安全关闭

#### 技术调整

- `web/CamWebServer.kt`：新增 UDP 相关字段和方法
- `service/CameraService.kt`：接入 UDP 生命周期
- 版本号：versionCode = 7, versionName = "1.4.0"

---

*文档结束 — HomeCam v1.4.0 工作记录*
