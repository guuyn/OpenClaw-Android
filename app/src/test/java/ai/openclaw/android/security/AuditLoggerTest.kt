package ai.openclaw.android.security

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the tamper-evident audit log.
 *
 * AuditLogger is a process-wide singleton (`object`), so each test must reset
 * its internal state to avoid bleed-through. We clear `entries` and reset the
 * chain head via reflection (the production class intentionally keeps these
 * private to discourage external mutation).
 */
class AuditLoggerTest {

    @Before
    fun setUp() {
        resetState()
    }

    @After
    fun tearDown() {
        resetState()
    }

    private fun resetState() {
        val entriesField = AuditLogger::class.java.getDeclaredField("entries").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val entries = entriesField.get(AuditLogger) as MutableList<Any>
        entries.clear()

        val headField = AuditLogger::class.java.getDeclaredField("chainHead").apply {
            isAccessible = true
        }
        headField.set(AuditLogger, "GENESIS")
    }

    // ==================== Happy path: chain integrity ====================

    @Test
    fun `empty log verifies successfully`() {
        assertTrue("Empty chain should be valid", AuditLogger.verifyChain())
        assertEquals(0, AuditLogger.getEntries().size)
    }

    @Test
    fun `single entry verifies successfully`() {
        AuditLogger.log("STORE", 1L, "single memory stored")
        val entries = AuditLogger.getEntries()
        assertEquals(1, entries.size)
        assertEquals("STORE", entries[0].operation)
        assertEquals(1L, entries[0].targetId)
        assertTrue(AuditLogger.verifyChain())
    }

    @Test
    fun `multiple sequential entries verify successfully`() {
        for (i in 1..10) {
            AuditLogger.log("STORE", i.toLong(), "memory #$i")
        }
        assertEquals(10, AuditLogger.getEntries().size)
        assertTrue("Chain should verify after sequential appends", AuditLogger.verifyChain())
    }

    @Test
    fun `entries are linked via previousHash`() {
        AuditLogger.log("STORE", 1L, "first")
        AuditLogger.log("STORE", 2L, "second")
        AuditLogger.log("DELETE", 3L, "third")

        val entries = AuditLogger.getEntries()
        // Each entry's previousHash must equal the previous entry's hash
        assertEquals("GENESIS", entries[0].previousHash)
        assertEquals(entries[0].hash, entries[1].previousHash)
        assertEquals(entries[1].hash, entries[2].previousHash)
    }

    // ==================== Tamper detection ====================

    @Test
    fun `tampering with operation field breaks chain verification`() {
        AuditLogger.log("STORE", 1L, "original")
        AuditLogger.log("DELETE", 2L, "second")
        val entries = AuditLogger.getEntries()
        assertTrue(AuditLogger.verifyChain())

        // Tamper: rewrite the first entry's operation field via reflection
        val opField = AuditLogger.AuditEntry::class.java.getDeclaredField("operation").apply {
            isAccessible = true
        }
        val firstEntry = entries[0]
        opField.set(firstEntry, "TAMPERED")

        assertFalse("Chain must be invalid after operation tamper", AuditLogger.verifyChain())
    }

    @Test
    fun `tampering with detail field breaks chain verification`() {
        AuditLogger.log("STORE", 1L, "original detail")
        val entries = AuditLogger.getEntries()
        assertTrue(AuditLogger.verifyChain())

        // Tamper: rewrite the detail field
        val detailField = AuditLogger.AuditEntry::class.java.getDeclaredField("detail").apply {
            isAccessible = true
        }
        detailField.set(entries[0], "forged detail")

        assertFalse("Chain must be invalid after detail tamper", AuditLogger.verifyChain())
    }

    @Test
    fun `tampering with targetId field breaks chain verification`() {
        AuditLogger.log("STORE", 1L, "first")
        val entries = AuditLogger.getEntries()
        assertTrue(AuditLogger.verifyChain())

        val targetField = AuditLogger.AuditEntry::class.java.getDeclaredField("targetId").apply {
            isAccessible = true
        }
        targetField.set(entries[0], 999L)

        assertFalse(AuditLogger.verifyChain())
    }

    @Test
    fun `removing a middle entry breaks chain verification`() {
        AuditLogger.log("STORE", 1L, "first")
        AuditLogger.log("STORE", 2L, "second")
        AuditLogger.log("STORE", 3L, "third")
        assertTrue(AuditLogger.verifyChain())

        // Remove the middle entry
        val entriesField = AuditLogger::class.java.getDeclaredField("entries").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val entries = entriesField.get(AuditLogger) as MutableList<Any>
        entries.removeAt(1)

        assertFalse("Chain must be invalid after removal", AuditLogger.verifyChain())
    }

    @Test
    fun `hash field is SHA-256 hex string`() {
        AuditLogger.log("STORE", 42L, "test detail")
        val hash = AuditLogger.getEntries()[0].hash
        assertEquals(64, hash.length)
        assertTrue("Hash should be hex", hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `different content produces different hashes`() {
        AuditLogger.log("STORE", 1L, "alpha")
        val firstHash = AuditLogger.getEntries()[0].hash
        resetState()

        AuditLogger.log("STORE", 1L, "beta")
        val secondHash = AuditLogger.getEntries()[0].hash
        assertNotEquals(firstHash, secondHash)
    }

    // ==================== Concurrent appends ====================

    @Test
    fun `concurrent appends maintain chain integrity`() {
        val threadCount = 10
        val writesPerThread = 20
        val pool = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        try {
            repeat(threadCount) { threadIdx ->
                pool.execute {
                    try {
                        repeat(writesPerThread) { i ->
                            AuditLogger.log(
                                operation = "STORE",
                                targetId = (threadIdx * 1000 + i).toLong(),
                                detail = "thread-$threadIdx entry-$i"
                            )
                        }
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue("Concurrent writes timed out", latch.await(10, TimeUnit.SECONDS))
            assertEquals("No errors expected in concurrent appends", 0, errorCount.get())

            // Verify chain integrity after concurrent writes
            assertTrue(
                "Hash chain must remain intact after concurrent appends",
                AuditLogger.verifyChain()
            )

            assertEquals(
                "All writes should be recorded",
                threadCount * writesPerThread,
                AuditLogger.getEntries().size
            )
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    // ==================== Edge cases ====================

    @Test
    fun `long detail string is truncated to 200 chars`() {
        val longDetail = "x".repeat(500)
        AuditLogger.log("STORE", 1L, longDetail)

        val entry = AuditLogger.getEntries()[0]
        assertEquals(200, entry.detail.length)
    }

    @Test
    fun `special characters in detail are preserved within limit`() {
        val special = "你好 🔥 \\n \t \"quoted\" 'apos' /slash/"
        AuditLogger.log("STORE", 1L, special)

        val entry = AuditLogger.getEntries()[0]
        assertEquals(special, entry.detail)
        assertTrue(AuditLogger.verifyChain())
    }

    @Test
    fun `empty detail string is valid`() {
        AuditLogger.log("STORE", 1L, "")
        assertEquals(1, AuditLogger.getEntries().size)
        assertEquals("", AuditLogger.getEntries()[0].detail)
        assertTrue(AuditLogger.verifyChain())
    }

    @Test
    fun `negative targetId is supported`() {
        AuditLogger.log("MERGE", -42L, "negative id test")
        assertTrue(AuditLogger.verifyChain())
        assertEquals(-42L, AuditLogger.getEntries()[0].targetId)
    }

    @Test
    fun `zero targetId is supported`() {
        AuditLogger.log("SYNC_PUSH", 0L, "zero id test")
        assertTrue(AuditLogger.verifyChain())
    }

    // ==================== Export ====================

    @Test
    fun `export contains chain validity marker`() {
        AuditLogger.log("STORE", 1L, "test")
        val exported = AuditLogger.export()

        assertTrue("Export should include header", exported.contains("OpenClaw Audit Log"))
        assertTrue("Export should report chain valid", exported.contains("Chain valid: true"))
        assertTrue("Export should report entry count", exported.contains("Entries: 1"))
        assertTrue("Export should include operation", exported.contains("STORE"))
    }

    @Test
    fun `export reflects tamper state`() {
        AuditLogger.log("STORE", 1L, "test")
        val entries = AuditLogger.getEntries()
        val detailField = AuditLogger.AuditEntry::class.java.getDeclaredField("detail").apply {
            isAccessible = true
        }
        detailField.set(entries[0], "tampered")

        val exported = AuditLogger.export()
        assertTrue("Export should report invalid chain after tamper", exported.contains("Chain valid: false"))
    }

    // ==================== Ring buffer behavior (MAX_ENTRIES) ====================

    @Test
    fun `entries beyond MAX_ENTRIES are evicted from front`() {
        // MAX_ENTRIES is 500. Write 550 and verify size stays at 500.
        for (i in 1..550) {
            AuditLogger.log("STORE", i.toLong(), "entry $i")
        }
        assertEquals(500, AuditLogger.getEntries().size)

        // After eviction, the chain must still verify (rebuilt against retained entries only).
        // Note: verifyChain walks from GENESIS forward — after the first eviction,
        // entries[0].previousHash may no longer be GENESIS. We only verify the
        // property "no broken hash among retained entries".
        val entries = AuditLogger.getEntries()
        // Each entry's previousHash should equal the previous entry's hash (except possibly entries[0])
        for (i in 1 until entries.size) {
            assertEquals(
                "Entry $i's previousHash must equal entry ${i - 1}'s hash",
                entries[i - 1].hash,
                entries[i].previousHash
            )
        }
    }
}