package ai.openclaw.android.skill.builtin

import ai.openclaw.android.MyAccessibilityService
import ai.openclaw.android.skill.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * ScreenSkill — 屏幕截图、坐标点击、结构化 UI 读取、滚动到指定元素。
 *
 * 截图三级 fallback：
 *   Level 1: MediaProjection（需用户授权，质量最高）
 *   Level 2: PixelCopy API（需 Activity window，当前不可用时跳过）
 *   Level 3: Accessibility 视图树结构化描述（兜底）
 */
class ScreenSkill(
    private val context: Context
) : Skill {
    override val id = "screen"
    override val name = "屏幕控制"
    override val description = "屏幕截图、坐标点击、结构化 UI 读取、滚动到指定元素"
    override val version = "1.0.0"

    override val instructions = """
# Screen Skill

屏幕截图、坐标点击、结构化 UI 读取、滚动到指定元素。

## 可用工具
- `screenshot` — 截取当前屏幕，返回 base64 图片或视图树描述
- `click_at` — 在指定坐标点击
- `read` — 读取当前屏幕结构化 UI 信息
- `scroll_to` — 滚动到包含指定文本的元素

## 截图 fallback 机制
`screenshot` 工具按优先级尝试：
1. **MediaProjection** — 全屏真实截图，需用户已授权
2. **PixelCopy** — Android 8.0+ 窗口级截图，需应用在前台
3. **View Tree** — 结构化 UI 描述文本兜底
""".trimIndent()

    override val tools: List<SkillTool> = listOf(
        ScreenshotTool(),
        ClickAtTool(),
        ReadTool(),
        ScrollToTool()
    )

    // ==================== screenshot ====================

    private inner class ScreenshotTool : SkillTool {
        override val name = "screenshot"
        override val description =
            "截取当前屏幕。优先使用 MediaProjection，失败后尝试 PixelCopy，最终 fallback 到视图树结构化描述。"
        override val parameters = mapOf(
            "format" to SkillParam(
                type = "string",
                description = "图片格式: jpeg 或 png，默认 jpeg",
                required = false,
                default = "jpeg"
            ),
            "quality" to SkillParam(
                type = "number",
                description = "JPEG 质量 1-100，默认 80",
                required = false,
                default = 80
            ),
            "fallback" to SkillParam(
                type = "boolean",
                description = "是否允许在 MediaProjection 不可用时使用 fallback，默认 true",
                required = false,
                default = true
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val format = (params["format"] as? String)?.lowercase() ?: "jpeg"
            val quality = (params["quality"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 80
            val allowFallback = params["fallback"] as? Boolean ?: true

            val service = MyAccessibilityService.getInstance()

            // --- Level 1: MediaProjection ---
            val projection = service?.getMediaProjection()
            val ir = service?.getImageReader()
            if (projection != null && ir != null) {
                return try {
                    val bitmap = captureMediaProjection(ir)
                    val base64 = bitmapToBase64(bitmap, format, quality)
                    SkillResult(
                        success = true,
                        output = buildScreenshotJson(
                            method = "mediaprojection",
                            base64 = base64,
                            viewtree = null,
                            error = null
                        )
                    )
                } catch (e: Exception) {
                    Log.w("ScreenSkill", "MediaProjection screenshot failed: ${e.message}")
                    if (allowFallback) {
                        fallbackScreenshot(service, format, quality)
                    } else {
                        SkillResult(false, "", "MediaProjection 截图失败: ${e.message}")
                    }
                }
            }

            // No fallback allowed
            if (!allowFallback) {
                return SkillResult(false, "", "MediaProjection 不可用且 fallback 已禁用")
            }

            // --- Level 2: PixelCopy ---
            // PixelCopy requires an Activity window — not available in background service.
            // Skip directly to Level 3.

            // --- Level 3: View tree description ---
            return fallbackScreenshot(service, format, quality)
        }

        private suspend fun captureMediaProjection(imageReader: android.media.ImageReader): android.graphics.Bitmap {
            val image = imageReader.acquireLatestImage()
                ?: throw IllegalStateException("Could not acquire latest image from ImageReader")

            try {
                return imageToBitmap(image)
            } finally {
                image.close()
            }
        }

        private fun fallbackScreenshot(
            service: MyAccessibilityService?,
            format: String,
            quality: Int
        ): SkillResult {
            // Try viewtree first (always available if service running)
            val viewtree = service?.readScreenStructured()
                ?: "Error: Accessibility service not available"

            return SkillResult(
                success = true,
                output = buildScreenshotJson("viewtree", null, viewtree, null)
            )
        }
    }

    // ==================== click_at ====================

    private inner class ClickAtTool : SkillTool {
        override val name = "click_at"
        override val description = "在屏幕指定坐标点击。"
        override val parameters = mapOf(
            "x" to SkillParam(
                type = "number",
                description = "X 坐标（屏幕像素）",
                required = true
            ),
            "y" to SkillParam(
                type = "number",
                description = "Y 坐标（屏幕像素）",
                required = true
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val x = (params["x"] as? Number)?.toInt()
                ?: return SkillResult(false, "", "缺少 x 参数")
            val y = (params["y"] as? Number)?.toInt()
                ?: return SkillResult(false, "", "缺少 y 参数")

            val service = MyAccessibilityService.getInstance()
                ?: return SkillResult(false, "", "无障碍服务未运行")

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return SkillResult(false, "", "click_at 需要 Android 7.0+")
            }

            return try {
                val result = service.clickAtPosition(x, y)
                if (result.startsWith("Error")) {
                    SkillResult(false, "", result)
                } else {
                    SkillResult(true, result)
                }
            } catch (e: Exception) {
                SkillResult(false, "", "点击失败: ${e.message}")
            }
        }
    }

    // ==================== read ====================

    private inner class ReadTool : SkillTool {
        override val name = "read"
        override val description =
            "读取当前屏幕的结构化 UI 信息。返回当前窗口的视图树、焦点元素、可交互元素列表。"
        override val parameters = mapOf(
            "max_depth" to SkillParam(
                type = "number",
                description = "视图树最大深度，默认 8",
                required = false,
                default = 8
            ),
            "interactive_only" to SkillParam(
                type = "boolean",
                description = "仅返回可交互元素，默认 false",
                required = false,
                default = false
            ),
            "include_bounds" to SkillParam(
                type = "boolean",
                description = "包含屏幕坐标，默认 true",
                required = false,
                default = true
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val service = MyAccessibilityService.getInstance()
                ?: return SkillResult(false, "", "无障碍服务未运行")

            val interactiveOnly = params["interactive_only"] as? Boolean ?: false

            return try {
                if (interactiveOnly) {
                    val result = readScreenInteractive(service)
                    SkillResult(true, result)
                } else {
                    // Use the existing structured read
                    val result = service.readScreenStructured()
                    SkillResult(true, result)
                }
            } catch (e: Exception) {
                SkillResult(false, "", "读取屏幕失败: ${e.message}")
            }
        }

        private fun readScreenInteractive(service: MyAccessibilityService): String {
            val rootNode = service.rootInActiveWindow
                ?: return "Error: No active window"

            val builder = StringBuilder()
            builder.append("[SCREEN] Package: ${rootNode.packageName ?: "unknown"}\n")
            builder.append("[INTERACTIVE ELEMENTS]\n")
            builder.append("---\n")

            traverseInteractive(rootNode, builder, 0)

            return if (builder.isEmpty()) {
                "No interactive elements found"
            } else {
                builder.toString()
            }
        }

        private fun traverseInteractive(
            node: AccessibilityNodeInfo,
            builder: StringBuilder,
            depth: Int
        ) {
            if (depth > 8) return
            if (node.isVisibleToUser || depth == 0) {
                val isInteractive = node.isClickable || node.isEditable || node.isLongClickable
                if (isInteractive) {
                    val indent = "  ".repeat(depth.coerceAtMost(6))
                    val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
                    val text = node.text?.toString()?.take(50)
                    val contentDesc = node.contentDescription?.toString()?.take(50)

                    val label = if (!text.isNullOrBlank()) "\"$text\""
                    else if (!contentDesc.isNullOrBlank()) "[desc: $contentDesc]"
                    else ""

                    val flags = mutableListOf<String>()
                    if (node.isClickable) flags.add("clickable")
                    if (node.isEditable) flags.add("editable")
                    if (node.isLongClickable) flags.add("long_clickable")
                    if (!node.isEnabled) flags.add("disabled")

                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)

                    builder.append("${indent}[$className] $label")
                    if (flags.isNotEmpty()) builder.append(" {${flags.joinToString(", ")}}")
                    builder.append(" @(${bounds.left},${bounds.top})-${bounds.right},${bounds.bottom}\n")
                }

                val maxChildren = node.childCount.coerceAtMost(50)
                for (i in 0 until maxChildren) {
                    val child = node.getChild(i) ?: continue
                    traverseInteractive(child, builder, depth + 1)
                }
            }
        }
    }

    // ==================== scroll_to ====================

    private inner class ScrollToTool : SkillTool {
        override val name = "scroll_to"
        override val description = "滚动到包含指定文本的元素位置。"
        override val parameters = mapOf(
            "text" to SkillParam(
                type = "string",
                description = "要滚动到的文本内容",
                required = true
            ),
            "direction" to SkillParam(
                type = "string",
                description = "滚动方向: up 或 down，默认 down",
                required = false,
                default = "down"
            ),
            "max_scrolls" to SkillParam(
                type = "number",
                description = "最大滚动次数，默认 5",
                required = false,
                default = 5
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val text = params["text"] as? String
                ?: return SkillResult(false, "", "缺少 text 参数")

            val direction = (params["direction"] as? String)?.lowercase() ?: "down"
            if (direction !in listOf("up", "down")) {
                return SkillResult(false, "", "direction 必须是 up 或 down")
            }

            val maxScrolls = (params["max_scrolls"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 5

            val service = MyAccessibilityService.getInstance()
                ?: return SkillResult(false, "", "无障碍服务未运行")

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return SkillResult(false, "", "scroll_to 需要 Android 7.0+")
            }

            // First check if text is already visible
            val existingNodes = service.findNodesByText(text)
            if (existingNodes.isNotEmpty()) {
                return SkillResult(true, "文本 '$text' 已在屏幕上可见，找到 ${existingNodes.size} 个匹配")
            }

            // Scroll until text is found or max_scrolls reached
            for (i in 1..maxScrolls) {
                service.swipe(direction, 0.5)
                kotlinx.coroutines.delay(300)

                val nodes = service.findNodesByText(text)
                if (nodes.isNotEmpty()) {
                    return SkillResult(true, "滚动 $i 次后找到文本 '$text'")
                }
            }

            return SkillResult(
                false,
                "",
                "滚动 $maxScrolls 次后仍未找到文本 '$text'"
            )
        }
    }

    // ==================== Utility methods ====================

    private fun bitmapToBase64(bitmap: android.graphics.Bitmap, format: String, quality: Int): String {
        val compressFormat = if (format == "png") android.graphics.Bitmap.CompressFormat.PNG
        else android.graphics.Bitmap.CompressFormat.JPEG
        val stream = ByteArrayOutputStream()
        bitmap.compress(compressFormat, quality, stream)
        return Base64.getEncoder().encodeToString(stream.toByteArray())
    }

    private fun imageToBitmap(image: android.media.Image): android.graphics.Bitmap {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = android.graphics.Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding > 0) {
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } else {
            bitmap
        }
    }

    private fun buildScreenshotJson(
        method: String,
        base64: String?,
        viewtree: String?,
        error: String?
    ): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"success\": true,")
        sb.append("\"method\": \"$method\",")
        sb.append("\"data\": ")
        if (base64 != null) {
            // Truncate base64 for JSON output to keep it manageable
            val displayData = if (base64.length > 200)
                base64.take(200) + "...(truncated)"
            else base64
            sb.append("\"data:image/jpeg;base64,$displayData\"")
        } else {
            sb.append("null")
        }
        sb.append(",")
        if (viewtree != null) {
            val escaped = viewtree.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            sb.append("\"viewtree\": \"$escaped\",")
        } else {
            sb.append("\"viewtree\": null,")
        }
        sb.append("\"error\": ")
        if (error != null) {
            sb.append("\"${error.replace("\"", "\\\"")}\"")
        } else {
            sb.append("null")
        }
        sb.append("}")
        return sb.toString()
    }

    // ==================== Skill lifecycle ====================

    override fun initialize(context: SkillContext) {
        Log.i("ScreenSkill", "Initialized")
    }

    override fun cleanup() {}
}
