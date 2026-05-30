# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.0] - 2026-05-30

### 首个正式版本

#### Trigger 触发器系统
- ✅ NotificationReply — 支持系统 RemoteInput + 自动回复
- ✅ CustomScript — ScriptOrchestrator 动态脚本执行
- ✅ ActionExecutor — 4 种动作（SkillCall / AgentQuery / NotificationReply / CustomScript）
- ✅ EventBus — 事件总线 + 过滤器（Package / Keyword / Category）+ 去重 + 冷却
- ✅ CronScheduler — cron 表达式解析 + 定时调度
- ✅ 63 个单元测试全部通过

#### Node 端能力（Phase 1-3）
- ✅ ScreenSkill — 截屏三级 fallback（MediaProjection → PixelCopy → ViewTree）+ 坐标点击 + 增强读屏 + 滚动查找
- ✅ DeviceSkill — 设备信息 + 状态（电池/存储/网络/内存）+ 健康评分 + 运行应用
- ✅ CameraSkill — 拍照（前置/后置）+ 录像 + 最新相册
- ✅ FileXferSkill — 文件读写 + pull/push + 目录列表 + 沙箱安全
- ✅ ShellSkill — 白名单命令执行 + 黑名单双保险
- ✅ NotifySkill — 通知列表 + 操作 + 回复
- ✅ 全部 6 个 Skill 在 HMA-AL00 (API 29) 真机验证通过

#### 架构改进
- ✅ Plugin SDK + PluginManager（Phase 1）
- ✅ Gateway 重构 — 会话管理迁移至 GatewayManager，ChatViewModel 零参构造
- ✅ ParentChildResolver 拓扑排序组件注册
- ✅ AGP 8.2 / Kotlin 2.3.0 兼容性修复

#### CI/CD
- ✅ GitHub Actions：自动测试 + lint + Debug APK 构建
- ✅ Release 构建：打 tag 自动签名发布 APK
- ✅ versionCode 自动递增（基于 git commit 数）

---
