# OpenClaw-Android 当前状态 (2026-05-27)

## 今日完成

### Phase 1: Node 端基础能力 — ScreenSkill + DeviceSkill ✅

**决策来源**：Hub 头脑风暴（2026-05-27），glm5 建议 "先修路（基础能力），再造车（Trigger 系统）"

**新增文件**：
- `app/src/main/java/ai/openclaw/android/skill/builtin/ScreenSkill.kt` (17KB)
  - `screen_screenshot` — 三级 fallback: MediaProjection → PixelCopy → ViewTree
  - `screen_click_at(x, y)` — 坐标点击
  - `screen_read` — 增强读屏
  - `screen_scroll_to(text)` — 滚动查找
- `app/src/main/java/ai/openclaw/android/skill/builtin/DeviceSkill.kt` (22KB)
  - `device_info` — 设备基本信息
  - `device_status` — 电池/存储/网络/运行时间
  - `device_health` — 健康评分
  - `device_running_apps` — 运行应用列表

**设计文档**：`docs/NODE-CAPABILITIES-DESIGN.md`

**编译状态**：✅ `./gradlew assembleDebug` BUILD SUCCESSFUL

**下一步**：真机验证 → Phase 2 (CameraSkill + FileXferSkill)

### Hub 协作

- 与 glm5-windows 完成头脑风暴，确定方向
- 向 hermes 发送 Hub Server 演进反馈，hermes 选择方案 A（Hub 原生 topic CRUD）
- 清理了 E 盘根目录 25+ 个旧 hub 临时文件，归档到 `_archive/hub-legacy-files/`

### 项目文档

- 更新 `docs/ASSESSMENT-AND-ROADMAP.md`（新增方向六，更新执行建议）
- 创建 `docs/CURRENT-STATUS-2026-05-27.md`
