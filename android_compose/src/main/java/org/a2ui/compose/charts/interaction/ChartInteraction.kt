package org.a2ui.compose.charts.interaction

import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import kotlin.math.*

/**
 * 图表交互状态
 */
@Stable
class ChartInteractionState {
    // 缩放状态
    var scale by mutableFloatStateOf(1f)
        private set

    // 平移偏移
    var offset by mutableStateOf(Offset.Zero)
        private set

    // 选择区域
    var selectionRect by mutableStateOf<Rect?>(null)
        private set

    // 是否正在交互
    var isInteracting by mutableStateOf(false)
        private set

    // 缩放范围限制
    var minScale = 0.5f
    var maxScale = 10f

    // 平移边界
    var bounds = Rect.Zero

    /**
     * 应用缩放
     */
    fun applyScale(scaleFactor: Float, pivot: Offset = Offset.Zero) {
        val newScale = (scale * scaleFactor).coerceIn(minScale, maxScale)
        if (newScale != scale) {
            // 计算以pivot为中心的缩放偏移调整
            val scaleChange = newScale / scale
            val pivotOffset = pivot - offset
            val newOffset = offset + pivotOffset * (1 - scaleChange)

            scale = newScale
            offset = constrainOffset(newOffset)
        }
    }

    /**
     * 应用平移
     */
    fun applyPan(delta: Offset) {
        offset = constrainOffset(offset + delta)
    }

    /**
     * 设置选择区域
     */
    fun setSelection(rect: Rect?) {
        selectionRect = rect
    }

    /**
     * 开始交互
     */
    fun startInteraction() {
        isInteracting = true
    }

    /**
     * 结束交互
     */
    fun endInteraction() {
        isInteracting = false
    }

    /**
     * 重置到初始状态
     */
    fun reset() {
        scale = 1f
        offset = Offset.Zero
        selectionRect = null
        isInteracting = false
    }

    /**
     * 约束偏移在边界内
     */
    private fun constrainOffset(newOffset: Offset): Offset {
        if (bounds == Rect.Zero) return newOffset

        val scaledBounds = Rect(
            left = bounds.left * scale,
            top = bounds.top * scale,
            right = bounds.right * scale,
            bottom = bounds.bottom * scale
        )

        return Offset(
            x = newOffset.x.coerceIn(
                minimumValue = -scaledBounds.width + bounds.width,
                maximumValue = 0f
            ),
            y = newOffset.y.coerceIn(
                minimumValue = -scaledBounds.height + bounds.height,
                maximumValue = 0f
            )
        )
    }
}

/**
 * 图表交互类型
 */
enum class ChartInteractionMode {
    NONE,           // 无交互
    PAN,            // 平移
    ZOOM,           // 缩放
    SELECT,         // 选择
    PAN_AND_ZOOM,   // 平移和缩放
    ALL             // 所有交互
}

/**
 * 图表交互配置
 */
data class ChartInteractionConfig(
    val mode: ChartInteractionMode = ChartInteractionMode.PAN_AND_ZOOM,
    val enableDoubleTapZoom: Boolean = true,
    val enablePinchZoom: Boolean = true,
    val enableSelection: Boolean = true,
    val zoomSensitivity: Float = 1f,
    val panSensitivity: Float = 1f,
    val selectionColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Blue.copy(alpha = 0.3f),
    val selectionBorderColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Blue,
    val selectionBorderWidth: androidx.compose.ui.unit.Dp = 2.dp
)

/**
 * 选择事件数据
 */
data class ChartSelectionEvent(
    val selectionRect: Rect,
    val dataIndices: List<Int>,
    val dataValues: List<Float>
)

/**
 * 缩放事件数据
 */
data class ChartZoomEvent(
    val scale: Float,
    val center: Offset,
    val visibleRange: Pair<Float, Float>
)

/**
 * 平移事件数据
 */
data class ChartPanEvent(
    val offset: Offset,
    val visibleRange: Pair<Float, Float>
)

/**
 * 创建图表交互状态
 */
@Composable
fun rememberChartInteractionState(): ChartInteractionState {
    return remember { ChartInteractionState() }
}

/**
 * 图表交互修饰符
 */
fun androidx.compose.ui.Modifier.chartInteraction(
    state: ChartInteractionState,
    config: ChartInteractionConfig,
    onSelection: ((ChartSelectionEvent) -> Unit)? = null,
    onZoom: ((ChartZoomEvent) -> Unit)? = null,
    onPan: ((ChartPanEvent) -> Unit)? = null
): androidx.compose.ui.Modifier {
    return this.pointerInput(state, config) {
        var selectionStart: Offset? = null

        detectDragGestures(
            onDragStart = { offset ->
                state.startInteraction()
                when (config.mode) {
                    ChartInteractionMode.SELECT, ChartInteractionMode.ALL -> {
                        selectionStart = offset
                    }
                    else -> {}
                }
            },
            onDragEnd = {
                state.endInteraction()
                selectionStart?.let { start ->
                    state.selectionRect?.let { rect ->
                        onSelection?.invoke(
                            ChartSelectionEvent(
                                selectionRect = rect,
                                dataIndices = emptyList(), // 需要根据具体图表计算
                                dataValues = emptyList()
                            )
                        )
                    }
                }
                selectionStart = null
            },
            onDrag = { change, _ ->
                when (config.mode) {
                    ChartInteractionMode.PAN, ChartInteractionMode.PAN_AND_ZOOM, ChartInteractionMode.ALL -> {
                        if (selectionStart == null) {
                            val delta = change.position * config.panSensitivity
                            state.applyPan(delta)
                            onPan?.invoke(
                                ChartPanEvent(
                                    offset = state.offset,
                                    visibleRange = 0f to 1f // 需要根据具体图表计算
                                )
                            )
                        }
                    }
                    ChartInteractionMode.SELECT, ChartInteractionMode.ALL -> {
                        selectionStart?.let { start ->
                            val current = change.position
                            val rect = Rect(
                                offset = Offset(
                                    x = minOf(start.x, current.x),
                                    y = minOf(start.y, current.y)
                                ),
                                size = androidx.compose.ui.geometry.Size(
                                    width = abs(current.x - start.x),
                                    height = abs(current.y - start.y)
                                )
                            )
                            state.setSelection(rect)
                        }
                    }
                    else -> {}
                }
            }
        )
    }.pointerInput(state, config) {
        // 处理缩放手势
        if (config.enablePinchZoom &&
            (config.mode == ChartInteractionMode.ZOOM ||
             config.mode == ChartInteractionMode.PAN_AND_ZOOM ||
             config.mode == ChartInteractionMode.ALL)) {

            detectTransformGestures { centroid, pan, zoom, _ ->
                state.startInteraction()

                // 应用缩放
                if (zoom != 1f) {
                    state.applyScale(zoom * config.zoomSensitivity, centroid)
                    onZoom?.invoke(
                        ChartZoomEvent(
                            scale = state.scale,
                            center = centroid,
                            visibleRange = 0f to 1f // 需要根据具体图表计算
                        )
                    )
                }

                // 应用平移（如果支持）
                if (config.mode == ChartInteractionMode.PAN_AND_ZOOM || config.mode == ChartInteractionMode.ALL) {
                    state.applyPan(pan * config.panSensitivity)
                    onPan?.invoke(
                        ChartPanEvent(
                            offset = state.offset,
                            visibleRange = 0f to 1f
                        )
                    )
                }

                state.endInteraction()
            }
        }
    }.pointerInput(state, config) {
        // 处理双击缩放
        if (config.enableDoubleTapZoom &&
            (config.mode == ChartInteractionMode.ZOOM ||
             config.mode == ChartInteractionMode.PAN_AND_ZOOM ||
             config.mode == ChartInteractionMode.ALL)) {

            detectTapGestures(
                onDoubleTap = { offset ->
                    val targetScale = if (state.scale > 1f) 1f else 2f
                    val scaleFactor = targetScale / state.scale
                    state.applyScale(scaleFactor, offset)

                    onZoom?.invoke(
                        ChartZoomEvent(
                            scale = state.scale,
                            center = offset,
                            visibleRange = 0f to 1f
                        )
                    )
                }
            )
        }
    }
}