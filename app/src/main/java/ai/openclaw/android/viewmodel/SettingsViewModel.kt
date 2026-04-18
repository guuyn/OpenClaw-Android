package ai.openclaw.android.viewmodel

import ai.openclaw.android.ConfigManager
import ai.openclaw.android.LogManager
import ai.openclaw.android.data.local.AppDatabase
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置 ViewModel
 *
 * 管理配置状态（API Key、模型名称、提供商）、服务状态、UI 展开状态
 */
class SettingsViewModel(
    val database: AppDatabase
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    // ==================== 配置状态 ====================

    private val _modelApiKey = MutableStateFlow("")
    val modelApiKey: StateFlow<String> = _modelApiKey.asStateFlow()

    private val _modelName = MutableStateFlow("qwen-plus")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    private val _modelProvider = MutableStateFlow("OPENAI")
    val modelProvider: StateFlow<String> = _modelProvider.asStateFlow()

    private val _modelBaseUrl = MutableStateFlow("")
    val modelBaseUrl: StateFlow<String> = _modelBaseUrl.asStateFlow()

    // ==================== UI 状态 ====================

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _configExpanded = MutableStateFlow(true)
    val configExpanded: StateFlow<Boolean> = _configExpanded.asStateFlow()

    private val _logExpanded = MutableStateFlow(false)
    val logExpanded: StateFlow<Boolean> = _logExpanded.asStateFlow()

    // ==================== 初始化 ====================

    /**
     * 从 ConfigManager 加载已保存的配置
     */
    fun loadConfig() {
        _modelApiKey.value = ConfigManager.getModelApiKey()
        _modelName.value = ConfigManager.getModelName()
        _modelProvider.value = try {
            ConfigManager.getModelProvider()
        } catch (_: Exception) {
            "OPENAI"
        }
        _modelBaseUrl.value = ConfigManager.getModelBaseUrl()
        _serviceRunning.value = ConfigManager.isServiceEnabled()
    }

    // ==================== 状态更新方法 ====================

    fun setModelApiKey(value: String) { _modelApiKey.value = value }
    fun setModelName(value: String) { _modelName.value = value }
    fun setModelProvider(value: String) { _modelProvider.value = value }
    fun setModelBaseUrl(value: String) { _modelBaseUrl.value = value }
    fun setConfigExpanded(value: Boolean) { _configExpanded.value = value }
    fun setLogExpanded(value: Boolean) { _logExpanded.value = value }

    /**
     * 切换服务运行状态
     */
    fun toggleService() {
        _serviceRunning.value = !_serviceRunning.value
    }
}
