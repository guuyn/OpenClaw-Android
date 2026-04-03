package ai.openclaw.android.permission

import android.Manifest
import android.content.Context
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
    val isPermanentlyDenied: Boolean
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
            ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
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

        /** Map skillId to its permissions */
        fun getPermissionsForSkill(skillId: String): Array<String>? = when (skillId) {
            "location" -> LOCATION_PERMISSIONS
            "contact" -> CONTACT_PERMISSIONS
            "sms" -> SMS_PERMISSIONS
            "calendar" -> CALENDAR_PERMISSIONS
            else -> null
        }

        /** Map skillId to Chinese display name */
        fun getSkillDisplayName(skillId: String): String = when (skillId) {
            "location" -> "定位"
            "contact" -> "通讯录"
            "sms" -> "短信"
            "calendar" -> "日程"
            "weather" -> "天气"
            "translate" -> "翻译"
            "reminder" -> "提醒"
            else -> skillId
        }
    }
}
