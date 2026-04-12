package ai.openclaw.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ai.openclaw.android.agent.SessionEvent
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
    fun sendMessage(text: String): Flow<SessionEvent>
    suspend fun reconfigureModel(config: ModelConfig): Boolean
    fun getAvailableSkills(): List<SkillInfo>
}

data class ModelConfig(
    val provider: ModelProvider,
    val apiKey: String,
    val modelName: String
)

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String
)
