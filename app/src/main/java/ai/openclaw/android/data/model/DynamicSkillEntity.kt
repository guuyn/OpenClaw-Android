package ai.openclaw.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dynamic_skills")
data class DynamicSkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val version: String,
    val instructions: String,
    val script: String,               // JS 脚本源码
    val toolsJson: String,            // 工具定义 JSON 数组
    val permissions: String = "",     // 所需权限，逗号分隔
    val createdAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true
)
