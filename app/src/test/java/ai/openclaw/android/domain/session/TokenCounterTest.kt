package ai.openclaw.android.domain.session

import org.junit.Test
import org.junit.Assert.*

class TokenCounterTest {
    private val counter = TokenCounter()
    
    @Test
    fun estimate_chineseText() {
        val text = "这是一个测试消息"
        val tokens = counter.estimate(text)
        // 中文约 1.5 字/token，7 个字 ≈ 5 tokens
        assertTrue(tokens in 3..7)
    }
    
    @Test
    fun estimate_englishText() {
        val text = "This is a test message"
        val tokens = counter.estimate(text)
        // 英文约 0.25 词/token，4 个词 ≈ 5 tokens
        assertTrue(tokens in 3..7)
    }
    
    @Test
    fun estimate_mixedText() {
        val text = "这是 test 混合消息"
        val tokens = counter.estimate(text)
        assertTrue(tokens > 0)
    }
    
    @Test
    fun estimate_emptyString() {
        val tokens = counter.estimate("")
        assertEquals(0, tokens)
    }
    
    @Test
    fun countExact_withNullTokenizer() {
        val text = "This is a test message"
        val tokens = counter.countExact(text, null)
        // Should fall back to estimate method
        assertEquals(counter.estimate(text), tokens)
    }
}