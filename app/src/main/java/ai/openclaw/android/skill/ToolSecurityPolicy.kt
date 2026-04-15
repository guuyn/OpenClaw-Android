package ai.openclaw.android.skill

/**
 * 工具安全策略
 */
enum class ToolSecurityPolicy {
    /** 自动执行（幂等操作） */
    AUTO_EXECUTE,
    /** 询问用户（非幂等操作，首次） */
    ASK_USER,
    /** 拒绝执行（用户已拒绝） */
    DENY
}

/**
 * 用户审批决策
 */
enum class ApprovalDecision {
    /** 总是允许 */
    ALWAYS_APPROVE,
    /** 总是拒绝 */
    ALWAYS_DENY,
    /** 每次都询问 */
    ASK_EVERY_TIME
}

/**
 * 用户审批偏好
 *
 * @param toolId 完整工具 ID: "bitcoin_price_set_alert"
 * @param decision 用户决策
 * @param lastUsedAt 最后使用时间戳
 */
@kotlinx.serialization.Serializable
data class UserApprovalPreference(
    val toolId: String,
    val decision: ApprovalDecision,
    val lastUsedAt: Long = System.currentTimeMillis()
)

/**
 * 安全审查器 — 纯逻辑，可 JVM 单元测试
 */
object SecurityReview {
    /**
     * 审查工具，返回安全策略
     *
     * @param toolName 工具名称
     * @param isIdempotent 是否幂等
     * @param preference 用户已有的审批偏好（可为 null）
     * @return 安全策略
     */
    fun reviewTool(
        toolName: String,
        isIdempotent: Boolean,
        preference: UserApprovalPreference?
    ): ToolSecurityPolicy {
        return when {
            // 幂等操作 → 直接执行
            isIdempotent -> ToolSecurityPolicy.AUTO_EXECUTE
            // 无历史偏好 → 首次询问
            preference == null -> ToolSecurityPolicy.ASK_USER
            // 有偏好 → 按偏好处理
            else -> when (preference.decision) {
                ApprovalDecision.ALWAYS_APPROVE -> ToolSecurityPolicy.AUTO_EXECUTE
                ApprovalDecision.ALWAYS_DENY -> ToolSecurityPolicy.DENY
                ApprovalDecision.ASK_EVERY_TIME -> ToolSecurityPolicy.ASK_USER
            }
        }
    }
}
