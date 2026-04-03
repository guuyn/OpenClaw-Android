package ai.openclaw.android

import android.util.Log
import ai.openclaw.android.accessibility.AccessibilityBridge
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.model.BailianClient
import ai.openclaw.android.model.ModelProvider
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.skill.builtin.WeatherSkill
import ai.openclaw.android.skill.builtin.MultiSearchSkill
import ai.openclaw.android.skill.builtin.TranslateSkill
import ai.openclaw.android.skill.builtin.ReminderSkill
import ai.openclaw.android.skill.builtin.CalendarSkill
import ai.openclaw.android.skill.builtin.LocationSkill
import ai.openclaw.android.skill.builtin.ContactSkill
import ai.openclaw.android.skill.builtin.SMSSkill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * GatewayManager - Manages all Gateway components
 */
class GatewayManager(private val service: GatewayService) {
    
    companion object {
        private const val TAG = "GatewayManager"
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Components
    private var modelClient: BailianClient? = null
    private var agentSession: AgentSession? = null
    private var accessibilityBridge: AccessibilityBridge? = null
    private var skillManager: SkillManager? = null
    
    // State
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    /**
     * Start the Gateway
     */
    fun start() {
        Log.d(TAG, "Starting Gateway...")
        
        // Check configuration
        if (!ConfigManager.isConfigured()) {
            _connectionState.value = ConnectionState.Error("Configuration incomplete")
            return
        }
        
        serviceScope.launch {
            try {
                _connectionState.value = ConnectionState.Connecting
                
                // Initialize components
                initializeComponents()
                
                _connectionState.value = ConnectionState.Connected
                Log.d(TAG, "Gateway started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Gateway: ${e.message}")
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Stop the Gateway
     */
    fun stop() {
        Log.d(TAG, "Stopping Gateway...")
        
        // Cleanup skills
        skillManager?.getLoadedSkills()?.values?.forEach { skill ->
            try {
                skill.cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup skill: ${e.message}")
            }
        }
        skillManager = null
        
        agentSession = null
        accessibilityBridge = null
        
        _connectionState.value = ConnectionState.Disconnected
        Log.d(TAG, "Gateway stopped")
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stop()
        serviceScope.cancel()
    }
    
    private fun initializeComponents() {
        Log.d(TAG, "Initializing components...")
        
        // Initialize ModelClient
        modelClient = BailianClient().apply {
            configure(
                provider = ModelProvider.valueOf(ConfigManager.getModelProvider()),
                apiKey = ConfigManager.getModelApiKey(),
                model = ConfigManager.getModelName()
            )
        }
        
        // Initialize AccessibilityBridge
        accessibilityBridge = AccessibilityBridge()
        
        // Initialize SkillManager and register skills
        skillManager = SkillManager(service).apply {
            registerSkill(WeatherSkill())
            registerSkill(MultiSearchSkill())
            // Register new mobile skills
            registerSkill(TranslateSkill())
            registerSkill(ReminderSkill(service))
            registerSkill(CalendarSkill(service))
            registerSkill(LocationSkill(service))
            registerSkill(ContactSkill(service))
            registerSkill(SMSSkill(service))
        }
        
        // Initialize AgentSession with SkillManager
        agentSession = AgentSession(
            modelClient = modelClient!!,
            skillManager = skillManager!!,
            maxContextTokens = 4000
        ).apply {
            setToolsWithSkills(
                accessibilityTools = accessibilityBridge!!.getTools(),
                executor = { toolCall ->
                    accessibilityBridge!!.execute(toolCall)
                }
            )
        }
        
        Log.d(TAG, "Components initialized")
    }
}