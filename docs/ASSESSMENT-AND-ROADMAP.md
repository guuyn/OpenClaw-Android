# OpenClaw-Android 项目评估与路线图

> 评估日期：2026-05-16
> 项目：OpenClaw-Android (https://github.com/guuyn/OpenClaw-Android.git)

---

## 1. 项目概况

### 1.1 代码规模
| 指标 | 数值 |
|------|------|
| Kotlin 源文件 | **177 个** |
| 包数量 | **38 个** |
| 单元测试 | **30 个** |
| 仪器测试 | **10 个** |
| 总测试数 | **40 个** |
| 最近提交 | 2026-05-15 |

### 1.2 包结构与职责

```
ai.openclaw.android/
├── MainActivity.kt                          # 应用入口
├── ChatScreen.kt                            # 主聊天 UI
├── GatewayManager.kt                        # 网关连接管理
├── accessility/                             # 无障碍/自动控制
├── agent/                                   # Agent 会话核心
├── config/                                  # 应用配置
├── data/                                    # 数据层
│   ├── local/                               # Room 数据库 + DAO
│   └── model/                               # 数据模型
├── di/                                      # Koin 依赖注入
├── domain/                                  # 业务逻辑层
│   ├── agent/                               # Agent 路由、会话管理
│   ├── memory/                              # 记忆系统
│   ├── model/                               # 模型选择
│   └── session/                             # 会话压缩、Token 计数
├── feishu/                                  # 飞书集成
├── gateway/                                 # 网关协议 + 合同
├── memory/                                  # 向量记忆实现
├── ml/                                      # 端侧模型 (LiteRT/TFLite)
├── model/                                   # LLM 客户端 (SSE/本地)
├── notification/                            # 通知监听与分类
├── permission/                              # 权限管理
├── personalcenter/                          # 个人中心 (LLM 技能)
│   ├── models/                              # 数据模型
│   └── sources/                             # 数据源
├── security/                                # 安全模块
├── skill/                                   # 技能系统核心
│   └── builtin/                             # 内置技能 (天气/翻译/提醒等)
├── skills/                                  # 技能增强
├── trigger/                                 # 触发器系统
│   ├── dao/                                 # 触发器数据库
│   ├── models/                              # 触发器模型
│   ├── scheduler/                           # 调度器
│   └── skill/                               # 触发器技能
├── ui/                                      # UI 组件
│   ├── components/                          # 通用组件
│   └── theme/                               # Material3 主题
├── util/                                    # 工具类
├── viewmodel/                               # ViewModel 层
├── voice/                                   # 语音
│   ├── stt/                                 # 语音识别
│   ├── tts/                                 # 语音合成
│   └── ui/                                  # 语音 UI
```

### 1.3 技术栈

| 技术 | 版本/说明 |
|------|-----------|
| Kotlin | **2.3.0**, JVM target 17 |
| Compile SDK | **36** (Android 16) |
| Min SDK | **29** (Android 10) |
| Target SDK | 35 |
| UI | Jetpack Compose + Material3 |
| 依赖注入 | Koin |
| 数据库 | Room + KSP |
| 网络 | OkHttp 4.12 + SSE |
| JSON | Kotlin Serialization |
| 端侧模型 | LiteRT 2.1.3 + LiteRT-LM + ONNX |
| JS 引擎 | Rhino (QuickJS 备选) |
| 后台任务 | WorkManager |
| 崩溃上报 | Bugly |
| CI | Android Testing (Unit + Instrumented) |

### 1.4 最近 5 个提交

| 提交 | 内容 |
|------|------|
| c7a7d5d | ParentChildResolver 拓扑排序集成 (2026-05-15) |
| 8b621b2 | 移除未使用的 ResponseType import |
| a49583e | ChatViewModel 简化为零参数构造函数 |
| bad121f | 会话管理迁移到 GatewayContract |
| bf3f562 | 模型/会话生命周期从 ChatViewModel 迁移到 GatewayManager |

---

## 2. 代码质量评估

### 2.1 强项 ✅

1. **架构清晰**：data → domain → ui 三层分离，GatewayContract 抽象了网关协议
2. **Mock 体系完整**：MockGateway + 单元测试让状态层可测试
3. **A2UI 渲染器**：动态 UI 组件渲染 + ParentChildResolver 拓扑排序，支持 streaming 降级
4. **端侧能力**：LiteRT 本地推理 + ScriptEngine 动态技能，支持离线场景
5. **测试覆盖**：40 个测试覆盖了 Agent 路由、记忆、技能、会话压缩等核心模块
6. **网关重构完成**：会话管理已从 ViewModel 剥离，GatewayManager 统一管理生命周期

### 2.2 弱项/风险点 ⚠️

1. **TODO/FIXME 积压**（8 个未解决）：
   - `ActionExecutor.kt`：自动回复、脚本执行、JSON 解析 3 处未实现
   - `SmartNotificationListener.kt`：通知发送未实现
   - `LocalLLMClient.kt`：图像输入支持待完善
   - `ModelDownloadManager.kt`：SHA256 校验缺失
   - `ChatScreen.kt`：action 点击处理未实现
   - `GatewayManager.kt`：确认对话框未实现

2. **skill/ 与 skills/ 双目录**：命名混乱，职责不清晰

3. **feishu/ 模块耦合**：飞书集成直接嵌入主应用，可能影响可扩展性

4. **AccessibilityService 模块**：自动化控制是重头功能但目前状态不明

5. **Release 构建**：versionCode = 1，versionName = "1.0"，尚未发版

6. **无 CI/CD**：无 GitHub Actions 或 CI 配置

### 2.3 TODO/FIXME 汇总

| 文件 | 问题 | 优先级 |
|------|------|--------|
| ActionExecutor.kt | 自动回复/脚本执行/JSON 解析未实现 | 高 |
| SmartNotificationListener.kt | 通知发送未实现 | 中 |
| LocalLLMClient.kt | 图像输入 API 待更新 | 低 |
| ModelDownloadManager.kt | SHA256 校验缺失 | 中 |
| ChatScreen.kt | Action 按钮无响应 | 中 |
| GatewayManager.kt | 确认对话框 | 低 |

---

## 3. 测试覆盖评估

### 3.1 已覆盖 ✅

| 模块 | 测试数 | 类型 |
|------|--------|------|
| Agent 路由/会话 | 6 | Unit |
| Agent 会话集成 | 1 | Instrumented |
| 记忆系统 | 3 | Unit |
| 会话压缩/Token | 2 | Unit + 1 Instrumented |
| 内置技能 | 8 | Unit |
| 动态技能集成 | 1 | Unit |
| DAO 层 | 3 | Instrumented |
| Chat/Message UI | 2 | Instrumented |
| Gateway | 2 | Unit |
| 嵌入服务 | 2 | Unit + 1 Instrumented |

### 3.2 未覆盖的关键模块 ❌

| 模块 | 说明 |
|------|------|
| **Trigger 触发器系统** | 完整调度器、调度规则、执行流程无测试 |
| **PersonalCenter 个人中心** | LLM 优先级分类、去重引擎无测试 |
| **AccessibilityService** | 自动化控制无测试 |
| **Feishu 客户端** | 仅有 mock 测试 |
| **ViewModel 层** | ChatViewModel 无直接测试 |
| **Voice (STT/TTS)** | 语音流程无测试 |
| **Integration** | 仅有 ChatIntegration 一个集成测试 |
| **Permission** | 权限管理无测试 |

---

## 4. 下一步方向

### 方向一：完成 Trigger 触发器系统 🔥 **最高优先级**

**目标**：让 OpenClaw 能根据通知、时间、位置等条件**自动触发**技能执行，这是"主动式 AI 助手"的核心差异点。

**需要完成**：
- ActionExecutor 的 3 个 TODO 实现（自动回复、脚本执行、JSON 解析）
- SmartNotificationListener 通知发送实现
- 触发器规则引擎完善
- 触发器 UI 配置界面

**为什么重要**：这是 OpenClaw 区别于普通聊天 App 的关键特性 —— 不只是被动问答，而是**主动感知和执行**。

**工作量**：L (预计 2-3 周)
**依赖**：现有 Trigger DAO + 模型已完成，ScriptEngine 可用
**优先级**：🔥🔥🔥🔥🔥

---

### 方向二：端侧模型体验优化 🔥 **高优先级**

**目标**：让本地 LLM（Gemma 4 E4B）在端侧可用、流畅。

**需要完成**：
- ModelDownloadManager SHA256 校验
- LocalLLMClient 图像输入支持
- 模型下载进度 UI
- 端侧模型性能基准测试（token/s、内存占用）
- LiteRT-LM vs ONNX 对比

**为什么重要**：离线 AI 是隐私保护的关键场景，也是 OpenClaw 的技术壁垒。

**工作量**：M (预计 1-2 周)
**依赖**：LiteRT 集成已完成
**优先级**：🔥🔥🔥🔥

---

### 方向三：个人中心 + 技能生态完善

**目标**：完善技能发现、安装、管理闭环。

**需要完成**：
- skill/ 与 skills/ 目录合并或明确分工
- 技能商店/市场 UI
- 技能配置编辑器
- 技能运行沙箱隔离强化
- 个人中心的 LLM 优先级分类、去重引擎补全测试

**为什么重要**：技能生态是 OpenClaw 可扩展性的核心。

**工作量**：L (预计 2-3 周)
**依赖**：技能核心架构已存在
**优先级**：🔥🔥🔥

---

### 方向四：CI/CD + 发版准备

**目标**：建立自动化构建、测试、发版流程。

**需要完成**：
- GitHub Actions CI 配置（build + test + lint）
- Release 签名正式配置
- versionCode/versionName 管理策略
- APK 自动分发（百度网盘 or 其他）
- Bugly 正式上线

**为什么重要**：没有 CI/CD 就没有质量保障，没有版本管理就无法迭代。

**工作量**：S (预计 3-5 天)
**依赖**：无
**优先级**：🔥🔥🔥🔥

---

### 方向五：Accessibility 自动化控制

**目标**：实现 App 级别的自动化操作（模拟点击、读取屏幕内容、自动填写表单）。

**需要完成**：
- 当前 accessibility/ 模块状态评估
- 屏幕内容语义化解析
- 自动化动作执行器
- 安全边界（用户确认机制）

**为什么重要**：这是 OpenClaw 从"聊天助手"走向"AI 代理"的关键一步。

**工作量**：XL (预计 4-6 周)
**依赖**：Android Accessibility API
**优先级**：🔥🔥🔥

---

## 5. 执行建议

**建议顺序**：

1. **第 1 周**：CI/CD 搭建 (方向四) — 快速见效，为后续开发提供保障
2. **第 2-3 周**：Trigger 触发器 (方向一) — 核心差异化功能
3. **第 4 周**：端侧模型优化 (方向二) — 隐私 + 离线能力
4. **第 5-7 周**：技能生态 (方向三) — 可扩展性
5. **第 8-12 周**：Accessibility 自动化 (方向五) — 长期愿景

---

*本报告由 OpenClaw main + ACP Claude Code 协作生成*
*下次评估建议：每个方向完成后更新*
