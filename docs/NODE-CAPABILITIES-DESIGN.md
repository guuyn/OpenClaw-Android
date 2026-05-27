# OpenClaw-Android Node 端基础能力完善方案

> 设计日期：2026-05-27  
> 设计者：Architect Subagent  
> 状态：Design Draft  
> 目标：将 OpenClaw-Android 从"聊天助手"升级为完整的 Node 节点，支持 OpenClaw 网关协议中的标准节点能力

---

## 1. 背景与目标

### 1.1 当前状态

OpenClaw-Android 已经具备以下能力：

| 能力 | 状态 | 说明 |
|------|------|------|
| 对话交互 | ✅ 完整 | AgentSession + 多 Agent 路由 + 流式输出 |
| 技能系统 | ✅ 完整 | SkillManager + 14 个内置技能 + 动态技能 |
| 无障碍自动化 | ✅ 基础 | AccessibilityBridge（12 个工具） |
| 截图 | ⚠️ 受限 | 依赖 MediaProjection，需用户授权，失败无 fallback |
| 记忆系统 | ✅ 完整 | 混合搜索 + 向量嵌入 + FTS |
| 语音 | ✅ 完整 | STT + TTS |
| 文件收发 | ❌ 缺失 | 无文件传输能力 |
| Shell 执行 | ❌ 缺失 | 无 exec/shell 能力 |
| Camera 拍照 | ❌ 缺失 | 有相机权限但无拍照工具 |
| 位置信息 | ⚠️ 基础 | LocationSkill 仅有 GPS 定位，无 Node 协议集成 |

### 1.2 Node 协议需求

OpenClaw 网关协议定义的标准 Node 能力包括：

| 协议能力 | 对应 Android 能力 | 当前状态 |
|----------|-------------------|----------|
| `camera_snap` | Camera 拍照 | ❌ 缺失 |
| `camera_clip` | Camera 录像 | ❌ 缺失 |
| `photos_latest` | 读取相册最新照片 | ⚠️ 部分（FileSkill 有文件操作但无相册 API） |
| `screen_record` | 屏幕截图 | ⚠️ 受限 |
| `location_get` | 获取位置 | ⚠️ 基础 |
| `notifications_list` | 读取通知 | ⚠️ 有 NotificationListener 但无工具暴露 |
| `notifications_action` | 操作通知 | ⚠️ 有 RemoteInput 但无工具暴露 |
| `device_status` | 设备状态 | ❌ 缺失 |
| `device_info` | 设备信息 | ❌ 缺失 |
| `device_health` | 设备健康 | ❌ 缺失 |
| `invoke` | 远程调用 | ❌ 缺失 |

### 1.3 设计目标

1. **补齐 Node 协议标准能力**：Camera、截图、文件、Shell、位置
2. **统一工具注册机制**：将新能力通过 Skill 系统注册，复用现有 Tool Call 链路
3. **保持架构一致性**：遵循现有 Skill/SkillTool 模式，不引入新的注册范式
4. **权限最小化**：按需请求权限，不提前申请未使用的权限
5. **渐进式交付**：分阶段实施，每阶段可独立验证

---

## 2. 架构设计

### 2.1 总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        GatewayManager                           │
│  (生命周期管理、组件初始化、Feishu 网关通信)                        │
└────────────┬──────────────────────────────┬─────────────────────┘
             │                              │
             ▼                              ▼
┌────────────────────┐         ┌──────────────────────────────────┐
│   AgentSession     │         │        SkillManager              │
│  (对话编排 +         │         │   (技能注册 + 工具执行)           │
│   Tool Call 循环)   │◄────────┤                                  │
└────────┬───────────┘         │  ┌─────────────┐  ┌────────────┐ │
         │                     │  │ Built-in    │  │ Dynamic    │ │
         │                     │  │ Skills      │  │ Skills     │ │
         ▼                     │  └──────┬──────┘  └─────┬──────┘ │
┌────────────────────┐         └─────────┼───────────────┼────────┘
│  AccessibilityBridge│                  │               │
│  (无障碍自动化)      │                  ▼               ▼
└────────────────────┘    ┌────────────────────────────────────┐
                          │     新增 Node 能力 Skills           │
                          │                                     │
                          │  ┌──────────┐ ┌──────────┐         │
                          │  │CameraSkill│ │ScreenSkill│         │
                          │  └──────────┘ └──────────┘         │
                          │  ┌──────────┐ ┌──────────┐         │
                          │  │FileXferSkill│ │ShellSkill│         │
                          │  └──────────┘ └──────────┘         │
                          │  ┌──────────┐ ┌──────────┐         │
                          │  │DeviceSkill│ │NotifySkill│         │
                          │  └──────────┘ └──────────┘         │
                          └────────────────────────────────────┘
```

### 2.2 模块划分

所有新能力统一通过 **Skill** 模式注册到 SkillManager，复用现有的 Tool Call 链路。

| 模块 | 文件 | 职责 | 依赖 |
|------|------|------|------|
| **ScreenSkill** | `skill/builtin/ScreenSkill.kt` | 截图增强（MediaProjection fallback + View 树快照） | MediaProjection, AccessibilityNodeInfo |
| **CameraSkill** | `skill/builtin/CameraSkill.kt` | 拍照、录像、相册读取 | Camera2 API, MediaStore |
| **FileXferSkill** | `skill/builtin/FileXferSkill.kt` | 文件收发、上传、下载 | Storage Access Framework, ContentResolver |
| **ShellSkill** | `skill/builtin/ShellSkill.kt` | Shell 命令执行（受限） | ProcessBuilder |
| **DeviceSkill** | `skill/builtin/DeviceSkill.kt` | 设备状态、信息、健康 | BatteryManager, ActivityManager, TelephonyManager |
| **NotifySkill** | `skill/builtin/NotifySkill.kt` | 通知列表、操作 | NotificationListenerService |

### 2.3 与现有系统的集成

```
AgentSession.setToolsWithSkills(
    accessTools = AccessibilityBridge.getTools(),  // 现有
    skillTools = SkillManager.getAllTools()        // 新增 Skills 自动包含
)
```

新增的每个 Skill 实现 `Skill` 接口，注册到 `SkillManager.loadBuiltinSkills()`，其工具自动获得 `{skillId}_{toolName}` 的命名空间。

---

## 3. 各能力详细设计

### 3.1 截图能力增强（ScreenSkill）

#### 3.1.1 问题分析

当前截图完全依赖 MediaProjection：
- MediaProjection 需要用户通过系统弹窗授权（`startActivityForResult`）
- 授权后由 `MyAccessibilityService.initMediaProjection()` 持有
- 如果用户拒绝或 MediaProjection 失效，截图完全不可用
- 无 fallback 机制

#### 3.1.2 设计方案：三级 Fallback

```
screenshot tool 调用
    │
    ├─ Level 1: MediaProjection (优先)
    │   └─ 需要用户已授权，质量最高，全屏真实截图
    │
    ├─ Level 2: Accessibility 视图树快照 (fallback)
    │   └─ 利用 AccessibilityNodeInfo 生成结构化 UI 描述
    │      + 可选：View 层级的简化视觉表示
    │
    └─ Level 3: PixelCopy API (Android 10+, API 29+)
        └─ 需要 Activity 上下文，可截取特定 Window
        └─ 需要应用在前台
```

#### 3.1.3 Tool 定义

**`screen_screenshot`**
```
名称: screen_screenshot
描述: 截取当前屏幕。返回 base64 编码的图片。
参数:
  - format: string, 可选, 默认 "jpeg"
    枚举: ["jpeg", "png"]
  - quality: number, 可选, 默认 80
    范围: 1-100 (仅 jpeg 有效)
  - fallback: boolean, 可选, 默认 true
    是否允许在 MediaProjection 不可用时使用 fallback
返回值: JSON 对象
  {
    "success": true,
    "method": "mediaprojection" | "pixelcopy" | "viewtree",
    "data": "base64..." | null,
    "viewtree": "结构化UI描述" | null,  // fallback 时返回
    "error": null | "错误信息"
  }
```

**`screen_read`**
```
名称: screen_read
描述: 读取当前屏幕的结构化 UI 信息（增强版 read_screen）。
      返回当前窗口的视图树、焦点元素、可交互元素列表。
参数:
  - max_depth: number, 可选, 默认 8
    视图树最大深度
  - interactive_only: boolean, 可选, 默认 false
    仅返回可交互元素
  - include_bounds: boolean, 可选, 默认 true
    包含屏幕坐标
返回值: JSON 字符串，结构化 UI 描述
```

**`screen_scroll_to`**
```
名称: screen_scroll_to
描述: 滚动到包含指定文本的元素位置。
参数:
  - text: string, 必填
    要滚动到的文本内容
  - direction: string, 可选, 默认 "down"
    枚举: ["up", "down"]
  - max_scrolls: number, 可选, 默认 5
    最大滚动次数
返回值: 操作结果描述
```

**`screen_click_at`**
```
名称: screen_click_at
描述: 在屏幕指定坐标点击。
参数:
  - x: number, 必填
    X 坐标（屏幕像素）
  - y: number, 必填
    Y 坐标（屏幕像素）
返回值: 操作结果描述
```

#### 3.1.4 数据流

```
Agent (LLM)
  │  Tool Call: screen_screenshot { format: "jpeg", quality: 80 }
  ▼
AgentSession.executeToolCall()
  │  识别为 screen_screenshot → 路由到 ScreenSkill
  ▼
ScreenSkill.execute("screenshot", params)
  │
  ├─ try MediaProjection → success → base64 JPEG
  ├─ catch → try PixelCopy (需 Activity 上下文)
  └─ catch → 返回 viewtree 结构化描述
  │
  ▼
返回 JSON 结果给 AgentSession
  │
  ▼
AgentSession 将结果作为 tool message 加入历史
  │
  ▼
LLM 收到截图结果，继续推理
```

#### 3.1.5 实现要点

- `ScreenSkill` 需要持有 `Context` 和 `MediaProjection` 状态引用
- PixelCopy 需要 Activity 的 `Window`，通过 `SkillContext` 扩展或回调注入
- ViewTree fallback 复用 `MyAccessibilityService.readScreenStructured()` 的逻辑

---

### 3.2 Camera 拍照能力（CameraSkill）

#### 3.2.1 问题分析

- 项目已有 `CAMERA` 权限声明（用于用户手动拍照发送图片）
- 但无 Agent 驱动的自动拍照能力
- 需要支持：拍照、录像、相册读取

#### 3.2.2 Tool 定义

**`camera_capture`**
```
名称: camera_capture
描述: 使用设备摄像头拍照。返回照片的 base64 编码。
参数:
  - camera: string, 可选, 默认 "back"
    枚举: ["back", "front"]
    选择前置或后置摄像头
  - resolution: string, 可选, 默认 "medium"
    枚举: ["low", "medium", "high"]
    低: 640x480, 中: 1280x720, 高: 1920x1080
  - label: string, 可选
    照片描述标签，用于后续检索
返回值: JSON 对象
  {
    "success": true,
    "base64": "data:image/jpeg;base64,...",
    "width": 1280,
    "height": 720,
    "timestamp": "2026-05-27T16:30:00Z",
    "camera": "back"
  }
```

**`camera_record`**
```
名称: camera_record
描述: 录制短视频。返回视频文件路径或 base64。
参数:
  - camera: string, 可选, 默认 "back"
    枚举: ["back", "front"]
  - duration_seconds: number, 可选, 默认 10
    录制时长，最大 60 秒
  - resolution: string, 可选, 默认 "medium"
    枚举: ["low", "medium", "high"]
返回值: JSON 对象
  {
    "success": true,
    "file_path": "/data/user/0/.../video.mp4",
    "duration_seconds": 10,
    "file_size_bytes": 2048576,
    "thumbnail_base64": "..."
  }
```

**`camera_gallery_latest`**
```
名称: camera_gallery_latest
描述: 获取相册中最新的照片。
参数:
  - count: number, 可选, 默认 1
    获取数量，最大 10
  - max_age_hours: number, 可选
    仅获取最近 N 小时内的照片
  - thumbnail: boolean, 可选, 默认 false
    是否返回缩略图而非原图
返回值: JSON 数组
  [
    {
      "uri": "content://media/...",
      "display_name": "IMG_20260527_163000.jpg",
      "date_taken": "2026-05-27T16:30:00Z",
      "base64": "data:image/jpeg;base64,...",
      "width": 4032,
      "height": 3024
    }
  ]
```

#### 3.2.3 权限需求

| 权限 | 用途 | Android 版本 |
|------|------|-------------|
| `CAMERA` | 拍照/录像 | 所有版本 |
| `READ_MEDIA_IMAGES` | 读取相册 | API 33+ |
| `READ_MEDIA_VIDEO` | 读取视频 | API 33+ |
| `READ_EXTERNAL_STORAGE` | 读取相册 | API 32 及以下 |

#### 3.2.4 实现要点

- 使用 **CameraX** 或 **Camera2** API 进行拍照
- 拍照流程：创建 ImageCapture → 设置分辨率 → captureToBuffer → 压缩 → base64
- 录像流程：创建 VideoCapture → 设置时长 → startRecording → 停止 → 返回文件路径
- 相册读取：使用 `ContentResolver` 查询 `MediaStore.Images.Media`，按 `DATE_TAKEN` 降序
- 图片压缩：复用现有的 `ImageUtils.compressBitmap()`（1200px max, JPEG 80%）

---

### 3.3 文件传输能力（FileXferSkill）

#### 3.3.1 问题分析

- Agent 无法主动发送文件给用户
- Agent 无法接收用户上传的文件（除了聊天中的图片）
- 需要建立文件收发通道

#### 3.3.2 Tool 定义

**`file_read`**
```
名称: file_read
描述: 读取设备上的文件内容。支持文本文件和图片。
参数:
  - path: string, 必填
    文件路径（绝对路径或相对路径）
  - max_bytes: number, 可选, 默认 100000
    最大读取字节数，防止读取大文件
  - encoding: string, 可选, 默认 "utf-8"
    文本编码
返回值: JSON 对象
  {
    "success": true,
    "content": "文件文本内容",
    "mime_type": "text/plain",
    "size_bytes": 1024,
    "truncated": false
  }
```

**`file_write`**
```
名称: file_write
描述: 将内容写入设备文件。
参数:
  - path: string, 必填
    目标文件路径
  - content: string, 必填
    文件内容（文本）
  - encoding: string, 可选, 默认 "utf-8"
  - overwrite: boolean, 可选, 默认 false
    是否覆盖已有文件
返回值: JSON 对象
  {
    "success": true,
    "path": "/path/to/file",
    "size_bytes": 2048
  }
```

**`file_list`**
```
名称: file_list
描述: 列出目录内容。
参数:
  - path: string, 可选, 默认应用私有目录
    目录路径
  - max_depth: number, 可选, 默认 1
    递归深度
  - pattern: string, 可选
    文件名过滤模式（glob）
返回值: JSON 数组
  [
    {
      "name": "file.txt",
      "path": "/path/to/file.txt",
      "is_directory": false,
      "size_bytes": 1024,
      "modified": "2026-05-27T16:00:00Z"
    }
  ]
```

**`file_share`**
```
名称: file_share
描述: 将文件分享给用户（通过系统分享 Intent 或保存到共享存储）。
参数:
  - path: string, 必填
    要分享的文件路径
  - mime_type: string, 可选
    MIME 类型
  - save_to_downloads: boolean, 可选, 默认 false
    同时保存到 Downloads 目录
返回值: JSON 对象
  {
    "success": true,
    "shared_via": "intent" | "downloads",
    "saved_path": "/storage/emulated/0/Download/..."
  }
```

**`file_download`**
```
名称: file_download
描述: 从 URL 下载文件到设备。
参数:
  - url: string, 必填
    文件 URL
  - filename: string, 可选
    保存的文件名，默认从 URL 推断
  - dest_dir: string, 可选, 默认应用私有目录
    目标目录
返回值: JSON 对象
  {
    "success": true,
    "path": "/path/to/downloaded_file",
    "size_bytes": 4096,
    "mime_type": "application/pdf"
  }
```

#### 3.3.3 安全边界

- **沙箱限制**：默认只能访问应用私有目录 (`context.filesDir`, `context.cacheDir`)
- **共享存储**：访问外部存储需要 `MANAGE_EXTERNAL_STORAGE` 权限（Android 11+）或使用 SAF (Storage Access Framework)
- **路径遍历防护**：所有路径操作需验证不超出允许范围（防止 `../../../etc/passwd`）
- **文件大小限制**：单次读取/写入限制 10MB，下载限制 100MB

---

### 3.4 Shell 执行能力（ShellSkill）

#### 3.4.1 问题分析

- Android 应用无 root 权限时 shell 能力受限
- 但仍可执行部分系统命令获取设备信息
- 需要严格的安全策略

#### 3.4.2 Tool 定义

**`shell_exec`**
```
名称: shell_exec
描述: 执行 shell 命令并返回输出。
      注意：受 Android 安全模型限制，只能执行非特权命令。
      危险命令（如 rm, dd, format）被禁止。
参数:
  - command: string, 必填
    要执行的命令
  - timeout_seconds: number, 可选, 默认 10
    命令超时时间
  - interactive: boolean, 可选, 默认 false
    是否为交互式命令
返回值: JSON 对象
  {
    "success": true,
    "stdout": "命令输出",
    "stderr": "",
    "exit_code": 0,
    "duration_ms": 150
  }
```

#### 3.4.3 安全策略

```kotlin
// 命令白名单（初始版本）
val ALLOWED_COMMANDS = setOf(
    // 设备信息
    "getprop", "dumpsys", "pm list", "pm path",
    // 系统状态
    "cat /proc/meminfo", "cat /proc/cpuinfo", "df", "free",
    "top -n 1", "ps",
    // 网络
    "ip route", "ip addr", "netstat", "ping",
    // 日志
    "logcat -d -t 100",
    // 包管理
    "pm list packages", "pm list packages -3",
    // 存储
    "ls", "ls -la", "find", "du",
    // 其他安全命令
    "date", "whoami", "id", "uname"
)

// 危险命令黑名单
val BLOCKED_PATTERNS = listOf(
    "rm -rf", "dd if=", "mkfs", "format",
    "chmod 777", "chown root", "su ",
    "reboot", "shutdown", "kill -9",
    ">", ">>", "|", "&", ";", "$(",  // 重定向和管道
    "/dev/", "/sys/"
)
```

#### 3.4.4 实现要点

- 使用 `ProcessBuilder` 执行命令
- 设置超时（`process.waitFor(timeout, TimeUnit.SECONDS)`）
- 读取 stdout 和 stderr 流（避免死锁，使用独立线程读取）
- 输出截断：stdout 最大 10000 字符
- 所有执行记录到 AuditLogger

---

### 3.5 设备状态与信息（DeviceSkill）

#### 3.5.1 Tool 定义

**`device_info`**
```
名称: device_info
描述: 获取设备基本信息。
参数: 无
返回值: JSON 对象
  {
    "model": "Pixel 8",
    "manufacturer": "Google",
    "brand": "google",
    "android_version": "14",
    "api_level": 34,
    "build_id": "AP1A.240305.018.A2",
    "screen_resolution": "1080x2400",
    "screen_density": 420,
    "cpu_abi": "arm64-v8a",
    "is_emulator": false
  }
```

**`device_status`**
```
名称: device_status
描述: 获取设备当前运行状态。
参数: 无
返回值: JSON 对象
  {
    "battery_level": 75,
    "battery_charging": true,
    "battery_health": "good",
    "battery_temperature_c": 32.5,
    "storage_total_bytes": 128000000000,
    "storage_available_bytes": 45000000000,
    "ram_total_bytes": 8000000000,
    "ram_available_bytes": 3200000000,
    "uptime_seconds": 86400,
    "network_type": "wifi",
    "network_connected": true,
    "wifi_ssid": "MyNetwork",
    "timezone": "Asia/Shanghai",
    "locale": "zh-CN"
  }
```

**`device_health`**
```
名称: device_health
描述: 设备健康检查，返回综合健康评分和详细信息。
参数: 无
返回值: JSON 对象
  {
    "overall_health": "good",  // "good" | "warning" | "critical"
    "score": 85,
    "checks": {
      "battery": { "status": "good", "level": 75 },
      "storage": { "status": "warning", "available_percent": 35 },
      "memory": { "status": "good", "available_percent": 40 },
      "temperature": { "status": "good", "celsius": 32.5 },
      "network": { "status": "good", "connected": true }
    },
    "recommendations": ["存储空间不足 40%，建议清理"]
  }
```

**`device_running_apps`**
```
名称: device_running_apps
描述: 获取当前运行的应用列表。
参数:
  - limit: number, 可选, 默认 20
    最大返回数量
  - user_only: boolean, 可选, 默认 true
    仅返回用户安装的应用
返回值: JSON 数组
  [
    {
      "package_name": "com.example.app",
      "label": "Example App",
      "pid": 12345,
      "importance": "foreground",
      "rss_kb": 150000
    }
  ]
```

#### 3.5.2 权限需求

| 能力 | 所需权限 | 说明 |
|------|----------|------|
| device_info | 无 | 全部使用 Build 类静态信息 |
| device_status (电池) | 无 | BatteryManager 无需权限 |
| device_status (存储) | 无 | StatFs 无需权限 |
| device_status (网络) | `ACCESS_NETWORK_STATE` | 已有 |
| device_running_apps | 无 (API 34+) / `QUERY_ALL_PACKAGES` | Android 14+ 限制 |

---

### 3.6 通知操作能力（NotifySkill）

#### 3.6.1 问题分析

- 已有 `SmartNotificationListener` 监听通知
- 但未通过 Tool 暴露给 Agent
- Agent 无法主动查询或操作通知

#### 3.6.2 Tool 定义

**`notify_list`**
```
名称: notify_list
描述: 获取当前活跃的通知列表。
参数:
  - limit: number, 可选, 默认 20
    最大返回数量
  - package_name: string, 可选
    按包名过滤
  - min_priority: string, 可选, 默认 "low"
    枚举: ["low", "default", "high", "max"]
    最低优先级
返回值: JSON 数组
  [
    {
      "key": "0|com.example.app|123|456",
      "package": "com.example.app",
      "title": "新消息",
      "text": "你好，这是一条消息",
      "time": "2026-05-27T16:30:00Z",
      "priority": "default",
      "category": "message",
      "actions": ["回复", "标记已读"]
    }
  ]
```

**`notify_dismiss`**
```
名称: notify_dismiss
描述: 清除指定通知。
参数:
  - key: string, 必填
    通知的 key（从 notify_list 获取）
返回值: 操作结果
```

**`notify_reply`**
```
名称: notify_reply
描述: 回复通知（通过 RemoteInput）。
参数:
  - key: string, 必填
    通知的 key
  - text: string, 必填
    回复内容
返回值: 操作结果
```

#### 3.6.3 实现要点

- 复用现有的 `SmartNotificationListener` 的通知缓存
- 通知 key 格式：`{userId}|{packageName}|{id}|{tag}`
- RemoteInput 回复需要反射调用 `NotificationAction.sendReply()`
- 权限：`BIND_NOTIFICATION_LISTENER_SERVICE`（已声明）

---

## 4. 数据流总览

### 4.1 Tool Call 完整链路

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          1. 用户消息到达                                  │
│                     GatewayManager.sendMessage(text, images)             │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                        2. Agent 路由                                      │
│              AgentRouter.route(text) → agentId                           │
│              AgentSessionManager.getOrCreate(agentId)                    │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    3. AgentSession 对话编排                                │
│                                                                          │
│  3a. buildMessages() → 包含 system prompt + history                      │
│  3b. modelClient.chat(messages, tools)                                   │
│       → tools = AccessibilityBridge.getTools() + SkillManager.getAllTools│
│       → 新增 Skills 的工具自动包含在此列表中                               │
│  3c. LLM 返回 tool_calls (如 screen_screenshot, camera_capture)          │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    4. 工具执行路由                                         │
│                                                                          │
│  executeToolCall(toolCall):                                              │
│    │                                                                     │
│    ├─ 检查是否为 accessibility tool (直接匹配名称)                         │
│    │   → AccessibilityBridge.execute(toolCall)                           │
│    │                                                                     │
│    ├─ 检查是否为 skill tool (包含 "_" 命名空间)                            │
│    │   → 解析 skillId (最长前缀匹配)                                      │
│    │   → PermissionManager.checkPermissions(skillId)                     │
│    │   → SkillManager.executeTool(fullName, params)                      │
│    │                                                                     │
│    └─ 未知工具 → 返回错误                                                 │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    5. Skill 执行                                          │
│                                                                          │
│  SkillManager.executeTool("screen_screenshot", params):                  │
│    │                                                                     │
│    ├─ parseToolName("screen_screenshot") → ("screen", "screenshot")      │
│    ├─ loadedSkills["screen"].tools.find { name == "screenshot" }         │
│    ├─ skillTool.execute(params)                                          │
│    │   → 实际 Android API 调用 (MediaProjection/Camera2/...)             │
│    │   → 结果序列化                                                      │
│    └─ 返回 SkillResult(success, output, error)                           │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    6. 结果返回                                            │
│                                                                          │
│  SkillResult.output → AgentSession                                       │
│    → 包装为 Message(role="tool", content=result, toolCallId=id)          │
│    → 加入 history                                                        │
│    → 进入下一轮 LLM 调用 (最多 50 轮)                                    │
│    → 最终生成回答 → 流式返回给用户                                        │
└──────────────────────────────────────────────────────────────────────────┘
```

### 4.2 新增 Skill 注册流程

```kotlin
// GatewayManager.initializeComponents() 中:

skillManager = SkillManager(service).apply {
    // 现有 skills...
    registerSkill(WeatherSkill())
    registerSkill(MultiSearchSkill())
    // ...

    // 新增 Node 能力 skills
    registerSkill(ScreenSkill(service, accessibilityBridge))
    registerSkill(CameraSkill(service))
    registerSkill(FileXferSkill(service))
    registerSkill(ShellSkill(service))
    registerSkill(DeviceSkill(service))
    registerSkill(NotifySkill(service))
}
```

---

## 5. 优先级排序与实施计划

### 5.1 优先级矩阵

| 优先级 | 能力 | 业务价值 | 技术难度 | 依赖风险 | 综合评分 |
|--------|------|----------|----------|----------|----------|
| **P0** | ScreenSkill (截图增强) | ⭐⭐⭐⭐⭐ | ⭐⭐ | 低 | **最高** |
| **P0** | DeviceSkill (设备状态) | ⭐⭐⭐⭐ | ⭐ | 无 | **最高** |
| **P1** | CameraSkill (拍照) | ⭐⭐⭐⭐ | ⭐⭐⭐ | 中 | **高** |
| **P1** | FileXferSkill (文件) | ⭐⭐⭐⭐ | ⭐⭐ | 中 | **高** |
| **P2** | ShellSkill (Shell) | ⭐⭐⭐ | ⭐⭐ | 低 | **中** |
| **P2** | NotifySkill (通知) | ⭐⭐⭐ | ⭐⭐ | 低 | **中** |

### 5.2 分阶段实施计划

#### Phase 1: 基础设施（Week 1-2）

**目标**：完成截图增强 + 设备状态，建立新 Skill 的开发模式

| 任务 | 交付物 | 工作量 |
|------|--------|--------|
| 创建 ScreenSkill | `ScreenSkill.kt` | 3d |
| 创建 DeviceSkill | `DeviceSkill.kt` | 1d |
| 集成到 GatewayManager | 修改初始化代码 | 0.5d |
| 单元测试 | ScreenSkillTest, DeviceSkillTest | 1d |
| 集成测试 | AgentSession 端到端 | 1d |
| 文档更新 | 更新 CLAUDE.md | 0.5d |

**里程碑**：Agent 可通过 `screen_screenshot` 和 `device_status` 获取设备信息

#### Phase 2: 媒体能力（Week 3-4）

**目标**：完成 Camera 拍照 + 相册读取

| 任务 | 交付物 | 工作量 |
|------|--------|--------|
| 创建 CameraSkill | `CameraSkill.kt` | 4d |
| 权限集成 | PermissionManager 扩展 | 1d |
| 单元测试 | CameraSkillTest (mock) | 1d |
| 仪器测试 | CameraSkillInstrumentedTest | 2d |

**里程碑**：Agent 可通过 `camera_capture` 和 `camera_gallery_latest` 获取图片

#### Phase 3: 文件与 Shell（Week 5-6）

**目标**：完成文件传输 + Shell 执行

| 任务 | 交付物 | 工作量 |
|------|--------|--------|
| 创建 FileXferSkill | `FileXferSkill.kt` | 3d |
| 创建 ShellSkill | `ShellSkill.kt` | 2d |
| 安全策略实现 | 命令白名单/黑名单 | 1d |
| 单元测试 | FileXferSkillTest, ShellSkillTest | 2d |

**里程碑**：Agent 可读写文件、执行受限 Shell 命令

#### Phase 4: 通知能力（Week 7）

**目标**：完成通知操作能力

| 任务 | 交付物 | 工作量 |
|------|--------|--------|
| 创建 NotifySkill | `NotifySkill.kt` | 2d |
| 与 SmartNotificationListener 集成 | 通知缓存接口 | 1d |
| 测试 | NotifySkillTest | 1d |

**里程碑**：Agent 可查询、操作通知

---

## 6. 风险点与缓解措施

### 6.1 Android 权限限制

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| MediaProjection 需要用户授权弹窗 | 截图可能不可用 | 三级 fallback 机制 |
| Camera 需要运行时权限 | 拍照可能不可用 | PermissionManager 集成，引导用户授权 |
| Android 14+ 限制后台 Activity 启动 | 应用启动可能失败 | 使用 FLAG_ACTIVITY_NEW_TASK + 前台服务 |
| Android 14+ 限制 RunningApps 查询 | 进程列表不完整 | 使用 `QUERY_ALL_PACKAGES` 或降级到可见应用 |
| Android 11+ 存储分区限制 | 文件访问受限 | 使用 SAF 或申请 `MANAGE_EXTERNAL_STORAGE` |

### 6.2 系统版本差异

| API 差异 | 影响范围 | 处理策略 |
|----------|----------|----------|
| MediaProjection (API 21+) | minSdk 29 无影响 | 无需兼容处理 |
| PixelCopy (API 27+) | minSdk 29 无影响 | 无需兼容处理 |
| CameraX vs Camera2 | 所有版本 | 优先 CameraX (更简洁的 API) |
| NotificationListener (API 18+) | minSdk 29 无影响 | 无需兼容处理 |
| Storage Access Framework | API 19+ | 使用 SAFFolderPicker |
| `QUERY_ALL_PACKAGES` | API 30+ | 降级到 `PackageManager.queryIntentActivities` |

### 6.3 安全考虑

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| Shell 命令注入 | 🔴 高 | 命令白名单 + 参数转义 + 危险命令黑名单 |
| 文件路径遍历 | 🔴 高 | 路径规范化 + 沙箱限制 |
| 截图包含敏感信息 | 🟡 中 | AuditLogger 记录所有截图操作 |
| Camera 滥用 | 🟡 中 | ToolSecurityPolicy 审批机制 |
| 通知内容泄露 | 🟡 中 | 通知内容仅在 Agent 上下文中使用，不外传 |
| Shell 执行耗时阻塞 | 🟡 中 | 超时控制 + IO Dispatcher |

### 6.4 性能考虑

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 截图 base64 体积大 | 网络传输慢 | JPEG 80% 压缩 + 1200px 限制 |
| Camera 拍照阻塞主线程 | UI 卡顿 | Dispatchers.IO + 图片压缩后台执行 |
| Shell 命令输出过大 | 内存溢出 | stdout 截断 10000 字符 |
| 文件读取大文件 | OOM | max_bytes 限制 + 流式读取 |

---

## 7. DeviceCapabilities 扩展

当前 `DeviceCapabilities` 缺少 Node 相关能力标识。需要扩展：

```kotlin
data class DeviceCapabilities(
    // 现有字段
    val hasScreen: Boolean = true,
    val hasTts: Boolean,
    val hasStt: Boolean,
    val hasRichText: Boolean = true,
    val hasNetwork: Boolean,
    val isInteractive: Boolean = true,
    val isAudioMuted: Boolean = false,
    
    // 新增 Node 能力字段
    val hasCamera: Boolean = true,           // 有摄像头
    val hasCameraFront: Boolean = true,      // 有前置摄像头
    val hasCameraRear: Boolean = true,       // 有后置摄像头
    val hasScreenCapture: Boolean = false,   // MediaProjection 可用
    val hasShellAccess: Boolean = true,      // 可执行受限 shell 命令
    val hasFileAccess: Boolean = true,       // 可访问文件系统
    val hasNotificationListener: Boolean = false, // 通知监听已启用
    val hasAccessibilityService: Boolean = false, // 无障碍服务已启用
    val screenResolution: String = "",       // "1080x2400"
    val storageAvailableBytes: Long = 0,     // 可用存储空间
    val batteryLevel: Int = 0,               // 电池电量百分比
    val isCharging: Boolean = false          // 是否充电中
)
```

扩展后的 `fromContext()` 方法将检测所有新增能力。

---

## 8. 接口定义汇总

### 8.1 ScreenSkill 接口

```kotlin
interface ScreenSkill : Skill {
    // 工具列表
    val tools: List<SkillTool> = listOf(
        screen_screenshot,   // 截图 (三级 fallback)
        screen_read,         // 结构化 UI 读取
        screen_scroll_to,    // 滚动到指定元素
        screen_click_at      // 坐标点击
    )
    
    // 依赖注入
    fun setMediaProjectionProvider(provider: MediaProjectionProvider)
    fun setActivityProvider(provider: ActivityProvider)  // for PixelCopy
}
```

### 8.2 CameraSkill 接口

```kotlin
interface CameraSkill : Skill {
    val tools: List<SkillTool> = listOf(
        camera_capture,      // 拍照
        camera_record,       // 录像
        camera_gallery_latest // 相册最新照片
    )
    
    // 运行时状态
    fun isCameraAvailable(): Boolean
    fun getCameraCount(): Int
}
```

### 8.3 FileXferSkill 接口

```kotlin
interface FileXferSkill : Skill {
    val tools: List<SkillTool> = listOf(
        file_read,           // 读取文件
        file_write,          // 写入文件
        file_list,           // 列出目录
        file_share,          // 分享文件
        file_download        // 下载文件
    )
    
    // 安全配置
    fun setSandboxRoot(root: File)
    fun setMaxUploadSize(bytes: Long)
    fun setMaxDownloadSize(bytes: Long)
}
```

### 8.4 ShellSkill 接口

```kotlin
interface ShellSkill : Skill {
    val tools: List<SkillTool> = listOf(
        shell_exec           // 执行命令
    )
    
    // 安全配置
    fun setCommandWhitelist(commands: Set<String>)
    fun setCommandBlacklist(patterns: List<Regex>)
    fun setMaxOutputLength(length: Int)
}
```

### 8.5 DeviceSkill 接口

```kotlin
interface DeviceSkill : Skill {
    val tools: List<SkillTool> = listOf(
        device_info,         // 设备信息
        device_status,       // 设备状态
        device_health,       // 设备健康
        device_running_apps  // 运行中的应用
    )
}
```

### 8.6 NotifySkill 接口

```kotlin
interface NotifySkill : Skill {
    val tools: List<SkillTool> = listOf(
        notify_list,         // 通知列表
        notify_dismiss,      // 清除通知
        notify_reply         // 回复通知
    )
    
    // 依赖注入
    fun setNotificationListener(listener: NotificationListenerProxy)
}
```

---

## 9. 测试策略

### 9.1 单元测试

| Skill | 测试重点 | Mock 策略 |
|-------|----------|-----------|
| ScreenSkill | fallback 逻辑、参数解析 | Mock MediaProjection, Mock AccessibilityService |
| CameraSkill | 参数验证、压缩逻辑 | Mock CameraX, Mock ImageCapture |
| FileXferSkill | 路径安全、沙箱限制 | Mock File 系统 (临时目录) |
| ShellSkill | 命令白名单/黑名单 | Mock ProcessBuilder |
| DeviceSkill | 数据格式化 | Mock System APIs (Build, BatteryManager) |
| NotifySkill | 通知解析、key 匹配 | Mock StatusBarNotification |

### 9.2 仪器测试

| Skill | 测试场景 | 设备要求 |
|-------|----------|----------|
| ScreenSkill | 真实截图、fallback 切换 | 真机，无障碍服务开启 |
| CameraSkill | 真实拍照、相册读取 | 真机，摄像头可用 |
| FileXferSkill | 真实文件读写 | 真机或模拟器 |
| ShellSkill | 真实命令执行 | 真机或模拟器 |
| DeviceSkill | 真实设备信息 | 真机或模拟器 |
| NotifySkill | 通知读取、操作 | 真机，通知监听开启 |

### 9.3 集成测试

- AgentSession 端到端：发送消息 → LLM 调用工具 → 执行 → 返回结果
- 多工具链：`device_status` → 根据结果调用 `screen_screenshot`
- 错误处理：权限拒绝、服务不可用时的降级行为

---

## 10. 后续演进方向

### 10.1 短期（3 个月内）

1. **截图 AI 分析**：截图结果直接送入 LLM 进行视觉理解（已有 Vision 能力）
2. **自动化流程**：将多个工具调用组合为自动化流程（如"打开微信 → 找到联系人 → 发送消息"）
3. **工具调用审计**：完整的 AuditLogger 集成，所有工具调用可追溯

### 10.2 中期（6 个月内）

1. **屏幕录制**：`camera_clip` 对应能力，录制屏幕操作视频
2. **多模态理解**：截图 + Camera 照片统一送入多模态 LLM
3. **远程文件同步**：通过网关协议实现设备间文件同步

### 10.3 长期（12 个月内）

1. **根权限模式**：在 root 设备上解锁完整 Shell 能力
2. **自定义手势**：支持复杂手势录制和回放
3. **跨设备协作**：多台 Android 设备通过网关协议协同工作

---

## 附录 A: 文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `skill/builtin/ScreenSkill.kt` | 新增 | 截图增强 Skill |
| `skill/builtin/CameraSkill.kt` | 新增 | Camera 拍照 Skill |
| `skill/builtin/FileXferSkill.kt` | 新增 | 文件传输 Skill |
| `skill/builtin/ShellSkill.kt` | 新增 | Shell 执行 Skill |
| `skill/builtin/DeviceSkill.kt` | 新增 | 设备状态 Skill |
| `skill/builtin/NotifySkill.kt` | 新增 | 通知操作 Skill |
| `domain/DeviceCapabilities.kt` | 修改 | 扩展 Node 能力字段 |
| `GatewayManager.kt` | 修改 | 注册新 Skills |
| `permission/PermissionManager.kt` | 修改 | 新增权限组定义 |
| `test/.../ScreenSkillTest.kt` | 新增 | 单元测试 |
| `test/.../CameraSkillTest.kt` | 新增 | 单元测试 |
| `test/.../FileXferSkillTest.kt` | 新增 | 单元测试 |
| `test/.../ShellSkillTest.kt` | 新增 | 单元测试 |
| `test/.../DeviceSkillTest.kt` | 新增 | 单元测试 |
| `test/.../NotifySkillTest.kt` | 新增 | 单元测试 |
| `androidTest/.../ScreenSkillInstrumentedTest.kt` | 新增 | 仪器测试 |
| `androidTest/.../CameraSkillInstrumentedTest.kt` | 新增 | 仪器测试 |

## 附录 B: 现有能力复用清单

| 现有组件 | 复用方式 | 新 Skill |
|----------|----------|----------|
| `MyAccessibilityService.readScreenStructured()` | 直接调用 | ScreenSkill |
| `MyAccessibilityService.takeScreenshot()` | 作为 Level 1 路径 | ScreenSkill |
| `MyAccessibilityService.clickAtPosition()` | 暴露为 tool | ScreenSkill |
| `ImageUtils.compressBitmap()` | 复用压缩逻辑 | CameraSkill, ScreenSkill |
| `SmartNotificationListener` | 读取通知缓存 | NotifySkill |
| `LocationSkill` | 参考实现模式 | 所有新 Skill |
| `PermissionManager` | 权限检查框架 | CameraSkill, FileXferSkill |
| `AuditLogger` | 操作审计 | ShellSkill, FileXferSkill |

---

*本设计文档由 Architect Subagent 基于对 OpenClaw-Android 代码库的全面分析生成*  
*下次评审建议：Phase 1 完成后更新实施状态*
