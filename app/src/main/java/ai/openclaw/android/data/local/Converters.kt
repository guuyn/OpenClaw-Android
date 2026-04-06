package ai.openclaw.android.data.local

import androidx.room.TypeConverter
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.data.model.MemoryType
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

    @TypeConverter
    fun fromMemoryType(type: MemoryType): String = type.name

    @TypeConverter
    fun toMemoryType(value: String): MemoryType = MemoryType.valueOf(value)

    @TypeConverter
    fun fromStringList(list: List<String>): String = list.joinToString(",")

    @TypeConverter
    fun toStringList(value: String): List<String> = 
        if (value.isEmpty()) emptyList() else value.split(",")

    @TypeConverter
    fun fromFloatArray(array: FloatArray): String = array.joinToString(",") { it.toString() }

    @TypeConverter
    fun toFloatArray(value: String): FloatArray = 
        if (value.isEmpty()) floatArrayOf() 
        else value.split(",").map { it.toFloat() }.toFloatArray()
}