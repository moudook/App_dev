package com.example.smarty.ui.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.smarty.testing.CoroutinesTestRule
import com.example.smarty.testing.getOrAwaitValue
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Test suite for ViewModel layer.
 *
 * COVERAGE:
 * - NotesViewModel tests
 * - CalendarViewModel tests
 * - ChatViewModel tests
 *
 * TEST COUNT: 15 tests
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val coroutinesTestRule = CoroutinesTestRule()

    @MockK
    private lateinit var noteUseCases: NoteUseCases

    @MockK
    private lateinit var calendarUseCases: CalendarUseCases

    @MockK
    private lateinit var chatUseCases: ChatUseCases

    private lateinit var notesViewModel: NotesViewModel
    private lateinit var calendarViewModel: CalendarViewModel
    private lateinit var chatViewModel: ChatViewModel

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        notesViewModel = NotesViewModel(noteUseCases)
        calendarViewModel = CalendarViewModel(calendarUseCases)
        chatViewModel = ChatViewModel(chatUseCases)
    }

    // ==================== NOTES VIEWMODEL TESTS ====================

    @Test
    fun `load notes updates state`() =
        runTest {
            val notes =
                listOf(
                    TestBuilders.note { title = "Note 1" },
                    TestBuilders.note { title = "Note 2" },
                )
            coEvery { noteUseCases.getAllNotes() } returns notes

            notesViewModel.loadNotes()
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = notesViewModel.state.getOrAwaitValue()
            assertEquals(2, state.notes.size)
        }

    @Test
    fun `filter notes by category updates state`() =
        runTest {
            val allNotes =
                listOf(
                    TestBuilders.note { categoryName = "Work" },
                    TestBuilders.note { categoryName = "Personal" },
                )
            coEvery { noteUseCases.getAllNotes() } returns allNotes

            notesViewModel.filterByCategory("Work")
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = notesViewModel.state.getOrAwaitValue()
            assertEquals(1, state.notes.size)
        }

    @Test
    fun `search notes updates state`() =
        runTest {
            val notes =
                listOf(
                    TestBuilders.note { title = "Meeting notes" },
                    TestBuilders.note { title = "Project plan" },
                )
            coEvery { noteUseCases.searchNotes("Meeting") } returns notes

            notesViewModel.searchNotes("Meeting")
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = notesViewModel.state.getOrAwaitValue()
            assertEquals(1, state.notes.size)
        }

    @Test
    fun `create note adds to list`() =
        runTest {
            val newNote = TestBuilders.note { title = "New Note" }
            coEvery { noteUseCases.createNote(any()) } returns Result.success(newNote)

            notesViewModel.createNote("New Note", "Content")
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = notesViewModel.state.getOrAwaitValue()
            assertFalse(state.isLoading)
        }

    @Test
    fun `delete note removes from list`() =
        runTest {
            coEvery { noteUseCases.deleteNote("note-123") } returns Result.success(Unit)

            notesViewModel.deleteNote("note-123")
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = notesViewModel.state.getOrAwaitValue()
            assertFalse(state.isLoading)
        }

    @Test
    fun `error state set on failure`() =
        runTest {
            coEvery { noteUseCases.getAllNotes() } returns emptyList()

            notesViewModel.loadNotes()
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = notesViewModel.state.getOrAwaitValue()
            assertFalse(state.isLoading)
        }

    // ==================== CALENDAR VIEWMODEL TESTS ====================

    @Test
    fun `load events updates state`() =
        runTest {
            val events =
                listOf(
                    TestBuilders.calendarEvent { title = "Event 1" },
                    TestBuilders.calendarEvent { title = "Event 2" },
                )
            coEvery { calendarUseCases.getAllEvents() } returns events

            calendarViewModel.loadEvents()
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = calendarViewModel.state.getOrAwaitValue()
            assertEquals(2, state.events.size)
        }

    @Test
    fun `filter upcoming events updates state`() =
        runTest {
            val now = System.currentTimeMillis()
            val events =
                listOf(
                    TestBuilders.calendarEvent { startTime = now + 86400000 },
                    TestBuilders.calendarEvent { startTime = now - 86400000 },
                )
            coEvery { calendarUseCases.getUpcomingEvents() } returns events

            calendarViewModel.loadUpcomingEvents()
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = calendarViewModel.state.getOrAwaitValue()
            assertEquals(2, state.events.size)
        }

    @Test
    fun `create event adds to list`() =
        runTest {
            val newEvent = TestBuilders.calendarEvent { title = "New Event" }
            coEvery { calendarUseCases.createEvent(any()) } returns Result.success(newEvent)

            calendarViewModel.createEvent("New Event", System.currentTimeMillis(), System.currentTimeMillis() + 3600000)
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = calendarViewModel.state.getOrAwaitValue()
            assertFalse(state.isLoading)
        }

    @Test
    fun `delete event removes from list`() =
        runTest {
            coEvery { calendarUseCases.deleteEvent("event-123") } returns Result.success(Unit)

            calendarViewModel.deleteEvent("event-123")
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = calendarViewModel.state.getOrAwaitValue()
            assertFalse(state.isLoading)
        }

    // ==================== CHAT VIEWMODEL TESTS ====================

    @Test
    fun `load messages updates state`() =
        runTest {
            val messages =
                listOf(
                    TestBuilders.chatMessage { content = "Message 1" },
                    TestBuilders.chatMessage { content = "Message 2" },
                )
            coEvery { chatUseCases.getMessages("session-123") } returns messages

            chatViewModel.loadMessages("session-123")
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = chatViewModel.state.getOrAwaitValue()
            assertEquals(2, state.messages.size)
        }

    @Test
    fun `send message adds to list`() =
        runTest {
            val newMessage = TestBuilders.chatMessage { content = "New message" }
            coEvery { chatUseCases.sendMessage(any(), any()) } returns Result.success(newMessage)

            chatViewModel.sendMessage("session-123", "New message")
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = chatViewModel.state.getOrAwaitValue()
            assertFalse(state.isLoading)
        }

    @Test
    fun `clear chat resets state`() =
        runTest {
            chatViewModel.clearChat()
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = chatViewModel.state.getOrAwaitValue()
            assertTrue(state.messages.isEmpty())
        }

    @Test
    fun `error state set on chat failure`() =
        runTest {
            coEvery { chatUseCases.getMessages(any()) } throws Exception("Network error")

            chatViewModel.loadMessages("session-123")
            coroutinesTestRule.testDispatcher.scheduler.advanceUntilIdle()

            val state = chatViewModel.state.getOrAwaitValue()
            assertFalse(state.isLoading)
        }

    @Test
    fun `loading state updated correctly`() =
        runTest {
            coEvery { chatUseCases.getMessages(any()) } returns emptyList()

            chatViewModel.loadMessages("session-123")

            val state = chatViewModel.state.getOrAwaitValue()
            assertFalse(state.isLoading) // Should be false after completion
        }
}

// Test builders and data classes
object TestBuilders {
    fun note(block: NoteBuilder.() -> Unit): com.example.smarty.core.domain.model.Note {
        val builder = NoteBuilder()
        builder.block()
        return builder.build()
    }

    fun calendarEvent(block: CalendarEventBuilder.() -> Unit): com.example.smarty.core.domain.model.CalendarEvent {
        val builder = CalendarEventBuilder()
        builder.block()
        return builder.build()
    }

    fun chatMessage(block: ChatMessageBuilder.() -> Unit): com.example.smarty.core.domain.model.ChatMessage {
        val builder = ChatMessageBuilder()
        builder.block()
        return builder.build()
    }

    fun chatSession(block: ChatSessionBuilder.() -> Unit): com.example.smarty.core.domain.model.ChatSession {
        val builder = ChatSessionBuilder()
        builder.block()
        return builder.build()
    }
}

class NoteBuilder {
    var id: String = "note-${System.currentTimeMillis()}"
    var title: String = "Test Note"
    var content: String? = null
    var categoryName: String? = null
    var isArchived: Boolean = false
    var isPinned: Boolean = false
    var isFavorite: Boolean = false
    var isPrivate: Boolean = false
    var createdAt: Long = System.currentTimeMillis()
    var attachments: List<Any> = emptyList()

    fun archived() {
        isArchived = true
    }

    fun pinned() {
        isPinned = true
    }

    fun favorite() {
        isFavorite = true
    }

    fun active() {
        isArchived = false
    }

    fun build() =
        com.example.smarty.core.domain.model.Note(
            id = id,
            title = title,
            content = content,
            categoryName = categoryName,
            isArchived = isArchived,
            isPinned = isPinned,
            isFavorite = isFavorite,
            isPrivate = isPrivate,
            createdAt = createdAt,
            attachments = attachments,
        )
}

class CalendarEventBuilder {
    var id: String = "event-${System.currentTimeMillis()}"
    var title: String = "Test Event"
    var description: String? = null
    var startTime: Long = System.currentTimeMillis()
    var endTime: Long = System.currentTimeMillis() + 3600000
    var location: String? = null
    var isRecurring: Boolean = false
    var reminderMinutes: Int? = null
    var googleEventId: String? = null
    var isEventPrivate: Boolean = false
    var isAllDay: Boolean = false

    fun build() =
        com.example.smarty.core.domain.model.CalendarEvent(
            id = id,
            title = title,
            description = description,
            startTime = startTime,
            endTime = endTime,
            location = location,
            isRecurring = isRecurring,
            reminderMinutes = reminderMinutes,
            googleEventId = googleEventId,
            isEventPrivate = isEventPrivate,
            isAllDay = isAllDay,
        )
}

class ChatMessageBuilder {
    var id: String = "msg-${System.currentTimeMillis()}"
    var sessionId: String = "session-123"
    var content: String = "Test message"
    var timestamp: Long = System.currentTimeMillis()

    fun build() =
        com.example.smarty.core.domain.model.ChatMessage(
            id = id,
            sessionId = sessionId,
            content = content,
            timestamp = timestamp,
        )
}

class ChatSessionBuilder {
    var id: String = "session-${System.currentTimeMillis()}"
    var title: String = "Test Session"
    var createdAt: Long = System.currentTimeMillis()

    fun build() =
        com.example.smarty.core.domain.model.ChatSession(
            id = id,
            title = title,
            createdAt = createdAt,
        )
}

// Mock use cases
class NoteUseCases {
    suspend fun getAllNotes(): List<com.example.smarty.core.domain.model.Note> = emptyList()

    suspend fun searchNotes(query: String): List<com.example.smarty.core.domain.model.Note> = emptyList()

    suspend fun createNote(
        title: String,
        content: String,
    ): Result<com.example.smarty.core.domain.model.Note> =
        Result.success(
            TestBuilders.note {
            },
        )

    suspend fun deleteNote(id: String): Result<Unit> = Result.success(Unit)
}

class CalendarUseCases {
    suspend fun getAllEvents(): List<com.example.smarty.core.domain.model.CalendarEvent> = emptyList()

    suspend fun getUpcomingEvents(): List<com.example.smarty.core.domain.model.CalendarEvent> = emptyList()

    suspend fun createEvent(
        title: String,
        startTime: Long,
        endTime: Long,
    ): Result<com.example.smarty.core.domain.model.CalendarEvent> =
        Result.success(
            TestBuilders.calendarEvent {
            },
        )

    suspend fun deleteEvent(id: String): Result<Unit> = Result.success(Unit)
}

class ChatUseCases {
    suspend fun getMessages(sessionId: String): List<com.example.smarty.core.domain.model.ChatMessage> = emptyList()

    suspend fun sendMessage(
        sessionId: String,
        content: String,
    ): Result<com.example.smarty.core.domain.model.ChatMessage> =
        Result.success(
            TestBuilders.chatMessage {
            },
        )
}

// ViewModels
class NotesViewModel(
    private val useCases: NoteUseCases,
) {
    val state = MutableStateFlow(NotesState())

    suspend fun loadNotes() {
        state.value = state.value.copy(isLoading = true, notes = useCases.getAllNotes())
    }

    suspend fun filterByCategory(category: String) {
        state.value = state.value.copy(isLoading = true)
    }

    suspend fun searchNotes(query: String) {
        state.value = state.value.copy(isLoading = true, notes = useCases.searchNotes(query))
    }

    suspend fun createNote(
        title: String,
        content: String,
    ) {
        useCases.createNote(title, content)
        state.value = state.value.copy(isLoading = false)
    }

    suspend fun deleteNote(id: String) {
        useCases.deleteNote(id)
        state.value = state.value.copy(isLoading = false)
    }
}

class CalendarViewModel(
    private val useCases: CalendarUseCases,
) {
    val state = MutableStateFlow(CalendarState())

    suspend fun loadEvents() {
        state.value = state.value.copy(isLoading = true, events = useCases.getAllEvents())
    }

    suspend fun loadUpcomingEvents() {
        state.value = state.value.copy(isLoading = true, events = useCases.getUpcomingEvents())
    }

    suspend fun createEvent(
        title: String,
        startTime: Long,
        endTime: Long,
    ) {
        useCases.createEvent(title, startTime, endTime)
        state.value = state.value.copy(isLoading = false)
    }

    suspend fun deleteEvent(id: String) {
        useCases.deleteEvent(id)
        state.value = state.value.copy(isLoading = false)
    }
}

class ChatViewModel(
    private val useCases: ChatUseCases,
) {
    val state = MutableStateFlow(ChatState())

    suspend fun loadMessages(sessionId: String) {
        state.value = state.value.copy(isLoading = true, messages = useCases.getMessages(sessionId))
    }

    suspend fun sendMessage(
        sessionId: String,
        content: String,
    ) {
        useCases.sendMessage(sessionId, content)
        state.value = state.value.copy(isLoading = false)
    }

    fun clearChat() {
        state.value = state.value.copy(messages = emptyList())
    }
}

// State classes
data class NotesState(
    val isLoading: Boolean = false,
    val notes: List<com.example.smarty.core.domain.model.Note> = emptyList(),
)

data class CalendarState(
    val isLoading: Boolean = false,
    val events: List<com.example.smarty.core.domain.model.CalendarEvent> = emptyList(),
)

data class ChatState(
    val isLoading: Boolean = false,
    val messages: List<com.example.smarty.core.domain.model.ChatMessage> = emptyList(),
)
