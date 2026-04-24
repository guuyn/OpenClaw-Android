package org.a2ui.compose.charts.advanced

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import org.a2ui.compose.charts.*
import org.a2ui.compose.theme.A2UIThemeConfig
import kotlin.math.*

/**
 * 热力图数据
 */
data class HeatmapData(
    val values: Array<FloatArray>,
    val xLabels: List<String> = emptyList(),
    val yLabels: List<String> = emptyList(),
    val minValue: Float? = null,
    val maxValue: Float? = null
) {
    val rows: Int get() = values.size
    val cols: Int get() = if (values.isNotEmpty()) values[0].size else 0

    fun getValueRange(): Pair<Float, Float> {
        if (minValue != null && maxValue != null) {
            return minValue to maxValue
        }

        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE

        values.forEach { row ->
            row.forEach { value ->
                if (value < min) min = value
                if (value > max) max = value
            }
        }

        return min to max
    }
}

/**
 * 雷达图数据
 */
data class RadarData(
    val series: List<RadarSeries>,
    val axes: List<String>,
    val maxValue: Float = 100f
)

data class RadarSeries(
    val name: String,
    val values: List<Float>,
    val color: Color,
    val fillAlpha: Float = 0.3f,
    val strokeWidth: Float = 2f
)

/**
 * 气泡图数据
 */
data class BubbleData(
    val bubbles: List<BubblePoint>,
    val xRange: Pair<Float, Float>? = null,
    val yRange: Pair<Float, Float>? = null,
    val sizeRange: Pair<Float, Float>? = null
)

data class BubblePoint(
    val x: Float,
    val y: Float,
    val size: Float,
    val label: String = "",
    val color: Color = Color.Blue,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * 热力图组件
 */
@Composable
fun HeatmapChart(
    data: HeatmapData,
    modifier: Modifier = Modifier,
    config: ChartConfig = ChartConfig(),
    themeConfig: A2UIThemeConfig,
    colorScheme: List<Color> = defaultHeatmapColors
) {
    val animatedProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = if (config.animationEnabled && themeConfig.enableAnimations) {
            tween(config.animationDuration, easing = FastOutSlowInEasing)
        } else {
            tween(0)
        },
        label = "heatmap_animation"
    )

    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.bodySmall

    Canvas(modifier = modifier.fillMaxSize()) {
        if (data.rows == 0 || data.cols == 0) return@Canvas

        drawHeatmap(
            data = data,
            progress = animatedProgress,
            colorScheme = colorScheme,
            config = config,
            textMeasurer = textMeasurer,
            textStyle = textStyle,
            size = size
        )
    }
}

/**
 * 雷达图组件
 */
@Composable
fun RadarChart(
    data: RadarData,
    modifier: Modifier = Modifier,
    config: ChartConfig = ChartConfig(),
    themeConfig: A2UIThemeConfig
) {
    val animatedProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = if (config.animationEnabled && themeConfig.enableAnimations) {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        } else {
            tween(0)
        },
        label = "radar_animation"
    )

    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.bodySmall

    Canvas(modifier = modifier.fillMaxSize()) {
        if (data.axes.isEmpty() || data.series.isEmpty()) return@Canvas

        drawRadarChart(
            data = data,
            progress = animatedProgress,
            config = config,
            textMeasurer = textMeasurer,
            textStyle = textStyle,
            size = size
        )
    }
}

/**
 * 气泡图组件
 */
@Composable
fun BubbleChart(
    data: BubbleData,
    modifier: Modifier = Modifier,
    config: ChartConfig = ChartConfig(),
    themeConfig: A2UIThemeConfig
) {
    val animatedProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = if (config.animationEnabled && themeConfig.enableAnimations) {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            )
        } else {
            tween(0)
        },
        label = "bubble_animation"
    )

    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.bodySmall

    Canvas(modifier = modifier.fillMaxSize()) {
        if (data.bubbles.isEmpty()) return@Canvas

        drawBubbleChart(
            data = data,
            progress = animatedProgress,
            config = config,
            textMeasurer = textMeasurer,
            textStyle = textStyle,
            size = size
        )
    }
}

/**
 * 绘制热力图
 */
private fun DrawScope.drawHeatmap(
    data: HeatmapData,
    progress: Float,
    colorScheme: List<Color>,
    config: ChartConfig,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    textStyle: TextStyle,
    size: Size
) {
    val padding = 60.dp.toPx()
    val chartWidth = size.width - padding * 2
    val chartHeight = size.height - padding * 2

    val cellWidth = chartWidth / data.cols
    val cellHeight = chartHeight / data.rows

    val (minValue, maxValue) = data.getValueRange()
    val valueRange = maxValue - minValue

    // 绘制热力图单元格
    for (row in 0 until data.rows) {
        for (col in 0 until data.cols) {
            val value = data.values[row][col]
            val normalizedValue = if (valueRange > 0) {
                (value - minValue) / valueRange
            } else 0f

            // 应用动画进度
            val animatedValue = normalizedValue * progress

            // 根据值选择颜色
            val color = interpolateColor(colorScheme, animatedValue)

            val x = padding + col * cellWidth
            val y = padding + row * cellHeight

            drawRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(cellWidth, cellHeight)
            )

            // 绘制边框
            if (config.strokeWidth.value > 0) {
                drawRect(
                    color = Color.White.copy(alpha = 0.3f),
                    topLeft = Offset(x, y),
                    size = Size(cellWidth, cellHeight),
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // 绘制数值文本
            if (config.showLabels) {
                val text = "%.1f".format(value)
                val textLayoutResult = textMeasurer.measure(text, textStyle)
                val textColor = if (animatedValue > 0.5f) Color.White else Color.Black

                drawText(
                    textLayoutResult = textLayoutResult,
                    color = textColor,
                    topLeft = Offset(
                        x + (cellWidth - textLayoutResult.size.width) / 2,
                        y + (cellHeight - textLayoutResult.size.height) / 2
                    )
                )
            }
        }
    }

    // 绘制轴标签
    if (config.showLabels) {
        // X轴标签
        data.xLabels.forEachIndexed { index, label ->
            if (index < data.cols) {
                val x = padding + index * cellWidth + cellWidth / 2
                val y = padding + chartHeight + 20.dp.toPx()

                val textLayoutResult = textMeasurer.measure(label, textStyle)
                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(x - textLayoutResult.size.width / 2, y)
                )
            }
        }

        // Y轴标签
        data.yLabels.forEachIndexed { index, label ->
            if (index < data.rows) {
                val x = padding - 10.dp.toPx()
                val y = padding + index * cellHeight + cellHeight / 2

                val textLayoutResult = textMeasurer.measure(label, textStyle)
                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(x - textLayoutResult.size.width, y - textLayoutResult.size.height / 2)
                )
            }
        }
    }
}

/**
 * 绘制雷达图
 */
private fun DrawScope.drawRadarChart(
    data: RadarData,
    progress: Float,
    config: ChartConfig,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    textStyle: TextStyle,
    size: Size
) {
    val center = Offset(size.width / 2, size.height / 2)
    val radius = minOf(size.width, size.height) / 2 - 60.dp.toPx()
    val axesCount = data.axes.size

    // 绘制网格
    if (config.showGrid) {
        drawRadarGrid(center, radius, axesCount, data.maxValue)
    }

    // 绘制轴线和标签
    for (i in 0 until axesCount) {
        val angle = -PI / 2 + (2 * PI * i / axesCount)
        val endX = center.x + cos(angle).toFloat() * radius
        val endY = center.y + sin(angle).toFloat() * radius

        // 绘制轴线
        drawLine(
            color = Color.Gray.copy(alpha = 0.5f),
            start = center,
            end = Offset(endX, endY),
            strokeWidth = 1.dp.toPx()
        )

        // 绘制标签
        if (config.showLabels && i < data.axes.size) {
            val labelX = center.x + cos(angle).toFloat() * (radius + 20.dp.toPx())
            val labelY = center.y + sin(angle).toFloat() * (radius + 20.dp.toPx())

            val textLayoutResult = textMeasurer.measure(data.axes[i], textStyle)
            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(
                    labelX - textLayoutResult.size.width / 2,
                    labelY - textLayoutResult.size.height / 2
                )
            )
        }
    }

    // 绘制数据系列
    data.series.forEach { series ->
        if (series.values.size >= axesCount) {
            drawRadarSeries(
                series = series,
                center = center,
                radius = radius,
                axesCount = axesCount,
                maxValue = data.maxValue,
                progress = progress
            )
        }
    }
}

/**
 * 绘制气泡图
 */
private fun DrawScope.drawBubbleChart(
    data: BubbleData,
    progress: Float,
    config: ChartConfig,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    textStyle: TextStyle,
    size: Size
) {
    val padding = 60.dp.toPx()
    val chartWidth = size.width - padding * 2
    val chartHeight = size.height - padding * 2

    // 计算数据范围
    val xValues = data.bubbles.map { it.x }
    val yValues = data.bubbles.map { it.y }
    val sizeValues = data.bubbles.map { it.size }

    val xRange = data.xRange ?: (xValues.minOrNull()!! to xValues.maxOrNull()!!)
    val yRange = data.yRange ?: (yValues.minOrNull()!! to yValues.maxOrNull()!!)
    val sizeRange = data.sizeRange ?: (sizeValues.minOrNull()!! to sizeValues.maxOrNull()!!)

    // 绘制网格
    if (config.showGrid) {
        drawBubbleGrid(padding, chartWidth, chartHeight)
    }

    // 绘制气泡
    data.bubbles.forEach { bubble ->
        val x = padding + ((bubble.x - xRange.first) / (xRange.second - xRange.first)) * chartWidth
        val y = padding + chartHeight - ((bubble.y - yRange.first) / (yRange.second - yRange.first)) * chartHeight

        val normalizedSize = (bubble.size - sizeRange.first) / (sizeRange.second - sizeRange.first)
        val bubbleRadius = (10.dp.toPx() + normalizedSize * 30.dp.toPx()) * progress

        // 绘制气泡
        drawCircle(
            color = bubble.color.copy(alpha = 0.7f),
            radius = bubbleRadius,
            center = Offset(x, y)
        )

        // 绘制边框
        drawCircle(
            color = bubble.color,
            radius = bubbleRadius,
            center = Offset(x, y),
            style = Stroke(width = 2.dp.toPx())
        )

        // 绘制标签
        if (config.showLabels && bubble.label.isNotEmpty()) {
            val textLayoutResult = textMeasurer.measure(bubble.label, textStyle)
            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(
                    x - textLayoutResult.size.width / 2,
                    y - textLayoutResult.size.height / 2
                )
            )
        }
    }
}

/**
 * 绘制雷达图网格
 */
private fun DrawScope.drawRadarGrid(
    center: Offset,
    radius: Float,
    axesCount: Int,
    maxValue: Float
) {
    val gridLevels = 5

    // 绘制同心圆网格
    for (level in 1..gridLevels) {
        val levelRadius = radius * level / gridLevels
        drawCircle(
            color = Color.Gray.copy(alpha = 0.3f),
            radius = levelRadius,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

/**
 * 绘制雷达图数据系列
 */
private fun DrawScope.drawRadarSeries(
    series: RadarSeries,
    center: Offset,
    radius: Float,
    axesCount: Int,
    maxValue: Float,
    progress: Float
) {
    val path = Path()
    val points = mutableListOf<Offset>()

    // 计算各点坐标
    for (i in 0 until axesCount) {
        val value = if (i < series.values.size) series.values[i] else 0f
        val normalizedValue = (value / maxValue).coerceIn(0f, 1f) * progress
        val pointRadius = radius * normalizedValue

        val angle = -PI / 2 + (2 * PI * i / axesCount)
        val x = center.x + cos(angle).toFloat() * pointRadius
        val y = center.y + sin(angle).toFloat() * pointRadius

        points.add(Offset(x, y))

        if (i == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }

    path.close()

    // 绘制填充区域
    drawPath(
        path = path,
        color = series.color.copy(alpha = series.fillAlpha)
    )

    // 绘制边框
    drawPath(
        path = path,
        color = series.color,
        style = Stroke(
            width = series.strokeWidth.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )

    // 绘制数据点
    points.forEach { point ->
        drawCircle(
            color = series.color,
            radius = 4.dp.toPx(),
            center = point
        )
        drawCircle(
            color = Color.White,
            radius = 2.dp.toPx(),
            center = point
        )
    }
}

/**
 * 绘制气泡图网格
 */
private fun DrawScope.drawBubbleGrid(
    padding: Float,
    chartWidth: Float,
    chartHeight: Float
) {
    val gridColor = Color.Gray.copy(alpha = 0.3f)
    val gridLines = 5

    // 垂直网格线
    for (i in 0..gridLines) {
        val x = padding + (chartWidth / gridLines) * i
        drawLine(
            color = gridColor,
            start = Offset(x, padding),
            end = Offset(x, padding + chartHeight),
            strokeWidth = 1.dp.toPx()
        )
    }

    // 水平网格线
    for (i in 0..gridLines) {
        val y = padding + (chartHeight / gridLines) * i
        drawLine(
            color = gridColor,
            start = Offset(padding, y),
            end = Offset(padding + chartWidth, y),
            strokeWidth = 1.dp.toPx()
        )
    }
}

/**
 * 颜色插值函数
 */
private fun interpolateColor(colors: List<Color>, value: Float): Color {
    if (colors.isEmpty()) return Color.Gray
    if (colors.size == 1) return colors[0]

    val clampedValue = value.coerceIn(0f, 1f)
    val scaledValue = clampedValue * (colors.size - 1)
    val index = scaledValue.toInt().coerceIn(0, colors.size - 2)
    val fraction = scaledValue - index

    return lerp(colors[index], colors[index + 1], fraction)
}

/**
 * 默认热力图颜色方案
 */
private val defaultHeatmapColors = listOf(
    Color(0xFF313695), // 深蓝
    Color(0xFF4575B4), // 蓝
    Color(0xFF74ADD1), // 浅蓝
    Color(0xFFABD9E9), // 很浅蓝
    Color(0xFFE0F3F8), // 白蓝
    Color(0xFFFEE090), // 白黄
    Color(0xFFFDAE61), // 浅黄
    Color(0xFFF46D43), // 橙
    Color(0xFFD73027), // 红
    Color(0xFFA50026)  // 深红
)