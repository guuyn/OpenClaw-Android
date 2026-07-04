# Spec: Skill v2 Card Adaptation - Batch 2

## Goal
适配剩余 10 个技能为 A2UI 卡片输出格式。每个技能返回 `[A2UI]{"type":"xxx","data":{...},"actions":[...]}[/A2UI]`。

## 已有卡片类型（renderer 已支持）
- weather, search_result, translation, reminder, calendar, location, contact, sms, app, settings

## 需要新增的卡片类型 + 对应技能

### 1. AppLauncherSkill → app_list + app_launch
- **app_list**: 应用列表卡片
  - data: {title, apps: [{name, packageName, icon?}], total}
  - actions: [{label:"🔍 搜索应用", action:"search_apps"}]
- **app_launch**: 应用启动确认
  - data: {title:"已打开应用", appName, packageName}

### 2. FileSkill → file_read + file_write + file_list
- **file_read**: 文件内容卡片
  - data: {title, fileName, path, size, content, charCount}
  - actions: [{label:"📋 复制内容", action:"copy"}]
- **file_write**: 写入确认
  - data: {title:"写入成功", fileName, path, size, mode}
- **file_list**: 目录列表
  - data: {title, path, storageLabel, items: [{name, type, size}], total}

### 3. SettingsSkill → settings_action + volume_status + bluetooth_status
- **settings_action**: 设置页面打开
  - data: {title, settingType, status}
- **volume_status**: 音量状态
  - data: {title, stream, level, maxLevel, ringerMode}
- **bluetooth_status**: 蓝牙状态
  - data: {title, enabled, note}

### 4. SMSSkill → sms_list + sms_send + sms_unread
- **sms_list**: 短信列表
  - data: {title, messages: [{sender, body, date}], total, unread}
  - actions: [{label:"📤 发送短信", action:"send_sms"}]
- **sms_send**: 发送确认
  - data: {title:"短信已发送", phoneNumber, message}
- **sms_unread**: 未读统计
  - data: {title, unreadCount}

### 5. DeviceSkill (控制类 tools) → device_action
- **device_action**: 设备操作确认
  - data: {title, action, status, detail}

### 6. DeviceSkill (系统信息 tools) → device_info (已有类型，需要确认)
- 已有 device_info 卡片？检查 renderer...

### 7. NotificationSkill → notification_list + notification_action
- **notification_list**: 通知列表
  - data: {title, notifications: [{title, body, time, app}], total, unread}
  - actions: [{label:"🗑️ 清除所有", action:"clear_all"}]
- **notification_send**: 发送确认（已有 notification 类型？）

### 8. ReminderSkill → 已有 reminder 卡片

### 9. TranslateSkill → 已有 translation 卡片

### 10. WeatherSkill → 已有 weather 卡片

## 已有卡片不需修改
- WeatherSkill ✅
- TranslateSkill ✅ 
- ReminderSkill ✅
- CalendarSkill ✅
- ContactSkill ✅
- LocationSkill ✅
- MultiSearchSkill ✅

## 需要修改的文件

### Skill 文件（添加 buildXxxCard + [A2UI] 输出）
1. `app/src/main/java/ai/openclaw/android/skill/builtin/AppLauncherSkill.kt`
2. `app/src/main/java/ai/openclaw/android/skill/builtin/FileSkill.kt`
3. `app/src/main/java/ai/openclaw/android/skill/builtin/SettingsSkill.kt`
4. `app/src/main/java/ai/openclaw/android/skill/builtin/SMSSkill.kt`
5. `app/src/main/java/ai/openclaw/android/skill/builtin/DeviceSkill.kt` (控制类 tools)
6. `app/src/main/java/ai/openclaw/android/skill/builtin/NotificationSkill.kt`

### Renderer 文件（添加新 typeTitle）
7. `app/src/main/java/ai/openclaw/android/ui/A2UIComposeRenderer.kt` (两处 typeTitle)
8. `app/src/main/java/ai/openclaw/android/ui/A2UICards.kt` (如有 title mapping)

## 输出格式规范
所有卡片使用 kotlinx.serialization.json 构建，与 MultiSearchSkill 一致：
```kotlin
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

@OptIn(ExperimentalSerializationApi::class)
private fun buildXxxCard(...): String {
    val card = JsonObject(mapOf(
        "type" to JsonPrimitive("xxx"),
        "data" to JsonObject(mapOf(...)),
        "actions" to JsonArray(listOf(JsonObject(mapOf(...))))
    ))
    return Json.encodeToString(JsonObject.serializer(), card)
}
```

返回时包裹: `SkillResult(true, "[A2UI]$cardJson[/A2UI]")`

## 完成标准
- `./gradlew assembleDebug` BUILD SUCCESSFUL
- `./gradlew test` 全部通过
