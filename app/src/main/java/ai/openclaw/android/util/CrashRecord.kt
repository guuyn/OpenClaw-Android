package ai.openclaw.android.util

import android.util.Log
import com.tencent.bugly.crashreport.CrashReport

/**
 * 统一封装 Bugly 自定义日志。
 *
 * 设计原则：
 * - 不发送 PII（不记录用户输入内容、API Key 等敏感信息）
 * - 只记录错误类型、模块名、异常类名
 * - 使用 postCatchedException() 记录非致命异常，附带 Android Log 作为上下文
 *
 * 注意：Bugly SDK 4.x 不公开 `CrashReport.log()` API，
 * 自定义日志通过 Android Log + postCatchedException 组合实现。
 */
object CrashRecord {

    private const val TAG_PREFIX = "BuglyCrashRecord"

    /**
     * 记录 AgentSession 相关错误
     */
    fun logAgentSessionError(errorType: String, context: String, exceptionMessage: String?) {
        Log.w(TAG_PREFIX, "[AgentSession] type=$errorType, exception=${exceptionMessage ?: "none"}")
    }

    /**
     * 记录 LocalLLMClient 相关错误
     * - 上报非致命异常到 Bugly
     * - 同时记录 Android Log
     */
    fun logLocalLLMError(errorType: String, e: Throwable) {
        Log.e(TAG_PREFIX, "[LocalLLMClient] type=$errorType, exception=${e.javaClass.simpleName}: ${e.message}", e)
        // 非致命异常上报到 Bugly
        CrashReport.postCatchedException(e)
    }

    /**
     * 记录 LocalLLMClient 警告（不上报 Bugly，仅本地日志）
     */
    fun logLocalLLMWarning(errorType: String, message: String?) {
        Log.w(TAG_PREFIX, "[LocalLLMClient] type=$errorType, message=${message ?: "unknown"}")
    }
}
