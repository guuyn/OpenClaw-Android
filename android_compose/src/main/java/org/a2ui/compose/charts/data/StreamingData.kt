package org.a2ui.compose.charts.data

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import kotlin.random.Random

/**
 * 流式数据源接口
 */
interface StreamingDataSource<T> {
    val dataFlow: Flow<T>
    fun start()
    fun stop()
    fun isActive(): Boolean
}

/**
 * 实时数据点
 */
data class RealTimeDataPoint(
    val timestamp: Long = System.currentTimeMillis(),
    val value: Float,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * 实时数据系列
 */
data class RealTimeDataSeries(
    val name: String,
    val points: List<RealTimeDataPoint>,
    val maxPoints: Int = 100,
    val color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Blue
) {
    fun addPoint(point: RealTimeDataPoint): RealTimeDataSeries {
        val newPoints = (points + point).takeLast(maxPoints)
        return copy(points = newPoints)
    }

    fun getLatestValue(): Float? = points.lastOrNull()?.value
    fun getValueRange(): Pair<Float, Float>? {
        if (points.isEmpty()) return null
        val values = points.map { it.value }
        return values.minOrNull()!! to values.maxOrNull()!!
    }
}

/**
 * 流式图表数据状态
 */
@Stable
class StreamingChartState<T>(
    private val dataSource: StreamingDataSource<T>,
    private val dataTransformer: (T) -> RealTimeDataPoint
) {
    private var _series by mutableStateOf(RealTimeDataSeries("Default", emptyList()))
    val series: RealTimeDataSeries get() = _series

    private var _isStreaming by mutableStateOf(false)
    val isStreaming: Boolean get() = _isStreaming

    private var streamingJob: Job? = null

    /**
     * 开始流式数据更新
     */
    fun startStreaming(scope: CoroutineScope) {
        if (_isStreaming) return

        _isStreaming = true
        dataSource.start()

        streamingJob = scope.launch {
            dataSource.dataFlow
                .catch { e ->
                    // 处理数据流错误
                    println("Streaming data error: ${e.message}")
                }
                .collect { rawData ->
                    val dataPoint = dataTransformer(rawData)
                    _series = _series.addPoint(dataPoint)
                }
        }
    }

    /**
     * 停止流式数据更新
     */
    fun stopStreaming() {
        if (!_isStreaming) return

        _isStreaming = false
        streamingJob?.cancel()
        streamingJob = null
        dataSource.stop()
    }

    /**
     * 清空数据
     */
    fun clearData() {
        _series = _series.copy(points = emptyList())
    }

    /**
     * 更新系列配置
     */
    fun updateSeries(
        name: String? = null,
        maxPoints: Int? = null,
        color: androidx.compose.ui.graphics.Color? = null
    ) {
        _series = _series.copy(
            name = name ?: _series.name,
            maxPoints = maxPoints ?: _series.maxPoints,
            color = color ?: _series.color
        )
    }
}

/**
 * 模拟数据源实现
 */
class SimulatedDataSource(
    private val updateInterval: Long = 1000L,
    private val valueRange: Pair<Float, Float> = 0f to 100f
) : StreamingDataSource<Float> {

    private var _isActive = false
    private var baseValue = (valueRange.first + valueRange.second) / 2

    override val dataFlow: Flow<Float> = flow {
        while (_isActive) {
            // 生成模拟数据（随机游走）
            val change = Random.nextFloat() * 10f - 5f // (-5f..5f).random()
            baseValue = (baseValue + change).coerceIn(valueRange.first, valueRange.second)
            emit(baseValue)
            delay(updateInterval)
        }
    }

    override fun start() {
        _isActive = true
    }

    override fun stop() {
        _isActive = false
    }

    override fun isActive(): Boolean = _isActive
}

/**
 * WebSocket数据源实现（模拟）
 */
class WebSocketDataSource(
    private val url: String,
    private val reconnectInterval: Long = 5000L
) : StreamingDataSource<String> {

    private var _isActive = false
    private var reconnectJob: Job? = null

    override val dataFlow: Flow<String> = flow {
        while (_isActive) {
            try {
                // 模拟WebSocket连接和数据接收
                // 实际实现中这里会是真正的WebSocket客户端
                emit(generateMockWebSocketData())
                delay(1000L)
            } catch (e: Exception) {
                // 连接失败，等待重连
                delay(reconnectInterval)
            }
        }
    }

    override fun start() {
        _isActive = true
    }

    override fun stop() {
        _isActive = false
        reconnectJob?.cancel()
    }

    override fun isActive(): Boolean = _isActive

    private fun generateMockWebSocketData(): String {
        // 模拟股票价格数据
        val price = Random.nextFloat() * 100f + 100f // (100f..200f).random()
        val change = Random.nextFloat() * 10f - 5f // (-5f..5f).random()
        val volume = Random.nextInt(1000, 10001) // (1000..10000).random()

        return """
            {
                "symbol": "AAPL",
                "price": $price,
                "change": $change,
                "volume": $volume,
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()
    }
}

/**
 * 批量数据更新器
 */
class BatchDataUpdater<T>(
    private val batchSize: Int = 10,
    private val flushInterval: Long = 100L
) {
    private val buffer = mutableListOf<T>()
    private var lastFlushTime = System.currentTimeMillis()

    fun addData(data: T): List<T>? {
        buffer.add(data)

        val currentTime = System.currentTimeMillis()
        val shouldFlush = buffer.size >= batchSize ||
                         (currentTime - lastFlushTime) >= flushInterval

        return if (shouldFlush) {
            val batch = buffer.toList()
            buffer.clear()
            lastFlushTime = currentTime
            batch
        } else {
            null
        }
    }

    fun flush(): List<T> {
        val batch = buffer.toList()
        buffer.clear()
        lastFlushTime = System.currentTimeMillis()
        return batch
    }
}

/**
 * 数据缓冲区管理器
 */
class DataBufferManager<T>(
    private val maxBufferSize: Int = 1000,
    private val compressionThreshold: Int = 500
) {
    private val buffer = mutableListOf<T>()

    fun addData(data: List<T>) {
        buffer.addAll(data)

        // 如果缓冲区过大，进行数据压缩
        if (buffer.size > maxBufferSize) {
            compressBuffer()
        }
    }

    fun getData(count: Int): List<T> {
        return buffer.takeLast(count)
    }

    fun getAllData(): List<T> = buffer.toList()

    fun clear() {
        buffer.clear()
    }

    private fun compressBuffer() {
        // 简单的数据压缩：保留最新的数据，对旧数据进行采样
        if (buffer.size <= compressionThreshold) return

        val recentData = buffer.takeLast(compressionThreshold)
        val oldData = buffer.dropLast(compressionThreshold)

        // 对旧数据进行2:1采样
        val sampledOldData = oldData.filterIndexed { index, _ -> index % 2 == 0 }

        buffer.clear()
        buffer.addAll(sampledOldData)
        buffer.addAll(recentData)
    }
}

/**
 * 创建流式图表状态
 */
@Composable
fun <T> rememberStreamingChartState(
    dataSource: StreamingDataSource<T>,
    dataTransformer: (T) -> RealTimeDataPoint
): StreamingChartState<T> {
    return remember(dataSource) {
        StreamingChartState(dataSource, dataTransformer)
    }
}

/**
 * 流式数据效果
 */
@Composable
fun <T> StreamingDataEffect(
    state: StreamingChartState<T>,
    autoStart: Boolean = true
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(state, autoStart) {
        if (autoStart) {
            state.startStreaming(scope)
        }
    }

    DisposableEffect(state) {
        onDispose {
            state.stopStreaming()
        }
    }
}

/**
 * 数据质量监控
 */
class DataQualityMonitor {
    private var totalPoints = 0
    private var droppedPoints = 0
    private var lastUpdateTime = 0L

    fun recordDataPoint(timestamp: Long) {
        totalPoints++
        lastUpdateTime = timestamp
    }

    fun recordDroppedPoint() {
        droppedPoints++
    }

    fun getDropRate(): Float {
        return if (totalPoints > 0) droppedPoints.toFloat() / totalPoints else 0f
    }

    fun getUpdateFrequency(): Float {
        val currentTime = System.currentTimeMillis()
        return if (lastUpdateTime > 0) {
            1000f / (currentTime - lastUpdateTime)
        } else 0f
    }

    fun reset() {
        totalPoints = 0
        droppedPoints = 0
        lastUpdateTime = 0L
    }
}