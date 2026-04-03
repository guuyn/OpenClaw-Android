package ai.openclaw.android

import android.app.Application
import android.content.Context
import ai.openclaw.android.permission.PermissionManager

class OpenClawApplication : Application() {
    lateinit var permissionManager: PermissionManager
        private set

    override fun onCreate() {
        super.onCreate()
        permissionManager = PermissionManager(this)
    }
}

fun Context.permissionManager(): PermissionManager =
    (applicationContext as OpenClawApplication).permissionManager
