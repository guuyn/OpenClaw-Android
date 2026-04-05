package ai.openclaw.android.feishu

/**
 * 飞书客户端接口，用于处理飞书消息的收发
 */
interface FeishuClient {
    /**
     * 连接到飞书 WebSocket 服务
     */
    fun connect(appId: String, appSecret: String)
    
    /**
     * 断开连接
     */
    fun disconnect()
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean
    
    /**
     * 设置事件监听器
     */
    fun setEventListener(listener: (FeishuEvent) -> Unit)
    
    /**
     * 发送消息到指定聊天
     */
    suspend fun sendMessage(chatId: String, content: String): Result<String>
    
    /**
     * 上传文件到指定聊天
     */
    suspend fun uploadFile(chatId: String, filePath: String): Result<String>
}