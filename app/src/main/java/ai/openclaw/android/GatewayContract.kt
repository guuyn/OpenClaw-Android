package ai.openclaw.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.domain.DeviceCapabilities
import ai.openclaw.android.domain.memory.MemoryManager
import ai.openclaw.android.domain.session.HybridSessionManager
import ai.openclaw.android.model.ImageContent
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.ModelProvider

/**
 * Gateway 服务契约接口
 * Activity 只依赖此接口，不直接访问 GatewayManager 内部组件
 * 为将来改成远程 Service（真正跨进程）留了退路
 */
interface GatewayContract {
    fun isReady(): Boolean
    fun getModelLoadState(): LocalLLMClient.LoadState?
    fun getConnectionState(): StateFlow<GatewayManager.ConnectionState>
    fun sendMessage(text: String, images: List<ImageContent>? = null): Flow<SessionEvent>
    suspend fun reconfigureModel(config: ModelConfig): Boolean
    fun getAvailableSkills(): List<SkillInfo>
    fun getAvailableAgents(): List<AgentInfo>

    /**
     * Request MediaProjection permission for screenshots.
     * Returns an Intent that must be launched with startActivityForResult.
     */
    fun getScreenCaptureIntent(): android.content.Intent?

    /**
     * Initialize MediaProjection after user grants permission.
     */
    fun initScreenCapture(resultCode: Int, data: android.content.Intent): Boolean

    // ========== Extended methods for ChatViewModel integration ==========

    /** 获取 HybridSessionManager，用于 Session CRUD 操作 */
    fun getSessionManager(): HybridSessionManager?

    /** 获取 MemoryManager，用于记忆查询 */
    fun getMemoryManager(): MemoryManager?

    /** 获取当前 AgentSession（用于 clearHistory 等 direct session 操作） */
    fun getAgentSession(): AgentSession?

    /** 获取可用 Agent 列表（用于多 Agent UI） */
    fun getAgents(): List<AgentInfo>

    /** 清空当前会话历史 */
    fun clearHistory()

    /** 注入 ScriptSkill UI Provider */
    fun setScriptUiProvider(provider: Any?)

    /** 获取设备能力信息 */
    fun getDeviceCapabilities(): DeviceCapabilities?
}

data class ModelConfig(
    val provider: ModelProvider,
    val apiKey: String,
    val modelName: String,
    val baseUrl: String = ""
)

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String
)

data class AgentInfo(
    val id: String,
    val name: String,
    val isDefault: Boolean
)
