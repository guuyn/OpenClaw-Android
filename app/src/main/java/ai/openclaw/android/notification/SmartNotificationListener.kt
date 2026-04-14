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
        
        // 服务实例（供 companion 方法调用系统 API）
        @Volatile private var instance: SmartNotificationListener? = null
        
        // 监听器是否已连接（getActiveNotifications() 只在连接后有效）
        @Volatile private var isConnected = false
        
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
         * 直接从系统通知栏获取当前所有通知（同步方法）
         * 这是 list_notifications 技能的主要数据源，不依赖内存 StateFlow
         */
        fun getActiveNotificationsList(): List<SmartNotification> {
            val svc = instance ?: run {
                Log.w(TAG, "getActiveNotificationsList: service not available")
                return emptyList()
            }
            
            // 如果监听器还没连接，getActiveNotifications() 会返回空
            // 此时回退到内存 StateFlow（由 onNotificationPosted 填充）
            if (!isConnected) {
                Log.d(TAG, "getActiveNotificationsList: not connected yet, falling back to StateFlow")
                return _notifications.value
            }
            
            return try {
                val activeSbns = svc.getActiveNotifications()
                if (activeSbns.isNullOrEmpty()) {
                    Log.d(TAG, "getActiveNotifications returned 0 notifications")
                    return emptyList()
                }
                Log.d(TAG, "getActiveNotifications returned ${activeSbns.size} notifications")
                activeSbns.mapNotNull { sbn ->
                    svc.parseNotification(sbn)
                }.sortedByDescending { it.timestamp }
                    .take(100)
            } catch (e: SecurityException) {
                Log.e(TAG, "No permission: ${e.message}")
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed: ${e.message}")
                emptyList()
            }
        }

        /**
         * 从系统通知栏主动拉取当前所有通知（异步，更新 StateFlow）
         * 用于：1) 启动时初始加载  2) 内存列表为空时的兜底查询
         */
        fun fetchActiveNotificationsFromSystem() {
            val svc = instance ?: run {
                Log.w(TAG, "fetchActiveNotifications: service not available")
                return
            }
            companionScope.launch {
                svc.loadActiveNotifications()
            }
        }

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
         * 重新从系统拉取通知（兜底/刷新用）
         */
        fun refreshFromSystem() {
            fetchActiveNotificationsFromSystem()
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
        instance = this
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
        isConnected = true
        Log.d(TAG, "Notification listener connected")
        
        // 检查通知监听权限是否授予
        try {
            val enabled = android.provider.Settings.Secure.getString(
                contentResolver,
                "enabled_notification_listeners"
            )
            val isGranted = enabled?.contains(packageName) == true
            Log.d(TAG, "Notification listener permission granted: $isGranted (enabled: $enabled)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check permission: ${e.message}")
        }
        
        // 【1. 启动时主动拉取】获取当前状态栏所有通知
        // 延迟 5 秒等待系统同步完成
        scope.launch {
            delay(5000)
            Log.d(TAG, "Calling getActiveNotifications()...")
            loadActiveNotifications()
        }
        
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
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
        // 【2. 被动监听】用户在系统通知栏移除了通知，同步更新
        val id = sbn.key
        scope.launch {
            _notifications.value = _notifications.value.filter { it.id != id }
        }
    }
    
    /**
     * 主动加载系统通知栏所有通知
     * 启动时调用 + 内存列表为空时兜底
     */
    private suspend fun loadActiveNotifications(retryCount: Int = 0) {
        try {
            Log.d(TAG, "=== loadActiveNotifications attempt ${retryCount + 1} ===")
            Log.d(TAG, "packageName=$packageName")
            val activeSbns = getActiveNotifications()
            Log.d(TAG, "getActiveNotifications returned ${activeSbns?.size ?: 0} notifications (type: ${activeSbns?.javaClass?.simpleName})")
            
            if (activeSbns.isNullOrEmpty()) {
                Log.w(TAG, "getActiveNotifications returned empty list (retry $retryCount/5)")
                if (retryCount < 5) {
                    val delayMs = 2000L * (retryCount + 1)
                    Log.d(TAG, "Retrying in ${delayMs}ms...")
                    delay(delayMs)
                    loadActiveNotifications(retryCount + 1)
                } else {
                    Log.e(TAG, "Failed to get notifications after 6 retries (12s total)")
                    _notifications.value = emptyList()
                }
                return
            }
            
            Log.d(TAG, "Found ${activeSbns.size} active notifications, parsing...")
            activeSbns.forEachIndexed { index, sbn ->
                Log.d(TAG, "  [$index] pkg=${sbn.packageName} id=${sbn.id} key=${sbn.key}")
                val extras = sbn.notification.extras
                val title = extras.getString(Notification.EXTRA_TITLE) ?: "(no title)"
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "(no text)"
                Log.d(TAG, "       title=\"$title\" text=\"$text\"")
            }
            
            val parsed = activeSbns.mapNotNull { parseNotification(it) }
            Log.d(TAG, "Parsed ${parsed.size} valid notifications from ${activeSbns.size}")
            
            if (parsed.isEmpty()) {
                Log.w(TAG, "All ${activeSbns.size} notifications were filtered out (empty title/text or self)")
            }
            
            // 分类所有通知
            val classified = parsed.map { notification ->
                val category = classifier.classify(notification)
                if (category == NotificationCategory.NOISE) {
                    Log.d(TAG, "Filtered as noise: ${notification.title}")
                    null
                } else {
                    notification.copy(category = category)
                }
            }.filterNotNull()
                .sortedByDescending { it.timestamp }
                .take(100)
            
            _notifications.value = classified
            Log.d(TAG, "=== Final: ${classified.size} notifications loaded (${parsed.size - classified.size} filtered as noise) ===")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            if (retryCount < 2) {
                delay(2000L)
                loadActiveNotifications(retryCount + 1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}", e)
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