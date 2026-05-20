# HomeCam 事件标签说明

## 事件类型总表

| 内部类型 | 事件日志 | 通知推送 | Web UI | 触发条件 |
|---------|---------|---------|--------|--------|
| `motion` | 不记录 | 不推送 | 人物移动 | EfficientDet 检测到人物/动物 |
| `cry` | 婴儿哭声 | 婴儿哭声 | 婴儿哭声 | YAMNet 音频分类 → 婴儿哭声 |
| `sleep` | 宝宝睡着了 | 宝宝睡着了 | 宝宝睡着了 | Face Landmarker 闭眼持续 > 阈值 |
| `wake_up` | 宝宝睡醒了 | 宝宝睡醒了 | 宝宝睡醒了 | 睡眠状态恢复睁眼 |
| `enter` | 有{标签}进入了 | 有{标签}进入了 | 有{标签}进入了 | 画面出现新人物（Occupancy 状态机） |
| `leave` | 有{标签}离开了 | 有{标签}离开了 | 有{标签}离开了 | 人物离开画面（Occupancy 状态机） |
| `fall` | 检测到有人摔倒 | 检测到有人摔倒 | 检测到有人摔倒 | Pose Landmarker 躯干角度 > 50° 持续约3秒 |
| `get_up` | 有人站起来了 | 有人站起来了 | 有人站起来了 | 摔倒后恢复站立（角度恢复正常） |
| `phone` | 有人在玩手机（{置信度}） | 有人在玩手机（{置信度}） | 有人在玩手机（{置信度}） | 手机重叠 + Hand Landmarker 握持手势 |

## 事件触发逻辑

### motion（人物移动）
- **模型**: EfficientDet-Lite0（COCO 数据集）
- **检测对象**: person, cat, dog
- **特殊规则**: 仅触发录像保存，不记入事件日志（避免刷屏）
- **标签映射**: `"person"` → `"人"`, `"cat"` → `"猫"`, `"dog"` → `"狗"`, 其它 → 原英文

### cry（婴儿哭声）
- **模型**: YAMNet（音频分类）
- **检测**: 滑动窗口分析音频分类结果，婴儿哭声置信度 > 0.7
- **冷却**: 30 秒内不重复触发

### sleep / wake_up（睡眠检测）
- **模型**: MediaPipe Face Landmarker
- **算法**: EAR（Eye Aspect Ratio）= 左眼高/宽比 与 右眼高/宽比 的平均值
  - 闭眼: EAR < 0.065
  - 睁眼: EAR ≥ 0.08
- **状态机**: AWAKE(睁眼) → 闭眼持续约20秒 → ASLEEP → 睁眼 → AWAKE(触发 wake_up)
- **冷却**: 60 秒

### enter / leave（进出检测）
- **模型**: EfficientDet-Lite0
- **算法**: Occupancy 状态机，跟踪人物 Bounding Box 中心点
- **标签**: 继承 motion 检测的人物标签（如"人"、"猫"）

### fall / get_up（跌倒检测）
- **模型**: MediaPipe Pose Landmarker
- **ROI**: 人物 Bounding Box + 30% padding 裁剪
- **算法**: 躯干角度 = 肩部中点 → 髋部中点 与垂直轴夹角
  - 阈值 > 50° 判定为可能摔倒
- **状态机**:
  ```
  STANDING → torsoAngle>50° 持续约3秒 → POSSIBLE_FALL(累计计数)
  POSSIBLE_FALL → 计数 ≥ threshold → FALL_EVENT (触发 "fall")
  POSSIBLE_FALL → torsoAngle恢复 → STANDING (计数归零)
  FALL_EVENT → torsoAngle恢复 → STANDING (触发 "get_up")
  ```
- **threshold**: 根据 FPS 和检测间隔动态计算 `((fps/interval)*3).coerceIn(5, 60)`
- **冷却**: 10 秒

### phone（玩手机检测）
- **模型**: EfficientDet-Lite0（识别 cell phone）+ MediaPipe Hand Landmarker
- **算法**: 双重置信度加权
  - `P(phoneNear)` = 手机 Bounding Box 与人物 Bounding Box 重叠 ? 1.0 : 0.0
  - `P(handGrasp)` = 检测到握持手势 ? 1.0 : 0.0
  - `combinedP = 0.4 × P(phoneNear) + 0.6 × P(handGrasp)`
  - **握持判定**: 至少 3 根手指弯曲（指尖 Y > 掌指关节 Y）
- **触发**: combinedP > 0.5
- **置信度**: `(combinedP × 100).toInt()` → 如 `"phone:70"` → 显示 `有人在玩手机（70%）`
- **冷却**: 10 秒

## 事件数据流

```
EventDetector.analyzeFrame()
  │
  ├── onEventDetected("motion")     ──→ CameraService: 仅录像，不记日志
  ├── onEventDetected("enter")      ──→ CameraService: 记日志 + 录像 + 通知
  ├── onEventDetected("leave")      ──→ CameraService: 记日志 + 录像 + 通知
  ├── onEventDetected("sleep")      ──→ CameraService: 记日志 + 录像 + 通知
  ├── onEventDetected("wake_up")    ──→ CameraService: 记日志 + 录像 + 通知
  ├── onEventDetected("fall")       ──→ CameraService: 记日志 + 录像 + 通知
  ├── onEventDetected("get_up")     ──→ CameraService: 记日志 + 录像 + 通知
  └── onEventDetected("phone:70")   ──→ CameraService: 解析→记日志+录像+通知
                                              │
                                              ├── eventHistory (最新1000条)
                                              ├── VideoRecorder.saveEventVideo()
                                              └── updateNotification() → 状态栏推送
```

## 录像文件命名

| 事件类型 | 文件名缩写 | 示例 |
|---------|-----------|------|
| motion | MOT | `HomeCam_MOT_20260520_143000.mp4` |
| cry | CRY | `HomeCam_CRY_20260520_143000.mp4` |
| fall | FAL | `HomeCam_FAL_20260520_143000.mp4` |
| get_up | GUP | `HomeCam_GUP_20260520_143000.mp4` |
| phone | PHN | `HomeCam_PHN_20260520_143000.mp4` |
| danger | DNG | `HomeCam_DNG_20260520_143000.mp4` |
| 其它 | EVT | `HomeCam_EVT_20260520_143000.mp4` |

## 设置项关联

| 检测功能 | 设置开关 | 依赖项 | 默认 |
|---------|---------|-------|------|
| 人物移动 | `motion_detection` | 无 | 开启 |
| 检测频率 | `detection_interval` | 无（全局） | 每3帧 |
| 跌倒检测 | `fall_detection` | 需开启人物移动 | 关闭 |
| 玩手机检测 | `phone_detection` | 需开启人物移动 | 关闭 |
| 婴儿哭声 | `cry_detection` | 无 | 关闭 |
| 睡眠检测 | `sleep_detection` | 无 | 关闭 |
