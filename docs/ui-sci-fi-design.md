# OpenClaw Android — 科幻 UI 设计方案

> 设计师: Windows (glm5-windows) | 2026-04-15
> 状态: 提案讨论中

---

## 一、设计理念

**关键词**: 全息感 · 玻璃拟态 · 光线流动 · 深空暗色

核心灵感来源：
- **《银翼杀手2049》** — 雨中霓虹、全息投影
- **《攻壳机动队》** — 半透明信息层、几何光效
- **ChatGPT 官方 App** — 简洁对话流 + 渐变色
- **Copilot App** — 品牌色流动、暗色优先

设计哲学：**功能第一，科幻氛围是加分项，不是负担**。不为了炫酷牺牲可用性。

---

## 二、配色方案

### 主色调：深海蓝 + 青色霓虹

```
┌─────────────────────────────────────────────────────────┐
│  背景层级                                                │
│                                                          │
│  基底: #0A0E1A (近乎纯黑，微蓝)                          │
│  一层: #111827 (卡片/表面)                               │
│  二层: #1E293B (悬浮层/弹窗)                             │
│  三层: #334155 (边框/分割线)                             │
│                                                          │
│  强调色                                                  │
│                                                          │
│  主色: #06D6A0 (青色霓虹 / Matrix Green)                 │
│  辅色: #4CC9F0 (冰蓝色)                                  │
│  用户气泡: 渐变 #06D6A0 → #4CC9F0                        │
│  AI 气泡: #1E293B + 青色左边框 1px                       │
│  错误/警告: #EF4444                                      │
│  成功: #06D6A0                                           │
│                                                          │
│  文字                                                    │
│                                                          │
│  主文字: #F1F5F9 (亮白)                                  │
│  副文字: #94A3B8 (灰蓝)                                  │
│  禁用: #64748B                                           │
└─────────────────────────────────────────────────────────┘
```

### 与现有配色的对比

| 元素 | 现有 | 改进后 |
|------|------|--------|
| 背景 | Material Dynamic（系统决定） | 固定深色 #0A0E1A |
| 用户气泡 | primary 紫色 | 青蓝渐变 |
| AI 气泡 | surfaceVariant 灰色 | 暗色 + 青色边线 |
| 强调色 | 紫色系 | #06D6A0 青色霓虹 |
| 输入框 | OutlinedTextField | 玻璃拟态 + 发光边框 |

---

## 三、核心组件设计

### 3.1 主界面布局（ChatScreen）

```
┌──────────────────────────────────┐
│  ◉ OpenClaw              ⚙ 👤   │  ← 顶栏：毛玻璃效果
├──────────────────────────────────┤
│                                  │
│  ┌─────────────────────────┐    │  ← AI 消息气泡
│  │ ▎ 今天天气晴朗，温度    │    │     暗色背景 + 青色左边线
│  │ ▎ 22°C，适合外出。      │    │     左上角: OpenClaw logo 小图标
│  └─────────────────────────┘    │
│              ┌──────────────┐   │  ← 用户消息气泡
│              │ 今天天气怎样？│   │     青蓝渐变背景
│              └──────────────┘   │     圆角 16dp
│                                  │
│  ┌─────────────────────────┐    │
│  │ 🌤 北京 · 晴            │    │  ← 天气卡片（A2UI）
│  │ 22°C  湿度 45%          │    │     毛玻璃卡片 + 霓虹图标
│  │ ━━━━━━━━━━━━━━━━━━━━   │    │     分割线发光效果
│  │ 明天: 多云 18-25°C      │    │
│  └─────────────────────────┘    │
│                                  │
│  ● ● ●                          │  ← AI 思考中
│     (三个点，脉冲动画)           │     青色呼吸灯
│                                  │
├──────────────────────────────────┤
│ ┌─────────────────────────┐ 🎤  │  ← 输入区
│ │  输入消息...             │     │     玻璃拟态背景
│ └─────────────────────────┘     │     聚焦时边框发青色光
│                                  │     语音按钮：脉冲光环
└──────────────────────────────────┘
```

### 3.2 消息气泡

**用户消息**：
```kotlin
// 青蓝渐变背景
Brush.horizontalGradient(
    colors = listOf(Color(0xFF06D6A0), Color(0xFF4CC9F0))
)
// 圆角: topStart=16, topEnd=16, bottomStart=16, bottomEnd=4
// 文字: 白色 #FFFFFF
// 阴影: 0 4dp 12dp rgba(6,214,160,0.15) — 青色阴影
```

**AI 消息**：
```kotlin
// 背景: #1E293B
// 左边框: 2dp, 青色 #06D6A0
// 圆角: topStart=16, topEnd=16, bottomStart=4, bottomEnd=16
// 文字: #F1F5F9
// 头像: OpenClaw 小 logo (可替换为 AI 头像)
// ★ 新增：数据风时间戳装饰
// 气泡右下角极小字号(9sp)，颜色 #475569
// 格式: 十六进制风格 "0x67FE3A2C" 或点阵式 "18:42:07.312"
// 视觉上是装饰元素，不影响正常阅读
```

### 3.3 输入框（玻璃拟态 + 能量条）

```kotlin
// 背景: rgba(30,41,59,0.6) — 半透明
// backdropFilter: blur(20dp) （Android 12+ BlurEffect）
// 边框: 1dp, #334155
// 聚焦时边框: 1dp, #06D6A0 + 外发光 (shadow)
// 圆角: 24dp
// 发送按钮: 青色圆形 + 发送图标
// 语音按钮: 有录音时显示脉冲光环动画

// ★ 新增：聚焦时底部能量条
// 聚焦时从中心向两端展开一道青色光线，暗示"准备就绪"
@Composable
fun EnergyBar(isFocused: Boolean) {
    val widthFraction by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(widthFraction)
                .height(1.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF06D6A0),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}
```

### 3.4 思考动画

替代现有 `CircularProgressIndicator`：

```kotlin
// 三个圆点，从左到右依次脉冲
// 颜色: #06D6A0 (青色)
// 动画: 依次缩放 0.6 → 1.0 + 透明度 0.3 → 1.0
// 间隔: 200ms
// 循环: infinite
// 位置: AI 气泡左侧
```

### 3.5 A2UI 卡片（工具结果）

```kotlin
// 背景: 毛玻璃 #1E293B + alpha 0.7
// 边框: 1dp #06D6A0 (顶部 2dp, 其余 1dp)
// 圆角: 12dp
// 标题行: 图标(青色) + 标题(白色) + 类型标签
// 分割线: 水平线, 颜色 #334155, 两端渐变消失
// 内容: 紧凑信息布局
// 底部操作按钮: 青色描边 + 文字
```

---

## 四、动效设计

### 4.1 页面级

| 动效 | 描述 | 实现 |
|------|------|------|
| **消息进入** | 从下方滑入 + 淡入 | `slideInVertically + fadeIn`, 300ms |
| **消息消失** | 向上收缩 + 淡出 | `shrinkVertically + fadeOut`, 200ms |
| **页面切换** | 淡入淡出 | `fadeIn/fadeOut`, 200ms |

### 4.2 组件级

| 动效 | 描述 | 实现 |
|------|------|------|
| **发送按钮** | 按下缩放 0.9, 弹回 | `animateFloatAsState + spring` |
| **输入框聚焦** | 边框颜色渐变 + 微光 | `animateColorAsState`, 300ms |
| **语音脉冲** | 录音时按钮外环扩散 | `InfiniteTransition + scale`, 正弦波 |
| **AI 思考点** | 三点依次脉冲 | `InfiniteTransition`, 错开 200ms |
| **新消息提示** | 底部浮出"新消息"按钮 | `AnimatedVisibility`, 渐显 |

### 4.3 装饰级（轻量，不影响性能）

| 动效 | 描述 | 实现 |
|------|------|------|
| **顶栏底部线** | 1px 青色线，从左到右缓慢流动 | `LinearGradient + offset animation` |
| **输入框微光** | 边缘偶尔闪过一道光 | 可选，`ShaderEffect` |

### 4.4 氛围级（背景装饰）

| 动效 | 描述 | 实现 |
|------|------|------|
| **粒子背景** | 极淡的星点/微粒，缓慢漂移 | 静态 drawable 叠加层，`alpha=0.05`，无需实时渲染 |
| **网格暗纹** | 极淡六边形/方形网格纹理 | SVG/Vector drawable，`alpha=0.03`，仅空状态页可见 |
| **扫描线加载** | AI 回复时气泡上水平光线从上到下扫过 | `LinearGradient + offset animation`, 800ms/次 |
| **打字机光标** | 流式回复末尾闪烁的青色方块/竖线 | `InfiniteTransition + blink`, 530ms 周期 |

---

## 五、新增视觉元素

### 5.1 深空粒子背景

```kotlin
// 方案A：静态装饰层（推荐，零性能开销）
// 在主题背景上叠加一层极淡的星点 drawable
Box(
    modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF0A0E1A))
) {
    // 星点装饰层 — alpha 极低，不干扰阅读
    Image(
        painter = painterResource(R.drawable.bg_stars),
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            .alpha(0.05f),
        contentScale = ContentScale.Crop
    )
    // 实际内容...
}

// 方案B：动态粒子（可选，仅空状态页使用）
// 使用 Canvas + Animation，限制粒子数量 ≤30
```

### 5.2 全息扫描线

AI 消息气泡在"正在生成"状态时，叠加一道从上到下移动的水平光线：

```kotlin
// 叠加在 AI 气泡内容之上
@Composable
fun ScanLineOverlay(isGenerating: Boolean) {
    if (!isGenerating) return
    val infiniteTransition = rememberInfiniteTransition()
    val scanOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val y = size.height * scanOffset
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x4006D6A0), // 青色半透明
                            Color.Transparent
                        )
                    ),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx()
                )
            }
    )
}
```

### 5.3 打字机光标

AI 流式回复时，在文字末尾显示闪烁光标：

```kotlin
// 闪烁的青色竖线光标
@Composable
fun TypingCursor() {
    val infiniteTransition = rememberInfiniteTransition()
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 530),
            repeatMode = RepeatMode.Reverse
        )
    )
    Box(
        modifier = Modifier
            .width(2.dp)
            .height(16.dp)
            .background(Color(0xFF06D6A0).copy(alpha = cursorAlpha))
    )
}
```

### 5.4 模型状态指示器

顶栏圆点 `◉` 设计为动态状态：

```kotlin
// 在线：绿色稳定发光
// 思考中：青色呼吸脉冲
// 断开：红色闪烁
@Composable
fun StatusIndicator(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.ONLINE -> Color(0xFF06D6A0)
        ConnectionState.THINKING -> Color(0xFF4CC9F0)
        ConnectionState.OFFLINE -> Color(0xFFEF4444)
    }
    val infiniteTransition = rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state == ConnectionState.OFFLINE) 400 else 1500),
            repeatMode = RepeatMode.Reverse
        )
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color.copy(alpha = pulse), CircleShape)
            // 外发光环（思考中时更明显）
            .then(
                if (state == ConnectionState.THINKING) {
                    Modifier.shadow(
                        elevation = 4.dp,
                        shape = CircleShape,
                        ambientColor = color.copy(alpha = 0.5f),
                        spotColor = color.copy(alpha = 0.5f)
                    )
                } else Modifier
            )
    )
}
```

---

## 六、底部导航栏

```
┌──────────────────────────────────┐
│   💬          🔔          ⚙️     │
│   聊天        通知        设置    │
│                                  │
│   ═══                          │  ← 选中项底部: 青色指示线
└──────────────────────────────────┘
```

```kotlin
// 背景: #0A0E1A (与页面背景同色，无分割)
// 图标: 选中 #06D6A0, 未选 #64748B
// 标签: 选中 #06D6A0, 未选 #64748B
// 选中指示: 2dp 高度, 青色渐变条, 宽度 24dp 居中
// 通知 Badge: 红色 #EF4444, 小圆点 (数字>99 显示 "99+")
```

---

## 六、顶栏

```
┌──────────────────────────────────┐
│  ◉ OpenClaw              ⚙ 👤   │
│  ═══════════════════════════    │  ← 底部: 1px 发光线
└──────────────────────────────────┘
```

```kotlin
// 背景: rgba(10,14,26,0.8) + 毛玻璃效果
// 标题: "OpenClaw", 白色, FontWeight.Bold
// 副标题(可选): 显示模型名 "qwen-plus", 灰色小字
// 右侧: 设置图标 + 用户头像
// 底部分割线: 1px, 青色 #06D6A0, 30% 透明度
```

---

## 七、设置页面

```kotlin
// 分组卡片风格
// 每组: 标题(青色) + 内容(暗色卡片)
// 卡片: #1E293B 背景, 1dp #334155 边框, 12dp 圆角
// 开关: Material Switch, 选中色 #06D6A0
// 输入框: 与聊天输入框同一风格
```

---

## 八、空状态页面（首次打开 / 无对话）

```
┌────────────────────────────────────────┐
│  ◉ OpenClaw · qwen-plus        ⚙  👤  │
│  ────────────────────────────────────  │
│                                        │
│                                        │
│              ╭──────────╮              │
│              │  ◉ LOGO  │              │  ← 全息风 logo，缓慢脉冲
│              ╰──────────╯              │
│                                        │
│           你好，我是 OpenClaw           │  ← 青色副标题
│                                        │
│   ┌──────────┐  ┌──────────────────┐   │
│   │ 🌤 今天  │  │ 📝 帮我写一段    │   │  ← 快捷提问卡片
│   │ 天气怎样 │  │ Python 代码      │   │     毛玻璃 + 青色边框
│   └──────────┘  └──────────────────┘   │     点击直接发送
│   ┌────────────────────────────────┐   │
│   │ 💡 给我讲一个有趣的故事       │   │
│   └────────────────────────────────┘   │
│                                        │
│     · · · · · · · · · · · · · · ·     │  ← 极淡六边形网格背景
│                                        │
├────────────────────────────────────────┤
│ ╭──────────────────────────────╮ 🎤    │
│ │  输入消息...              ➤ │ ○     │
│ ╰──────────────────────────────╯       │
│ ────────────────────────────────────   │
│  💬 聊天    🔔 通知    ⚙️ 设置        │
└────────────────────────────────────────┘
```

```kotlin
// 背景: #0A0E1A + 极淡六边形网格 (alpha=0.03)
// Logo: 青色描边圆环 + 内部图标，带缓慢脉冲动画 (scale 1.0→1.05, 3s)
// 副标题: "你好，我是 OpenClaw", #4CC9F0, 居中
// 快捷卡片:
//   - 背景: rgba(30,41,59,0.5) + blur
//   - 边框: 1dp #334155，hover/focus 时变为 #06D6A0
//   - 圆角: 12dp
//   - 内容: 图标(青色) + 预设文案(白色)
//   - 点击行为: 将文案填入输入框或直接发送
// 装饰粒子: ≤20个极淡星点，极慢漂浮 (可选，仅空状态页)
```

---

## 九、错误与异常状态

### 9.1 网络断开

```
┌──────────────────────────────────┐
│  ⚠ SIGNAL LOST                  │  ← 红色闪烁边框
│  ─────────────────────────────   │
│  连接已断开，请检查网络设置      │  ← 白色文字
│                                  │
│  ⟳ 重新连接                     │  ← 青色描边按钮
└──────────────────────────────────┘
```

```kotlin
// 卡片: #1E293B 背景
// 边框: 1dp #EF4444，带闪烁动画 (alpha 0.5↔1.0, 1s)
// 图标: 红色警告三角
// 标题: "SIGNAL LOST" 或 "CONNECTION TERMINATED"，等宽字体
// 描述: #94A3B8 灰色
// 重试按钮: 青色描边 + "重新连接" 文字
```

### 9.2 API 限流 / 服务器错误

```kotlin
// 同上风格，标题改为:
// 429: "BANDWIDTH EXCEEDED"
// 500: "SYSTEM OVERLOAD"
// 401: "ACCESS DENIED"
// 均使用等宽字体 + 红色警告色
// 重试按钮改为 "稍后重试"
```

### 9.3 内联错误（消息中的错误）

```kotlin
// 在消息流中插入错误卡片
// 背景: rgba(239,68,68,0.1) — 淡红色
// 左边框: 2dp #EF4444
// 图标: ⚠ + 错误码 (如 "E-429")
// 文字: 错误描述
// 底部: "重试" 青色文字链接
```

---

## 十、上下文菜单（消息长按操作）

### 设计方案：底部抽屉式菜单

```
┌──────────────────────────────────┐
│         ── 拖拽条 ──             │  ← 灰色圆角条
│                                  │
│  📋 复制                        │  ← 选中项背景微亮
│  🔄 重新生成                    │
│  📤 分享                        │
│  🗑 删除                        │  ← 红色文字
│                                  │
└──────────────────────────────────┘
```

```kotlin
// 背景: #1E293B + 毛玻璃效果
// 拖拽条: #475569, 宽 40dp, 高 4dp, 圆角 2dp
// 选项:
//   - 高度: 52dp
//   - 图标: 24dp, 未选中 #94A3B8, 选中 #06D6A0
//   - 文字: 16sp, #F1F5F9
//   - 选中态: 背景 #334155, 图标变青色
//   - 删除选项: 图标和文字用 #EF4444
// 出现动画: 从底部滑入 + 背景模糊层淡入
// 消失动画: 向下滑出 + 背景恢复
// 触觉反馈: 弹出时短振动 (HapticFeedback.CONTEXT_CLICK)
```

---

## 十一、页面转场动画

### 11.1 基础转场（增强）

| 转场 | 描述 | 实现 |
|------|------|------|
| **页面进入** | 从右侧滑入 + 微弱缩放 (0.95→1.0) + 淡入 | `slideInHorizontally + scaleX + fadeIn`, 300ms |
| **页面退出** | 向左微移 + 淡出 + 轻微缩放 (1.0→0.98) | `slideOutHorizontally + scaleX + fadeOut`, 250ms |
| **返回** | 反向，从左侧滑入 | 同上方向相反 |

### 11.2 共享元素过渡

点击 A2UI 卡片展开详情时：

```kotlin
// 使用 SharedTransitionLayout (Compose 1.7+)
// 卡片位置 → 全屏详情页
// 过渡: 卡片放大 + 背景模糊渐显 + 其他内容淡出
// 时长: 400ms, FastOutSlowInEasing
// 注意: 需要.compose.animation 共享元素 API
```

### 11.3 滚动视差

```kotlin
// 顶栏和底栏的透明度随滚动距离变化
// 滚动 > 100px 时:
//   - 顶栏背景 alpha: 0.8 → 0.95 (更实)
//   - 底栏增加 1dp 顶部分割线
// 滚动回顶部时恢复
// 实现: 监听 LazyColumn 滚动偏移，animateFloatAsState
```

---

## 十二、交互细节

### 12.1 触觉反馈

| 场景 | 触觉类型 | 强度 |
|------|----------|------|
| 发送消息 | `HapticFeedbackType.CONFIRM` | 轻振 |
| AI 回复到达 | `HapticFeedbackType.TICK` | 微振 |
| 长按消息弹出菜单 | `HapticFeedbackType.CONTEXT_CLICK` | 中振 |
| 切换页面/Tab | `HapticFeedbackType.TICK` | 微振 |
| 错误提示 | `HapticFeedbackType.REJECT` | 重振 |

```kotlin
// 使用 Compose 1.3+ 的 hapticFeedback
val hapticFeedback = LocalHapticFeedback.current
// 发送时
hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
```

### 12.2 声音设计（可选功能，默认关闭）

| 场景 | 音效 | 开关 |
|------|------|------|
| 发送消息 | 清脆短促的电子音 (50ms) | 设置中 "发送音效" |
| AI 回复到达 | 柔和的全息提示音 (100ms) | 设置中 "接收音效" |
| 错误 | 低沉的警告音 (200ms) | 设置中 "错误提示音" |

```kotlin
// 音效文件: 放置在 res/raw/ 目录
// send_sound.ogg / receive_sound.ogg / error_sound.ogg
// 使用 SoundPool 播放，避免延迟
// 默认关闭，用户可在设置中开启
```

### 12.3 滚动回底按钮

```kotlin
// 滚动距底部 > 5 条消息时显示
// 位置: 右下角，距底栏 16dp
// 外观: 青色圆形 (40dp) + 向下箭头
// 背景: rgba(6,214,160,0.15) — 半透明青色
// 边框: 1dp #06D6A0
// 点击: 平滑滚动到底部 + 短振动
// 动画: AnimatedVisibility + scale + fade
```

---

## 十三、亮色模式（可选）

如果需要亮色模式，保持科幻感但反转：

```
背景: #F8FAFC (极浅灰蓝)
一层: #FFFFFF
强调色: #059669 (深青, 降低饱和度)
文字: #0F172A (深色)
AI 气泡: #F1F5F9 背景 + #059669 左边框
```

---

## 十四、实现计划

### Phase 1 — 配色与主题 (1-2天)

- [ ] 重写 `Color.kt`：定义完整科幻色板
- [ ] 重写 `Theme.kt`：暗色优先，自定义 ColorScheme
- [ ] 替换 `dynamicColor` 为固定主题
- [ ] 状态栏/导航栏颜色适配
- [ ] 制作粒子背景 drawable (bg_stars) 和网格纹理 (bg_grid)

### Phase 2 — 聊天界面 (2-3天)

- [ ] 重写消息气泡样式（渐变、边框、阴影、数据风时间戳）
- [ ] 新增思考动画组件（三点脉冲）
- [ ] 新增全息扫描线叠加层（AI 生成状态）
- [ ] 新增打字机光标组件（流式回复）
- [ ] 重写输入框（玻璃拟态 + 发光边框 + 能量条）
- [ ] 语音按钮脉冲动画
- [ ] 顶栏毛玻璃效果 + 状态指示器

### Phase 3 — 完整体验 (2-3天)

- [ ] A2UI 卡片科幻风格
- [ ] 底部导航栏指示器
- [ ] 增强页面转场动画（缩放 + 共享元素）
- [ ] 设置页面风格统一
- [ ] 空状态页面（全息 logo + 快捷提问卡片 + 网格背景）
- [ ] 错误状态卡片（SIGNAL LOST 等科幻风格）

### Phase 4 — 交互与细节 (1-2天)

- [ ] 触觉反馈集成（发送/接收/长按/错误）
- [ ] 上下文菜单（底部抽屉 + 青色高亮）
- [ ] 滚动回底按钮
- [ ] 滚动视差（顶栏/底栏透明度变化）
- [ ] 声音设计（可选，默认关闭）

### 预计总工期: 7-10 天

---

## 十五、技术要点

### 玻璃拟态实现（Android 12+）

```kotlin
// 使用 Modifier.blur() 或 BlurredContent
// 降级方案: 半透明背景 + 固定模糊图片
Box(
    modifier = Modifier
        .background(Color(0x401E293B))  // 半透明
        .blur(20.dp)                     // Android 12+
)
```

### 渐变实现

```kotlin
// 用户气泡渐变
Box(
    modifier = Modifier
        .background(
            brush = Brush.horizontalGradient(
                colors = listOf(Cyan500, Blue400)
            ),
            shape = RoundedCornerShape(...)
        )
)
```

### 发光效果

```kotlin
// 输入框聚焦发光
Modifier.drawBehind {
    drawRoundRect(
        color = GlowColor,
        cornerRadius = CornerRadius(24.dp.toPx()),
        style = Stroke(width = 2.dp.toPx()),
        blendMode = BlendMode.SrcOver
    )
}
// 或使用 shadow:
Modifier.shadow(
    elevation = 8.dp,
    shape = RoundedCornerShape(24.dp),
    ambientColor = Color(0x4006D6A0),
    spotColor = Color(0x4006D6A0)
)
```

### 触觉反馈

```kotlin
// Compose 内置触觉反馈
val hapticFeedback = LocalHapticFeedback.current

// 发送消息
hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)

// AI 回复到达
hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
```

### 声音播放

```kotlin
// 使用 SoundPool 低延迟播放短音效
val soundPool = SoundPool.Builder().setMaxStreams(3).build()
val sendSoundId = soundPool.load(context, R.raw.send_sound, 1)

// 播放
soundPool.play(sendSoundId, 0.5f, 0.5f, 1, 0, 1f)

// 注意: 在 Activity.onDestroy 中释放 soundPool.release()
```

### 滚动偏移监听

```kotlin
// 监听 LazyColumn 滚动位置，驱动视差效果
val scrollState = rememberLazyListState()
val scrollOffset by remember {
    derivedStateOf {
        scrollState.firstVisibleItemIndex * 100 +
            (scrollState.firstVisibleItemScrollOffset)
    }
}
val topBarAlpha by animateFloatAsState(
    targetValue = if (scrollOffset > 100) 0.95f else 0.8f
)
```

---

## 十六、效果预览（ASCII Art）

### 整体效果

```
┌────────────────────────────────────────┐
│  ◉ OpenClaw · qwen-plus        ⚙  👤  │
│  ────────────────────────────────────  │ ← 发光分割线
│  · · · · · · · · · · · · · · · · · ·  │ ← 极淡粒子背景
│                                        │
│  ▎你好，我是 OpenClaw。              │
│  ▎有什么可以帮你的？                  │
│  ▎▓▒░                        0x67FE3A │ ← 扫描线 + 数据时间戳
│                                        │
│               ┌───────────────────┐    │
│               │ 今天北京天气怎样？ │    │ ← 用户气泡: 青蓝渐变
│               └───────────────────┘    │
│                                        │
│  ╭────────────────────────────────╮    │
│  │ 🌤 北京 · 晴 22°C             │    │ ← 天气卡片
│  │ ───────────────────────────── │    │
│  │ 💧 湿度 45%    🌬 风力 3级    │    │
│  │                                │    │
│  │ 明天: ☁ 多云 18-25°C          │    │
│  ╰────────────────────────────────╯    │
│                                        │
│  ● ● ●                                │ ← 思考动画
│                                        │
│  ╭──────────────────────────────╮ 🎤   │ ← 输入区
│  │  输入消息...              ▊  │ ○    │ ← 打字机光标 ▊
│  ╰──────────────────────────────╯      │
│  ═════════════════════════════════════  │ ← 能量条（聚焦时展开）
│  ────────────────────────────────────  │
│   💬 聊天    🔔 通知    ⚙️ 设置        │
│   ═══                                  │ ← 选中指示器
└────────────────────────────────────────┘
```

---

## 十七、竞品参考对比

| 设计元素 | ChatGPT App | Copilot App | 本方案 |
|----------|-------------|-------------|--------|
| 背景 | 纯白/纯黑 | 深色渐变 | 深空暗色 + 粒子装饰 |
| 用户气泡 | 灰色/品牌色 | 品牌渐变 | 青蓝渐变 |
| AI 气泡 | 白/暗色 | 深色卡片 | 暗色 + 青边线 + 扫描线 |
| 输入框 | 圆角矩形 | 圆角 + 阴影 | 玻璃拟态 + 发光 + 能量条 |
| 动画 | 简约 | 品牌色流动 | 青色脉冲 + 微光 + 打字机光标 |
| 卡片 | 简洁 | 微渐变 | 毛玻璃 + 霓虹边 |
| 错误状态 | 简单 toast | 内联提示 | SIGNAL LOST 科幻风 |
| 空状态 | 静态 logo | 品牌插画 | 全息 logo + 快捷卡片 + 网格 |
| 触觉/声音 | 基础 | 基础 | 分层触觉 + 可选电子音效 |
| 整体风格 | 极简 | 现代商务 | 科幻实用 |

---

**设计确认后，我可以直接生成 Kotlin/Compose 代码开始落地。**
