package ai.openclaw.android.notification

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

/**
 * 通知监听服务
 * 监听所有应用通知，分类后交给 NotificationManager 处理
 */
class SmartNotificationListener : NotificationListenerService() {
    
    companion object {
        private const val TAG = "SmartNotification"
        
        // 通知状态流（供 UI 订阅）
        private val _notifications = MutableStateFlow<List<SmartNotification>>(emptyList())
        val notifications: StateFlow<List<SmartNotification>> = _notifications.asStateFlow()
        
        // 分类器
        private lateinit var classifier: NotificationClassifier
        
        // 获取待处理通知数量
        fun getPendingCount(): Int = _notifications.value.count { !it.isRead }
        
        // 获取各类型通知
        fun getUrgentCount(): Int = _notifications.value.count { it.category == NotificationCategory.URGENT && !it.isRead }
        fun getImportantCount(): Int = _notifications.value.count { it.category == NotificationCategory.IMPORTANT && !it.isRead }
        fun getNormalCount(): Int = _notifications.value.count { it.category == NotificationCategory.NORMAL && !it.isRead }
        fun getNoiseCount(): Int = _notifications.value.count { it.category == NotificationCategory.NOISE }

        // 后台作用域（供 companion 方法使用）
        private val companionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        /**
         * 删除通知（companion 方法，供非实例上下文调用）
         */
        fun deleteNotification(notificationId: String) {
            companionScope.launch {
                _notifications.value = _notifications.value.filter { it.id != notificationId }
            }
        }

        /**
         * 清空所有通知（companion 方法，供非实例上下文调用）
         */
        fun clearAll() {
            companionScope.launch {
                _notifications.value = emptyList()
            }
        }

        /**
         * 标记通知为已读（companion 方法，供非实例上下文调用）
         */
        fun markAsRead(notificationId: String) {
            companionScope.launch {
                _notifications.value = _notifications.value.map {
                    if (it.id == notificationId) it.copy(isRead = true) else it
                }
            }
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    override fun onCreate() {
        super.onCreate()
        classifier = NotificationClassifier(this)
        Log.d(TAG, "SmartNotificationListener created")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            classifier.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing classifier", e)
        }
        scope.cancel()
        Log.d(TAG, "SmartNotificationListener destroyed")
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = parseNotification(sbn)
        if (notification != null) {
            scope.launch {
                processNotification(notification)
            }
        }
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 用户在系统通知栏移除了通知，同步更新我们的列表
        val id = sbn.key
        scope.launch {
            _notifications.value = _notifications.value.filter { it.id != id }
        }
    }
    
    /**
     * 解析系统通知为我们的数据模型
     */
    private fun parseNotification(sbn: StatusBarNotification): SmartNotification? {
        val packageName = sbn.packageName
        val notification = sbn.notification
        
        // 提取通知内容
        val extras = notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text
        
        // 过滤空通知
        if (title.isBlank() && text.isBlank()) {
            return null
        }
        
        // 过滤自己应用的通知（避免循环）
        if (packageName == "ai.openclaw.android") {
            return null
        }
        
        return SmartNotification(
            id = sbn.key,
            packageName = packageName,
            title = title,
            text = text,
            bigText = bigText,
            timestamp = sbn.postTime,
            category = NotificationCategory.PENDING, // 待分类
            isRead = false,
            extras = extrasToMap(extras)
        )
    }
    
    /**
     * 处理通知：分类 + 存储
     */
    private suspend fun processNotification(notification: SmartNotification) {
        // 分类
        val category = classifier.classify(notification)
        val classified = notification.copy(category = category)
        
        Log.d(TAG, "Notification from ${notification.packageName}: ${notification.title} -> $category")
        
        // 噪音通知可以选择不存储
        if (category == NotificationCategory.NOISE) {
            Log.d(TAG, "Noise notification ignored: ${notification.title}")
            return
        }
        
        // 添加到列表
        _notifications.value = (_notifications.value + classified)
            .sortedByDescending { it.timestamp }
            .take(100) // 限制最多 100 条
        
        // 根据类别决定是否提醒用户
        handleClassifiedNotification(classified)
    }
    
    /**
     * 根据类别处理通知
     */
    private fun handleClassifiedNotification(notification: SmartNotification) {
        when (notification.category) {
            NotificationCategory.URGENT -> {
                // 紧急通知：立即提醒
                // TODO: 发送本地通知或更新状态栏
                Log.d(TAG, "URGENT notification: ${notification.title}")
            }
            NotificationCategory.IMPORTANT -> {
                // 重要通知：5分钟汇总提醒
                Log.d(TAG, "IMPORTANT notification: ${notification.title}")
            }
            NotificationCategory.NORMAL -> {
                // 一般通知：30分钟汇总提醒
                Log.d(TAG, "NORMAL notification: ${notification.title}")
            }
            NotificationCategory.NOISE -> {
                // 已过滤
            }
            NotificationCategory.PENDING -> {}
        }
    }
    
    /**
     * Bundle 转 Map
     */
    private fun extrasToMap(extras: Bundle): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (key in extras.keySet()) {
            extras.get(key)?.toString()?.let { value ->
                map[key] = value.take(500) // 限制长度
            }
        }
        return map
    }
    
    // ==================== 公共方法（供外部调用） ====================
    
    /**
     * 标记通知为已读
     */
    fun markAsRead(notificationId: String) {
        scope.launch {
            _notifications.value = _notifications.value.map {
                if (it.id == notificationId) it.copy(isRead = true) else it
            }
        }
    }
    
    /**
     * 标记所有通知为已读
     */
    fun markAllAsRead() {
        scope.launch {
            _notifications.value = _notifications.value.map { it.copy(isRead = true) }
        }
    }
    
    /**
     * 删除通知
     */
    fun deleteNotification(notificationId: String) {
        scope.launch {
            _notifications.value = _notifications.value.filter { it.id != notificationId }
        }
    }
    
    /**
     * 清空所有通知
     */
    fun clearAll() {
        scope.launch {
            _notifications.value = emptyList()
        }
    }
}

// ==================== 数据模型 ====================

/**
 * 智能通知数据类
 */
data class SmartNotification(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val bigText: String,
    val timestamp: Long,
    val category: NotificationCategory,
    val isRead: Boolean,
    val extras: Map<String, String>
)

/**
 * 通知类别
 */
enum class NotificationCategory {
    PENDING,    // 待分类
    URGENT,     // 紧急（微信、电话、短信）
    IMPORTANT,  // 重要（工作应用、日历）
    NORMAL,     // 一般（新闻、社交）
    NOISE       // 噪音（广告、推广）
}