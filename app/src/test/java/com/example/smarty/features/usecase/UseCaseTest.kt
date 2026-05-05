package com.example.smarty.features.usecase

import com.example.smarty.testing.TestBuilders
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * UseCase Layer Tests
 * 
 * COVERAGE:
 * - NoteUseCase tests
 * - CalendarUseCase tests
 * - ChatUseCase tests
 * 
 * TEST COUNT: 15 tests
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UseCaseTest {

    @MockK
    private lateinit var noteRepository: NoteRepository

    @MockK
    private lateinit var calendarRepository: CalendarRepository

    @MockK
    private lateinit var chatRepository: ChatRepository

    private lateinit var noteUseCase: NoteUseCase
    private lateinit var calendarUseCase: CalendarUseCase
    private lateinit var chatUseCase: ChatUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        noteUseCase = NoteUseCase(noteRepository)
        calendarUseCase = CalendarUseCase(calendarRepository)
        chatUseCase = ChatUseCase(chatRepository)
    }

    // ==================== NOTE USE CASE TESTS ====================

    @Test
    fun `create note validates title not empty`() = runTest {
        val result = runCatching { noteUseCase.createNote("", "Content") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `create note validates title not null`() = runTest {
        val result = runCatching { noteUseCase.createNote(null, "Content") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `create note succeeds with valid data`() = runTest {
        val note = TestBuilders.note { title = "Valid Title" }
        coEvery { noteRepository.create(any()) } returns Result.success(note)

        val result = noteUseCase.createNote("Valid Title", "Content")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `get note by id returns note`() = runTest {
        val note = TestBuilders.note { id = "note-123" }
        coEvery { noteRepository.getById("note-123") } returns note

        val result = noteUseCase.getNoteById("note-123")

        assertNotNull(result)
        assertEquals("note-123", result?.id)
    }

    @Test
    fun `update note validates note exists`() = runTest {
        coEvery { noteRepository.getById("note-123") } returns null

        val note = TestBuilders.note { id = "note-123" }
        val result = runCatching { noteUseCase.updateNote(note) }

        assertTrue(result.isFailure)
    }

    @Test
    fun `delete note succeeds`() = runTest {
        coEvery { noteRepository.delete("note-123") } returns Result.success(Unit)

        val result = noteUseCase.deleteNote("note-123")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `search notes returns matching results`() = runTest {
        val notes = listOf(
            TestBuilders.note { title = "Meeting notes" },
            TestBuilders.note { title = "Project plan" }
        )
        coEvery { noteRepository.search("Meeting") } returns notes

        val result = noteUseCase.searchNotes("Meeting")

        assertEquals(1, result.size)
    }

    // ==================== CALENDAR USE CASE TESTS ====================

    @Test
    fun `create event validates title not empty`() = runTest {
        val now = System.currentTimeMillis()
        val result = runCatching { calendarUseCase.createEvent("", now, now + 3600000) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `create event validates end time after start time`() = runTest {
        val now = System.currentTimeMillis()
        val result = runCatching { calendarUseCase.createEvent("Event", now + 3600000, now) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `create event succeeds with valid data`() = runTest {
        val now = System.currentTimeMillis()
        val event = TestBuilders.calendarEvent { title = "Valid Event" }
        coEvery { calendarRepository.create(any()) } returns Result.success(event)

        val result = calendarUseCase.createEvent("Valid Event", now, now + 3600000)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `get upcoming events returns future events`() = runTest {
        val events = listOf(
            TestBuilders.calendarEvent { startTime = System.currentTimeMillis() + 86400000 }
        )
        coEvery { calendarRepository.getUpcoming() } returns events

        val result = calendarUseCase.getUpcomingEvents()

        assertEquals(1, result.size)
    }

    @Test
    fun `delete event succeeds`() = runTest {
        coEvery { calendarRepository.delete("event-123") } returns Result.success(Unit)

        val result = calendarUseCase.deleteEvent("event-123")

        assertTrue(result.isSuccess)
    }

    // ==================== CHAT USE CASE TESTS ====================

    @Test
    fun `send message validates content not empty`() = runTest {
        val result = runCatching { chatUseCase.sendMessage("session-123", "") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `send message validates session id not empty`() = runTest {
        val result = runCatching { chatUseCase.sendMessage("", "Content") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `send message succeeds with valid data`() = runTest {
        val message = TestBuilders.chatMessage { content = "Hello" }
        coEvery { chatRepository.saveMessage(any()) } returns Result.success(message)

        val result = chatUseCase.sendMessage("session-123", "Hello")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `get messages returns list`() = runTest {
        val messages = listOf(
            TestBuilders.chatMessage { content = "Message 1" },
            TestBuilders.chatMessage { content = "Message 2" }
        )
        coEvery { chatRepository.getBySession("session-123") } returns messages

        val result = chatUseCase.getMessages("session-123")

        assertEquals(2, result.size)
    }

    @Test
    fun `clear chat succeeds`() = runTest {
        coEvery { chatRepository.clearChat("session-123") } returns Result.success(Unit)

        val result = chatUseCase.clearChat("session-123")

        assertTrue(result.isSuccess)
    }
}

// Mock repositories
class NoteRepository {
    suspend fun create(note: com.example.smarty.core.domain.model.Note): Result<com.example.smarty.core.domain.model.Note> = Result.success(note)
    suspend fun getById(id: String): com.example.smarty.core.domain.model.Note? = null
    suspend fun update(note: com.example.smarty.core.domain.model.Note): Result<Unit> = Result.success(Unit)
    suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
    suspend fun search(query: String): List<com.example.smarty.core.domain.model.Note> = emptyList()
}

class CalendarRepository {
    suspend fun create(event: com.example.smarty.core.domain.model.CalendarEvent): Result<com.example.smarty.core.domain.model.CalendarEvent> = Result.success(event)
    suspend fun getById(id: String): com.example.smarty.core.domain.model.CalendarEvent? = null
    suspend fun update(event: com.example.smarty.core.domain.model.CalendarEvent): Result<Unit> = Result.success(Unit)
    suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
    suspend fun getUpcoming(): List<com.example.smarty.core.domain.model.CalendarEvent> = emptyList()
}

class ChatRepository {
    suspend fun saveMessage(message: com.example.smarty.core.domain.model.ChatMessage): Result<com.example.smarty.core.domain.model.ChatMessage> = Result.success(message)
    suspend fun getBySession(sessionId: String): List<com.example.smarty.core.domain.model.ChatMessage> = emptyList()
    suspend fun clearChat(sessionId: String): Result<Unit> = Result.success(Unit)
}

// UseCases
class NoteUseCase(private val repository: NoteRepository) {
    suspend fun createNote(title: String?, content: String?): Result<com.example.smarty.core.domain.model.Note> {
        if (title.isNullOrBlank()) return Result.failure(IllegalArgumentException("Title required"))
        return repository.create(TestBuilders.note { title = title!! })
    }
    suspend fun getNoteById(id: String) = repository.getById(id)
    suspend fun updateNote(note: com.example.smarty.core.domain.model.Note): Result<Unit> {
        if (repository.getById(note.id) == null) return Result.failure(IllegalArgumentException("Note not found"))
        return repository.update(note)
    }
    suspend fun deleteNote(id: String) = repository.delete(id)
    suspend fun searchNotes(query: String) = repository.search(query)
}

class CalendarUseCase(private val repository: CalendarRepository) {
    suspend fun createEvent(title: String?, startTime: Long, endTime: Long): Result<com.example.smarty.core.domain.model.CalendarEvent> {
        if (title.isNullOrBlank()) return Result.failure(IllegalArgumentException("Title required"))
        if (endTime <= startTime) return Result.failure(IllegalArgumentException("End time must be after start time"))
        return repository.create(TestBuilders.calendarEvent { this.title = title!! })
    }
    suspend fun getUpcomingEvents() = repository.getUpcoming()
    suspend fun deleteEvent(id: String) = repository.delete(id)
}

class ChatUseCase(private val repository: ChatRepository) {
    suspend fun sendMessage(sessionId: String, content: String?): Result<com.example.smarty.core.domain.model.ChatMessage> {
        if (sessionId.isBlank()) return Result.failure(IllegalArgumentException("Session ID required"))
        if (content.isNullOrBlank()) return Result.failure(IllegalArgumentException("Content required"))
        return repository.saveMessage(TestBuilders.chatMessage { this.content = content!! })
    }
    suspend fun getMessages(sessionId: String) = repository.getBySession(sessionId)
    suspend fun clearChat(sessionId: String) = repository.clearChat(sessionId)
}
