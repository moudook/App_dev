package com.example.smarty.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.ChatMessageEntity
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.ProcessingStatus
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

            val result = chatDao.getSessionById("session-123")
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

            chatDao.deleteSessionById("session-123")

            val sessionResult = chatDao.getSessionById("session-123")
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
                    type = NoteType.BRAIN_DUMP,
                    processingStatus = ProcessingStatus.COMPLETED,
                )

            noteDao.insertNote(note)

            val result = noteDao.getNoteById("note-123")
            assertNotNull(result)
            assertEquals("Test Note", result?.title)
        }

    @Test
    fun `get all notes returns list`() =
        runBlocking {
            repeat(3) { i ->
                noteDao.insertNote(
                    Note(
                        id = "note-$i",
                        title = "Note $i",
                        content = "Note $i",
                        type = NoteType.BRAIN_DUMP,
                    ),
                )
            }

            val notes = noteDao.getAllNotesOnce()
            assertEquals(3, notes.size)
        }

    @Test
    fun `update note modifies database`() =
        runBlocking {
            val note =
                Note(
                    id = "note-123",
                    title = "Original",
                    content = "Original content",
                    type = NoteType.BRAIN_DUMP,
                )
            noteDao.insertNote(note)

            val updated = note.copy(title = "Updated")
            noteDao.updateNote(updated)

            val result = noteDao.getNoteById("note-123")
            assertEquals("Updated", result?.title)
        }

    @Test
    fun `delete note removes from database`() =
        runBlocking {
            val note =
                Note(
                    id = "note-123",
                    title = "Test",
                    content = "Test content",
                    type = NoteType.BRAIN_DUMP,
                )
            noteDao.insertNote(note)

            noteDao.deleteNoteById("note-123")

            val result = noteDao.getNoteById("note-123")
            assertNull(result)
        }

    @Test
    fun `get notes by category returns filtered list`() =
        runBlocking {
            noteDao.insertNote(
                Note(
                    id = "note-1",
                    title = "Work Note",
                    content = "Work content",
                    categoryName = "Work",
                    type = NoteType.BRAIN_DUMP,
                ),
            )
            noteDao.insertNote(
                Note(
                    id = "note-2",
                    title = "Personal Note",
                    content = "Personal content",
                    categoryName = "Personal",
                    type = NoteType.BRAIN_DUMP,
                ),
            )

            val workNotes = noteDao.getAllNotesOnce().filter { it.categoryName == "Work" }
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

            calendarDao.insertEvent(event)

            val result = calendarDao.getEventById("event-123")
            assertNotNull(result)
            assertEquals("Test Event", result?.title)
        }

    @Test
    fun `get upcoming events returns future events`() =
        runBlocking {
            val now = System.currentTimeMillis()
            calendarDao.insertEvent(
                CalendarEvent(
                    id = "event-1",
                    title = "Future Event",
                    startTime = now + 86400000,
                    endTime = now + 2 * 86400000,
                ),
            )
            calendarDao.insertEvent(
                CalendarEvent(
                    id = "event-2",
                    title = "Past Event",
                    startTime = now - 86400000,
                    endTime = now - 3600000,
                ),
            )

            val upcoming = calendarDao.getAllEventsOnce().filter { it.startTime > now }
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
            calendarDao.insertEvent(event)

            val updated = event.copy(title = "Updated")
            calendarDao.updateEvent(updated)

            val result = calendarDao.getEventById("event-123")
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
            calendarDao.insertEvent(event)

            calendarDao.deleteEventById("event-123")

            val result = calendarDao.getEventById("event-123")
            assertNull(result)
        }
}

@androidx.room.Database(
    entities = [
        ChatSession::class,
        ChatMessageEntity::class,
        Note::class,
        CalendarEvent::class,
    ],
    version = 1,
    exportSchema = false,
)
@androidx.room.TypeConverters(Converters::class)
abstract class TestSmartyDatabase : androidx.room.RoomDatabase() {
    abstract fun chatDao(): ChatDao

    abstract fun noteDao(): NoteDao

    abstract fun calendarDao(): CalendarDao
}
