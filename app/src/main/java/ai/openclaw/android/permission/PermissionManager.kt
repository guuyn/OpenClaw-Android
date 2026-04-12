package ai.openclaw.android.permission

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PermissionRequest(
    val id: String,
    val permissions: Array<String>,
    val skillId: String,
    val skillName: String,
    val deferred: CompletableDeferred<Boolean>
)

data class PermissionGroupStatus(
    val skillId: String,
    val displayName: String,
    val permissions: Array<String>,
    val isGranted: Boolean,
    val isPermanentlyDenied: Boolean,
    /** If true, this permission requires a system settings page (e.g. MANAGE_EXTERNAL_STORAGE) */
    val isSpecialPermission: Boolean = false
)

class PermissionManager(private val context: Context) {

    private val _currentRequest = MutableStateFlow<PermissionRequest?>(null)
    val currentRequest: StateFlow<PermissionRequest?> = _currentRequest.asStateFlow()

    /**
     * Suspend function that requests permissions and waits for the user's response.
     * The UI layer observes [currentRequest] and launches the system permission dialog.
     */
    suspend fun requestPermission(
        permissions: Array<String>,
        skillId: String,
        skillName: String
    ): Boolean {
        // Fast path: already granted
        if (hasPermissions(permissions)) return true

        val deferred = CompletableDeferred<Boolean>()
        val request = PermissionRequest(
            id = UUID.randomUUID().toString(),
            permissions = permissions,
            skillId = skillId,
            skillName = skillName,
            deferred = deferred
        )
        _currentRequest.value = request
        return deferred.await()
    }

    /**
     * Called by the UI layer when the permission dialog result is available.
     */
    fun resolveRequest(requestId: String, granted: Boolean) {
        val current = _currentRequest.value
        if (current?.id == requestId) {
            current.deferred.complete(granted)
            _currentRequest.value = null
        }
    }

    fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all { perm ->
            when {
                // MANAGE_EXTERNAL_STORAGE is a special permission checked via Environment API
                perm == Manifest.permission.MANAGE_EXTERNAL_STORAGE -> hasAllFilesAccess()
                else -> ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /**
     * Returns true if any of the given permissions is a "special" permission
     * that must be granted through system settings (not via standard requestPermission flow).
     */
    fun hasSpecialPermission(permissions: Array<String>): Boolean {
        return permissions.any { it == Manifest.permission.MANAGE_EXTERNAL_STORAGE }
    }

    /**
     * Check if MANAGE_EXTERNAL_STORAGE (All Files Access) is granted.
     * On Android 11+ (API 30+), this uses Environment.isExternalStorageManager().
     * On older versions, falls back to regular READ/WRITE_EXTERNAL_STORAGE checks.
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // On Android 10 and below, check legacy storage permissions directly
            // DO NOT call hasPermissions() to avoid infinite recursion
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val grantedWrite = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            granted && grantedWrite
        }
    }

    /**
     * Get all permission groups with their current status.
     */
    fun getAllPermissionGroups(): List<PermissionGroupStatus> {
        return listOf(
            PermissionGroupStatus(
                skillId = "location",
                displayName = "定位",
                permissions = LOCATION_PERMISSIONS,
                isGranted = hasPermissions(LOCATION_PERMISSIONS),
                isPermanentlyDenied = false
            ),
            PermissionGroupStatus(
                skillId = "contact",
                displayName = "通讯录",
                permissions = CONTACT_PERMISSIONS,
                isGranted = hasPermissions(CONTACT_PERMISSIONS),
                isPermanentlyDenied = false
            ),
            PermissionGroupStatus(
                skillId = "sms",
                displayName = "短信",
                permissions = SMS_PERMISSIONS,
                isGranted = hasPermissions(SMS_PERMISSIONS),
                isPermanentlyDenied = false
            ),
            PermissionGroupStatus(
                skillId = "calendar",
                displayName = "日程",
                permissions = CALENDAR_PERMISSIONS,
                isGranted = hasPermissions(CALENDAR_PERMISSIONS),
                isPermanentlyDenied = false
            ),
            PermissionGroupStatus(
                skillId = "storage",
                displayName = "文件存储（本地模型）",
                permissions = STORAGE_PERMISSIONS,
                isGranted = hasAllFilesAccess(),
                isPermanentlyDenied = false,
                isSpecialPermission = true
            )
        )
    }

    companion object {
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val CONTACT_PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS)
        val SMS_PERMISSIONS = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)
        val CALENDAR_PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val STORAGE_PERMISSIONS = arrayOf(Manifest.permission.MANAGE_EXTERNAL_STORAGE)

        /** Map skillId to its permissions */
        fun getPermissionsForSkill(skillId: String): Array<String>? = when (skillId) {
            "location" -> LOCATION_PERMISSIONS
            "contact" -> CONTACT_PERMISSIONS
            "sms" -> SMS_PERMISSIONS
            "calendar" -> CALENDAR_PERMISSIONS
            "storage" -> STORAGE_PERMISSIONS
            else -> null
        }

        /** Map skillId to Chinese display name */
        fun getSkillDisplayName(skillId: String): String = when (skillId) {
            "location" -> "定位"
            "contact" -> "通讯录"
            "sms" -> "短信"
            "calendar" -> "日程"
            "storage" -> "文件存储"
            "weather" -> "天气"
            "translate" -> "翻译"
            "reminder" -> "提醒"
            else -> skillId
        }
    }
}
