# OpenClaw-Android 应用交互方案设计

> 2026-04-11 讨论确定

## 背景

虚拟 Display 方案（豆包手机路线）需要系统签名，第三方 App 无法使用。但 AI Agent 如果不能操作应用，价值会大幅降低。

## 核心设计原则

**不走"全自动"路线，走"Intent 优先 + Accessibility 兜底 + 用户确认"的渐进路线**

| 原则 | 说明 |
|------|------|
| AI 做 90% | 理解意图、构建参数、打开页面、填入内容 |
| 用户做 10% | 最后确认/发送，保障安全 |
| 三层降级 | URL Scheme → Intent → AccessibilityService |
| 安全优势 | 支付/转账等高风险操作必须用户确认 |

---

## 三级能力矩阵

### Tier 1：URL Scheme / App Links（覆盖 60%+ 场景）

**不需要任何特殊权限**，纯 Intent 跳转：

| 场景 | 实现方式 | 完成度 |
|------|---------|--------|
| 打电话 | `tel:13800138000` | ✅ 100% |
| 发短信 | `smsto:13800138000` | ✅ 90% |
| 发邮件 | `mailto:xxx@xxx.com` | ✅ 100% |
| 打开地图导航 | `geo:lat,lng` | ✅ 100% |
| 打开微信聊天 | Intent `com.tencent.mm` | ✅ 70% |
| 打开支付宝扫码 | Intent 支付宝扫码页 | ✅ 80% |
| 打开淘宝商品 | 淘宝 URL Scheme | ✅ 70% |
| 打开 B站视频 | B站 URL Scheme | ✅ 80% |

### Tier 2：App 官方 API / SDK（覆盖 20% 场景）

| 场景 | 实现方式 | 说明 |
|------|---------|------|
| 天气 | wttr.in / 天气 API | ✅ 已有 |
| 搜索 | 搜索引擎 API | ✅ 已有 |
| 翻译 | 翻译 API | ✅ 已有 |
| 快递查询 | 快递100 API | 可接入 |
| 智能家居 | 米家/HomeKit API | 可接入 |

### Tier 3：AccessibilityService（覆盖 20% 兜底场景）

当 Tier 1 和 Tier 2 都搞不定时：

- 读屏 + dispatchGesture 模拟操作
- **局限**：只能前台操作，需要用户让出控制权
- **适用**：没有 API 也没有 URL Scheme 的冷门操作

---

## Hybrid 执行模式

### Schema 扩展

在 Skill JSON 中新增 `execution_mode` 和 `primary`/`fallback` 结构：

```jsonc
{
  "tools": [{
    "name": "send_wechat",
    "description": "发送微信消息给指定联系人",
    "execution_mode": "hybrid",
    
    // 优先方案：URL Scheme / Intent
    "primary": {
      "type": "intent",
      "action": "android.intent.action.VIEW",
      "package": "com.tencent.mm",
      "data": "weixin://dl/chat?name={{contact}}",
      "extras": {
        "android.intent.extra.TEXT": "{{message}}"
      },
      "wait_for_user": true
    },
    
    // 兜底方案：AccessibilityService
    "fallback": {
      "type": "accessibility",
      "target_package": "com.tencent.mm",
      "actions": [
        {"type": "click_text", "text": "搜索"},
        {"type": "input_text", "text": "{{contact}}"},
        {"type": "click_text", "text": "{{contact}}"},
        {"type": "input_text", "text": "{{message}}"}
      ]
    }
  }]
}
```

### execution_mode 类型

| 值 | 说明 | 适用场景 |
|----|------|---------|
| `auto` | 自动执行，无需用户确认 | 天气查询、搜索、翻译 |
| `hybrid` | 优先 primary，失败 fallback | 发消息、打开 App |
| `manual` | 始终需要用户确认 | 支付、转账、删除操作 |

### 执行流程

```
1. 尝试 primary（Intent 跳转）→ 成功 → 用户确认 → 完成
2. primary 失败/不可用 → 尝试 fallback（AccessibilityService）
3. 都不可用 → 返回错误提示用户
```

---

## wait_for_user 交互设计

### 场景示例

```
用户：帮我给张三发微信说晚上6点老地方吃饭

AI 执行：
1. 构建 Intent，打开微信并定位到张三的聊天窗口
2. 把文字填入输入框
3. 弹 A2UI 卡片："已准备好发送给 张三：'晚上6点老地方吃饭'"
4. 显示 [确认发送] 和 [修改] 按钮
5. 用户点击确认 → AI 模拟点击发送按钮（AccessibilityService）
   或用户自己点发送 → 完成
```

### A2UI 确认卡片

```json
{
  "type": "confirmation",
  "data": {
    "title": "确认操作",
    "message": "已准备好发送给 张三",
    "content": "晚上6点老地方吃饭",
    "actions": [
      {"text": "确认发送", "action": "confirm"},
      {"text": "修改内容", "action": "edit"},
      {"text": "取消", "action": "cancel"}
    ]
  }
}
```

---

## 安全风险控制

| 风险等级 | 操作类型 | 策略 |
|---------|---------|------|
| 低 | 查询、浏览、打开页面 | 自动执行（auto） |
| 中 | 发送消息、添加日程 | 用户确认（hybrid） |
| 高 | 支付、转账、删除数据 | 始终手动（manual） |

---

## 落地路径

| 阶段 | 目标 | 预计时间 |
|------|------|---------|
| **Phase 1** | 完善 URL Scheme 库（覆盖常用 App） | 1-2 周 |
| **Phase 2** | Skill 支持 hybrid 模式（primary + fallback） | 1 周 |
| **Phase 3** | AccessibilityService 增强（dispatchGesture + 截图） | 2 周 |
| **Phase 4** | A2UI 确认卡片 + 用户交互 | 1 周 |

---

## 常用 App URL Scheme 参考

### 微信
```
weixin://dl/chat          // 打开聊天
weixin://dl/scan          // 扫一扫
weixin://dl/moments       // 朋友圈
weixin://dl/officialaccounts  // 公众号
weixin://dl/profile       // 个人信息
```

### 支付宝
```
alipay://alipayclient/?{"actionType":"toPlatform","platformId":"main"}  // 首页
alipay://alipayclient/?{"actionType":"toScan"}                          // 扫码
alipay://alipayclient/?{"actionType":"toTransfer"}                      // 转账
```

### 淘宝
```
taobao://item.taobao.com/item.htm?id=123456  // 商品详情
taobao://s.taobao.com/search?q=关键词         // 搜索
```

### B站
```
bilibili://video/av123456    // 视频
bilibili://user/123456       // 用户主页
bilibili://search?keyword=xx // 搜索
```

---

*2026-04-11 · 讨论确定*
