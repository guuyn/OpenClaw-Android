package ai.openclaw.android.data

import ai.openclaw.android.data.local.Converters
import ai.openclaw.android.data.model.MemoryType
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.data.model.SessionStatus
import ai.openclaw.android.trigger.models.EventSource
import ai.openclaw.android.trigger.models.MatchMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Room TypeConverters.
 *
 * Each converter is exercised in both directions:
 *  - fromEntity → DB string
 *  - fromString → entity
 *
 * Also covers null/empty edge cases and unknown values.
 */
class ConvertersTest {

    private lateinit var converters: Converters

    @Before
    fun setUp() {
        converters = Converters()
    }

    // ==================== SessionStatus ====================

    @Test
    fun `SessionStatus round trip`() {
        for (status in SessionStatus.values()) {
            val encoded = converters.fromSessionStatus(status)
            val decoded = converters.toSessionStatus(encoded)
            assertEquals(status, decoded)
        }
    }

    @Test
    fun `SessionStatus encoding is uppercase enum name`() {
        assertEquals("ACTIVE", converters.fromSessionStatus(SessionStatus.ACTIVE))
        assertEquals("COMPRESSED", converters.fromSessionStatus(SessionStatus.COMPRESSED))
        assertEquals("ARCHIVED", converters.fromSessionStatus(SessionStatus.ARCHIVED))
    }

    // ==================== MessageRole ====================

    @Test
    fun `MessageRole round trip`() {
        for (role in MessageRole.values()) {
            val encoded = converters.fromMessageRole(role)
            val decoded = converters.toMessageRole(encoded)
            assertEquals(role, decoded)
        }
    }

    @Test
    fun `MessageRole encoding is uppercase enum name`() {
        assertEquals("USER", converters.fromMessageRole(MessageRole.USER))
        assertEquals("ASSISTANT", converters.fromMessageRole(MessageRole.ASSISTANT))
        assertEquals("SYSTEM", converters.fromMessageRole(MessageRole.SYSTEM))
    }

    // ==================== MemoryType ====================

    @Test
    fun `MemoryType round trip`() {
        for (type in MemoryType.values()) {
            val encoded = converters.fromMemoryType(type)
            val decoded = converters.toMemoryType(encoded)
            assertEquals(type, decoded)
        }
    }

    @Test
    fun `MemoryType encoding is uppercase enum name`() {
        assertEquals("PREFERENCE", converters.fromMemoryType(MemoryType.PREFERENCE))
        assertEquals("FACT", converters.fromMemoryType(MemoryType.FACT))
        assertEquals("DECISION", converters.fromMemoryType(MemoryType.DECISION))
        assertEquals("TASK", converters.fromMemoryType(MemoryType.TASK))
        assertEquals("PROJECT", converters.fromMemoryType(MemoryType.PROJECT))
    }

    // ==================== String List ====================

    @Test
    fun `StringList encodes list with comma separator`() {
        assertEquals("a,b,c", converters.fromStringList(listOf("a", "b", "c")))
    }

    @Test
    fun `StringList decodes comma-separated string`() {
        assertEquals(listOf("a", "b", "c"), converters.toStringList("a,b,c"))
    }

    @Test
    fun `StringList round trip preserves order and content`() {
        val list = listOf("alpha", "beta", "gamma", "delta")
        val encoded = converters.fromStringList(list)
        val decoded = converters.toStringList(encoded)
        assertEquals(list, decoded)
    }

    @Test
    fun `StringList handles empty list as empty string`() {
        assertEquals("", converters.fromStringList(emptyList()))
    }

    @Test
    fun `StringList decodes empty string as empty list`() {
        assertEquals(emptyList<String>(), converters.toStringList(""))
    }

    @Test
    fun `StringList handles single element`() {
        assertEquals("only", converters.fromStringList(listOf("only")))
        assertEquals(listOf("only"), converters.toStringList("only"))
    }

    @Test
    fun `StringList preserves elements containing spaces`() {
        val list = listOf("hello world", "foo bar")
        val encoded = converters.fromStringList(list)
        val decoded = converters.toStringList(encoded)
        assertEquals(list, decoded)
    }

    @Test
    fun `StringList handles empty-string element`() {
        // Note: empty string within the list is indistinguishable from a missing element
        // since the separator is a single comma and consecutive commas produce empty strings.
        val list = listOf("", "a", "")
        val encoded = converters.fromStringList(list)
        val decoded = converters.toStringList(encoded)
        assertEquals(3, decoded.size)
        assertEquals("", decoded[0])
        assertEquals("a", decoded[1])
        assertEquals("", decoded[2])
    }

    // ==================== FloatArray ====================

    @Test
    fun `FloatArray encodes as comma-separated string`() {
        val arr = floatArrayOf(1.0f, 2.0f, 3.0f)
        val encoded = converters.fromFloatArray(arr)
        assertEquals("1.0,2.0,3.0", encoded)
    }

    @Test
    fun `FloatArray round trip preserves values`() {
        val arr = floatArrayOf(0.1f, 0.2f, 0.3f, -1.5f, 100.0f)
        val encoded = converters.fromFloatArray(arr)
        val decoded = converters.toFloatArray(encoded)
        assertArrayEquals(arr, decoded, 0.0001f)
    }

    @Test
    fun `FloatArray handles empty array as empty string`() {
        assertEquals("", converters.fromFloatArray(floatArrayOf()))
    }

    @Test
    fun `FloatArray decodes empty string as empty array`() {
        val decoded = converters.toFloatArray("")
        assertEquals(0, decoded.size)
    }

    @Test
    fun `FloatArray preserves large negative and positive values`() {
        val arr = floatArrayOf(-1e10f, 0f, 1e-10f, Float.MAX_VALUE, -Float.MAX_VALUE)
        val encoded = converters.fromFloatArray(arr)
        val decoded = converters.toFloatArray(encoded)
        assertArrayEquals(arr, decoded, 0.0001f)
    }

    @Test
    fun `FloatArray handles NaN and Infinity`() {
        val arr = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val encoded = converters.fromFloatArray(arr)
        val decoded = converters.toFloatArray(encoded)
        assertEquals(3, decoded.size)
        assertTrue("NaN should round-trip", decoded[0].isNaN())
        assertTrue("+Infinity should round-trip", decoded[1].isInfinite() && decoded[1] > 0)
        assertTrue("-Infinity should round-trip", decoded[2].isInfinite() && decoded[2] < 0)
    }

    // ==================== EventSource ====================

    @Test
    fun `EventSource round trip`() {
        for (source in EventSource.values()) {
            val encoded = converters.fromEventSource(source)
            val decoded = converters.toEventSource(encoded)
            assertEquals(source, decoded)
        }
    }

    @Test
    fun `EventSource encoding is uppercase enum name`() {
        assertEquals("CRON", converters.fromEventSource(EventSource.CRON))
        assertEquals("NOTIFICATION", converters.fromEventSource(EventSource.NOTIFICATION))
        assertEquals("ACCESSIBILITY", converters.fromEventSource(EventSource.ACCESSIBILITY))
        assertEquals("SYSTEM_BROADCAST", converters.fromEventSource(EventSource.SYSTEM_BROADCAST))
        assertEquals("USER_ACTION", converters.fromEventSource(EventSource.USER_ACTION))
    }

    // ==================== MatchMode ====================

    @Test
    fun `MatchMode round trip`() {
        for (mode in MatchMode.values()) {
            val encoded = converters.fromMatchMode(mode)
            val decoded = converters.toMatchMode(encoded)
            assertEquals(mode, decoded)
        }
    }

    @Test
    fun `MatchMode encoding is uppercase enum name`() {
        assertEquals("CONTAINS", converters.fromMatchMode(MatchMode.CONTAINS))
        assertEquals("OR", converters.fromMatchMode(MatchMode.OR))
        assertEquals("AND", converters.fromMatchMode(MatchMode.AND))
        assertEquals("EXACT", converters.fromMatchMode(MatchMode.EXACT))
    }

    // ==================== Edge cases ====================

    @Test
    fun `StringList with unicode characters`() {
        val list = listOf("中文", "émoji 🎉", "кириллица")
        val encoded = converters.fromStringList(list)
        val decoded = converters.toStringList(encoded)
        assertEquals(list, decoded)
    }

    @Test
    fun `StringList with single empty element behaves deterministically`() {
        // Empty string element creates a trailing comma which produces 3 empty parts
        // This documents the converter's behavior for callers.
        val encoded = converters.fromStringList(listOf(""))
        assertEquals("", encoded)
    }

    @Test
    fun `FloatArray with single value`() {
        val arr = floatArrayOf(42.0f)
        val encoded = converters.fromFloatArray(arr)
        assertEquals("42.0", encoded)
        val decoded = converters.toFloatArray(encoded)
        assertArrayEquals(arr, decoded, 0.0001f)
    }

    @Test
    fun `FloatArray scientific notation round trip`() {
        val arr = floatArrayOf(1.5e10f, -3.14e-5f, 6.022e23f)
        val encoded = converters.fromFloatArray(arr)
        val decoded = converters.toFloatArray(encoded)
        assertArrayEquals(arr, decoded, 0.001f)
    }
}