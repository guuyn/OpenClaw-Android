package ai.openclaw.android.domain.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ColdStartManager.
 *
 * ColdStartManager tracks first-launch timestamp in SharedPreferences and
 * determines whether to use SENSORY_ONLY (first 72h) or FULL mode.
 *
 * Time-based behavior is exercised by manually setting the stored timestamp
 * to simulate elapsed hours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ColdStartManagerTest {

    private lateinit var context: Context
    private lateinit var manager: ColdStartManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear shared preferences to start fresh
        context.getSharedPreferences("openclaw_cold_start", Context.MODE_PRIVATE)
            .edit().clear().commit()
        manager = ColdStartManager(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("openclaw_cold_start", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /** Helper: directly set the first-launch timestamp via reflection. */
    private fun setFirstLaunchTimestamp(ts: Long) {
        val prefs = context.getSharedPreferences("openclaw_cold_start", Context.MODE_PRIVATE)
        prefs.edit().putLong("first_launch_ts", ts).commit()
    }

    /** Helper: get the stored first-launch timestamp. */
    private fun getFirstLaunchTimestamp(): Long {
        val prefs = context.getSharedPreferences("openclaw_cold_start", Context.MODE_PRIVATE)
        return prefs.getLong("first_launch_ts", 0L)
    }

    // ==================== First launch detection ====================

    @Test
    fun `markFirstLaunchIfNeeded records timestamp on first call`() {
        assertEquals(0L, getFirstLaunchTimestamp())
        manager.markFirstLaunchIfNeeded()
        val ts = getFirstLaunchTimestamp()
        assertTrue("Timestamp should be > 0 after markFirstLaunchIfNeeded", ts > 0L)
    }

    @Test
    fun `markFirstLaunchIfNeeded does not overwrite existing timestamp`() {
        manager.markFirstLaunchIfNeeded()
        val first = getFirstLaunchTimestamp()

        Thread.sleep(2)
        manager.markFirstLaunchIfNeeded()
        val second = getFirstLaunchTimestamp()

        assertEquals(
            "Second call should not overwrite existing timestamp",
            first,
            second
        )
    }

    @Test
    fun `mode without recorded first launch is FULL`() {
        // No markFirstLaunchIfNeeded called → no timestamp
        assertEquals(0L, getFirstLaunchTimestamp())
        assertEquals(ColdStartManager.MemoryMode.FULL, manager.mode)
    }

    // ==================== Mode based on elapsed time ====================

    @Test
    fun `mode is SENSORY_ONLY immediately after first launch`() {
        manager.markFirstLaunchIfNeeded()
        // Just launched, so elapsed is ~0 hours
        assertEquals(ColdStartManager.MemoryMode.SENSORY_ONLY, manager.mode)
    }

    @Test
    fun `mode is SENSORY_ONLY within 72 hours`() {
        manager.markFirstLaunchIfNeeded()
        val firstLaunch = getFirstLaunchTimestamp()

        // Simulate 24 hours elapsed
        setFirstLaunchTimestamp(firstLaunch - 24L * 3_600_000L)
        assertEquals(ColdStartManager.MemoryMode.SENSORY_ONLY, manager.mode)

        // Simulate 48 hours elapsed
        setFirstLaunchTimestamp(firstLaunch - 48L * 3_600_000L)
        assertEquals(ColdStartManager.MemoryMode.SENSORY_ONLY, manager.mode)

        // Simulate 71 hours elapsed
        setFirstLaunchTimestamp(firstLaunch - 71L * 3_600_000L)
        assertEquals(ColdStartManager.MemoryMode.SENSORY_ONLY, manager.mode)
    }

    @Test
    fun `mode is FULL after 72 hours elapsed`() {
        manager.markFirstLaunchIfNeeded()
        val firstLaunch = getFirstLaunchTimestamp()

        // Simulate 72 hours elapsed
        setFirstLaunchTimestamp(firstLaunch - 72L * 3_600_000L)
        assertEquals(ColdStartManager.MemoryMode.FULL, manager.mode)
    }

    @Test
    fun `mode is FULL long after first launch`() {
        manager.markFirstLaunchIfNeeded()
        val firstLaunch = getFirstLaunchTimestamp()

        // Simulate 1 year elapsed
        setFirstLaunchTimestamp(firstLaunch - 365L * 24L * 3_600_000L)
        assertEquals(ColdStartManager.MemoryMode.FULL, manager.mode)
    }

    // ==================== Boundary at 72h ====================

    @Test
    fun `mode boundary 1 hour before 72 hours is SENSORY_ONLY`() {
        manager.markFirstLaunchIfNeeded()
        val firstLaunch = getFirstLaunchTimestamp()

        // 71 hours ago → still SENSORY_ONLY (with margin to avoid timing races)
        setFirstLaunchTimestamp(firstLaunch - 71L * 3_600_000L)
        assertEquals(
            "At 71h ago, mode should be SENSORY_ONLY",
            ColdStartManager.MemoryMode.SENSORY_ONLY,
            manager.mode
        )
    }

    @Test
    fun `mode boundary 1 hour after 72 hours is FULL`() {
        manager.markFirstLaunchIfNeeded()
        val firstLaunch = getFirstLaunchTimestamp()

        // 73 hours ago → FULL (with margin)
        setFirstLaunchTimestamp(firstLaunch - 73L * 3_600_000L)
        assertEquals(
            "At 73h ago, mode should be FULL",
            ColdStartManager.MemoryMode.FULL,
            manager.mode
        )
    }

    // ==================== hoursRemaining ====================

    @Test
    fun `hoursRemaining is 0 in FULL mode`() {
        manager.markFirstLaunchIfNeeded()
        val firstLaunch = getFirstLaunchTimestamp()

        setFirstLaunchTimestamp(firstLaunch - 100L * 3_600_000L) // 100h ago
        assertEquals(0L, manager.hoursRemaining)
    }

    @Test
    fun `hoursRemaining is 0 without first launch record`() {
        // No timestamp → treated as FULL → 0 hours remaining
        assertEquals(0L, manager.hoursRemaining)
    }

    @Test
    fun `hoursRemaining counts down from 72h`() {
        manager.markFirstLaunchIfNeeded()
        val firstLaunch = getFirstLaunchTimestamp()

        // Simulate 0 hours elapsed → 72h remaining
        setFirstLaunchTimestamp(firstLaunch)
        val remaining0 = manager.hoursRemaining
        assertTrue("Should have ~72h remaining: $remaining0", remaining0 in 71L..72L)

        // Simulate 24 hours elapsed → 48h remaining
        setFirstLaunchTimestamp(firstLaunch - 24L * 3_600_000L)
        val remaining24 = manager.hoursRemaining
        assertTrue("Should have ~48h remaining: $remaining24", remaining24 in 47L..48L)

        // Simulate 71 hours elapsed → 1h remaining
        setFirstLaunchTimestamp(firstLaunch - 71L * 3_600_000L)
        val remaining71 = manager.hoursRemaining
        assertTrue("Should have ~1h remaining: $remaining71", remaining71 in 0L..1L)
    }

    @Test
    fun `hoursRemaining never goes negative`() {
        manager.markFirstLaunchIfNeeded()
        val firstLaunch = getFirstLaunchTimestamp()

        setFirstLaunchTimestamp(firstLaunch - 1000L * 3_600_000L) // far in past
        assertTrue(
            "hoursRemaining should not be negative",
            manager.hoursRemaining >= 0L
        )
    }

    // ==================== Persistence across instances ====================

    @Test
    fun `mode survives ColdStartManager re-instantiation`() {
        manager.markFirstLaunchIfNeeded()
        val firstLaunch = getFirstLaunchTimestamp()

        setFirstLaunchTimestamp(firstLaunch - 10L * 3_600_000L)

        // New instance reads the persisted timestamp
        val manager2 = ColdStartManager(context)
        assertEquals(ColdStartManager.MemoryMode.SENSORY_ONLY, manager2.mode)
    }
}