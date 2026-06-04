# Auto-Dev Branch 任务计划

> 创建时间: 2026-06-01
> 分支: `feature/auto-dev`
> 基于: `master` (v1.0.0 release)
> 指令: 用户授权自由推进，Hub 投票决策分歧

---

## 项目现状摘要 (2026-05-31 master)

- **23 个内置技能**，6730 行 Kotlin
- **Trigger 系统**: NotificationReply + CustomScript ✅ (v1)
- **Plugin SDK**: plugin-sdk + PluginManager ✅ (v1)
- **Node 能力**: Screen/Device/Camera/FileXfer/Shell/Notify ✅ (Phase 1-3)
- **A2UI 卡片**: 14 种 v2 卡片 ✅
- **UI**: 科幻主题 + 毛玻璃 + 气泡动画 ✅
- **v1.0.0** 已推送到 GitHub master + tag

---

## T001: Trigger 自动化系统 v2 (AI 驱动)

**优先级**: 🔥 P0
**状态**: 规划中

### 背景
已有 Trigger v1（NotificationReply + CustomScript），但：
- 依赖预定义脚本，灵活性差
- 无法根据上下文智能决策
- 没有与 LLM 联动

### 任务拆解
1. **TriggerEngine 核心** — 触发条件评估引擎（时间/事件/状态/ML 分类）
2. **AI Trigger 决策** — LLM 根据上下文决定是否执行 Trigger，而非硬编码脚本
3. **Trigger 日志 + 反馈** — 记录每次执行结果，用户可纠正
4. **Trigger 配置 UI** — 设置页新增 Trigger 管理界面

### 新增文件
- `app/src/main/java/ai/openclaw/android/trigger/TriggerEngine.kt`
- `app/src/main/java/ai/openclaw/android/trigger/AITriggerDecision.kt`
- `app/src/main/java/ai/openclaw/android/trigger/TriggerLogManager.kt`
- `viewmodel/TriggerViewModel.kt`
- `ui/trigger/TriggerScreen.kt`

---

## T002: 设备控制 Phase 1 (手电筒/音量/剪贴板/亮屏)

**优先级**: 🔥 P0
**状态**: 规划中

### 背景
之前多次列入 TODO 但一直没实现，用户感知最强。

### 任务拆解
1. **DeviceSkill 扩展** — 新增 `device_flashlight`, `device_volume`, `device_clipboard`, `device_wake_screen`
2. **权限处理** — 闪光灯/Camera2 API、音量控制、剪贴板 API
3. **A2UI 卡片** — 设备控制面板卡片

### 新增/修改文件
- 修改: `app/src/main/java/ai/openclaw/android/skill/builtin/DeviceSkill.kt`
- 新增: `app/src/main/java/ai/openclaw/android/skill/builtin/DeviceControlSkill.kt`

---

## T003: 技能 v2 卡片适配 (剩余 9 个)

**优先级**: P1
**状态**: 规划中

### 待适配
- MultiSearchSkill
- AppLauncherSkill
- CalendarSkill
- ContactSkill
- FileSkill
- LocationSkill
- SettingsSkill
- SMSSkill
- GenerateSkillSkill

### 任务拆解
每个技能：修改返回值格式 → 新增对应卡片类型 → A2UICardRouter 注册

---

## T004: Plugin System 增强

**优先级**: P1
**状态**: 规划中

### 背景
Plugin SDK v1 已有，但插件安装/卸载/热更新还不完善。

### 任务拆解
1. **插件市场本地索引** — 扫描可用插件
2. **插件安装/卸载** — APK 式安装流程
3. **插件权限管理** — 沙箱隔离
4. **插件热更新** — 无需重启 App

---

## 决策记录

| 议题 | 决策 | 理由 |
|------|------|------|
| 先做哪个？ | T001 + T002 并行 | T001 是架构升级，T002 用户感知最强 |
| T003 适配哪些？ | 先适配高频使用的（Search、Calendar、Location） | 80/20 原则 |
| T004 优先级？ | P1，等 T001 完成后再做 | 依赖 Trigger 的事件模型 |

---

*由 guyan 创建于 2026-06-01 23:07*
