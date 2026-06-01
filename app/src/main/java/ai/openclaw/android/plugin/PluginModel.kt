package ai.openclaw.android.plugin

/**
 * 插件数据模型 — 安装态插件元信息。
 *
 * 与 [ai.openclaw.android.plugin.PluginManager.PluginInfo]（运行时发现态）不同，
 * 本模型描述已安装到本地 filesDir/plugins 目录的插件。
 */
data class PluginInfo(
    /** 唯一标识（APK package name 或 ZIP 内 manifest 声明的 id） */
    val id: String,
    /** 展示名称 */
    val name: String,
    /** 语义化版本 */
    val version: String,
    /** 作者 */
    val author: String = "",
    /** 描述 */
    val description: String = "",
    /** 图标路径（可为 null） */
    val iconPath: String? = null,
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 安装时间戳（epoch ms） */
    val installedAt: Long = System.currentTimeMillis(),
    /** 最后更新时间戳（epoch ms） */
    val updatedAt: Long = System.currentTimeMillis(),
    /** 运行时发现的 package name（仅 APK 插件） */
    val packageName: String? = null,
    /** 引擎类型（如 litert, gguf） */
    val engineType: String? = null,
    /** 插件源文件路径（APK/ZIP） */
    val sourceFile: String? = null
)

/**
 * 插件安装结果
 */
sealed class PluginInstallResult {
    data class Success(val pluginInfo: PluginInfo) : PluginInstallResult()
    data class Failed(val reason: String, val originalInfo: PluginInfo? = null) : PluginInstallResult()

    val isSuccess: Boolean
        get() = this is Success
}

/**
 * 插件操作状态（用于 UI 显示和 Flow 通知）
 */
sealed class PluginOperationState {
    /** 空闲 */
    object Idle : PluginOperationState()
    /** 进行中 */
    data class InProgress(val message: String, val pluginId: String? = null) : PluginOperationState()
    /** 成功 */
    data class Completed(val message: String, val pluginId: String? = null) : PluginOperationState()
    /** 失败 */
    data class Error(val message: String, val pluginId: String? = null, val throwable: Throwable? = null) :
        PluginOperationState()
}
