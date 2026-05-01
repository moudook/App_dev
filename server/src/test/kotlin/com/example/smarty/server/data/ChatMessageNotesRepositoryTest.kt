package com.example.smarty.server.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * Unit tests for ChatMessageNotesRepository.
 *
 * Tests verify:
 * - Linking messages to notes
 * - Unlinking messages from notes
 * - Querying linked entities
 * - Cascade delete behavior
 * - Duplicate prevention
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatMessageNotesRepositoryTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: ChatMessageNotesRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var noteRepository: NoteRepository

    @BeforeAll
    fun setup() {
        // Create in-memory test database (H2)
        val config =
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
                driverClassName = "org.h2.Driver"
                username = "sa"
                password = ""
                maximumPoolSize = 2
            }
        dataSource = HikariDataSource(config)

        // Create test tables
        createTestTables()

        // Initialize repositories
        val chatMessageNotesRepo = ChatMessageNotesRepository(dataSource)
        val calendarEventNotesRepo = CalendarEventNotesRepository(dataSource)
        val notesRepo = NoteRepository(dataSource, chatMessageNotesRepo, calendarEventNotesRepo)
        noteRepository = notesRepo
        chatRepository = ChatRepository(dataSource, chatMessageNotesRepo)
        repository = chatMessageNotesRepo
    }

    @AfterAll
    fun teardown() {
        dataSource.close()
    }

    @BeforeEach
    fun clearTables() {
        dataSource.connection.use { conn ->
            conn.createStatement().executeUpdate("DELETE FROM chat_message_notes")
            conn.createStatement().executeUpdate("DELETE FROM chat_messages")
            conn.createStatement().executeUpdate("DELETE FROM notes")
            conn.createStatement().executeUpdate("DELETE FROM chat_sessions")
            conn.createStatement().executeUpdate("DELETE FROM users")
        }
    }

    @Test
    @DisplayName("Should link message to note successfully")
    fun testLinkMessageToNote() =
        runTest {
            // Arrange
            val userId = "test-user-123"
            val messageId = createTestMessage(userId)
            val noteId = createTestNote(userId)

            // Act
            repository.linkMessageToNote(messageId, noteId)

            // Assert
            val linkedNotes = repository.getLinkedNotes(messageId)
            assertEquals(1, linkedNotes.size)
            assertEquals(noteId, linkedNotes[0])
        }

    @Test
    @DisplayName("Should prevent duplicate links")
    fun testPreventDuplicateLinks() =
        runTest {
            // Arrange
            val userId = "test-user-123"
            val messageId = createTestMessage(userId)
            val noteId = createTestNote(userId)

            // Act - Link twice
            repository.linkMessageToNote(messageId, noteId)
            repository.linkMessageToNote(messageId, noteId) // Should be ignored

            // Assert - Only one link should exist
            val count = repository.getLinkCountForMessage(messageId)
            assertEquals(1, count)
        }

    @Test
    @DisplayName("Should unlink message from note")
    fun testUnlinkMessageFromNote() =
        runTest {
            // Arrange
            val userId = "test-user-123"
            val messageId = createTestMessage(userId)
            val noteId = createTestNote(userId)
            repository.linkMessageToNote(messageId, noteId)

            // Act
            val success = repository.unlinkMessageFromNote(messageId, noteId)

            // Assert
            assertTrue(success)
            val linkedNotes = repository.getLinkedNotes(messageId)
            assertTrue(linkedNotes.isEmpty())
        }

    @Test
    @DisplayName("Should return empty list when no links exist")
    fun testGetLinkedNotesEmpty() =
        runTest {
            // Arrange
            val messageId = UUID.randomUUID()

            // Act
            val linkedNotes = repository.getLinkedNotes(messageId)

            // Assert
            assertTrue(linkedNotes.isEmpty())
        }

    @Test
    @DisplayName("Should link multiple notes to message")
    fun testLinkMultipleNotes() =
        runTest {
            // Arrange
            val userId = "test-user-123"
            val messageId = createTestMessage(userId)
            val noteId1 = createTestNote(userId)
            val noteId2 = createTestNote(userId)
            val noteId3 = createTestNote(userId)

            // Act
            repository.linkMultipleNotesToMessage(messageId, listOf(noteId1, noteId2, noteId3))

            // Assert
            val linkedNotes = repository.getLinkedNotes(messageId)
            assertEquals(3, linkedNotes.size)
            assertTrue(linkedNotes.containsAll(listOf(noteId1, noteId2, noteId3)))
        }

    @Test
    @DisplayName("Should get messages linked to note")
    fun testGetLinkedMessages() =
        runTest {
            // Arrange
            val userId = "test-user-123"
            val messageId1 = createTestMessage(userId)
            val messageId2 = createTestMessage(userId)
            val noteId = createTestNote(userId)

            repository.linkMessageToNote(messageId1, noteId)
            repository.linkMessageToNote(messageId2, noteId)

            // Act
            val linkedMessages = repository.getLinkedMessages(noteId)

            // Assert
            assertEquals(2, linkedMessages.size)
            assertTrue(linkedMessages.containsAll(listOf(messageId1, messageId2)))
        }

    @Test
    @DisplayName("Should delete all links for message")
    fun testDeleteAllForMessage() =
        runTest {
            // Arrange
            val userId = "test-user-123"
            val messageId = createTestMessage(userId)
            val noteId1 = createTestNote(userId)
            val noteId2 = createTestNote(userId)

            repository.linkMessageToNote(messageId, noteId1)
            repository.linkMessageToNote(messageId, noteId2)

            // Act
            val deletedCount = repository.deleteAllForMessage(messageId)

            // Assert
            assertEquals(2, deletedCount)
            val linkedNotes = repository.getLinkedNotes(messageId)
            assertTrue(linkedNotes.isEmpty())
        }

    @Test
    @DisplayName("Should delete all links for note")
    fun testDeleteAllForNote() =
        runTest {
            // Arrange
            val userId = "test-user-123"
            val messageId1 = createTestMessage(userId)
            val messageId2 = createTestMessage(userId)
            val noteId = createTestNote(userId)

            repository.linkMessageToNote(messageId1, noteId)
            repository.linkMessageToNote(messageId2, noteId)

            // Act
            val deletedCount = repository.deleteAllForNote(noteId)

            // Assert
            assertEquals(2, deletedCount)
            val linkedMessages = repository.getLinkedMessages(noteId)
            assertTrue(linkedMessages.isEmpty())
        }

    @Test
    @DisplayName("Should check if message is linked to note")
    fun testIsLinked() =
        runTest {
            // Arrange
            val userId = "test-user-123"
            val messageId = createTestMessage(userId)
            val noteId = createTestNote(userId)

            // Act - Before linking
            val linkedBefore = repository.isLinked(messageId, noteId)

            // Link
            repository.linkMessageToNote(messageId, noteId)

            // Act - After linking
            val linkedAfter = repository.isLinked(messageId, noteId)

            // Assert
            assertFalse(linkedBefore)
            assertTrue(linkedAfter)
        }

    // Helper methods

    private fun createTestTables() {
        dataSource.connection.use { conn ->
            conn.createStatement().executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS users (
                    firebase_uid TEXT PRIMARY KEY,
                    created_at TIMESTAMP DEFAULT NOW()
                )
            """,
            )

            conn.createStatement().executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS chat_sessions (
                    id UUID PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT NOW()
                )
            """,
            )

            conn.createStatement().executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS notes (
                    id UUID PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT NOW()
                )
            """,
            )

            conn.createStatement().executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id UUID PRIMARY KEY,
                    session_id UUID NOT NULL,
                    user_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT NOW()
                )
            """,
            )

            conn.createStatement().executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS chat_message_notes (
                    message_id UUID NOT NULL,
                    note_id UUID NOT NULL,
                    PRIMARY KEY (message_id, note_id)
                )
            """,
            )
        }
    }

    private suspend fun createTestMessage(userId: String): UUID {
        val sessionId = UUID.randomUUID()

        dataSource.connection.use { conn ->
            conn.createStatement().executeUpdate(
                "MERGE INTO users (firebase_uid) KEY(firebase_uid) VALUES ('$userId')",
            )

            conn.createStatement().executeUpdate(
                "INSERT INTO chat_sessions (id, user_id) VALUES ('$sessionId', '$userId')",
            )

            val messageId = UUID.randomUUID()
            conn.createStatement().executeUpdate(
                "INSERT INTO chat_messages (id, session_id, user_id, role, content) " +
                    "VALUES ('$messageId', '$sessionId', '$userId', 'USER', 'Test message')",
            )
            return messageId
        }
    }

    private suspend fun createTestNote(userId: String): UUID {
        dataSource.connection.use { conn ->
            conn.createStatement().executeUpdate(
                "MERGE INTO users (firebase_uid) KEY(firebase_uid) VALUES ('$userId')",
            )

            val noteId = UUID.randomUUID()
            conn.createStatement().executeUpdate(
                "INSERT INTO notes (id, user_id, title, content) " +
                    "VALUES ('$noteId', '$userId', 'Test Note', 'Test content')",
            )
            return noteId
        }
    }
}
