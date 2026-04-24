package org.a2ui.compose.charts.performance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * 虚拟化渲染器
 */
class VirtualizedRenderer<T>(
    private val itemHeight: Float,
    private val bufferSize: Int = 5
) {
    private var viewportStart = 0
    private var viewportEnd = 0
    private var totalItems = 0

    /**
     * 计算可见项目范围
     */
    fun calculateVisibleRange(
        scrollOffset: Float,
        viewportHeight: Float,
        itemCount: Int
    ): IntRange {
        totalItems = itemCount

        val startIndex = max(0, (scrollOffset / itemHeight).toInt() - bufferSize)
        val endIndex = min(
            itemCount - 1,
            ((scrollOffset + viewportHeight) / itemHeight).toInt() + bufferSize
        )

        viewportStart = startIndex
        viewportEnd = endIndex

        return startIndex..endIndex
    }

    /**
     * 获取项目的Y坐标
     */
    fun getItemY(index: Int): Float {
        return index * itemHeight
    }

    /**
     * 检查项目是否在可见范围内
     */
    fun isItemVisible(index: Int): Boolean {
        return index in viewportStart..viewportEnd
    }

    /**
     * 获取总内容高度
     */
    fun getTotalHeight(): Float {
        return totalItems * itemHeight
    }
}

/**
 * Canvas缓存管理器
 */
class CanvasCacheManager {
    private val cache = ConcurrentHashMap<String, CachedDrawing>()
    private val maxCacheSize = 50
    private var accessCounter = 0L

    data class CachedDrawing(
        val bitmap: ImageBitmap,
        val lastAccessed: Long,
        val accessCount: Long
    )

    /**
     * 获取缓存的绘制结果
     */
    fun getCachedDrawing(key: String): ImageBitmap? {
        val cached = cache[key]
        if (cached != null) {
            // 更新访问信息
            cache[key] = cached.copy(
                lastAccessed = System.currentTimeMillis(),
                accessCount = cached.accessCount + 1
            )
            return cached.bitmap
        }
        return null
    }

    /**
     * 缓存绘制结果
     */
    fun cacheDrawing(key: String, bitmap: ImageBitmap) {
        // 如果缓存已满，清理最少使用的项目
        if (cache.size >= maxCacheSize) {
            cleanupCache()
        }

        cache[key] = CachedDrawing(
            bitmap = bitmap,
            lastAccessed = System.currentTimeMillis(),
            accessCount = ++accessCounter
        )
    }

    /**
     * 清理缓存
     */
    private fun cleanupCache() {
        val sortedEntries = cache.entries.sortedBy { it.value.lastAccessed }
        val toRemove = sortedEntries.take(maxCacheSize / 4) // 移除25%的旧项目

        toRemove.forEach { entry ->
            cache.remove(entry.key)
        }
    }

    /**
     * 清空缓存
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            size = cache.size,
            maxSize = maxCacheSize,
            hitRate = calculateHitRate()
        )
    }

    private fun calculateHitRate(): Float {
        // 简化的命中率计算
        return if (accessCounter > 0) {
            cache.values.sumOf { it.accessCount }.toFloat() / accessCounter
        } else 0f
    }
}

data class CacheStats(
    val size: Int,
    val maxSize: Int,
    val hitRate: Float
)

/**
 * 渲染性能监控器
 */
class RenderPerformanceMonitor {
    private val frameTimes = mutableListOf<Long>()
    private val maxSamples = 60 // 保留最近60帧的数据
    private var lastFrameTime = 0L

    /**
     * 记录帧开始时间
     */
    fun startFrame() {
        lastFrameTime = System.nanoTime()
    }

    /**
     * 记录帧结束时间
     */
    fun endFrame() {
        val frameTime = System.nanoTime() - lastFrameTime
        frameTimes.add(frameTime)

        // 保持样本数量在限制内
        if (frameTimes.size > maxSamples) {
            frameTimes.removeAt(0)
        }
    }

    /**
     * 获取平均帧时间（毫秒）
     */
    fun getAverageFrameTime(): Float {
        return if (frameTimes.isNotEmpty()) {
            frameTimes.average().toFloat() / 1_000_000f
        } else 0f
    }

    /**
     * 获取FPS
     */
    fun getFPS(): Float {
        val avgFrameTime = getAverageFrameTime()
        return if (avgFrameTime > 0) 1000f / avgFrameTime else 0f
    }

    /**
     * 获取帧时间方差（用于检测卡顿）
     */
    fun getFrameTimeVariance(): Float {
        if (frameTimes.size < 2) return 0f

        val mean = frameTimes.average()
        val variance = frameTimes.map { (it - mean).pow(2) }.average()
        return (variance / 1_000_000f).toFloat() // 转换为毫秒
    }

    /**
     * 重置统计数据
     */
    fun reset() {
        frameTimes.clear()
        lastFrameTime = 0L
    }
}

/**
 * 分层渲染管理器
 */
class LayeredRenderManager {
    private val layers = mutableMapOf<String, RenderLayer>()
    private val layerOrder = mutableListOf<String>()

    data class RenderLayer(
        val name: String,
        val zIndex: Int,
        val isDirty: Boolean = true,
        val cachedBitmap: ImageBitmap? = null,
        val renderFunction: DrawScope.() -> Unit
    )

    /**
     * 添加渲染层
     */
    fun addLayer(
        name: String,
        zIndex: Int,
        renderFunction: DrawScope.() -> Unit
    ) {
        layers[name] = RenderLayer(name, zIndex, true, null, renderFunction)
        updateLayerOrder()
    }

    /**
     * 标记层为脏（需要重新渲染）
     */
    fun markLayerDirty(name: String) {
        layers[name]?.let { layer ->
            layers[name] = layer.copy(isDirty = true)
        }
    }

    /**
     * 渲染所有层
     */
    fun renderLayers(drawScope: DrawScope, size: Size) {
        layerOrder.forEach { layerName ->
            val layer = layers[layerName] ?: return@forEach

            if (layer.isDirty || layer.cachedBitmap == null) {
                // 重新渲染层
                val bitmap = createLayerBitmap(layer, size)
                layers[layerName] = layer.copy(
                    isDirty = false,
                    cachedBitmap = bitmap
                )
            }

            // 绘制缓存的层
            layer.cachedBitmap?.let { bitmap ->
                drawScope.drawImage(bitmap, Offset.Zero)
            }
        }
    }

    private fun createLayerBitmap(layer: RenderLayer, size: Size): ImageBitmap {
        // 创建离屏Canvas进行渲染
        val bitmap = ImageBitmap(size.width.toInt(), size.height.toInt())
        val canvas = Canvas(bitmap)
        val drawScope = CanvasDrawScope()

        // 在离屏Canvas上执行渲染函数
        drawScope.draw(
            density = Density(1f),
            layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
            canvas = canvas,
            size = size
        ) {
            layer.renderFunction(this)
        }

        return bitmap
    }

    private fun updateLayerOrder() {
        layerOrder.clear()
        layerOrder.addAll(
            layers.values
                .sortedBy { it.zIndex }
                .map { it.name }
        )
    }

    /**
     * 移除层
     */
    fun removeLayer(name: String) {
        layers.remove(name)
        layerOrder.remove(name)
    }

    /**
     * 清空所有层
     */
    fun clearLayers() {
        layers.clear()
        layerOrder.clear()
    }
}

/**
 * 数据采样器（用于大数据集的性能优化）
 */
class DataSampler<T> {
    /**
     * 基于像素的采样
     */
    fun sampleByPixels(
        data: List<T>,
        availablePixels: Int,
        valueExtractor: (T) -> Float
    ): List<T> {
        if (data.size <= availablePixels) return data

        val samplingRatio = data.size.toFloat() / availablePixels
        val sampledData = mutableListOf<T>()

        for (i in 0 until availablePixels) {
            val dataIndex = (i * samplingRatio).toInt().coerceIn(0, data.size - 1)
            sampledData.add(data[dataIndex])
        }

        return sampledData
    }

    /**
     * 最大-最小采样（保留重要的峰值和谷值）
     */
    fun sampleMinMax(
        data: List<T>,
        targetSize: Int,
        valueExtractor: (T) -> Float
    ): List<T> {
        if (data.size <= targetSize) return data

        val sampledData = mutableListOf<T>()
        val bucketSize = data.size / targetSize

        for (i in 0 until targetSize) {
            val bucketStart = i * bucketSize
            val bucketEnd = minOf((i + 1) * bucketSize, data.size)
            val bucket = data.subList(bucketStart, bucketEnd)

            if (bucket.isNotEmpty()) {
                // 找到桶中的最大值和最小值
                val minItem = bucket.minByOrNull { valueExtractor(it) }
                val maxItem = bucket.maxByOrNull { valueExtractor(it) }

                minItem?.let { sampledData.add(it) }
                if (maxItem != minItem) {
                    maxItem?.let { sampledData.add(it) }
                }
            }
        }

        return sampledData
    }

    /**
     * 自适应采样（根据数据变化率调整采样密度）
     */
    fun sampleAdaptive(
        data: List<T>,
        targetSize: Int,
        valueExtractor: (T) -> Float,
        changeThreshold: Float = 0.1f
    ): List<T> {
        if (data.size <= targetSize) return data

        val sampledData = mutableListOf<T>()
        sampledData.add(data.first()) // 总是包含第一个点

        var lastValue = valueExtractor(data.first())
        var lastIndex = 0

        for (i in 1 until data.size - 1) {
            val currentValue = valueExtractor(data[i])
            val change = abs(currentValue - lastValue) / abs(lastValue)

            // 如果变化超过阈值，或者距离上次采样点太远，则采样
            if (change > changeThreshold || (i - lastIndex) > (data.size / targetSize)) {
                sampledData.add(data[i])
                lastValue = currentValue
                lastIndex = i
            }
        }

        sampledData.add(data.last()) // 总是包含最后一个点
        return sampledData
    }
}

/**
 * 渲染优化配置
 */
data class RenderOptimizationConfig(
    val enableVirtualization: Boolean = true,
    val enableCaching: Boolean = true,
    val enableLayering: Boolean = true,
    val maxDataPoints: Int = 1000,
    val samplingStrategy: SamplingStrategy = SamplingStrategy.ADAPTIVE,
    val cacheInvalidationThreshold: Float = 0.1f
)

enum class SamplingStrategy {
    NONE,
    PIXEL_BASED,
    MIN_MAX,
    ADAPTIVE
}

/**
 * 高性能图表组件基类
 */
@Composable
fun OptimizedChart(
    modifier: Modifier = Modifier,
    config: RenderOptimizationConfig = RenderOptimizationConfig(),
    onRender: DrawScope.(Size) -> Unit
) {
    val performanceMonitor = remember { RenderPerformanceMonitor() }
    val cacheManager = remember { CanvasCacheManager() }
    val layerManager = remember { LayeredRenderManager() }

    Canvas(modifier = modifier.fillMaxSize()) {
        performanceMonitor.startFrame()

        try {
            if (config.enableLayering) {
                layerManager.renderLayers(this, size)
            } else {
                onRender(size)
            }
        } finally {
            performanceMonitor.endFrame()
        }
    }

    // 性能监控效果
    LaunchedEffect(performanceMonitor) {
        while (true) {
            delay(1000) // 每秒检查一次性能
            val fps = performanceMonitor.getFPS()
            val variance = performanceMonitor.getFrameTimeVariance()

            // 如果性能不佳，可以触发优化策略
            if (fps < 30f || variance > 16f) {
                // 触发性能优化
                println("Performance warning: FPS=$fps, Variance=$variance")
            }
        }
    }
}

/**
 * 内存使用监控器
 */
object MemoryMonitor {
    private var lastGCTime = System.currentTimeMillis()
    private var gcCount = 0

    fun checkMemoryPressure(): MemoryPressure {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory

        val memoryUsageRatio = usedMemory.toFloat() / maxMemory

        return when {
            memoryUsageRatio > 0.9f -> MemoryPressure.CRITICAL
            memoryUsageRatio > 0.7f -> MemoryPressure.HIGH
            memoryUsageRatio > 0.5f -> MemoryPressure.MEDIUM
            else -> MemoryPressure.LOW
        }
    }

    fun suggestGC() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastGCTime > 5000) { // 至少5秒间隔
            System.gc()
            lastGCTime = currentTime
            gcCount++
        }
    }
}

enum class MemoryPressure {
    LOW, MEDIUM, HIGH, CRITICAL
}