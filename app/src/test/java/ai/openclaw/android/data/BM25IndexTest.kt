package ai.openclaw.android.data

import ai.openclaw.android.data.local.BM25Index
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * BM25Index unit tests — pure Kotlin scoring without Room dependency.
 *
 * Exercises:
 * - Tokenization (Chinese bigrams, English words, mixed)
 * - BM25 scoring with default k1=1.5, b=0.75
 * - IDF, TF, and length normalization
 * - Edge cases: empty query, single document, zero-relevance queries
 * - Index management: insert, remove, clear, size tracking
 */
class BM25IndexTest {

    private lateinit var index: BM25Index

    @Before
    fun setUp() {
        index = BM25Index()
    }

    private fun memory(
        id: Long,
        content: String,
        type: MemoryType = MemoryType.FACT,
        createdAt: Long = 1000L
    ): MemoryEntity = MemoryEntity(
        id = id,
        content = content,
        memoryType = type,
        priority = 3,
        source = "test",
        tags = emptyList(),
        createdAt = createdAt,
        lastAccessedAt = createdAt
    )

    // ==================== Tokenization ====================

    @Test
    fun `tokenize returns lowercase english words`() {
        val tokens = index.tokenize("Hello WORLD Foo")
        assertEquals(listOf("hello", "world", "foo"), tokens)
    }

    @Test
    fun `tokenize produces bigrams for chinese text`() {
        val tokens = index.tokenize("你好世界")
        // 4 chars → 3 bigrams
        assertTrue(tokens.contains("你好"))
        assertTrue(tokens.contains("好世"))
        assertTrue(tokens.contains("世界"))
    }

    @Test
    fun `tokenize keeps short chinese words as-is`() {
        val tokens = index.tokenize("编程")
        // 2 chars → bigrams = ["编程"], also kept as-is
        assertTrue("Should contain 编程 bigram", tokens.contains("编程"))
    }

    @Test
    fun `tokenize handles mixed chinese and english`() {
        // Note: the existing tokenizer has a quirk where it treats CJK chars as
        // part of English words when no space separator is present (since
        // isLetterOrDigit() returns true for CJK chars). We verify the
        // documented behavior below.
        val tokensSpace = index.tokenize("用 Kotlin 编程")
        // With spaces, "用" is a 1-char CJK run (dropped), "Kotlin" is the
        // English token, and "编程" is a 2-char CJK run (kept as a token).
        assertTrue("Space-separated 'Kotlin' should tokenize: $tokensSpace", tokensSpace.contains("kotlin"))
        assertTrue("Space-separated '编程' should tokenize: $tokensSpace", tokensSpace.contains("编程"))
    }

    @Test
    fun `tokenize splits English from adjacent CJK without space - regression for bug #2`() {
        // Regression test for production bug #2 (CURRENT-STATUS-2026-06-28):
        // "用Kotlin编程" used to tokenize as ["kotlin编程"] because the English
        // branch swallowed CJK characters (Character.isLetterOrDigit() returns
        // true for CJK). The fix excludes the CJK Unicode range from the
        // English collection loop, so the expected segmentation is:
        //   - "用" → single CJK char (run length 1; not a bigram, not kept)
        //   - "Kotlin" → English word → "kotlin"
        //   - "编程" → 2-char CJK run → bigram "编程" + full word "编程" (length 2)
        val tokens = index.tokenize("用Kotlin编程")
        assertTrue("Expected 'kotlin' as a standalone English token, got: $tokens",
            tokens.contains("kotlin"))
        assertTrue("Expected CJK bigram '编程' in tokens, got: $tokens",
            tokens.contains("编程"))
        // Critical regression assertion: the English word must NOT have
        // swallowed the trailing CJK characters.
        assertFalse("Bug #2 regression: English word should not contain CJK, got: $tokens",
            tokens.any { it.contains("kotlin编程") || it.contains("Kotlin编程") })
    }

    @Test
    fun `tokenize splits CJK from adjacent English when CJK comes first`() {
        // "编程Kotlin" → "编程" bigrams + "kotlin"
        val tokens = index.tokenize("编程Kotlin")
        assertTrue("Expected '编程' bigram, got: $tokens", tokens.contains("编程"))
        assertTrue("Expected 'kotlin' English word, got: $tokens", tokens.contains("kotlin"))
        assertFalse("English branch must not start with CJK, got: $tokens",
            tokens.any { it.contains("编程k", ignoreCase = true) || it.contains("编程K") })
    }

    @Test
    fun `tokenize splits English between two CJK runs without spaces`() {
        // "编程用Kotlin用编程" — CJK, English, CJK; English should remain standalone.
        val tokens = index.tokenize("编程用Kotlin用编程")
        assertTrue("English word 'kotlin' must be its own token, got: $tokens",
            tokens.contains("kotlin"))
        assertFalse("Bug #2 regression: English branch swallowed CJK, got: $tokens",
            tokens.any { "kotlin" in it && it != "kotlin" })
    }

    @Test
    fun `tokenize handles English-numeric-CJK mix without spaces`() {
        // "iOS2026编程" — letters + digits + CJK all glued. English/numeric
        // branch must stop at the CJK boundary.
        val tokens = index.tokenize("iOS2026编程")
        assertTrue("Expected 'ios2026' token, got: $tokens", tokens.contains("ios2026"))
        assertTrue("Expected '编程' bigram, got: $tokens", tokens.contains("编程"))
        assertFalse("Bug #2 regression: numeric/digit branch swallowed CJK, got: $tokens",
            tokens.any { it.contains("ios2026编程") })
    }

    @Test
    fun `tokenize strips punctuation`() {
        val tokens = index.tokenize("hello, world! how are you?")
        assertEquals(listOf("hello", "world", "how", "are", "you"), tokens)
    }

    @Test
    fun `tokenize preserves digits`() {
        val tokens = index.tokenize("OpenClaw version 2026")
        assertTrue(tokens.contains("openclaw"))
        assertTrue(tokens.contains("version"))
        assertTrue(tokens.contains("2026"))
    }

    @Test
    fun `tokenize returns empty for empty input`() {
        assertTrue(index.tokenize("").isEmpty())
    }

    @Test
    fun `tokenize returns empty for whitespace-only input`() {
        assertTrue(index.tokenize("   \t\n").isEmpty())
    }

    @Test
    fun `tokenize caches results`() {
        val first = index.tokenize("hello world")
        val second = index.tokenize("hello world")
        assertEquals(first, second)
    }

    // ==================== Index management ====================

    @Test
    fun `empty index has size 0`() {
        assertEquals(0, index.size)
    }

    @Test
    fun `indexing a memory increases size`() {
        index.index(memory(1, "hello world"))
        assertEquals(1, index.size)
        index.index(memory(2, "another memory"))
        assertEquals(2, index.size)
    }

    @Test
    fun `indexing same id twice replaces the entry`() {
        index.index(memory(1, "original content"))
        index.index(memory(1, "updated content with new words"))

        // The id is the same, so size should still be 1
        assertEquals(1, index.size)

        // The old content should no longer be findable
        val results = index.search("original")
        assertTrue("Original tokens should not be found", results.none { it.memoryId == 1L })
    }

    @Test
    fun `removeFromIndex removes the memory`() {
        index.index(memory(1, "hello world"))
        index.index(memory(2, "goodbye world"))
        assertEquals(2, index.size)

        index.removeFromIndex(1)
        assertEquals(1, index.size)

        val results = index.search("hello")
        assertTrue("Removed memory should not appear", results.none { it.memoryId == 1L })
    }

    @Test
    fun `clear resets the index`() {
        index.index(memory(1, "first"))
        index.index(memory(2, "second"))
        index.clear()
        assertEquals(0, index.size)
        assertTrue(index.search("first").isEmpty())
    }

    // ==================== Search behavior ====================

    @Test
    fun `search returns empty for empty index`() {
        assertTrue(index.search("anything").isEmpty())
    }

    @Test
    fun `search returns empty for blank query`() {
        index.index(memory(1, "hello world"))
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun `search finds exact match`() {
        index.index(memory(1, "kotlin programming language"))
        index.index(memory(2, "java programming language"))
        index.index(memory(3, "python programming language"))

        val results = index.search("kotlin")
        assertEquals(1, results.size)
        assertEquals(1L, results[0].memoryId)
        assertTrue(results[0].score > 0)
    }

    @Test
    fun `search ranks by relevance score`() {
        // Memory 1 mentions "kotlin" once, memory 2 mentions it twice.
        // Memory 2 should rank higher.
        index.index(memory(1, "kotlin is great"))
        index.index(memory(2, "kotlin kotlin kotlin is great"))
        index.index(memory(3, "java is also great"))

        val results = index.search("kotlin")
        assertTrue("At least 2 results expected", results.size >= 2)
        assertEquals("Highest TF doc should be ranked first", 2L, results[0].memoryId)
        // Memory 3 (java) shouldn't be in results for "kotlin"
        assertTrue(results.none { it.memoryId == 3L })
    }

    @Test
    fun `search returns no results when no match`() {
        index.index(memory(1, "hello world"))
        index.index(memory(2, "goodbye world"))
        val results = index.search("quantum")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search respects topK limit`() {
        for (i in 1..10) {
            index.index(memory(i.toLong(), "kotlin memory $i"))
        }
        val results = index.search("kotlin", topK = 3)
        assertEquals(3, results.size)
    }

    @Test
    fun `search is case-insensitive (via tokenization)`() {
        index.index(memory(1, "Kotlin Programming"))
        val results = index.search("kotlin")
        assertEquals(1, results.size)
        val resultsUpper = index.search("KOTLIN")
        assertEquals(1, resultsUpper.size)
    }

    // ==================== IDF and length normalization ====================

    @Test
    fun `search applies length normalization - shorter doc wins`() {
        // Both contain "kotlin", but memory 2 has many more unrelated tokens.
        // Memory 1 (shorter, more focused) should score higher on BM25.
        index.index(memory(1, "kotlin is awesome"))
        index.index(memory(2, "this memory has a lot of extra words including kotlin but mostly unrelated content"))

        val results = index.search("kotlin")
        assertTrue(results.size >= 2)
        // The shorter document should win or tie
        val rank1 = results.indexOfFirst { it.memoryId == 1L }
        val rank2 = results.indexOfFirst { it.memoryId == 2L }
        assertTrue(
            "Shorter doc (id=1) should rank higher than longer (id=2), ranks: 1=$rank1, 2=$rank2",
            rank1 < rank2
        )
    }

    @Test
    fun `search applies IDF - rare term scores higher`() {
        // "kotlin" appears in 1 doc, "language" appears in 3 docs.
        // When querying both, "kotlin" should contribute more (higher IDF).
        index.index(memory(1, "kotlin programming language"))
        index.index(memory(2, "java programming language"))
        index.index(memory(3, "python programming language"))
        index.index(memory(4, "rust is a systems language"))  // contains "language"

        val results = index.search("kotlin language")
        assertTrue(results.isNotEmpty())
        // Memory 1 (only doc with both terms) should be top-ranked
        assertEquals(1L, results[0].memoryId)
    }

    @Test
    fun `search handles time range filtering with different days`() {
        // Use createdAt values that differ by ≥1 day (DAY_MS = 86_400_000) so
        // they end up in distinct day buckets, which is how the time filter
        // partitions candidates.
        val dayMs = 86_400_000L
        val now = System.currentTimeMillis()
        val yesterday = now - dayMs
        index.index(memory(1, "kotlin note", createdAt = yesterday))
        index.index(memory(2, "kotlin recent", createdAt = now))

        // timeFrom = today → only memory 2 (created today) matches
        val results = index.search("kotlin", timeFrom = now - 1)
        assertEquals(1, results.size)
        assertEquals(2L, results[0].memoryId)
    }

    @Test
    fun `search with timeFrom before everything returns all`() {
        index.index(memory(1, "alpha"))
        index.index(memory(2, "beta"))
        index.index(memory(3, "gamma"))

        val results = index.search("alpha beta gamma", timeFrom = 0L)
        assertEquals(3, results.size)
    }

    @Test
    fun `search with timeTo in the past returns nothing`() {
        val now = System.currentTimeMillis()
        index.index(memory(1, "alpha", createdAt = now))
        index.index(memory(2, "beta", createdAt = now))

        // timeTo set to a long time ago — no candidates should match
        // (memories have createdAt = today, timeTo maps to a day bucket before today)
        val results = index.search("alpha beta", timeTo = 1L)
        assertEquals(0, results.size)
    }

    // ==================== Scoring properties ====================

    @Test
    fun `all scores are positive for matching documents`() {
        index.index(memory(1, "kotlin rocks"))
        val results = index.search("kotlin")
        assertTrue(results.isNotEmpty())
        for (r in results) {
            assertTrue("Score should be positive, got ${r.score}", r.score > 0)
        }
    }

    @Test
    fun `scores are sorted in descending order`() {
        for (i in 1..5) {
            index.index(memory(i.toLong(), "kotlin $i"))
        }
        val results = index.search("kotlin", topK = 10)
        for (i in 1 until results.size) {
            assertTrue(
                "Results must be sorted desc by score: ${results[i - 1].score} >= ${results[i].score}",
                results[i - 1].score >= results[i].score
            )
        }
    }

    @Test
    fun `score increases with more matching tokens in single doc`() {
        index.index(memory(1, "kotlin"))
        val singleScore = index.search("kotlin").first().score

        index.clear()
        index.index(memory(1, "kotlin kotlin kotlin kotlin"))
        val multipleScore = index.search("kotlin").first().score

        assertTrue(
            "More matching tokens → higher score, single=$singleScore, multiple=$multipleScore",
            multipleScore > singleScore
        )
    }

    @Test
    fun `reindexing preserves stable scoring`() {
        index.index(memory(1, "kotlin programming"))
        val firstScore = index.search("kotlin").first().score

        // Re-index with same content
        index.index(memory(1, "kotlin programming"))
        val secondScore = index.search("kotlin").first().score

        assertEquals(
            "Reindexing with same content should yield same score",
            firstScore,
            secondScore,
            0.0001
        )
    }
}