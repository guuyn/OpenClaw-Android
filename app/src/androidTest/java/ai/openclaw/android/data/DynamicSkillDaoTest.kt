package ai.openclaw.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.data.local.DynamicSkillDao
import ai.openclaw.android.data.model.DynamicSkillEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DynamicSkillDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dynamicSkillDao: DynamicSkillDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .fallbackToDestructiveMigration()
            .build()

        dynamicSkillDao = database.dynamicSkillDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndGetById() = runBlocking {
        val skill = DynamicSkillEntity(
            id = "skill-001",
            name = "Test Skill",
            description = "A test skill for unit testing",
            version = "1.0.0",
            instructions = "Do something useful",
            script = "console.log('hello')",
            toolsJson = "[]",
            permissions = "network,file"
        )

        val rowId = dynamicSkillDao.insert(skill)
        assertTrue(rowId > 0)

        val retrieved = dynamicSkillDao.getById("skill-001")
        assertNotNull(retrieved)
        assertEquals("skill-001", retrieved?.id)
        assertEquals("Test Skill", retrieved?.name)
        assertEquals("A test skill for unit testing", retrieved?.description)
        assertEquals("1.0.0", retrieved?.version)
        assertEquals("console.log('hello')", retrieved?.script)
        assertEquals("[]", retrieved?.toolsJson)
        assertEquals("network,file", retrieved?.permissions)
        assertTrue(retrieved?.enabled == true)
    }

    @Test
    fun getAllEnabledReturnsOnlyEnabledSkills() = runBlocking {
        val enabledSkill = DynamicSkillEntity(
            id = "skill-enabled",
            name = "Enabled Skill",
            description = "This skill is enabled",
            version = "1.0",
            instructions = "instructions",
            script = "script",
            toolsJson = "[]"
        )

        val disabledSkill = DynamicSkillEntity(
            id = "skill-disabled",
            name = "Disabled Skill",
            description = "This skill is disabled",
            version = "1.0",
            instructions = "instructions",
            script = "script",
            toolsJson = "[]",
            enabled = false
        )

        dynamicSkillDao.insert(enabledSkill)
        dynamicSkillDao.insert(disabledSkill)

        val enabledList = dynamicSkillDao.getAllEnabled().first()

        assertEquals(1, enabledList.size)
        assertEquals("skill-enabled", enabledList[0].id)
    }

    @Test
    fun disableRemovesFromEnabledList() = runBlocking {
        val skill = DynamicSkillEntity(
            id = "skill-to-disable",
            name = "To Disable",
            description = "Will be disabled",
            version = "1.0",
            instructions = "instructions",
            script = "script",
            toolsJson = "[]"
        )

        dynamicSkillDao.insert(skill)

        // Verify it's in the enabled list
        assertEquals(1, dynamicSkillDao.getAllEnabled().first().size)

        // Disable it
        dynamicSkillDao.disable("skill-to-disable")

        // Verify it's no longer in the enabled list
        val enabledList = dynamicSkillDao.getAllEnabled().first()
        assertEquals(0, enabledList.size)

        // But it's still in the database (just disabled)
        val retrieved = dynamicSkillDao.getById("skill-to-disable")
        assertNotNull(retrieved)
        assertTrue(retrieved?.enabled == false)
    }

    @Test
    fun deleteRemovesSkill() = runBlocking {
        val skill = DynamicSkillEntity(
            id = "skill-to-delete",
            name = "To Delete",
            description = "Will be deleted",
            version = "1.0",
            instructions = "instructions",
            script = "script",
            toolsJson = "[]"
        )

        dynamicSkillDao.insert(skill)
        assertEquals(1, dynamicSkillDao.getAllEnabled().first().size)

        val retrieved = dynamicSkillDao.getById("skill-to-delete")
        assertNotNull(retrieved)

        dynamicSkillDao.delete(retrieved!!)

        val afterDelete = dynamicSkillDao.getById("skill-to-delete")
        assertNull(afterDelete)
        assertEquals(0, dynamicSkillDao.getAllEnabled().first().size)
    }

    @Test
    fun insertWithConflictReplacesExisting() = runBlocking {
        val originalSkill = DynamicSkillEntity(
            id = "skill-replace",
            name = "Original",
            description = "Original description",
            version = "1.0",
            instructions = "original instructions",
            script = "original script",
            toolsJson = "[]"
        )

        dynamicSkillDao.insert(originalSkill)

        val updatedSkill = DynamicSkillEntity(
            id = "skill-replace",
            name = "Updated",
            description = "Updated description",
            version = "2.0",
            instructions = "updated instructions",
            script = "updated script",
            toolsJson = "[{\"type\":\"search\"}]"
        )

        dynamicSkillDao.insert(updatedSkill)

        val retrieved = dynamicSkillDao.getById("skill-replace")
        assertNotNull(retrieved)
        assertEquals("Updated", retrieved?.name)
        assertEquals("Updated description", retrieved?.description)
        assertEquals("2.0", retrieved?.version)
        assertEquals("updated script", retrieved?.script)
        assertEquals("[{\"type\":\"search\"}]", retrieved?.toolsJson)
    }

    @Test
    fun getAllEnabledOrderByCreatedAt() = runBlocking {
        val olderSkill = DynamicSkillEntity(
            id = "skill-older",
            name = "Older",
            description = "Created first",
            version = "1.0",
            instructions = "instructions",
            script = "script",
            toolsJson = "[]",
            createdAt = 1000L
        )

        val newerSkill = DynamicSkillEntity(
            id = "skill-newer",
            name = "Newer",
            description = "Created second",
            version = "1.0",
            instructions = "instructions",
            script = "script",
            toolsJson = "[]",
            createdAt = 2000L
        )

        dynamicSkillDao.insert(olderSkill)
        dynamicSkillDao.insert(newerSkill)

        val list = dynamicSkillDao.getAllEnabled().first()

        assertEquals(2, list.size)
        assertEquals("skill-older", list[0].id)
        assertEquals("skill-newer", list[1].id)
    }

    @Test
    fun getByIdReturnsNullForNonExistentId() = runBlocking {
        val result = dynamicSkillDao.getById("non-existent")
        assertNull(result)
    }
}
