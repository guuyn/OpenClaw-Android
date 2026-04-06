package ai.openclaw.android.data.model

enum class SessionStatus {
    ACTIVE,      // 活跃会话，正常交互
    COMPRESSED,  // 已压缩，部分消息被摘要替代
    ARCHIVED     // 已归档，不再活跃
}