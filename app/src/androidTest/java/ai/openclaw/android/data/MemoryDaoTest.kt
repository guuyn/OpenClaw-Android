package ai.openclaw.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.data.local.MemoryDao
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var memoryDao: MemoryDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        
        memoryDao = database.memoryDao()
    }

    @Test
    fun insertMemoryAndGetById() = runBlocking {
        val memory = MemoryEntity(
            content = "Test memory content",
            memoryType = MemoryType.FACT,
            priority = 3,
            source = "test",
            tags = listOf("test", "unit"),
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )

        val id = memoryDao.insert(memory)
        val retrievedMemory = memoryDao.getById(id)

        assertNotNull(retrievedMemory)
        assertEquals("Test memory content", retrievedMemory?.content)
        assertEquals(MemoryType.FACT, retrievedMemory?.memoryType)
        assertEquals(3, retrievedMemory?.priority)
    }

    @Test
    fun getMemoriesByType() = runBlocking {
        val memory1 = MemoryEntity(
            content = "Preference memory",
            memoryType = MemoryType.PREFERENCE,
            priority = 2,
            source = "test",
            tags = listOf("preference"),
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )

        val memory2 = MemoryEntity(
            content = "Fact memory",
            memoryType = MemoryType.FACT,
            priority = 3,
            source = "test",
            tags = listOf("fact"),
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )

        memoryDao.insert(memory1)
        memoryDao.insert(memory2)

        val preferences = memoryDao.getByType(MemoryType.PREFERENCE, 10)
        val facts = memoryDao.getByType(MemoryType.FACT, 10)

        assertEquals(1, preferences.size)
        assertEquals(1, facts.size)
        assertEquals("Preference memory", preferences[0].content)
        assertEquals("Fact memory", facts[0].content)
    }

    @Test
    fun getHighPriorityMemories() = runBlocking {
        val highPriorityMemory = MemoryEntity(
            content = "High priority memory",
            memoryType = MemoryType.TASK,
            priority = 5,
            source = "test",
            tags = listOf("high-priority"),
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )

        val lowPriorityMemory = MemoryEntity(
            content = "Low priority memory",
            memoryType = MemoryType.FACT,
            priority = 2,
            source = "test",
            tags = listOf("low-priority"),
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )

        memoryDao.insert(highPriorityMemory)
        memoryDao.insert(lowPriorityMemory)

        val highPriorityMemories = memoryDao.getHighPriority(10)

        assertEquals(1, highPriorityMemories.size)
        assertEquals("High priority memory", highPriorityMemories[0].content)
        assertTrue(highPriorityMemories[0].priority >= 4)
    }

    @Test
    fun updateMemoryAccessInfo() = runBlocking {
        val memory = MemoryEntity(
            content = "Test memory for access update",
            memoryType = MemoryType.PROJECT,
            priority = 3,
            source = "test",
            tags = listOf("access-test"),
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )

        val id = memoryDao.insert(memory)
        val initialAccessCount = memory.accessCount
        
        val newTimestamp = System.currentTimeMillis() + 1000
        memoryDao.updateAccess(id, newTimestamp)

        val updatedMemory = memoryDao.getById(id)
        
        assertEquals(newTimestamp, updatedMemory?.lastAccessedAt)
        assertEquals(initialAccessCount + 1, updatedMemory?.accessCount)
    }

    @Test
    fun deleteMemory() = runBlocking {
        val memory = MemoryEntity(
            content = "Test memory for deletion",
            memoryType = MemoryType.TASK,
            priority = 3,
            source = "test",
            tags = listOf("deletion-test"),
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )

        val id = memoryDao.insert(memory)
        val retrievedBeforeDelete = memoryDao.getById(id)
        assertNotNull(retrievedBeforeDelete)

        memoryDao.delete(memory)
        val retrievedAfterDelete = memoryDao.getById(id)
        assertNull(retrievedAfterDelete)
    }
}