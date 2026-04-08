package ai.openclaw.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.data.local.SessionDao
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionDao: SessionDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        
        sessionDao = database.sessionDao()
    }

    @Test
    fun insertSession_andRetrieveById() = runBlocking {
        val session = SessionEntity(
            sessionId = "test-session-id",
            name = "Test Session",
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            tokenCount = 0,
            status = SessionStatus.ACTIVE
        )

        sessionDao.insertSession(session)
        
        val retrievedSession = sessionDao.getSessionById("test-session-id")
        
        assertNotNull(retrievedSession)
        assertEquals("test-session-id", retrievedSession?.sessionId)
        assertEquals("Test Session", retrievedSession?.name)
        assertEquals(SessionStatus.ACTIVE, retrievedSession?.status)
    }

    @Test
    fun getDefaultSession_returnsNullName() = runBlocking {
        val session = SessionEntity(
            sessionId = "default-session-id",
            name = null,  // null 表示默认会话
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            tokenCount = 0,
            status = SessionStatus.ACTIVE
        )

        sessionDao.insertSession(session)
        
        val retrievedSession = sessionDao.getSessionById("default-session-id")
        
        assertNotNull(retrievedSession)
        assertNull(retrievedSession?.name)
    }

    @Test
    fun updateSession_updatesCorrectly() = runBlocking {
        val session = SessionEntity(
            sessionId = "update-test-id",
            name = "Original Name",
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            tokenCount = 0,
            status = SessionStatus.ACTIVE
        )

        sessionDao.insertSession(session)
        
        val updatedSession = session.copy(
            name = "Updated Name",
            lastActiveAt = System.currentTimeMillis() + 1000,
            tokenCount = 100,
            status = SessionStatus.COMPRESSED
        )
        
        sessionDao.updateSession(updatedSession)
        
        val retrievedSession = sessionDao.getSessionById("update-test-id")
        
        assertNotNull(retrievedSession)
        assertEquals("Updated Name", retrievedSession?.name)
        assertEquals(SessionStatus.COMPRESSED, retrievedSession?.status)
        assertEquals(100, retrievedSession?.tokenCount)
    }

    @Test
    fun deleteSession_removesFromDatabase() = runBlocking {
        val session = SessionEntity(
            sessionId = "delete-test-id",
            name = "To Delete",
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            tokenCount = 0,
            status = SessionStatus.ACTIVE
        )

        sessionDao.insertSession(session)
        
        var retrievedSession = sessionDao.getSessionById("delete-test-id")
        assertNotNull(retrievedSession)
        
        sessionDao.deleteSessionById("delete-test-id")
        
        retrievedSession = sessionDao.getSessionById("delete-test-id")
        assertNull(retrievedSession)
    }

    @Test
    fun getAllSessions_returnsAllSessions() = runBlocking {
        val session1 = SessionEntity(
            sessionId = "session-1",
            name = "Session 1",
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            tokenCount = 0,
            status = SessionStatus.ACTIVE
        )

        val session2 = SessionEntity(
            sessionId = "session-2",
            name = "Session 2",
            createdAt = System.currentTimeMillis() + 1000,
            lastActiveAt = System.currentTimeMillis() + 1000,
            tokenCount = 50,
            status = SessionStatus.ARCHIVED
        )

        sessionDao.insertSession(session1)
        sessionDao.insertSession(session2)
        
        val allSessions = sessionDao.getAllSessions().first()
        
        assertEquals(2, allSessions.size)
        assertTrue(allSessions.any { it.sessionId == "session-1" })
        assertTrue(allSessions.any { it.sessionId == "session-2" })
    }
}