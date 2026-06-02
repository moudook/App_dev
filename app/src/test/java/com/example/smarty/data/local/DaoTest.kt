package com.example.smarty.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.smarty.core.domain.model.ChatMessageEntity
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.model.Note
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAO Layer Tests
 *
 * COVERAGE:
 * - ChatDao tests
 * - NoteDao tests
 * - CalendarDao tests
 *
 * TEST COUNT: 15 tests
 */
@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private lateinit var database: TestSmartyDatabase
    private lateinit var chatDao: ChatDao
    private lateinit var noteDao: NoteDao
    private lateinit var calendarDao: CalendarDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, TestSmartyDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        chatDao = database.chatDao()
        noteDao = database.noteDao()
        calendarDao = database.calendarDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== CHAT DAO TESTS ====================

    @Test
    fun `insert chat session succeeds`() =
        runBlocking {
            val session =
                ChatSession(
                    id = "session-123",
                    title = "Test Session",
                )

            chatDao.insertSession(session)

            val result = chatDao.getSession("session-123")
            assertNotNull(result)
            assertEquals("Test Session", result?.title)
        }

    @Test
    fun `insert chat message succeeds`() =
        runBlocking {
            val session = ChatSession(id = "session-123", title = "Test")
            chatDao.insertSession(session)

            val message =
                ChatMessageEntity(
                    id = "msg-123",
                    sessionId = "session-123",
                    role = "USER",
                    content = "Hello",
                )

            chatDao.insertMessage(message)

            val messages = chatDao.getMessagesForSession("session-123").first()
            assertEquals(1, messages.size)
            assertEquals("Hello", messages[0].content)
        }

    @Test
    fun `get messages by role returns filtered list`() =
        runBlocking {
            val session = ChatSession(id = "session-123", title = "Test")
            chatDao.insertSession(session)

            repeat(3) { i ->
                chatDao.insertMessage(
                    ChatMessageEntity(
                        id = "msg-$i",
                        sessionId = "session-123",
                        role = if (i % 2 == 0) "USER" else "SMARTY",
                        content = "Message $i",
                    ),
                )
            }

            val smartyMessages = chatDao.getMessagesByRole("session-123", "SMARTY").first()
            assertEquals(2, smartyMessages.size) // Messages 1 and 3
        }

    @Test
    fun `delete session removes messages`() =
        runBlocking {
            val session = ChatSession(id = "session-123", title = "Test")
            chatDao.insertSession(session)
            chatDao.insertMessage(
                ChatMessageEntity(
                    id = "msg-123",
                    sessionId = "session-123",
                    role = "USER",
                    content = "Test",
                ),
            )

            chatDao.deleteSession("session-123")

            val sessionResult = chatDao.getSession("session-123")
            assertNull(sessionResult)
        }

    @Test
    fun `get recent sessions returns ordered list`() =
        runBlocking {
            val now = System.currentTimeMillis()
            repeat(5) { i ->
                chatDao.insertSession(
                    ChatSession(
                        id = "session-$i",
                        title = "Session $i",
                        updatedAt = now - (i * 86400000),
                    ),
                )
            }

            val recent = chatDao.getRecentSessions(3)
            assertEquals(3, recent.size)
        }

    // ==================== NOTE DAO TESTS ====================

    @Test
    fun `insert note succeeds`() =
        runBlocking {
            val note =
                Note(
                    id = "note-123",
                    title = "Test Note",
                    content = "Test content",
                )

            noteDao.insert(note)

            val result = noteDao.getById("note-123")
            assertNotNull(result)
            assertEquals("Test Note", result?.title)
        }

    @Test
    fun `get all notes returns list`() =
        runBlocking {
            repeat(3) { i ->
                noteDao.insert(
                    Note(
                        id = "note-$i",
                        title = "Note $i",
                    ),
                )
            }

            val notes = noteDao.getAll()
            assertEquals(3, notes.size)
        }

    @Test
    fun `update note modifies database`() =
        runBlocking {
            val note =
                Note(
                    id = "note-123",
                    title = "Original",
                )
            noteDao.insert(note)

            val updated = note.copy(title = "Updated")
            noteDao.update(updated)

            val result = noteDao.getById("note-123")
            assertEquals("Updated", result?.title)
        }

    @Test
    fun `delete note removes from database`() =
        runBlocking {
            val note =
                Note(
                    id = "note-123",
                    title = "Test",
                )
            noteDao.insert(note)

            noteDao.delete("note-123")

            val result = noteDao.getById("note-123")
            assertNull(result)
        }

    @Test
    fun `get notes by category returns filtered list`() =
        runBlocking {
            noteDao.insert(
                Note(
                    id = "note-1",
                    title = "Work Note",
                    categoryName = "Work",
                ),
            )
            noteDao.insert(
                Note(
                    id = "note-2",
                    title = "Personal Note",
                    categoryName = "Personal",
                ),
            )

            val workNotes = noteDao.getByCategory("Work")
            assertEquals(1, workNotes.size)
        }

    // ==================== CALENDAR DAO TESTS ====================

    @Test
    fun `insert calendar event succeeds`() =
        runBlocking {
            val event =
                CalendarEvent(
                    id = "event-123",
                    title = "Test Event",
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis() + 3600000,
                )

            calendarDao.insert(event)

            val result = calendarDao.getById("event-123")
            assertNotNull(result)
            assertEquals("Test Event", result?.title)
        }

    @Test
    fun `get upcoming events returns future events`() =
        runBlocking {
            val now = System.currentTimeMillis()
            calendarDao.insert(
                CalendarEvent(
                    id = "event-1",
                    title = "Future Event",
                    startTime = now + 86400000,
                ),
            )
            calendarDao.insert(
                CalendarEvent(
                    id = "event-2",
                    title = "Past Event",
                    startTime = now - 86400000,
                ),
            )

            val upcoming = calendarDao.getUpcoming()
            assertEquals(1, upcoming.size)
            assertEquals("Future Event", upcoming[0].title)
        }

    @Test
    fun `update event modifies database`() =
        runBlocking {
            val event =
                CalendarEvent(
                    id = "event-123",
                    title = "Original",
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis() + 3600000,
                )
            calendarDao.insert(event)

            val updated = event.copy(title = "Updated")
            calendarDao.update(updated)

            val result = calendarDao.getById("event-123")
            assertEquals("Updated", result?.title)
        }

    @Test
    fun `delete event removes from database`() =
        runBlocking {
            val event =
                CalendarEvent(
                    id = "event-123",
                    title = "Test",
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis() + 3600000,
                )
            calendarDao.insert(event)

            calendarDao.delete("event-123")

            val result = calendarDao.getById("event-123")
            assertNull(result)
        }
}

// Test DAOs
@androidx.room.Dao
interface ChatDao {
    @androidx.room.Insert suspend fun insertSession(session: ChatSession)

    @androidx.room.Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getSession(id: String): ChatSession?

    @androidx.room.Insert suspend fun insertMessage(message: ChatMessageEntity)

    @androidx.room.Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): kotlinx.coroutines.flow.Flow<List<ChatMessageEntity>>

    @androidx.room.Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId AND role = :role ORDER BY timestamp ASC")
    fun getMessagesByRole(
        sessionId: String,
        role: String,
    ): kotlinx.coroutines.flow.Flow<List<ChatMessageEntity>>

    @androidx.room.Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @androidx.room.Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentSessions(limit: Int): List<ChatSession>
}

@androidx.room.Dao
interface NoteDao {
    @androidx.room.Insert suspend fun insert(note: Note)

    @androidx.room.Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): Note?

    @androidx.room.Query("SELECT * FROM notes")
    suspend fun getAll(): List<Note>

    @androidx.room.Update suspend fun update(note: Note)

    @androidx.room.Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String): Int

    @androidx.room.Query("SELECT * FROM notes WHERE categoryName = :category")
    suspend fun getByCategory(category: String): List<Note>
}

@androidx.room.Dao
interface CalendarDao {
    @androidx.room.Insert suspend fun insert(event: CalendarEvent)

    @androidx.room.Query("SELECT * FROM calendar_events WHERE id = :id")
    suspend fun getById(id: String): CalendarEvent?

    @androidx.room.Query("SELECT * FROM calendar_events WHERE startTime > :now ORDER BY startTime ASC")
    suspend fun getUpcoming(now: Long = System.currentTimeMillis()): List<CalendarEvent>

    @androidx.room.Update suspend fun update(event: CalendarEvent)

    @androidx.room.Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun delete(id: String): Int
}

@androidx.room.Database(
    entities = [
        ChatSession::class,
        ChatMessageEntity::class,
    ],
    version = 1,
)
abstract class TestSmartyDatabase : androidx.room.RoomDatabase() {
    abstract fun chatDao(): ChatDao

    abstract fun noteDao(): NoteDao

    abstract fun calendarDao(): CalendarDao
}
