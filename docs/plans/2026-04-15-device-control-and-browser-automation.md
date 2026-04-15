# 手机设备控制与浏览器自动化方案

> **创建时间**: 2026-04-15  
> **状态**: 草案（待 guyan-wsl2 确认）  
> **提交 deadline**: 2026-04-15 早上

---

## 一、设备控制能力扩展

### 1.1 P0 - 高价值 + 易实现（第一优先级）

| 功能 | Android API | 难度 | 预估工时 |
|------|-------------|------|----------|
| **手电筒开关** | `CameraManager.setTorchMode()` | ⭐ | 0.5 天 |
| **亮屏** | `PowerManager.wakeLock` / `input keyevent KEYCODE_WAKE_UP` | ⭐ | 0.5 天 |
| **锁屏** | `input keyevent KEYCODE_POWER` | ⭐ | 0.5 天 |
| **音量控制/静音** | `AudioManager.setStreamVolume()` | ⭐ | 0.5 天 |
| **剪贴板读写** | `ClipboardManager` | ⭐ | 0.5 天 |

### 1.2 P1 - 需要权限或中等复杂度

| 功能 | Android API | 难度 | 预估工时 |
|------|-------------|------|----------|
| **通知栏操作** | `NotificationListenerService` | ⭐⭐ | 1 天 |
| **清除通知** | `NotificationManager.cancel()` | ⭐ | 0.5 天 |
| **媒体控制** | `MediaSession` | ⭐⭐ | 1 天 |
| **WiFi 开关** | `WifiManager.setWifiEnabled()` | ⭐⭐ | 0.5 天 |
| **飞行模式** | `Settings.Global.AIRPLANE_MODE_ON` | ⭐⭐ | 0.5 天 |

### 1.3 架构设计

```kotlin
// 统一设备控制器
class AndroidDeviceController(
    private val context: Context,
    private val accessibilityBridge: AccessibilityBridge
) {
    // 设备信息
    fun getDeviceInfo(): DeviceInfo
    fun getBatteryInfo(): BatteryInfo
    fun getStorageInfo(): StorageInfo
    fun getNetworkInfo(): NetworkInfo
    
    // 设备控制
    fun setTorch(on: Boolean)
    fun wakeScreen()
    fun lockScreen()
    fun setVolume(stream: Int, level: Int)
    fun mute()
    
    // 剪贴板
    fun getClipboardText(): String
    fun setClipboardText(text: String)
    
    // 通知
    fun expandNotifications()
    fun clearNotification(id: String)
    
    // 媒体
    fun mediaPlay()
    fun mediaPause()
    fun mediaNext()
    fun mediaPrevious()
}
```

**Skill 注册**：将 `AndroidDeviceController` 注册为新的 Skill `DeviceControlSkill`，通过 Tool Calling 暴露给 LLM。

---

## 二、手机无头浏览器自动化

### 2.1 方案选择：Accessibility Service

**为什么选这个方案：**
- ✅ 系统级 API，跨应用操作
- ✅ 可以获取完整 UI 树
- ✅ 无需额外安装（如 Termux）
- ✅ 与现有 AccessibilityBridge 复用
- ❌ WebView 内部元素受限
- ❌ 无 JavaScript 执行能力

### 2.2 最小 MVP（Phase 1）

**核心能力：**
1. **getTree** — 获取简化 UI 树
2. **click** — 按条件点击元素

**UI 树结构（简化版）：**
```json
{
  "package": "com.android.chrome",
  "nodes": [
    {
      "text": "Google",
      "resource_id": "com.android.chrome:id/search_box",
      "bounds": [100, 200, 500, 250],
      "clickable": true,
      "class_name": "android.widget.EditText"
    }
  ]
}
```

**Agent 指令格式：**
```json
{
  "action": "click",
  "target": {
    "resource_id": "com.android.chrome:id/search_box"
  }
}
// 或
{
  "action": "click", 
  "target": {
    "text_contains": "搜索"
  }
}
// 或
{
  "action": "click",
  "target": {
    "bounds": [100, 200, 500, 250]
  }
}
```

### 2.3 扩展能力（Phase 2）

| 功能 | 说明 | 难度 |
|------|------|------|
| **inputText** | 向输入框输入文本 | ⭐⭐ |
| **scroll** | 滚动页面 | ⭐⭐ |
| **back** | 返回上一页 | ⭐ |
| **screenshot** | 截图（MediaProjection） | ⭐⭐ |
| **findElements** | 查找匹配的元素列表 | ⭐⭐ |

### 2.4 实现路径

1. **扩展现有 `AccessibilityBridge`**
   - 增加 `getUITree()` 方法
   - 增加 `clickBySelector(selector)` 方法
   - 过滤装饰性节点，简化输出

2. **HTTP 接口（可选）**
   - 绑定 `127.0.0.1:端口`
   - 随机 token 认证
   - 供外部工具（如 ADB 脚本）调用

3. **Skill 注册**
   - 新增 `BrowserAutomationSkill`
   - Tool: `get_page_tree`, `click_element`, `input_text`

### 2.5 与 ADB 的关系

| 场景 | 方案 | 说明 |
|------|------|------|
| 系统级操作 | ADB | 手电筒、WiFi、install APK |
| App 内 UI 操作 | Accessibility | 点击、输入、读取内容 |
| 远程调试 | ADB | 从 WSL2 端控制 |
| 本地日常交互 | Accessibility | 手机侧自主操作 |

**互补，不替代。**

---

## 三、实施计划

### Phase 1（1-2 天）- 最小可用
- [ ] `DeviceControlSkill` 实现 P0 能力（手电筒/亮屏/锁屏/音量/剪贴板）
- [ ] `AccessibilityBridge` 增加 `getUITree()` 和 `clickBySelector()`
- [ ] 注册新 Skill 到 SkillManager
- [ ] 基础测试

### Phase 2（2-3 天）- 浏览器自动化
- [ ] `BrowserAutomationSkill` 实现
- [ ] 输入文本功能
- [ ] 滚动/返回功能
- [ ] Chrome 浏览器适配测试

### Phase 3（后续）- 完善
- [ ] 媒体控制
- [ ] 通知操作
- [ ] HTTP 接口（如需要）
- [ ] 更多浏览器适配

---

## 四、风险评估

| 风险 | 影响 | 缓解 |
|------|------|------|
| Accessibility 被某些 App 屏蔽 | 部分场景不可用 | ADB 兜底 |
| WebView 内部元素无法获取 | 网页自动化受限 | 结合 screenshot + OCR |
| Android 版本兼容 | 不同版本行为差异 | 初始化时 capability check |
| FLAG_SECURE 禁止截图 | 银行类 App 无法截图 | 跳过截图，用 UI 树 |

---

## 五、讨论记录

### 2026-04-14 跨实例讨论

**guyan-wsl2 意见：**
- 设备控制：同意 P0 清单，补充了 ADB 实战命令
- 建议封装 `AndroidDeviceController` 类统一管理
- 无头浏览器：倾向方案2（Accessibility Service）
- 先跑通 `getTree + click` 最小闭环
- HTTP 接口绑定 127.0.0.1 + 随机 token

**glm5-windows 意见：**
- 同意方案2，开始起草文档
- 补充了剪贴板、媒体控制等 Windows 端需要的能力

---

> **下一步**: guyan-wsl2 确认方案后，开始 Phase 1 实现
