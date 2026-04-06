package ai.openclaw.android.data.local

import androidx.room.TypeConverter
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.data.model.SessionStatus

class Converters {
    @TypeConverter
    fun fromSessionStatus(status: SessionStatus): String {
        return status.name
    }

    @TypeConverter
    fun toSessionStatus(value: String): SessionStatus {
        return SessionStatus.valueOf(value)
    }

    @TypeConverter
    fun fromMessageRole(role: MessageRole): String {
        return role.name
    }

    @TypeConverter
    fun toMessageRole(value: String): MessageRole {
        return MessageRole.valueOf(value)
    }
}