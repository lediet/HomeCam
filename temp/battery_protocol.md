# HomeCam 电量查询协议

## 概述

HomeCam 通过 Web API 提供手机电量查询接口，供 HomeCam-TE 或其他客户端应用获取当前设备的电池剩余电量。

---

## API 接口

### `GET /api/status`

返回 HomeCam 服务的完整状态 JSON，其中包含 `battery_level` 字段。

#### 响应格式

```json
{
  "running": true,
  "ip": "192.168.1.100",
  "port": 8080,
  "url": "http://192.168.1.100:8080",
  "battery_level": 85,
  "...": "..."
}
```

#### `battery_level` 字段说明

| 值范围 | 含义 |
|--------|------|
| `0` ~ `100` | 当前电池剩余百分比 |
| `-1` | 未知（读取失败或设备不支持） |

#### 更新频率

`battery_level` 在 HomeCam 服务端每 **60 秒**更新一次。客户端可根据自身需求决定轮询间隔（建议不少于 5 秒）。

---

## 客户端实现建议

### 轮询方式

```kotlin
// 示例：每 30 秒查询一次电量
fun startBatteryPolling() {
    handler.postDelayed(object : Runnable {
        override fun run() {
            fetchBatteryLevel()
            handler.postDelayed(this, 30000L)
        }
    }, 5000L)
}

fun fetchBatteryLevel() {
    val url = "http://${host}:${port}/api/status"
    // 使用 OkHttp / Volley / 原生 HttpURLConnection 发起 GET 请求
    // 解析 JSON 获取 battery_level 字段
}
```

### 展示建议

- `battery_level >= 0`：显示为 `电量 X%`
- `battery_level == -1`：不显示电量或显示 `--`
- 低电量提醒：`<= 30%` 建议变色提醒，`<= 15%` 建议警告

---

## 注意事项

1. **无权限要求**：电量通过 Android `ACTION_BATTERY_CHANGED` 粘性广播获取，无需额外声明权限。
2. **服务端本地读取**：电量由 HomeCam 服务端在手机本地读取，客户端通过 HTTP 查询，无需直接访问系统广播。
3. **服务未运行时**：如果 HomeCam 服务未启动，`/api/status` 返回的 `battery_level` 可能为 `-1`。