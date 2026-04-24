# 个人中心 - 重构设计

> 日期: 2026-04-23
> 状态: 开发中
> 替换: 通知页面 (Tab 1)

---

## 定位

汇总用户所有可能关注的信息（通知、日程、短信等）的集散地。**不按来源分类，按重要程度统一排序**，点击可跳转到对应应用。

## 核心需求

### 1. 多源数据聚合
| 来源 | 实现方式 | 权限 |
|------|----------|------|
| 通知 | `SmartNotificationListener` (已有) | 通知监听 (已有) |
| 日程 | `CalendarContract` ContentProvider | `READ_CALENDAR` (新增) |
| 短信 | `Telephony.Sms` ContentProvider | `READ_SMS` (新增) |
| 扩展 | 未来可接入邮件、待办等 | |

### 2. 统一数据模型

```kotlin
data class CenterItem(
    val id: String,
    val source: ItemSource,         // 来源: Notification / Calendar / Sms
    val sourceApp: String,          // "微信" / "系统日历" / "短信"
    val icon: ItemSourceIcon,       // 来源图标
    val importance: Float,          // 0.0 ~ 1.0 重要程度
    val title: String,
    val body: String,
    val timestamp: Long,            // 事件时间
    val createdAt: Long,            // 记录创建时间
    val isRead: Boolean,
    val openIntent: PendingIntent?, // 点击跳转
    val dedupKey: String,           // 去重键
    val relatedIds: List<String>,   // 关联的其他来源ID（合并展示用）
)
```

### 3. 重要程度计算

```
importance = baseScore × recencyWeight

baseScore:
  - 通知紧急(电话/微信语音/短信验证码)     → 0.95
  - 短信(银行/快递/验证码)                 → 0.85
  - 日程(今天内开始)                       → 0.80
  - 通知重要(工作邮件/日历)                → 0.70
  - 短信(普通)                             → 0.50
  - 日程(本周内)                           → 0.60
  - 通知一般                               → 0.40
  - 日程(未来)                             → 0.30

recencyWeight: 1.0 (1小时内) → 0.5 (24小时) → 0.3 (3天) → 0.1 (7天+)
```

### 4. 跨源去重 ⭐

**问题**: 同一件事可能从多个来源收到。例如：
- 日程"产品评审" + 微信通知"提醒：产品评审" + 短信提醒
- 短信验证码 + 该App的推送通知

**去重策略**:

```kotlin
// 去重键生成规则
fun generateDedupKey(item: CenterItem): String {
    // 提取关键特征：时间窗口 + 关键词指纹
    val timeBucket = item.timestamp / (30 * 60 * 1000L) // 30分钟窗口
    val keywords = extractKeywords(item.title + item.body) // 提取2-4个关键词
    val fingerprint = md5(keywords.sorted().joinToString(":"))
    return "${timeBucket}:${fingerprint}"
}
```

**合并规则**:
- 相同 dedupKey → 合并为一条
- 保留 importance 最高的源作为主展示
- 在 UI 上标记 "关联 3 条信息" 可展开查看其他来源

### 5. 实时刷新 ⭐

```
进入个人中心 → 立即拉取全量数据
之后:
  - 通知: ContentObserver 监听 (SmartNotificationListener 的 StateFlow 实时推送)
  - 日程: ContentObserver 监听 CalendarContract
  - 短信: ContentObserver 监听 Sms Inbox
  - 全量刷新: 每 60 秒兜底刷新一次 (防漏)
```

**架构**:
```
ViewModel
  ├── StateFlow<List<CenterItem>>  ← UI 订阅
  │
  ├── NotificationSource (已有 StateFlow)
  ├── CalendarSource (ContentObserver + 首次拉取)
  └── SmsSource (ContentObserver + 首次拉取)
  │
  └── Merger + Deduplicator → sorted by importance desc
```

### 6. UI 布局

```
┌───────────────────────────────────┐
│  🦞 个人中心            🔄 刷新    │
├───────────────────────────────────┤
│  ┌─────┐ ┌─────┐ ┌─────┐         │
│  │通知12│ │日程3 │ │短信5 │  统计  │
│  └─────┘ └─────┘ └─────┘         │
├───────────────────────────────────┤
│ 🔴 09:30  [微信]          0.92    │
│     张三：今晚聚餐吗？             │
│     🔗 关联 1 条通知               │
├───────────────────────────────────┤
│ 🟡 10:00  [日程]          0.80    │
│     产品评审会议 - 14:00           │
├───────────────────────────────────┤
│ 🟢 08:15  [短信]          0.75    │
│     【银行】验证码 482916          │
└───────────────────────────────────┘
       ↓ 按 importance 降序
```

### 7. 点击跳转

每条 item 携带 `openIntent: PendingIntent`：
- 通知 → 打开原始通知对应的 PendingIntent
- 日程 → `Intent(CalendarContract)` 打开日历 App 对应事件
- 短信 → `Intent(Telephony.Sms)` 打开短信 App 对应对话

---

## 文件变更

| 操作 | 文件 | 说明 |
|------|------|------|
| 新建 | `personalcenter/PersonalCenterScreen.kt` | UI 页面 |
| 新建 | `personalcenter/PersonalCenterViewModel.kt` | 数据聚合 + 去重 + 排序 |
| 新建 | `personalcenter/models/CenterItem.kt` | 统一数据模型 |
| 新建 | `personalcenter/models/ItemSource.kt` | 来源枚举 + 图标 |
| 新建 | `personalcenter/sources/NotificationSource.kt` | 通知数据源 (包装现有) |
| 新建 | `personalcenter/sources/CalendarSource.kt` | 日历数据源 |
| 新建 | `personalcenter/sources/SmsSource.kt` | 短信数据源 |
| 新建 | `personalcenter/DeduplicationEngine.kt` | 去重引擎 |
| 新建 | `personalcenter/ImportanceCalculator.kt` | 重要度计算 |
| 修改 | `MainActivity.kt` | Tab 1 替换 |
| 修改 | `AndroidManifest.xml` | 新增权限声明 |
| 保留 | `notification/*` | 原有通知监听服务保留 |

## 权限变更

```xml
<!-- 已有 -->
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />

<!-- 新增 -->
<uses-permission android:name="android.permission.READ_CALENDAR" />
<uses-permission android:name="android.permission.READ_SMS" />
```
