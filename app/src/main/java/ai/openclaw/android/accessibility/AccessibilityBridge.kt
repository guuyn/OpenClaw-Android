package ai.openclaw.android.accessibility

import android.os.Build
import android.util.Log
import ai.openclaw.android.MyAccessibilityService
import ai.openclaw.android.model.Tool
import ai.openclaw.android.model.ToolCall
import ai.openclaw.android.model.ToolFunction
import ai.openclaw.android.model.ToolParameters
import ai.openclaw.android.model.ToolProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * AccessibilityBridge - Converts Agent tool calls to Accessibility commands
 */
class AccessibilityBridge {
    
    companion object {
        private const val TAG = "AccessibilityBridge"
    }
    
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Get the list of tools provided by this bridge
     */
    fun getTools(): List<Tool> {
        return listOf(
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "click",
                    description = "Click on a UI element by its visible text. Use this to tap buttons, menu items, or any clickable element.",
                    parameters = ToolParameters(
                        type = "object",
                        properties = mapOf(
                            "text" to ToolProperty(
                                type = "string",
                                description = "The visible text of the element to click"
                            ),
                            "index" to ToolProperty(
                                type = "integer",
                                description = "Index if there are multiple elements with the same text (default: 0)"
                            )
                        ),
                        required = listOf("text")
                    )
                )
            ),
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "click_by_id",
                    description = "Click on a UI element by its resource ID. Use this when you know the specific ID of the element.",
                    parameters = ToolParameters(
                        type = "object",
                        properties = mapOf(
                            "resource_id" to ToolProperty(
                                type = "string",
                                description = "The resource ID of the element (e.g., 'com.example.app:id/button')"
                            ),
                            "index" to ToolProperty(
                                type = "integer",
                                description = "Index if there are multiple elements with the same ID (default: 0)"
                            )
                        ),
                        required = listOf("resource_id")
                    )
                )
            ),
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "long_click",
                    description = "Long press on a UI element by its visible text. This triggers context menus or special actions.",
                    parameters = ToolParameters(
                        type = "object",
                        properties = mapOf(
                            "text" to ToolProperty(
                                type = "string",
                                description = "The visible text of the element to long click"
                            )
                        ),
                        required = listOf("text")
                    )
                )
            ),
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "input_text",
                    description = "Type text into the currently focused input field. Make sure an input field is focused first.",
                    parameters = ToolParameters(
                        type = "object",
                        properties = mapOf(
                            "text" to ToolProperty(
                                type = "string",
                                description = "The text to type into the input field"
                            )
                        ),
                        required = listOf("text")
                    )
                )
            ),
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "swipe",
                    description = "Perform a swipe gesture on the screen. Use this to scroll lists or navigate between pages.",
                    parameters = ToolParameters(
                        type = "object",
                        properties = mapOf(
                            "direction" to ToolProperty(
                                type = "string",
                                description = "Direction to swipe: 'up', 'down', 'left', or 'right'"
                            ),
                            "distance" to ToolProperty(
                                type = "number",
                                description = "Distance ratio of screen (0.0-1.0, default: 0.5). Higher values = longer swipe."
                            )
                        ),
                        required = listOf("direction")
                    )
                )
            ),
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "press_back",
                    description = "Press the back button. Use this to navigate back or close dialogs/popups.",
                    parameters = ToolParameters(
                        type = "object",
                        properties = emptyMap(),
                        required = emptyList()
                    )
                )
            ),
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "press_home",
                    description = "Press the home button. This takes you to the home screen.",
                    parameters = ToolParameters(
                        type = "object",
                        properties = emptyMap(),
                        required = emptyList()
                    )
                )
            ),
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "read_screen",
                    description = "Read all visible text content on the current screen. Use this to understand what's displayed.",
                    parameters = ToolParameters(
                        type = "object",
                        properties = emptyMap(),
                        required = emptyList()
                    )
                )
            ),
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "screenshot",
                    description = "Take a screenshot of the current screen. The image will be returned as base64.",
                    parameters = ToolParameters(
                        type = "object",
                        properties = emptyMap(),
                        required = emptyList()
                    )
                )
            ),
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "find_elements",
                    description = "Find UI elements by text and return their positions. Use this to locate elements before clicking.",
                    parameters = ToolParameters(
                        type = "object",
                        properties = mapOf(
                            "text" to ToolProperty(
                                type = "string",
                                description = "Text to search for in UI elements"
                            )
                        ),
                        required = listOf("text")
                    )
                )
            )
        )
    }
    
    /**
     * Execute a tool call
     */
    suspend fun execute(toolCall: ToolCall): String {
        val service = MyAccessibilityService.getInstance()
        
        if (service == null) {
            return "Error: Accessibility service is not running. Please enable it in Settings."
        }
        
        val toolName = toolCall.function.name
        val params = parseArguments(toolCall.function.arguments)
        
        Log.d(TAG, "Executing tool: $toolName with params: $params")
        
        return withContext(Dispatchers.Main) {
            when (toolName) {
                "click" -> executeClick(service, params)
                "click_by_id" -> executeClickById(service, params)
                "long_click" -> executeLongClick(service, params)
                "input_text" -> executeInputText(service, params)
                "swipe" -> executeSwipe(service, params)
                "press_back" -> service.pressBack()
                "press_home" -> service.pressHome()
                "read_screen" -> service.readScreenText()
                "screenshot" -> executeScreenshot(service)
                "find_elements" -> executeFindElements(service, params)
                else -> "Error: Unknown tool '$toolName'"
            }
        }
    }
    
    private fun parseArguments(argumentsJson: String): Map<String, Any?> {
        return try {
            json.decodeFromString<Map<String, Any?>>(argumentsJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse arguments: ${e.message}")
            emptyMap()
        }
    }
    
    private fun executeClick(service: MyAccessibilityService, params: Map<String, Any?>): String {
        val text = params["text"] as? String ?: return "Error: Missing 'text' parameter"
        val index = (params["index"] as? Number)?.toInt() ?: 0
        return service.clickByText(text, index)
    }
    
    private fun executeClickById(service: MyAccessibilityService, params: Map<String, Any?>): String {
        val resourceId = params["resource_id"] as? String ?: return "Error: Missing 'resource_id' parameter"
        val index = (params["index"] as? Number)?.toInt() ?: 0
        return service.clickById(resourceId, index)
    }
    
    private fun executeLongClick(service: MyAccessibilityService, params: Map<String, Any?>): String {
        val text = params["text"] as? String ?: return "Error: Missing 'text' parameter"
        return service.longClickByText(text)
    }
    
    private fun executeInputText(service: MyAccessibilityService, params: Map<String, Any?>): String {
        val text = params["text"] as? String ?: return "Error: Missing 'text' parameter"
        return service.inputText(text)
    }
    
    private fun executeSwipe(service: MyAccessibilityService, params: Map<String, Any?>): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return "Error: Swipe requires Android 7.0 (API 24) or higher"
        }
        
        val direction = params["direction"] as? String ?: return "Error: Missing 'direction' parameter"
        val distance = (params["distance"] as? Number)?.toDouble() ?: 0.5
        
        return service.swipe(direction, distance)
    }
    
    private suspend fun executeScreenshot(service: MyAccessibilityService): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "Error: Screenshot requires Android 8.0 (API 26) or higher"
        }
        
        return service.takeScreenshot()
    }
    
    private fun executeFindElements(service: MyAccessibilityService, params: Map<String, Any?>): String {
        val text = params["text"] as? String ?: return "Error: Missing 'text' parameter"
        val nodes = service.findNodesByText(text)
        
        if (nodes.isEmpty()) {
            return "No elements found with text '$text'"
        }
        
        val result = StringBuilder("Found ${nodes.size} element(s):\n")
        nodes.forEachIndexed { index, node ->
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            result.append("[$index] '${node.text}' at (${bounds.left}, ${bounds.top})\n")
        }
        
        return result.toString()
    }
}