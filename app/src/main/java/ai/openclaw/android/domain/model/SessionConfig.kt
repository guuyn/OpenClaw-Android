package ai.openclaw.android.domain.model

/**
 * 会话配置类
 */
data class SessionConfig(
    val maxTokens: Int = 1800,                    // 触发压缩阈值
    val preserveRecentMessages: Int = 10,         // 保留最近 N 条
    val autoCompressDefault: Boolean = true,      // 是否默认开启自动压缩
    val importanceThreshold: Float = 0.7f,        // 语义重要性阈值，高于此值延迟压缩
    val minMessagesForCompress: Int = 5           // 至少 N 条消息才触发压缩
)
