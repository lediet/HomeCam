# HOMECAM-TE 改造需求文档

## 概述

为 HOMECAM-TE 增加两个主要功能：
1. **录像查看功能** — 类似 Web 端的历史视频查看，列表展示+播放
2. **RTSP 流解析** — 在现有 MJPEG 推流基础上，支持 RTSP H.264 流解析和显示

---

## 一、录像查看功能

### 1.1 功能描述

在 HOMECAM-TE 中增加录像查看页面，可以查看 HomeCam 设备上录制的历史视频列表、缩略图、事件类型标签，并支持点击播放视频。

### 1.2 API 依赖

HomeCam 服务器提供以下 REST API：

#### GET /api/videos
返回该设备上所有录像文件列表。

**响应格式**（JSON 数组）：
```json
[
  {
    "fileName": "HomeCam_CRY_20260528_143015.mp4",
    "timestamp": 1745485395000,
    "eventType": "cry",
    "eventLabel": "",
    "durationSec": 6,
    "fileSize": 1048576,
    "url": "/videos/HomeCam_CRY_20260528_143015.mp4"
  }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| fileName | String | 文件名 |
| timestamp | Long | Unix 毫秒时间戳 |
| eventType | String | 事件类型（motion/cry/sleep/wake_up/enter/leave/fall/get_up/phone） |
| eventLabel | String | 事件附加标签（phone 时为百分比如 "70%"，enter/leave 时为 "person"/"cat"/"dog"/"bird"） |
| durationSec | Int | 视频时长（秒） |
| fileSize | Long | 文件大小（字节） |
| url | String | 视频下载路径（如 "/videos/HomeCam_CRY_...mp4"） |

#### GET /videos/{fileName}
返回 MP4 文件内容。
Content-Type: `video/mp4`

可通过拼接服务器地址获取完整 URL：`http://{ip}:{port}{url}`

#### GET /api/thumbnails/{fileName}
返回视频缩略图 JPEG（320×240）。
Content-Type: `image/jpeg`

完整 URL：`http://{ip}:{port}/api/thumbnails/{fileName}`

### 1.3 数据模型

```kotlin
data class VideoRecord(
    val fileName: String,
    val timestamp: Long,
    val eventType: String,
    val eventLabel: String = "",
    val durationSec: Int,
    val fileSize: Long,
    val url: String
)
```

### 1.4 UI 设计

#### 录像列表入口
- 在 CameraCard 底部状态栏右侧增加录像按钮（🎬 图标）
- 每次点击请求对应设备的 `/api/videos`

#### 录像列表页

**布局**：
```
┌─────────────────────────────────────┐
│ ← 返回    设备名 - 历史录像          │
├─────────────────────────────────────┤
│ ┌──────────┬─────────────────┬───┐ │
│ │ 缩略图    │ 2026-05-28 14:30│ ▶ │ │
│ │ 80×45    │ 婴儿哭声│ 6秒│1.0MB│   │ │
│ ├──────────┴─────────────────┴───┤ │
│ │ ┌──────────┬─────────────────┬───┐ │
│ │ │ 缩略图    │ 2026-05-28 14:25│ ▶ │ │
│ │ │          │ 有人进入│ 8秒│2.1MB│ │ │
│ │ └──────────┴─────────────────┴───┘ │
│ │              ...                   │
│ └─────────────────────────────────────┘
│          [视频播放器区域]              │
│  ┌────────────────────────────────┐  │
│  │                                │  │
│  │         MP4 播放器             │  │
│  │                                │  │
│  │    ▶ 播放 / 暂停 / 进度条      │  │
│  └────────────────────────────────┘  │
└─────────────────────────────────────┘
```

**详细说明**：

1. **视频列表**（上半部分，可滚动）
   - 使用 LazyColumn
   - 每项展示：缩略图（80×45）+ 日期时间 + 事件类型 + 时长 + 文件大小 + 播放按钮
   - 缩略图从 `/api/thumbnails/{fileName}` 异步加载
   - 按 timestamp 降序排列（最新在前）
   - 默认显示最新 20 条，可下拉加载更多
   - 事件类型颜色标记（参考 Web 端风格）
   - 点击视频项或播放按钮 → 在播放器区域播放该视频

2. **视频播放器**（下半部分）
   - 使用 ExoPlayer（Media3）或 Android MediaPlayer（简单场景）
   - 播放完整 MP4 URL：`http://{ip}:{port}/videos/{fileName}`
   - 支持播放/暂停、进度条拖动
   - 横竖屏自适应
   - 初始状态显示占位文字

### 1.5 事件类型与图标映射

```kotlin
fun getEventTypeLabel(type: String): String = when (type) {
    "motion" -> "人物移动"
    "cry" -> "婴儿哭声"
    "sleep" -> "宝宝睡着了"
    "wake_up" -> "宝宝睡醒了"
    "enter" -> "有人进入"
    "leave" -> "有人离开"
    "fall" -> "有人摔倒"
    "get_up" -> "有人站起来了"
    "phone" -> "玩手机"
    else -> type
}

fun getEventTypeColor(type: String): Color = when (type) {
    "motion", "enter", "leave" -> Color(0xFF4CAF50)  // 绿色
    "cry" -> Color(0xFFFF9800)  // 橙色
    "sleep", "wake_up" -> Color(0xFF2196F3)  // 蓝色
    "fall", "get_up" -> Color(0xFFF44336)  // 红色
    "phone" -> Color(0xFF9C27B0)  // 紫色
    else -> Color(0xFF888888)
}
```

### 1.6 录像列表刷新策略

- 进入录像列表页时自动拉取最新列表
- 提供下拉刷新（SwipeRefresh）
- 不自动轮询（与事件轮询不同，视频列表不需要频繁刷新）

---

## 二、RTSP 流解析

### 2.1 功能描述

在现有 MJPEG 推流显示的基础上，增加 RTSP H.264 流解析能力。当设备同时启用 RTSP 推流时，TE 端优先使用 RTSP 流显示（画质更好、延迟更低），并可降级到 MJPEG。

### 2.2 RTSP 流信息

| 属性 | 值 |
|------|-----|
| 协议 | RTSP 1.0 |
| 视频编码 | H.264/AVC |
| 传输模式 | UDP 或 TCP Interleaved |
| 默认端口 | 8554 |
| 流路径 | `/live` |
| 完整 URL | `rtsp://{ip}:8554/live` |

RTSP URL 可从 `/api/status` 响应的 `rtsp_url` 字段获取。

### 2.3 实现方案

有两种方案可选：

#### 方案 A：ExoPlayer/Media3（推荐）

**优势**：
- ExoPlayer 原生支持 RTSP（Android 12+ 原生 MediaPlayer 也支持）
- 自动处理 UDP/TCP 传输模式协商
- 支持 H.264 硬件解码，功耗低
- 集成简单，只需几行代码

**依赖**（build.gradle.kts）：
```kotlin
implementation("androidx.media3:media3-exoplayer:1.4.0")
implementation("androidx.media3:media3-exoplayer-rtsp:1.4.0")
```

**集成方式**：
```kotlin
// 在 Compose 中使用 AndroidView 嵌入 ExoPlayer
@Composable
fun RtspView(url: String, modifier: Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            val player = ExoPlayer.Builder(ctx)
                .setMediaItems(listOf(MediaItem.fromUri(url)))
                .build()
            player.playWhenReady = true
            PlayerView(ctx).apply {
                this.player = player
                useController = false  // 全屏显示，无控制栏
            }
        },
        modifier = modifier
    )
}
```

**注意**：ExoPlayer RTSP 支持需要 Android 12 (API 31) 以上，HOMECAM-TE 的 minSdk=26 意味着部分旧设备无法使用 RTSP。

#### 方案 B：替换 MJPEG 为 RTSP 流

- 在 CameraCard 和全屏模式中，如果设备支持 RTSP（`rtsp_enabled=true`），将 MjpegView 替换为 RtspView
- 在设备列表的 CameraState 中新增 `rtspEnabled: Boolean` 和 `rtspUrl: String` 字段
- 每次状态轮询时更新这些字段
- 保留 MJPEG 作为降级方案（RTSP 连接失败时自动回退）

### 2.4 状态轮询扩展

现有的 EventPoller 每 5 秒调用 `/api/status`，需要新增对 `rtsp_enabled` 和 `rtsp_url` 字段的解析：

```kotlin
// 当前 CameraState 需要新增字段
data class CameraState(
    val deviceId: String,
    // ... 现有字段 ...
    val mjpgUrl: String = "",
    val rtspEnabled: Boolean = false,  // 新增
    val rtspUrl: String = "",           // 新增
)
```

### 2.5 自动切换逻辑

```
CameraCard 显示
  → 检查 CameraState.rtspEnabled
    → true: 尝试连接 RTSP 流 (RtspView)
      → 成功: 显示 RTSP 流
      → 失败: 降级到 MJPEG 流 (MjpegView)
    → false: 使用 MJPEG 流 (MjpegView)
```

### 2.6 RTSP 与 MJPEG 显示差异

| 特性 | MJPEG | RTSP |
|------|-------|------|
| 延迟 | 较高（~1-3 秒） | 较低（~200-500ms） |
| CPU 占用 | 较高（软解 JPEG） | 较低（硬件解码 H.264） |
| 画质 | 有损（JPEG 压缩） | 更好（H.264） |
| 带宽 | 较高（每帧 JPEG） | 较低（帧间编码） |
| 实现复杂度 | 简单（HTTP+JPEG 解析） | 中等（RTSP+H.264 解码） |
| 兼容性 | 所有 Android 版本 | 需 API 31+（ExoPlayer RTSP） |

---

## 三、设备数据结构变更

### 3.1 CameraDeviceEntity（Room）

当前实体无需变更（MJPEG URL 由 IP+Port 拼接，RTSP URL 由 API 获取）。

### 3.2 CameraState（运行时状态）

```kotlin
data class CameraState(
    val deviceId: String,
    val isOnline: Boolean = false,
    val isPoweredOn: Boolean = true,
    val latestEvent: String? = null,
    val latestEventTime: Long = 0L,
    val latestEventLabel: String = "",
    val availableCameras: List<CameraInfo> = emptyList(),
    val currentCameraId: String = "",
    val mjpgUrl: String = "",       // http://ip:port
    val rtspEnabled: Boolean = false, // 新增
    val rtspUrl: String = ""         // 新增 - rtsp://ip:8554/live
)
```

### 3.3 ApiClient 新增方法

```kotlin
// 已有方法
suspend fun getStatus(): Result<StatusResponse>
suspend fun getEvents(): Result<List<EventResponse>>
suspend fun getCameras(): Result<CamerasResponse>
suspend fun switchCamera(cameraId: String, logicalCameraId: String): Result<*>/
suspend fun setPower(on: Boolean): Result<*>

// 新增方法
suspend fun getVideos(): Result<List<VideoRecord>>
```

### 3.4 StatusResponse 扩展

```kotlin
// 当前解析的字段 + 新增
data class StatusResponse(
    val running: Boolean,
    val ip: String,
    val port: Int,
    val url: String,
    val latest_event: String?,
    val latest_event_time: Long,
    val latest_event_label: String,
    val current_camera_id: String?,
    val camera_powered: Boolean,
    val rtsp_url: String?,  // 新增
    val rtsp_enabled: Boolean?, // 新增
    val mjpg_enabled: Boolean? // 新增，判断是否可用 MJPEG
)
```

---

## 四、改造文件清单

| 文件 | 改动说明 |
|------|---------|
| `model/Models.kt` | 新增 `VideoRecord` 数据类；`CameraState` 新增 `rtspEnabled`/`rtspUrl` 字段；`StatusResponse` 新增 `rtsp_url`/`rtsp_enabled`/`mjpg_enabled` 字段 |
| `data/CameraRepository.kt` | 状态轮询结果中解析 RTSP 字段；新增 `getVideos()` 方法 |
| `network/ApiClient.kt` | 新增 `getVideos()` API 调用；`getStatus()` 解析 RTSP 字段 |
| `ui/CameraCard.kt` | 底部新增录像按钮入口 |
| `ui/TEGridScreen.kt` | 新增录像列表页面路由；新增 RTSP 切换逻辑（可选） |
| `ui/MainActivity.kt` | 新增录像页面状态管理 |
| `ui/MainViewModel.kt` | 新增视频列表状态管理 |
| **新文件** `ui/VideoHistoryScreen.kt` | 录像列表页 Compose 组件 |
| **新文件** `ui/VideoPlayerView.kt` | 视频播放器 Compose 组件（ExoPlayer） |
| **新文件** `ui/RtspView.kt` | RTSP 流显示 Compose 组件 |
| `app/build.gradle.kts` | 新增 ExoPlayer RTSP 依赖 |

---

## 五、注意事项

1. **RTSP 兼容性**：ExoPlayer RTSP 扩展要求 API 31+，如果 minSdk 保持 26，需要在代码中判断版本，低版本自动使用 MJPEG
2. **视频播放网络**：录像文件通过 HTTP 传输，确保 TE 端与 HomeCam 设备在同一局域网
3. **缩略图加载**：使用 Coil 或 Glide 加载缩略图，Coil 已在 Gradle 声明为依赖但未使用，可直接用
4. **播放器生命周期**：ExoPlayer 需要在 Composable 的 DisposableEffect 中 release，防止内存泄漏
5. **RTSP 切换时机**：进入页面时尝试连接 RTSP，不阻塞 UI；RTSP 连接失败时界面仍然显示，后台自动降级到 MJPEG
6. **录像列表为空**：显示空状态占位