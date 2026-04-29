package ai.openclaw.android.gateway

import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.model.ImageContent
import kotlinx.coroutines.flow.Flow

/**
 * 消息网关抽象接口
 *
 * 隔离 ChatViewModel 与具体网关实现（GatewayContract / MockGateway），
 * 使业务逻辑可在无网络/无服务的测试环境下独立验证。
 *
 * 实现类：
 * - [RealGateway] — 包装 GatewayContract，走真实 Feishu/AI 网关
 * - [MockGateway] — 返回预设离线响应，用于测试模式
 */
interface MessageGateway {
    /**
     * 发送用户消息，返回流式会话事件
     *
     * @param text 用户输入文本
     * @param images 可选附带图片（最多 3 张）
     * @return 流式 SessionEvent 序列
     */
    fun sendMessage(text: String, images: List<ImageContent>? = null): Flow<SessionEvent>

    /**
     * 网关是否已就绪可发送消息
     */
    fun isReady(): Boolean
}
