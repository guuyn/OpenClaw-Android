# 项目状态 2026-04-25

## 本次变更

### 多模态图片输入支持 (Vision)

**目标**: 用户可以在聊天中发送图片（从相册选择或拍照），LLM 提供商支持视觉理解。

**新增文件**:
- `model/ImageUtils.kt` — 图片处理工具类（压缩、Base64 编码、URI 转换、验证）
- `res/xml/file_paths.xml` — FileProvider 路径配置（相机临时文件）

**修改文件**:
- `model/ModelModels.kt` — 新增 `ImageContent` 数据类（base64/mediaType/description）；`Message` 新增 `images` 字段
- `ChatScreen.kt` — UI 层：
  - 图片选择器（相册最多 3 张 / 拍照）
  - 图片预览条（LazyRow，支持删除）
  - 输入框附件按钮（DropdownMenu: 从相册/拍照）
  - 用户消息气泡内嵌图片显示（最大高度 200dp）
  - 发送按钮在有图片或文字时均可用
- `MainActivity.kt` — `sendMessage` 签名适配 `(String, List<ImageContent>)`
- `GatewayContract.kt` — `sendMessage` 新增 `images` 参数
- `GatewayManager.kt` — 传递 images 到 AgentSession / agentRegistry
- `agent/AgentSession.kt` — `handleMessage` / `handleMessageStream` 新增 `images` 参数，存入 Message history
- `model/OpenAIClient.kt` — 新增 `convertMessageToJsonElement`，支持 OpenAI Vision 格式 (`image_url` with data URI)，兼容 Qwen/DashScope
- `model/AnthropicClient.kt` — 支持 Anthropic Vision 格式 (`type: "image"`, `source: {type: "base64"}`)
- `model/LocalLLMClient.kt` — LiteRT-LM SDK 暂不支持多模态，使用 `buildVisionFallbackContent` 降级方案（将图片信息附加为文本描述），含 TODO 标记
- `AndroidManifest.xml` — 新增 `CAMERA` / `READ_MEDIA_IMAGES` 权限 + FileProvider 配置
- `gradle.properties` — 启用 `org.gradle.java.home` 指向 Android Studio JBR

**设计细节**:
- 图片压缩：最大边长 1200px，JPEG 质量 80%
- Base64 上限：4MB/张
- 单条消息最多 3 张图片
- OpenAI/Qwen 兼容格式：`{"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}`
- Anthropic 格式：`{"type": "image", "source": {"type": "base64", "media_type": "...", "data": "..."}}`
- LiteRT-LM 降级：文本标注 `[图片已附加，但端侧模型暂不支持视觉输入]` + 图片描述

### QA 验证 + 自动化测试基础设施

- ✅ QA 验证报告: 11/11 通过（详见 `QA-VERIFICATION-REPORT-20260425.md`）
- ✅ `DEBUG_SEND_MESSAGE` 广播接收器 — 用于自动化测试发送消息
- ✅ 恢复集成测试 — `AgentSessionTest` + `SessionIntegrationTest`

### 其他变更

- **无障碍感知能力** — `getCurrentApp` + `launchApp` + 结构化 UI 树
- **工具路由修复** — 修复 `get_current_app` 被误识别为 skill 工具
- **截图可用化** — MediaProjection 授权流程
- 测试适配：`ChatScreenTest.kt` / `MessageBubbleTest.kt` 适配新 `sendMessage` 签名

### 编译验证
- ✅ BUILD SUCCESSFUL (94 tasks, all up-to-date)
- ✅ 已安装到无线调试设备验证

## 下一步
1. 真机验证多模态图片输入功能（各 LLM 提供商）
2. LiteRT-LM 多模态 SDK API 跟进（TODO 已标记）
3. 图片消息的 A2UI 渲染优化（可选）

---

*最后更新：2026-04-25*
