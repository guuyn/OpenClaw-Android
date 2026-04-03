package ai.openclaw.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MyAccessibilityService - Accessibility Service for UI automation
 * 
 * Provides screen interaction capabilities:
 * - Find and click elements
 * - Input text
 * - Swipe gestures
 * - Screenshots
 * - Read screen text
 */
class MyAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "MyAccessibilityService"
        private const val GESTURE_DURATION_MS = 300L
        
        @Volatile
        private var instance: MyAccessibilityService? = null
        
        fun getInstance(): MyAccessibilityService? = instance
    }
    
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0
    
    // MediaProjection for screenshots
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        // Get screen dimensions
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }
        
        Log.d(TAG, "Service connected. Screen: ${screenWidth}x${screenHeight}")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process events for now
        // Just log them for debugging
        event?.let {
            if (it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                Log.d(TAG, "Window changed: ${it.packageName}")
            }
        }
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        releaseMediaProjection()
    }
    
    // ==================== Node Finding ====================
    
    /**
     * Find nodes by text content
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        Log.d(TAG, "Found ${nodes.size} nodes with text: $text")
        return nodes
    }
    
    /**
     * Find nodes by resource ID
     */
    fun findNodesById(resourceId: String): List<AccessibilityNodeInfo> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(resourceId)
        Log.d(TAG, "Found ${nodes.size} nodes with ID: $resourceId")
        return nodes
    }
    
    /**
     * Find the currently focused editable node
     */
    fun findFocusedEditable(): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        return findFocusedEditableRecursive(rootNode)
    }
    
    private fun findFocusedEditableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditableRecursive(child)
            if (found != null) return found
        }
        
        return null
    }
    
    // ==================== Click Actions ====================
    
    /**
     * Click on an element by text
     */
    fun clickByText(text: String, index: Int = 0): String {
        val nodes = findNodesByText(text)
        
        if (nodes.isEmpty()) {
            return "Error: No element found with text '$text'"
        }
        
        if (index >= nodes.size) {
            return "Error: Index $index out of bounds (${nodes.size} elements found)"
        }
        
        val targetNode = nodes[index]
        return clickNode(targetNode, text)
    }
    
    /**
     * Click on an element by resource ID
     */
    fun clickById(resourceId: String, index: Int = 0): String {
        val nodes = findNodesById(resourceId)
        
        if (nodes.isEmpty()) {
            return "Error: No element found with ID '$resourceId'"
        }
        
        if (index >= nodes.size) {
            return "Error: Index $index out of bounds (${nodes.size} elements found)"
        }
        
        val targetNode = nodes[index]
        return clickNode(targetNode, resourceId)
    }
    
    /**
     * Click at specific coordinates
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun clickAtPosition(x: Int, y: Int): String {
        Log.d(TAG, "Clicking at ($x, $y)")
        
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS))
            .build()
        
        val latch = CountDownLatch(1)
        var success = false
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        
        latch.await(1, TimeUnit.SECONDS)
        
        return if (success) "Success: Clicked at ($x, $y)" else "Error: Click gesture failed"
    }
    
    private fun clickNode(node: AccessibilityNodeInfo, identifier: String): String {
        // Try to find clickable ancestor
        var clickableNode: AccessibilityNodeInfo? = node
        while (clickableNode != null && !clickableNode.isClickable) {
            clickableNode = clickableNode.parent
        }
        
        val targetNode = clickableNode ?: node
        
        // Try performAction first
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "Clicked via performAction: $identifier")
            return "Success: Clicked '$identifier'"
        }
        
        // Fallback to gesture
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val bounds = Rect()
            targetNode.getBoundsInScreen(bounds)
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            
            return clickAtPosition(centerX, centerY)
        }
        
        return "Error: Could not click '$identifier'"
    }
    
    // ==================== Long Click ====================
    
    /**
     * Long click on an element by text
     */
    fun longClickByText(text: String, index: Int = 0): String {
        val nodes = findNodesByText(text)
        
        if (nodes.isEmpty()) {
            return "Error: No element found with text '$text'"
        }
        
        if (index >= nodes.size) {
            return "Error: Index $index out of bounds"
        }
        
        val targetNode = nodes[index]
        
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            return "Success: Long clicked '$text'"
        }
        
        // Fallback to gesture (long press = 1000ms)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val bounds = Rect()
            targetNode.getBoundsInScreen(bounds)
            
            val path = Path().apply {
                moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1000))
                .build()
            
            val latch = CountDownLatch(1)
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            
            latch.await(2, TimeUnit.SECONDS)
            return "Success: Long clicked '$text' (via gesture)"
        }
        
        return "Error: Could not long click '$text'"
    }
    
    // ==================== Input Text ====================
    
    /**
     * Input text into the focused input field
     */
    fun inputText(text: String): String {
        val focusedNode = findFocusedEditable()
        
        if (focusedNode == null) {
            return "Error: No focused input field found"
        }
        
        // Clear existing text
        focusedNode.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
        )
        
        // Input new text
        val success = focusedNode.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        )
        
        return if (success) {
            Log.d(TAG, "Input text: $text")
            "Success: Input '$text'"
        } else {
            "Error: Failed to input text"
        }
    }
    
    /**
     * Input text into a specific input field by ID
     */
    fun inputTextById(resourceId: String, text: String, index: Int = 0): String {
        val nodes = findNodesById(resourceId)
        
        if (nodes.isEmpty()) {
            return "Error: No input field found with ID '$resourceId'"
        }
        
        if (index >= nodes.size) {
            return "Error: Index $index out of bounds"
        }
        
        val targetNode = nodes[index]
        
        if (!targetNode.isEditable) {
            return "Error: Element is not editable"
        }
        
        val success = targetNode.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        )
        
        return if (success) "Success: Input '$text'" else "Error: Failed to input text"
    }
    
    // ==================== Swipe Gestures ====================
    
    /**
     * Swipe in a direction
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun swipe(direction: String, distanceRatio: Double = 0.5): String {
        val centerX = screenWidth / 2
        val centerY = screenHeight / 2
        val distancePx = (screenHeight * distanceRatio).toInt()
        
        Log.d(TAG, "Swiping $direction, distance: $distancePx px")
        
        val path = Path()
        when (direction.lowercase()) {
            "up" -> {
                path.moveTo(centerX.toFloat(), (centerY + distancePx / 2).toFloat())
                path.lineTo(centerX.toFloat(), (centerY - distancePx / 2).toFloat())
            }
            "down" -> {
                path.moveTo(centerX.toFloat(), (centerY - distancePx / 2).toFloat())
                path.lineTo(centerX.toFloat(), (centerY + distancePx / 2).toFloat())
            }
            "left" -> {
                path.moveTo((centerX + distancePx / 2).toFloat(), centerY.toFloat())
                path.lineTo((centerX - distancePx / 2).toFloat(), centerY.toFloat())
            }
            "right" -> {
                path.moveTo((centerX - distancePx / 2).toFloat(), centerY.toFloat())
                path.lineTo((centerX + distancePx / 2).toFloat(), centerY.toFloat())
            }
            else -> return "Error: Unknown direction '$direction'. Use: up, down, left, right"
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS))
            .build()
        
        val latch = CountDownLatch(1)
        var success = false
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        
        latch.await(1, TimeUnit.SECONDS)
        
        return if (success) "Success: Swiped $direction" else "Error: Swipe failed"
    }
    
    // ==================== System Keys ====================
    
    /**
     * Press back button
     */
    fun pressBack(): String {
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        return if (success) "Success: Pressed back" else "Error: Failed to press back"
    }
    
    /**
     * Press home button
     */
    fun pressHome(): String {
        val success = performGlobalAction(GLOBAL_ACTION_HOME)
        return if (success) "Success: Pressed home" else "Error: Failed to press home"
    }
    
    /**
     * Open recents
     */
    fun pressRecents(): String {
        val success = performGlobalAction(GLOBAL_ACTION_RECENTS)
        return if (success) "Success: Opened recents" else "Error: Failed to open recents"
    }
    
    // ==================== Read Screen ====================
    
    /**
     * Read all visible text on the screen
     */
    fun readScreenText(): String {
        val rootNode = rootInActiveWindow ?: return "Error: Cannot access screen"
        val textBuilder = StringBuilder()
        
        traverseNodeForText(rootNode, textBuilder)
        
        val result = textBuilder.toString().trim()
        Log.d(TAG, "Screen text length: ${result.length}")
        
        return if (result.isNotEmpty()) result else "No text found on screen"
    }
    
    private fun traverseNodeForText(node: AccessibilityNodeInfo, builder: StringBuilder) {
        // Add node's text if present
        node.text?.let { text ->
            if (text.isNotEmpty()) {
                builder.append(text).append("\n")
            }
        }
        
        // Add content description if present
        node.contentDescription?.let { desc ->
            if (desc.isNotEmpty() && builder.indexOf(desc.toString()) == -1) {
                builder.append(desc).append("\n")
            }
        }
        
        // Traverse children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNodeForText(child, builder)
        }
    }
    
    // ==================== Screenshot ====================
    
    /**
     * Initialize MediaProjection for screenshots
     */
    fun initMediaProjection(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.e(TAG, "MediaProjection requires API 21+")
            return
        }
        
        releaseMediaProjection()
        
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)
        
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            android.graphics.PixelFormat.RGBA_8888, 2
        )
        
        mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        
        Log.d(TAG, "MediaProjection initialized")
    }
    
    /**
     * Take a screenshot and return as base64
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun takeScreenshot(): String {
        return withContext(Dispatchers.IO) {
            if (mediaProjection == null || imageReader == null) {
                return@withContext "Error: MediaProjection not initialized. Call initMediaProjection first."
            }
            
            try {
                val image = imageReader?.acquireLatestImage()
                if (image == null) {
                    return@withContext "Error: Could not capture image"
                }
                
                val bitmap = imageToBitmap(image)
                image.close()
                
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                
                val base64 = Base64.getEncoder().encodeToString(outputStream.toByteArray())
                Log.d(TAG, "Screenshot captured, base64 length: ${base64.length}")
                
                base64
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot failed: ${e.message}")
                "Error: ${e.message}"
            }
        }
    }
    
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        
        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Crop to actual screen size
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } else {
            bitmap
        }
    }
    
    private fun releaseMediaProjection() {
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }
}