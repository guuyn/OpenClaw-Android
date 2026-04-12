# OpenClaw-Android Skill Schema v1.0

> ✅ 2026-04-11 确认定稿

## 概述

技能配置化 JSON Schema。定义技能元数据、工具描述、执行器配置，替代硬编码的 Kotlin 类。

## 设计原则

- `{{variable}}` 模板语法 — 所有动态值从参数替换
- `type` 决定执行器 — 执行引擎根据 type 分发到对应 Handler
- 自描述 — 每个技能自带 instructions，动态注入 system prompt
- A2UI 交给 LLM — 技能作者不需要理解卡片格式
- 安全沙盒 — 文件操作限制在 allowed_dirs，HTTP 限制域名
- 可扩展 — 新增执行器类型不需要改 Schema 结构

## 10 种执行器类型

| # | type | 用途 | 状态 |
|---|------|------|------|
| 1 | http | 网络请求 | ✅ 即用 |
| 2 | intent | 启动 Activity | ✅ 即用 |
| 3 | content_resolver | 数据库查询 | ✅ 即用 |
| 4 | location | 定位 | ✅ 即用 |
| 5 | alarm | 闹钟/提醒 | ✅ 即用 |
| 6 | sms | 发短信 | ✅ 即用 |
| 7 | file | 文件读写 | ✅ 即用 |
| 8 | sensor | 硬件传感器 | 🔮 预留 |
| 9 | shell | Shell 命令 | 🔮 预留 |
| 10 | accessibility | 无障碍操作 | 🔮 预留 |

## 完整文档

飞书文档：https://feishu.cn/docx/MhxudVUP5o2BArxuXvrcirsLnqd

---

*Schema v1.0 · 2026-04-11 · 已定稿*
