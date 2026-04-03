package ai.openclaw.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Gateway Service - Foreground Service for OpenClaw Android
 * 
 * This service maintains the connection to Feishu and handles all
 * agent communication in the background.
 */
class GatewayService : Service() {

    companion object {
        private const val TAG = "GatewayService"
        
        // LogManager instance for centralized logging
        private val logManager = LogManager.shared
        private const val NOTIFICATION_CHANNEL_ID = "gateway_service"
        private const val NOTIFICATION_ID = 1001
        
        // Intent actions
        const val ACTION_START = "ai.openclaw.android.action.START"
        const val ACTION_STOP = "ai.openclaw.android.action.STOP"
        
        // Broadcast actions
        const val ACTION_STATUS_CHANGED = "ai.openclaw.android.action.STATUS_CHANGED"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_STATE = "state"
        
        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var gatewayManager: GatewayManager? = null
    private var isRunning = false
    
    override fun onCreate() {
        super.onCreate()
        logManager.log("INFO", TAG, "GatewayService created")
        Log.d(TAG, "onCreate")
        
        // Initialize ConfigManager
        ConfigManager.init(this)
        
        // Create notification channel
        createNotificationChannel()
        
        // Initialize GatewayManager
        gatewayManager = GatewayManager(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logManager.log("INFO", TAG, "GatewayService started, action=${intent?.action}")
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_STOP -> {
                stopGateway()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!isRunning) {
                    startForeground()
                    startGateway()
                }
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        logManager.log("INFO", TAG, "GatewayService destroyed")
        Log.d(TAG, "onDestroy")
        stopGateway()
        serviceScope.cancel()
        super.onDestroy()
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun startForeground() {
        val notification = createNotification("Gateway is starting...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun startGateway() {
        logManager.log("INFO", TAG, "Starting gateway...")
        isRunning = true
        broadcastStatus()
        
        // Check configuration
        if (!ConfigManager.isConfigured()) {
            logManager.log("WARN", TAG, "Configuration incomplete")
            updateNotification("Configuration incomplete")
            return
        }
        
        // Start gateway and monitor state
        serviceScope.launch {
            gatewayManager?.connectionState?.collectLatest { state ->
                val text = when (state) {
                    is GatewayManager.ConnectionState.Connected -> {
                        logManager.log("INFO", TAG, "Gateway connected")
                        ConfigManager.setServiceEnabled(true)
                        getString(R.string.notification_text_connected)
                    }
                    is GatewayManager.ConnectionState.Connecting -> {
                        logManager.log("INFO", TAG, "Gateway connecting...")
                        "Connecting..."
                    }
                    is GatewayManager.ConnectionState.Disconnected -> {
                        logManager.log("WARN", TAG, "Gateway disconnected")
                        ConfigManager.setServiceEnabled(false)
                        "Disconnected"
                    }
                    is GatewayManager.ConnectionState.Error -> {
                        logManager.log("ERROR", TAG, "Gateway error: ${state.message}")
                        ConfigManager.setServiceEnabled(false)
                        "Error: ${state.message}"
                    }
                }
                updateNotification(text)
            }
        }
        
        gatewayManager?.start()
    }
    
    private fun stopGateway() {
        logManager.log("INFO", TAG, "Stopping gateway...")
        isRunning = false
        ConfigManager.setServiceEnabled(false)
        broadcastStatus()
        gatewayManager?.stop()
    }
    
    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_IS_RUNNING, isRunning)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
    
    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = createNotification(text)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}