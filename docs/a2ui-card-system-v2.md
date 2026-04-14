# A2UI 手机卡片系统 v2.0 设计

> **创建日期**: 2026-04-13  
> **状态**: 设计阶段  
> **目标**: 手机友好的结构化卡片，替代 Markdown 长文本

---

## 1. 设计理念

### 1.1 核心原则

| 原则 | 说明 |
|------|------|
| **一屏说清** | 首屏 3 秒内看到核心结论 |
| **信息分层** | 结论 → 细节 → 完整数据，逐层展开 |
| **可操作** | 每张卡片都带快捷操作按钮 |
| **可读可听** | 文字卡片 + 语音播报双通道 |
| **统一语言** | 所有技能回复都走卡片，不用自由文本 |

### 1.2 对比：Markdown vs 卡片

```
❌ Markdown 回复（手机不友好）
┌─────────────────────────────┐
│ 根据查询，西安今天的天气是  │
│ 多云，气温 14°C，体感温度   │
│ 12°C，相对湿度 45%，东南    │
│ 风 3 级。未来三天：周二小   │
│ 雨 11-16°C，周三晴 10-18°C │
│ 周四多云 12-20°C。建议携   │
│ 带雨具。                    │
└─────────────────────────────┘
  ↑ 密密麻麻，关键信息被淹没

✅ 卡片回复（手机友好）
┌─────────────────────────────┐
│ ☀️ 西安 · 当前天气           │
│                             │
│   ⛅ 多云                    │
│   🌡️ 14°C  体感 12°C        │
│   💧 45%  💨 东南风 3级     │
│                             │
│  ┌──────┬──────┬──────┐     │
│  │ 周二 │ 周三 │ 周四 │     │
│  │ 🌧16°│ ☀18° │ ☁20° │     │
│  │ 11°  │ 10°  │ 12°  │     │
│  └──────┴──────┴──────┘     │
│                             │
│  [⏰ 降雨提醒]  [📤 分享]    │
└─────────────────────────────┘
  ↑ 3 秒扫完，操作按钮直达
```

---

## 2. 卡片类型体系

### 2.1 总览

| 卡片类型 | 用途 | 对应技能 | 优先级 |
|---------|------|---------|--------|
| **WeatherCard** | 天气信息 | WeatherSkill | P0 |
| **SearchResultCard** | 搜索结果 | MultiSearchSkill | P0 |
| **TranslationCard** | 翻译结果 | TranslateSkill | P0 |
| **ReminderCard** | 提醒列表/操作确认 | ReminderSkill | P0 |
| **CalendarCard** | 日程列表/操作确认 | CalendarSkill | P0 |
| **LocationCard** | 位置/地图信息 | LocationSkill | P0 |
| **ActionConfirmCard** | 操作确认（发消息、打电话等） | 所有操作类 | P0 |
| **ContactCard** | 联系人信息 | ContactSkill | P1 |
| **SMSCard** | 短信发送确认/列表 | SMSSkill | P1 |
| **AppCard** | 应用启动/列表 | AppLauncherSkill | P1 |
| **SettingsCard** | 设置变更确认 | SettingsSkill | P1 |
| **ErrorCard** | 错误/异常提示 | 全局 | P0 |
| **InfoCard** | 通用信息/纯文本 | 全局 | P0 |
| **SummaryCard** | 长内容摘要 | 全局 | P1 |

### 2.2 卡片尺寸规范

```
┌─────────────────────────────┐
│ [图标] 标题                  │  头部（32dp 高）
├─────────────────────────────┤
│                              │
│  核心结论区                   │  主体（可变高度）
│  - 单行结论 / 关键指标        │
│  - 列表 / 表格               │
│  - 图表 / 进度条             │
│                              │
├─────────────────────────────┤
│  [按钮1]  [按钮2]  [更多▼]   │  操作栏（40dp 高）
└─────────────────────────────┘
```

---

## 3. 各卡片类型详细设计

### 3.1 WeatherCard — 天气卡片

```jsonc
{
  "type": "weather",
  "layout": "weather",
  "data": {
    "title": "西安 · 天气",              // 卡片标题
    "city": "西安",
    "current": {
      "icon": "cloudy",                 // sunny / cloudy / rainy / snowy / stormy / foggy
      "condition": "多云",
      "temperature": "14",
      "feelsLike": "12",
      "humidity": "45",
      "wind": "东南风 3级"
    },
    "forecast": [                       // 未来预报（水平滑动）
      { "day": "周二", "icon": "rainy", "condition": "小雨", "high": "16", "low": "11" },
      { "day": "周三", "icon": "sunny", "condition": "晴", "high": "18", "low": "10" },
      { "day": "周四", "icon": "cloudy", "condition": "多云", "high": "20", "low": "12" }
    ],
    "alert": "今天傍晚有阵雨，建议携带雨具"  // 可选：天气提醒
  },
  "actions": [
    { "label": "⏰ 降雨提醒", "action": "set_rain_reminder" },
    { "label": "📅 7天预报", "action": "expand_forecast" },
    { "label": "📤 分享", "action": "share_weather" }
  ]
}
```

**UI 效果**:
```
┌─────────────────────────────┐
│ 🌤️ 西安 · 天气              │
│                             │
│    ⛅ 多云                   │
│    🌡️ 14°C   体感 12°C      │
│    💧 45%   💨 东南风 3级    │
│                             │
│  今天傍晚有阵雨，建议携带雨具  │
│                             │
│  ┌──────┬──────┬──────┐     │
│  │ 周二 │ 周三 │ 周四 │     │
│  │ 🌧   │ ☀️   │ ☁️   │     │
│  │ 16°  │ 18°  │ 20°  │     │
│  │ 11°  │ 10°  │ 12°  │     │
│  └──────┴──────┴──────┘     │
│                             │
│  [⏰ 提醒]  [7天]  [分享]    │
└─────────────────────────────┘
```

---

### 3.2 SearchResultCard — 搜索结果卡片

```jsonc
{
  "type": "search_result",
  "layout": "list",
  "data": {
    "title": "搜索结果：Android 16 新特性",
    "query": "Android 16 新特性",
    "items": [
      {
        "title": "Android 16 开发者预览版发布",
        "url": "https://developer.android.com/about/versions/16",
        "snippet": "Android 16 引入了...",
        "source": "developer.android.com"
      },
      {
        "title": "Android 16 十大新功能一览",
        "url": "https://example.com/android-16",
        "snippet": "隐私增强、性能提升...",
        "source": "example.com"
      }
    ],
    "total": 1280,
    "time": "0.32 秒"
  },
  "actions": [
    { "label": "🌐 网页浏览", "action": "open_browser" },
    { "label": "📋 复制摘要", "action": "copy_summary" }
  ]
}
```

**UI 效果**:
```
┌─────────────────────────────┐
│ 🔍 搜索：Android 16 新特性   │
│  找到 1,280 条结果 (0.32s)  │
├─────────────────────────────┤
│ 📄 Android 16 开发者预览版    │
│    developer.android.com     │
│    Android 16 引入了...       │
├─────────────────────────────┤
│ 📄 Android 16 十大新功能       │
│    example.com              │
│    隐私增强、性能提升...       │
├─────────────────────────────┤
│  [🌐 打开网页]  [📋 复制]     │
└─────────────────────────────┘
```

---

### 3.3 TranslationCard — 翻译卡片

```jsonc
{
  "type": "translation",
  "layout": "translation",
  "data": {
    "title": "翻译",
    "sourceText": "Hello, how are you today?",
    "sourceLang": "en",
    "targetText": "你好，你今天怎么样？",
    "targetLang": "zh-CN",
    "pronunciation": "nǐ hǎo, nǐ jīn tiān zěn me yàng?"  // 可选：发音
  },
  "actions": [
    { "label": "🔊 朗读", "action": "speak_target" },
    { "label": "📋 复制", "action": "copy_translation" },
    { "label": "🔄 切换语言", "action": "swap_languages" }
  ]
}
```

**UI 效果**:
```
┌─────────────────────────────┐
│ 🔤 翻译 (英语 → 中文)        │
├─────────────────────────────┤
│ Hello, how are you today?    │
├─────────────────────────────┤
│ 你好，你今天怎么样？            │
│ nǐ hǎo, nǐ jīn tiān...      │
├─────────────────────────────┤
│  [🔊 朗读]  [📋 复制]         │
└─────────────────────────────┘
```

---

### 3.4 ReminderCard — 提醒卡片

**列表模式**:
```jsonc
{
  "type": "reminder",
  "layout": "reminder_list",
  "data": {
    "title": "提醒列表",
    "count": 3,
    "items": [
      { "id": "1", "text": "下午 3 点开会", "time": "2026-04-13 15:00", "status": "pending" },
      { "id": "2", "text": "给张三回电话", "time": "2026-04-13 17:00", "status": "pending" },
      { "id": "3", "text": "买牛奶", "time": "2026-04-14 09:00", "status": "pending" }
    ]
  },
  "actions": [
    { "label": "➕ 新建提醒", "action": "add_reminder" }
  ]
}
```

**确认模式**（设置提醒后）:
```jsonc
{
  "type": "reminder",
  "layout": "reminder_confirm",
  "data": {
    "title": "✅ 已设置提醒",
    "text": "下午 3 点开会",
    "time": "2026-04-13 15:00",
    "relativeTime": "2 小时 12 分钟后"
  },
  "actions": [
    { "label": "✏️ 修改", "action": "edit_reminder" },
    { "label": "🗑️ 取消", "action": "cancel_reminder" }
  ]
}
```

---

### 3.5 CalendarCard — 日程卡片

```jsonc
{
  "type": "calendar",
  "layout": "calendar_list",
  "data": {
    "title": "今天的日程",
    "date": "2026-04-13",
    "items": [
      { "title": "产品评审会", "time": "10:00 - 11:00", "location": "会议室 A", "color": "#4A90D9" },
      { "title": "午餐与张三", "time": "12:00 - 13:00", "location": "公司楼下", "color": "#7ED321" },
      { "title": "项目复盘", "time": "15:00 - 16:30", "location": "线上", "color": "#F5A623" }
    ]
  },
  "actions": [
    { "label": "➕ 新建日程", "action": "add_event" },
    { "label": "📅 查看明天", "action": "next_day" }
  ]
}
```

---

### 3.6 LocationCard — 位置卡片

```jsonc
{
  "type": "location",
  "layout": "location",
  "data": {
    "title": "您的位置",
    "address": "西安市雁塔区科技路 XX 号",
    "latitude": "34.23",
    "longitude": "108.95",
    "nearby": [
      { "name": "大雁塔", "distance": "3.2km" },
      { "name": "曲江池", "distance": "5.1km" }
    ]
  },
  "actions": [
    { "label": "🧭 导航", "action": "navigate" },
    { "label": "📤 分享位置", "action": "share_location" }
  ]
}
```

---

### 3.7 ActionConfirmCard — 操作确认卡片 ⭐

这是**最重要**的新卡片类型，用于高风险操作（发消息、打电话、删数据等）。

```jsonc
{
  "type": "action_confirm",
  "layout": "action_confirm",
  "data": {
    "title": "确认操作",
    "icon": "send",                     // send / call / delete / pay / edit
    "riskLevel": "medium",              // low / medium / high
    "description": "已准备好发送微信消息",
    "details": {
      "联系人": "张三",
      "消息内容": "晚上6点老地方吃饭"
    },
    "warning": null                     // high 风险时显示警告
  },
  "actions": [
    { "label": "✅ 确认发送", "action": "confirm", "style": "primary" },
    { "label": "✏️ 修改内容", "action": "edit" },
    { "label": "❌ 取消", "action": "cancel", "style": "secondary" }
  ]
}
```

**UI 效果**:
```
┌─────────────────────────────┐
│ 📤 确认操作                  │
├─────────────────────────────┤
│ 已准备好发送微信消息          │
│                             │
│  联系人：张三                 │
│  消息内容：                   │
│  ┌─────────────────────┐    │
│  │ 晚上6点老地方吃饭     │    │
│  └─────────────────────┘    │
│                             │
├─────────────────────────────┤
│      [✅ 确认发送]           │  ← 主按钮（大）
│  [✏️ 修改]    [❌ 取消]       │  ← 次要按钮
└─────────────────────────────┘
```

---

### 3.8 ErrorCard — 错误卡片

```jsonc
{
  "type": "error",
  "layout": "error",
  "data": {
    "icon": "warning",                 // warning / error / info
    "title": "无法获取天气信息",
    "message": "网络连接失败，请检查网络设置后重试",
    "suggestion": "您可以尝试切换为云模型或稍后再试"
  },
  "actions": [
    { "label": "🔄 重试", "action": "retry" },
    { "label": "⚙️ 设置", "action": "open_settings" }
  ]
}
```

---

### 3.9 InfoCard — 通用信息卡片

用于 LLM 的普通文本回复，但做了结构化处理：

```jsonc
{
  "type": "info",
  "layout": "info",
  "data": {
    "title": "关于 OpenClaw",           // 可选
    "icon": "info",                     // info / lightbulb / tip
    "content": "OpenClaw 是一个 AI Agent 框架...",
    "summary": "一句话摘要"              // 可选：首行摘要
  },
  "actions": [
    { "label": "📋 复制全文", "action": "copy" },
    { "label": "🔊 朗读", "action": "speak" }
  ]
}
```

---

### 3.10 SummaryCard — 长内容摘要卡片

用于 LLM 返回较长文本时，自动折叠 + 展开：

```jsonc
{
  "type": "summary",
  "layout": "summary",
  "data": {
    "title": "AI 发展趋势分析",
    "icon": "article",
    "summary": "2026年AI发展主要集中在多模态、Agent和本地推理三个方向...",
    "fullContent": "详细内容全文..."    // 展开后显示
  },
  "actions": [
    { "label": "📖 阅读全文", "action": "expand" },
    { "label": "📋 复制", "action": "copy" },
    { "label": "🔊 朗读", "action": "speak" }
  ]
}
```

---

## 4. 通用规范

### 4.1 卡片统一结构

每个卡片都由 4 部分组成：

```jsonc
{
  "type": "weather",        // 卡片类型（必需）
  "layout": "weather",      // 布局变体（可选）
  "data": { ... },           // 卡片数据（必需）
  "actions": [               // 操作按钮（可选）
    { "label": "...", "action": "...", "style": "primary|secondary" }
  ]
}
```

### 4.2 按钮样式

| style | 用途 | 外观 |
|-------|------|------|
| **primary** | 主操作（确认、发送） | 实心大按钮，全宽 |
| **secondary** | 次操作（取消、修改） | 文字按钮，并排 |

### 4.3 风险等级

| 等级 | 用途 | 按钮颜色 |
|------|------|---------|
| **low** | 查询、浏览 | 默认主题色 |
| **medium** | 发消息、添加日程 | 橙色/蓝色 |
| **high** | 支付、删除 | 红色 + 警告文字 |

### 4.4 A2UI JSON 格式

卡片数据通过 `[A2UI]...[/A2UI]` 包裹：

```
[A2UI]
{"type": "weather", "layout": "weather", "data": {...}, "actions": [...]}
[/A2UI]
```

---

## 5. 交互模式

### 5.1 下拉展开

适用于 `SummaryCard`、`WeatherCard`（7天预报）

```
┌─────────────────────┐
│ 核心结论             │
│                     │
│      [展开 ▼]        │  ← 点击展开详情
└─────────────────────┘
```

### 5.2 水平滑动

适用于预报列表、搜索结果列表

```
← [周二] [周三] [周四] [周五] →
   🌧16°  ☀18°  ☁20°  🌤17°
   11°    10°   12°   11°
```

### 5.3 语音播报

超过 3 行文字的卡片，自动显示 🔊 按钮：

```
┌─────────────────────┐
│ 核心结论             │
│                     │
│  [📋 复制] [🔊 朗读] │
└─────────────────────┘
```

---

## 6. 实施计划

### Phase 1: 核心卡片（5-7 天）

| 任务 | 预估 | 说明 |
|------|------|------|
| 定义卡片数据类 | 0.5d | `A2UICard` sealed class |
| WeatherCard 重构 | 1d | 现有天气卡片升级为新版 |
| SearchResultCard | 1d | 搜索结果结构化 |
| TranslationCard | 0.5d | 翻译卡片 |
| ActionConfirmCard | 1.5d | 操作确认（核心！） |
| ErrorCard + InfoCard | 0.5d | 通用卡片 |
| 技能适配 | 1d | 修改 Skill 返回新格式 |

### Phase 2: 交互增强（3-4 天）

| 任务 | 预估 | 说明 |
|------|------|------|
| 卡片折叠/展开 | 1d | 长内容自动折叠 |
| 水平滑动列表 | 0.5d | LazyRow 实现 |
| 操作按钮回调 | 1d | 按钮点击 → AgentSession |
| 语音播报集成 | 1d | TTS 朗读卡片内容 |

### Phase 3: 智能生成（2-3 天）

| 任务 | 预估 | 说明 |
|------|------|------|
| 工具结果 → 卡片 | 1d | `generateCardFromResult()` |
| LLM Prompt 更新 | 0.5d | 教 LLM 输出卡片 JSON |
| 兜底机制 | 0.5d | LLM 不输出卡片时自动转换 |

---

## 7. 与 ScriptEngine 的集成

> **详见**: [ScriptEngine v0.2.0](./script-engine.md)

ScriptEngine 通过 `ui.renderCard()` Bridge 支持 JS 脚本**动态生成卡片**：

```javascript
// LLM 生成的脚本 — 对比 3 个城市天气
var cities = ["西安", "北京", "上海"];
var results = cities.map(function(city) {
  var data = JSON.parse(http.get("https://api.weather.com/" + city).body);
  return { city: city, temp: data.current.temp, condition: data.current.condition };
});

ui.renderCard(JSON.stringify({
  type: "weather",
  data: {
    title: "多城市天气对比",
    cities: results
  },
  actions: [{ label: "查看详情", action: "expand" }]
}));
```

| 维度 | 静态 Skill 卡片 | ScriptEngine 动态卡片 |
|------|---------------|---------------------|
| **来源** | Kotlin 硬编码 | LLM 生成 JS 脚本 |
| **灵活性** | 固定格式 | 任意组合数据 |
| **复杂度** | 简单查询 | 多 API 聚合、数据加工 |
| **渲染** | 同一种渲染器 | 同一种渲染器（统一通路） |

**安全约束**：
- ✅ 只能调用预定义的卡片类型
- ❌ 不能直接操作 UI 组件
- ❌ 不能注入 HTML/JSX

---

## 8. 向后兼容

- 现有的 `[A2UI]{"type":"weather","data":{...}}[/A2UI]` 格式继续支持
- 新格式增加 `layout` 和 `actions` 字段，旧格式渲染器自动适配
- 渐进迁移：先支持新格式的 WeatherCard，再逐步扩展
- ScriptEngine 生成的卡片与 Skill 卡片使用同一渲染通路

---

## 9. 更新记录

| 日期 | 版本 | 变更 | 作者 |
|------|------|------|------|
| 2026-04-13 | v2.0 | 初始设计，14 种卡片类型 | guyan-wsl2 |
| 2026-04-13 | v2.1 | 新增 ScriptEngine 集成章节 | guyan-wsl2 |

---

> **设计完成，等待评审后进入开发**
