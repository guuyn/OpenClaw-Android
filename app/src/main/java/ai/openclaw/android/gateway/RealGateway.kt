package ai.openclaw.android.gateway

import ai.openclaw.android.GatewayContract
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.model.ImageContent
import kotlinx.coroutines.flow.Flow

/**
 * 真实网关实现 — 包装 GatewayContract
 *
 * 将现有的 GatewayContract（Feishu 网关通信）适配为 MessageGateway 接口，
 * 使 ChatViewModel 通过统一接口发送消息，不直接依赖 GatewayContract。
 *
 * 生命周期由 Activity 管理服务绑定时创建/更新 contract 引用。
 */
class RealGateway(
    private var contractProvider: () -> GatewayContract?
) : MessageGateway {

    override fun sendMessage(text: String, images: List<ImageContent>?): Flow<SessionEvent> {
        val contract = contractProvider()
            ?: throw IllegalStateException("GatewayContract 未就绪，请检查 GatewayService 连接")
        return contract.sendMessage(text, images)
    }

    override fun isReady(): Boolean = contractProvider()?.isReady() == true
}
