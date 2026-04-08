package ai.openclaw.android.ml

import ai.openclaw.android.domain.memory.EmbeddingService
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import android.content.Context

class TfLiteEmbeddingServiceTest {
    private lateinit var service: TfLiteEmbeddingService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        service = TfLiteEmbeddingService(context)
    }

    @Test
    fun testDimension() = runTest {
        assertEquals(384, service.getDimension())
    }

    @Test
    fun testIsReadyBeforeInit() {
        assertFalse(service.isReady())
    }

    // 注意：完整测试需要模型文件
}