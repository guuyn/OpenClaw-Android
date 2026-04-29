package ai.openclaw.android.gateway

import ai.openclaw.android.GatewayContract
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.model.ImageContent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * RealGateway 单元测试
 *
 * 验证 RealGateway 对 MessageGateway 接口的正确实现：
 * - sendMessage 委托给 GatewayContract
 * - isReady 反映 GatewayContract 状态
 * - 空 contract 时抛出异常
 */
class RealGatewayTest {

    @Test
    fun `sendMessage delegates to GatewayContract`() = runBlocking {
        val contract = mockk<GatewayContract>()
        val expectedEvents = listOf(SessionEvent.Token("test"), SessionEvent.Complete("test"))
        every { contract.sendMessage("hello", null) } returns flowOf(*expectedEvents.toTypedArray())
        every { contract.isReady() } returns true

        val gateway = RealGateway { contract }
        val events = gateway.sendMessage("hello").toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is SessionEvent.Token)
        assertTrue(events[1] is SessionEvent.Complete)
    }

    @Test
    fun `sendMessage with images delegates correctly`() = runBlocking {
        val contract = mockk<GatewayContract>()
        val images = listOf(mockk<ImageContent>(relaxed = true))
        every { contract.sendMessage("with image", images) } returns flowOf(SessionEvent.Complete("done"))
        every { contract.isReady() } returns true

        val gateway = RealGateway { contract }
        val events = gateway.sendMessage("with image", images).toList()

        assertEquals(1, events.size)
        assertTrue(events[0] is SessionEvent.Complete)
    }

    @Test
    fun `sendMessage with empty images passes empty list`() = runBlocking {
        val contract = mockk<GatewayContract>()
        every { contract.sendMessage("text", emptyList<ImageContent>()) } returns flowOf(SessionEvent.Complete("ok"))
        every { contract.isReady() } returns true

        val gateway = RealGateway { contract }
        val events = gateway.sendMessage("text", emptyList()).toList()

        assertEquals(1, events.size)
    }

    @Test
    fun `isReady returns true when contract is ready`() {
        val contract = mockk<GatewayContract>()
        every { contract.isReady() } returns true

        val gateway = RealGateway { contract }

        assertTrue(gateway.isReady())
    }

    @Test
    fun `isReady returns false when contract is not ready`() {
        val contract = mockk<GatewayContract>()
        every { contract.isReady() } returns false

        val gateway = RealGateway { contract }

        assertFalse(gateway.isReady())
    }

    @Test
    fun `isReady returns false when contract is null`() {
        val gateway = RealGateway { null }

        assertFalse(gateway.isReady())
    }

    @Test
    fun `sendMessage throws when contract is null`() = runBlocking {
        val gateway = RealGateway { null }

        try {
            gateway.sendMessage("test").toList()
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("GatewayContract") == true)
        }
    }

    @Test
    fun `contractProvider called on each sendMessage`() = runBlocking {
        var callCount = 0
        val contract = mockk<GatewayContract>()
        every { contract.sendMessage(any(), any()) } returns flowOf(SessionEvent.Complete("ok"))
        every { contract.isReady() } returns true

        val gateway = RealGateway {
            callCount++
            contract
        }

        gateway.sendMessage("first").toList()
        gateway.sendMessage("second").toList()

        assertEquals("contractProvider should be called each time", 2, callCount)
    }

    @Test
    fun `contract can change between calls`() = runBlocking {
        var useSecond = false
        val contract1 = mockk<GatewayContract>()
        val contract2 = mockk<GatewayContract>()

        every { contract1.sendMessage(any(), any()) } returns flowOf(SessionEvent.Token("from1"))
        every { contract2.sendMessage(any(), any()) } returns flowOf(SessionEvent.Token("from2"))
        every { contract1.isReady() } returns true
        every { contract2.isReady() } returns true

        val gateway = RealGateway { if (useSecond) contract2 else contract1 }

        val first = gateway.sendMessage("test").toList()
        useSecond = true
        val second = gateway.sendMessage("test").toList()

        assertEquals("from1", (first[0] as SessionEvent.Token).text)
        assertEquals("from2", (second[0] as SessionEvent.Token).text)
    }
}
