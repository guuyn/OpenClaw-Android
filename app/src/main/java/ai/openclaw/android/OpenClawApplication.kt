package ai.openclaw.android

import android.app.Application
import android.content.Context
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.prefetch.PrefetchWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tencent.bugly.BuglyStrategy
import com.tencent.bugly.crashreport.CrashReport

class OpenClawApplication : Application() {
    lateinit var permissionManager: PermissionManager
        private set

    override fun onCreate() {
        super.onCreate()
        permissionManager = PermissionManager(this)
        initBugly()
        registerPrefetchWorker()
    }

    /**
     * 注册预采集定时任务
     */
    private fun registerPrefetchWorker() {
        val workRequest = PeriodicWorkRequestBuilder<PrefetchWorker>(30, java.util.concurrent.TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "prefetch_worker",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    /**
     * 初始化 Bugly 崩溃报告
     * - Debug 包：开启详细日志，方便本地调试
     * - Release 包：关闭日志输出，仅静默上报
     * - AppID 从 BuildConfig 读取，通过 local.properties 配置
     */
    private fun initBugly() {
        val appId = if (BuildConfig.DEBUG) {
            BuildConfig.BUGLY_APP_ID_DEBUG
        } else {
            BuildConfig.BUGLY_APP_ID_RELEASE
        }

        // 占位符检测：未配置真实 AppID 时跳过初始化
        if (appId.startsWith("placeholder") || appId.isBlank()) {
            android.util.Log.w(
                TAG,
                "Bugly AppID 未配置，跳过初始化。" +
                "请在 local.properties 中设置 BUGLY_APP_ID_DEBUG / BUGLY_APP_ID_RELEASE"
            )
            return
        }

        val strategy = BuglyStrategy().apply {
            // 应用版本号
            setAppVersion(BuildConfig.VERSION_NAME)

            // 渠道标识
            setAppChannel(if (BuildConfig.DEBUG) "debug" else "release")

            // 自定义设备标识（使用 Android ID，避免 IMEI 隐私问题）
            try {
                val androidId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )
                setDeviceID(androidId?.takeLast(16) ?: "unknown")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to get device identifier for Bugly", e)
            }

            // Debug 包开启日志上传，Release 关闭
            setBuglyLogUpload(BuildConfig.DEBUG)

            // 开启 ANR 监控
            setEnableANRCrashMonitor(true)

            // 开启 Native Crash 采集
            setEnableNativeCrashMonitor(true)
        }

        // Use reflection to explicitly call initCrashReport(Context, String, boolean, BuglyStrategy)
        // This avoids Kotlin overload resolution preferring the UserStrategy version
        val initMethod = CrashReport::class.java.getMethod(
            "initCrashReport",
            Context::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            BuglyStrategy::class.java
        )
        initMethod.invoke(null, applicationContext, appId, BuildConfig.DEBUG, strategy)

        android.util.Log.i(TAG, "Bugly initialized (appId: ${appId.take(3)}***, debug: ${BuildConfig.DEBUG})")

        // 设置自定义标签（用于 Bugly 控制台筛选）
        CrashReport.setUserId("openclaw-${if (BuildConfig.DEBUG) "debug" else "release"}")
        CrashReport.putUserData(this, "app_version", BuildConfig.VERSION_NAME)
        CrashReport.putUserData(this, "version_code", BuildConfig.VERSION_CODE.toString())
    }

    companion object {
        private const val TAG = "OpenClawApplication"
    }
}

fun Context.permissionManager(): PermissionManager =
    (applicationContext as OpenClawApplication).permissionManager
