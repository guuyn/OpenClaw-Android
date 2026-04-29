# A2UI 卡片与聊天框风格融合 — 技术方案

> **日期**: 2026-04-28  
> **作者**: 架构师  
> **状态**: 待评审  
> **影响范围**: `A2UICards.kt`, `ChatScreen.kt` (AiMessageBubble, UserMessageBubble)

---

## 1. 问题诊断

### 1.1 现状概览

OpenClaw-Android 使用 Sci-Fi 暗色主题，聊天气泡有统一的视觉语言：
- **AI 气泡**: 深色背景 (`#1E293B`) + 左侧 2dp 青色霓虹边框 + 不对称圆角
- **用户气泡**: 青蓝渐变背景 + 不对称圆角

A2UI 卡片（14 种类型）通过 `CardContainer` 渲染在气泡内部，但卡片有自己的独立样式系统，导致**卡片在气泡内看起来像"贴上去的外来物"**，而非气泡的自然延伸。

### 1.2 具体不一致项

#### ① 圆角不一致

| 组件 | 圆角 |
|------|------|
| AI 气泡 | `topStart=16, topEnd=16, bottomStart=4, bottomEnd=16` (不对称，左下角尖锐) |
| 用户气泡 | `topStart=16, topEnd=16, bottomStart=16, bottomEnd=4` (不对称，右下角尖锐) |
| A2UI 卡片 | `RoundedCornerShape(16.dp)` (四角均匀 16dp) |

**问题**: 卡片四角均匀圆角，与气泡的不对称圆角不匹配。当卡片作为气泡的第一个/最后一个子元素时，应该继承气泡的圆角。

#### ② 背景透明度不一致

| 组件 | 背景色 |
|------|--------|
| AI 气泡 | `SciFiAiBubbleBg = #1E293B` (完全不透明) |
| A2UI 卡片 | `SciFiSurfaceVariant.copy(alpha = 0.7f)` = `#1E293B` @ 70% 透明度 |

**问题**: 卡片背景 70% 透明度，在气泡内部会透出气泡背景色，造成"卡片是悬浮层"的视觉错觉。卡片应该与气泡背景**完全融合**（不透明或 100% 不透明度）。

#### ③ 边框/描边不一致

| 组件 | 边框 |
|------|------|
| AI 气泡 | 左侧 2dp 青色线 (`SciFiAiBubbleBorder = #06D6A0`)，无外边框 |
| A2UI 卡片 | 顶部 2dp 青色线 + 1dp 外边框 (`SciFiOutline = #334155`) |

**问题**: 
- 卡片有 1dp 外边框，在气泡内形成"框中框"效果
- 卡片顶部有 2dp 青色线，但气泡本身已经有左侧青色线，造成**装饰元素冲突**
- 气泡没有外边框，卡片有外边框，视觉层级混乱

#### ④ 阴影/高程不一致

| 组件 | 阴影 |
|------|------|
| AI 气泡 | 无阴影 (`tonalElevation=0, shadowElevation=0`) |
| A2UI 卡片 | 使用 `Card()` 组件，默认 Material3 阴影 |

**问题**: `Card()` 自带 elevation 阴影，在扁平化的气泡内显得突兀。气泡是扁平风格，卡片有阴影 = 风格冲突。

#### ⑤ 内边距不一致

| 组件 | 内边距 |
|------|--------|
| AI 气泡 | `start=14dp, top=12dp, end=12dp, bottom=12dp` |
| A2UI 卡片 | `16dp` 四边均匀 |

**问题**: 卡片 16dp 内边距比气泡的 12dp 更宽，导致卡片内容比气泡文字更"缩进"，视觉不连贯。

#### ⑥ 颜色系统混用

| 使用位置 | 使用的颜色系统 |
|----------|---------------|
| 气泡主体 | 自定义 SciFi 色板 (`SciFiOnSurfaceVariant`, `SciFiAiBubbleBg` 等) |
| 卡片内部元素 | **混用** MaterialTheme 色板 (`surfaceVariant`, `primaryContainer`, `tertiaryContainer`) 和 SciFi 色板 |

**具体问题**:
- `CardHeader` 使用 `SciFiPrimary` / `SciFiOnSurfaceVariant` ✅
- 但 `WeatherCard` 的额外信息标签使用 `MaterialTheme.colorScheme.surfaceVariant` 和 `onSurfaceVariant` ❌
- `ReminderCard` 的状态标签使用 `MaterialTheme.colorScheme.tertiaryContainer` ❌
- `TranslationCard` 的原文区域使用 `MaterialTheme.colorScheme.surfaceVariant` ❌
- `ActionConfirmCard` 的详细信息面板使用 `MaterialTheme.colorScheme.surfaceVariant` ❌

**问题**: 卡片内部大量使用 MaterialTheme 默认色板，而非 SciFi 自定义色板。虽然 Theme 层将 MaterialTheme 映射到了 SciFi 色板，但部分颜色（如 `tertiaryContainer`、`errorContainer`）的 alpha 混合效果与 SciFi 主题不协调。

#### ⑦ 按钮风格不一致

| 组件 | 按钮样式 |
|------|---------|
| 卡片 Primary 按钮 | `OutlinedButton` + `RoundedCornerShape(12.dp)` + `SciFiPrimary` 文字 + Material3 默认边框 |
| 卡片 Secondary 按钮 | `TextButton` + `RoundedCornerShape(8.dp)` |
| 气泡内无按钮 | — |

**问题**: 按钮边框使用 `ButtonDefaults.outlinedButtonBorder`（Material3 默认色），而非 SciFi 主题色。按钮圆角 (12dp/8dp) 与卡片 (16dp) 不协调。

#### ⑧ 标准协议渲染器 (A2UIComposeRenderer) 负边距 Hack

在 `AiMessageBubble` 中，标准协议卡片的渲染使用了负边距：
```kotlin
.padding(start = (-14).dp, end = (-12).dp)
```

**问题**: 这是一个视觉 hack，说明标准协议卡片的默认宽度无法与气泡内容区对齐。根本原因是卡片没有考虑气泡的 padding 上下文。

#### ⑨ ⚠️ Bug: AiMessageBubble 中重复的 when 分支

`ChatScreen.kt` 中 `AiMessageBubble` 的 segment 处理存在**三个重复的 `StandardProtocol` 分支**（约第 545-576 行）：
```kotlin
is MessageSegment.StandardProtocol -> { /* 版本1 */ }
is MessageSegment.StandardProtocol -> { /* 版本2: 带负边距 */ }
is MessageSegment.StandardProtocol -> { /* 版本3: 无负边距 */ }
```

这是明显的合并冲突/复制粘贴错误，会导致编译错误或不可预测的行为。

---

## 2. 设计方案

### 2.1 设计目标

1. **卡片是气泡的自然延伸**，而非"贴上去的外来物"
2. **统一颜色系统**：全部使用 SciFi 色板，消除 MaterialTheme 默认色的混用
3. **统一圆角策略**：卡片继承气泡的不对称圆角
4. **消除视觉噪音**：去掉卡片外边框和顶部装饰线
5. **修复已知 Bug**：清理重复的 when 分支

### 2.2 方案架构

```
┌─────────────────────────────────────────────────┐
│  AI 气泡 (AiMessageBubble)                       │
│  ┌───────────────────────────────────────────┐  │
│  │ 🤖 OpenClaw                               │  │
│  │                                           │  │
│  │  文字内容...                              │  │
│  │                                           │  │
│  │  ┌───────────────────────────────────┐   │  │  ← 改造后：无边框、无阴影、
│  │  │ 🌤️ 天气                           │   │  │    背景融合、圆角继承
│  │  │                                   │   │  │
│  │  │  25°C  上海 · 晴                  │   │  │
│  │  │  [体感 23°] [湿度 65%]            │   │  │
│  │  │                                   │   │  │
│  │  │  ─────────────────────────────    │   │  │
│  │  │  [查看完整天气预报]                │   │  │
│  │  └───────────────────────────────────┘   │  │
│  │                                           │  │
│  │  更多文字...                              │  │
│  │                              13:09        │  │
│  └───────────────────────────────────────────┘  │
│  ↑                                               │
│  2dp 青色左边框 (保留)                            │
└─────────────────────────────────────────────────┘
```

### 2.3 具体改造

#### 改造 1: CardContainer 去边框化

**当前**:
```kotlin
Card(
    shape = CardShape,  // 16dp 均匀圆角
    colors = CardDefaults.cardColors(containerColor = SciFiSurfaceVariant.copy(alpha = 0.7f)),
    modifier = modifier
        .fillMaxWidth()
        .drawBehind { /* 顶部 2dp 青色线 */ }
        .border(width = 1.dp, color = SciFiOutline, shape = CardShape)
)
```

**改造后**:
```kotlin
Surface(
    shape = RoundedCornerShape(12.dp),  // 缩小圆角，与气泡协调
    color = SciFiSurfaceVariant,        // 100% 不透明，与气泡背景融合
    tonalElevation = 0.dp,              // 无阴影
    modifier = modifier.fillMaxWidth()
        .border(                         // 仅保留左侧 2dp 青色线（与气泡呼应）
            width = 2.dp,
            color = SciFiAiBubbleBorder,
            shape = RoundedCornerShape(12.dp)
        )
        .padding(start = 2.dp)          // 补偿左边框宽度
)
```

**变更要点**:
- `Card` → `Surface`：消除 Material3 Card 的默认阴影
- 圆角 `16dp` → `12dp`：与气泡内部元素更协调
- 背景 `alpha=0.7f` → 不透明：消除"悬浮层"感
- 去掉顶部装饰线 + 外边框，改为**左侧 2dp 青色线**（与气泡左边框风格一致）
- 背景色从 `SciFiSurfaceVariant` 改为与气泡背景 `SciFiAiBubbleBg` 一致（两者值相同，但语义更清晰）

#### 改造 2: 卡片内边距统一

**当前**: `Column(modifier = Modifier.padding(16.dp))`

**改造后**: `Column(modifier = Modifier.padding(12.dp))`

与气泡内容区 padding (12dp) 保持一致。

#### 改造 3: 颜色系统统一到 SciFi 色板

将卡片内部所有 `MaterialTheme.colorScheme.*` 替换为对应的 SciFi 色板值：

| 原 MaterialTheme 色 | 替换为 SciFi 色 | 说明 |
|---------------------|----------------|------|
| `surfaceVariant` | `SciFiSurfaceVariant` | 卡片内部面板背景 |
| `onSurfaceVariant` | `SciFiOnSurfaceVariant` | 次要文字 |
| `primaryContainer` | `SciFiPrimary.copy(alpha=0.15f)` | 主色容器 |
| `onPrimaryContainer` | `SciFiOnSurface` | 主色容器上的文字 |
| `tertiaryContainer` | `SciFiTertiary.copy(alpha=0.15f)` | 辅助容器 |
| `errorContainer` | `SciFiError.copy(alpha=0.15f)` | 错误容器 |
| `primary` (文字) | `SciFiPrimary` | 主色文字 |

**涉及文件**: `A2UICards.kt` 中所有卡片组件的内部 `Surface` 颜色引用。

#### 改造 4: 按钮风格统一

**当前**:
```kotlin
OutlinedButton(
    shape = RoundedCornerShape(12.dp),
    border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp)  // Material3 默认色
)
```

**改造后**:
```kotlin
OutlinedButton(
    shape = RoundedCornerShape(10.dp),
    border = BorderStroke(1.dp, SciFiPrimary.copy(alpha = 0.4f)),   // SciFi 色
    colors = ButtonDefaults.outlinedButtonColors(contentColor = SciFiPrimary)
)
```

**变更要点**:
- 圆角 `12dp` → `10dp`：与卡片 12dp 圆角更协调
- 边框使用 `SciFiPrimary` 而非 Material3 默认色
- Secondary 按钮圆角 `8dp` → `8dp`（保持不变，已合理）

#### 改造 5: 圆角继承策略

对于卡片作为气泡内第一个/最后一个元素的情况，考虑动态圆角：

```kotlin
@Composable
fun CardContainer(
    isFirst: Boolean = false,
    isLast: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = when {
        isFirst && isLast -> RoundedCornerShape(12.dp)  // 单独卡片
        isFirst -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
        isLast -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
        else -> RoundedCornerShape(8.dp)  // 中间卡片
    }
    // ...
}
```

**优先级**: P2（可选优化），V1 先统一基础样式。

#### 改造 6: 修复 AiMessageBubble 重复分支 Bug

删除 `ChatScreen.kt` 中 `AiMessageBubble` 的重复 `StandardProtocol` when 分支，保留正确的版本（带负边距或统一宽度方案）。

#### 改造 7: 标准协议渲染器宽度适配

为 `A2UIComposeRenderer` 提供气泡上下文，使其渲染的卡片宽度与气泡内容区对齐，消除负边距 hack。

### 2.4 视觉对比

| 属性 | 改造前 | 改造后 |
|------|--------|--------|
| 卡片背景 | `#1E293B` @ 70% 透明 | `#1E293B` 完全不透明 |
| 卡片圆角 | 16dp 均匀 | 12dp 均匀（V2 可动态） |
| 卡片边框 | 顶部 2dp 线 + 1dp 外框 | 左侧 2dp 青色线（与气泡呼应） |
| 卡片阴影 | Material3 Card 默认阴影 | 无阴影 (elevation=0) |
| 卡片内边距 | 16dp | 12dp |
| 内部颜色 | MaterialTheme + SciFi 混用 | 纯 SciFi 色板 |
| 按钮边框 | Material3 默认色 | SciFiPrimary |

---

## 3. 任务清单

### Phase 1: 核心样式统一 (P0)

| # | 任务 | Agent | 文件 | 复杂度 | 说明 |
|---|------|-------|------|--------|------|
| 1.1 | 重构 CardContainer | Compose | `A2UICards.kt` | ⭐⭐⭐ | Card→Surface, 去边框, 改背景透明度, 改圆角, 加左边框 |
| 1.2 | 统一卡片内边距 | Compose | `A2UICards.kt` | ⭐ | 16dp → 12dp |
| 1.3 | 颜色系统迁移 | Compose | `A2UICards.kt` | ⭐⭐⭐ | 所有卡片内部 MaterialTheme → SciFi 色板 |
| 1.4 | 按钮风格统一 | Compose | `A2UICards.kt` | ⭐⭐ | CardActionButtons 边框和圆角 |

### Phase 2: Bug 修复 (P0)

| # | 任务 | Agent | 文件 | 复杂度 | 说明 |
|---|------|-------|------|--------|------|
| 2.1 | 修复重复 when 分支 | Compose | `ChatScreen.kt` | ⭐ | AiMessageBubble 中 3 个 StandardProtocol 分支 |

### Phase 3: 标准协议适配 (P1)

| # | 任务 | Agent | 文件 | 复杂度 | 说明 |
|---|------|-------|------|--------|------|
| 3.1 | 标准协议宽度适配 | Compose | `ChatScreen.kt`, `A2UIComposeRenderer.kt` | ⭐⭐⭐ | 消除负边距 hack, 统一宽度策略 |
| 3.2 | A2UITheme 与 SciFi 对齐 | Compose | `android_compose/.../A2UITheme.kt` | ⭐⭐ | 确保标准协议渲染器使用 SciFi 色板 |

### Phase 4: 圆角继承优化 (P2, 可选)

| # | 任务 | Agent | 文件 | 复杂度 | 说明 |
|---|------|-------|------|--------|------|
| 4.1 | 动态圆角策略 | Compose | `A2UICards.kt` | ⭐⭐ | 卡片根据位置继承气泡圆角 |
| 4.2 | 用户气泡内卡片适配 | Compose | `ChatScreen.kt` | ⭐⭐ | 用户气泡(渐变背景)内卡片的样式适配 |

---

## 4. 影响范围

### 直接修改文件

| 文件 | 修改内容 | 风险 |
|------|---------|------|
| `A2UICards.kt` | CardContainer 重构 + 14 种卡片内部颜色迁移 | **中** — 影响所有卡片渲染 |
| `ChatScreen.kt` | 修复重复分支 + 标准协议宽度适配 | **低** — 修复 bug + 微调 |
| `A2UITheme.kt` (android_compose 模块) | 标准协议渲染器主题对齐 | **低** — 仅影响标准协议 |

### 不受影响

- `A2UICardModels.kt` — 数据模型，不涉及 UI
- `A2UICardParser.kt` — 解析逻辑，不涉及 UI
- `MessageBubble.kt` — 这是旧版/增强版气泡，当前使用的是 `ChatScreen.kt` 内的 `MessageBubble`
- 主题文件 (`Color.kt`, `Theme.kt`, `ModifierExt.kt`) — 不需要修改，只需在卡片中使用已有色值

### 测试要求

根据 CLAUDE.md 要求，修改后需通过：
1. `./gradlew :app:testDebugUnitTest :android_compose:testDebugUnitTest` — 单元测试
2. `./gradlew :app:compileDebugAndroidTestKotlin` — 编译检查
3. `./gradlew assembleDebug` — 构建验证
4. 手动视觉验证：在设备上查看每种卡片类型在气泡内的渲染效果

---

## 5. 风险与注意事项

### 风险 1: 颜色对比度

SciFi 色板的 `SciFiOnSurfaceVariant (#94A3B8)` 在 `SciFiSurfaceVariant (#1E293B)` 上的对比度为 5.8:1，满足 WCAG AA。迁移时需注意保持此对比度。

### 风险 2: 亮色模式兼容

当前方案基于暗色主题。如果未来启用亮色模式，需同步调整：
- `SciFiLightAiBubbleBg` / `SciFiLightAiBubbleBorder` 已有定义
- 卡片需根据主题动态选择背景色

### 风险 3: 标准协议渲染器 (android_compose 模块)

标准协议由独立模块 `android_compose` 渲染，其 `A2UITheme.kt` 有独立主题系统。对齐时需确保不破坏该模块的独立性。

### 风险 4: 用户气泡内的卡片

用户气泡使用渐变背景 (青→蓝)，卡片放入后可能需要不同的背景色策略（而非直接使用 `SciFiSurfaceVariant`）。建议 V1 先处理 AI 气泡，用户气泡内卡片作为 V2 优化。

---

## 6. 验收标准

1. [ ] A2UI 卡片在 AI 气泡内无外边框、无阴影、背景不透明
2. [ ] 卡片左侧有 2dp 青色线，与气泡左边框风格一致
3. [ ] 卡片内部所有颜色使用 SciFi 色板，无 MaterialTheme 默认色混用
4. [ ] 卡片按钮边框使用 SciFiPrimary 色
5. [ ] AiMessageBubble 中无重复的 when 分支
6. [ ] 标准协议卡片无负边距 hack，宽度与气泡内容区对齐
7. [ ] 所有 14 种卡片类型在气泡内视觉一致
8. [ ] 所有单元测试通过
9. [ ] Debug 构建成功
