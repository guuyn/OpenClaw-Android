# OpenClaw-Android 会话管理测试指南

## 手动测试步骤

### 1. 安装 APK
```bash
adb install app-debug.apk
```

### 2. 启动应用并查看日志
```bash
adb logcat -c  # 清空日志
adb logcat | grep -E "HybridSessionManager|SessionDao|SessionCompressor"
```

### 3. 测试场景

#### 场景 1：会话创建
- 启动应用
- 验证默认会话自动创建
- 日志应显示：`Session created: sessionId=xxx`

#### 场景 2：消息持久化
- 发送一条消息："你好"
- 完全退出应用（杀死进程）
- 重新启动应用
- 验证历史消息仍然存在
- 日志应显示：`Messages loaded: count=2`

#### 场景 3：跨天会话保持
- 启动应用并发送消息
- 等待 1 分钟后再次发送
- 验证会话未重置
- 日志应显示：`Session active: sessionId=xxx, tokenCount=xxx`

#### 场景 4：Token 计数
- 发送多条消息
- 观察日志中的 tokenCount 变化
- 验证计数合理增长

#### 场景 5：会话压缩（手动触发）
- 发送 10+ 条消息
- 触发压缩阈值
- 验证压缩日志：`Session compressed: originalTokenCount=xxx, compressedTokenCount=xxx`

### 4. 数据库验证
```bash
# 使用 adb 查看数据库
adb shell run-as ai.openclaw.android
cd databases
sqlite3 app_database.db

# 查询会话表
SELECT * FROM sessions;
SELECT * FROM messages ORDER BY createdAt DESC LIMIT 10;
SELECT * FROM summaries;
```

### 5. 预期结果

| 功能 | 验证点 |
|------|--------|
| 会话创建 | 启动时自动创建默认会话 |
| 消息持久化 | 重启后历史消息保留 |
| Token 计数 | 随消息增长 |
| 会话压缩 | 达到阈值时触发压缩 |

