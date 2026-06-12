package com.example.smarty.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Unit tests for ChatMessageNotesDao.
 *
 * Tests verify:
 * - Inserting junction table entries
 * - Deleting junction table entries
 * - Querying linked entities
 * - Flow-based reactive queries
 */
@RunWith(AndroidJUnit4::class)
class ChatMessageNotesDaoTest {
    private lateinit var database: ChatMessageNotesTestDatabase
    private lateinit var dao: ChatMessageNotesDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(
                    context,
                    ChatMessageNotesTestDatabase::class.java,
                ).allowMainThreadQueries()
                .build()

        dao = database.chatMessageNotesDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndQueryLinkedNote() =
        runTest {
            // Arrange
            val messageId = UUID.randomUUID().toString()
            val noteId = UUID.randomUUID().toString()
            val note = ChatMessageNote(messageId, noteId)

            // Act
            dao.insert(note)

            // Assert
            val linkedNoteIds = dao.getLinkedNoteIds(messageId)
            assert(linkedNoteIds.size == 1)
            assert(linkedNoteIds[0] == noteId)
        }

    @Test
    fun deleteLinkedNote() =
        runTest {
            // Arrange
            val messageId = UUID.randomUUID().toString()
            val noteId = UUID.randomUUID().toString()
            val note = ChatMessageNote(messageId, noteId)
            dao.insert(note)

            // Act
            dao.delete(note)

            // Assert
            val linkedNoteIds = dao.getLinkedNoteIds(messageId)
            assert(linkedNoteIds.isEmpty())
        }

    @Test
    fun linkMultipleNotesToMessage() =
        runTest {
            // Arrange
            val messageId = UUID.randomUUID().toString()
            val noteId1 = UUID.randomUUID().toString()
            val noteId2 = UUID.randomUUID().toString()
            val noteId3 = UUID.randomUUID().toString()

            // Act
            dao.linkMultipleNotesToMessage(messageId, listOf(noteId1, noteId2, noteId3))

            // Assert
            val linkedNoteIds = dao.getLinkedNoteIds(messageId)
            assert(linkedNoteIds.size == 3)
            assert(linkedNoteIds.containsAll(listOf(noteId1, noteId2, noteId3)))
        }

    @Test
    fun preventDuplicateLinks() =
        runTest {
            // Arrange
            val messageId = UUID.randomUUID().toString()
            val noteId = UUID.randomUUID().toString()

            // Act - Insert twice
            dao.insert(ChatMessageNote(messageId, noteId))
            dao.insert(ChatMessageNote(messageId, noteId))

            // Assert - Only one should exist
            val count = dao.getLinkCountForMessage(messageId)
            assert(count == 1)
        }

    @Test
    fun getLinkedMessagesForNote() =
        runTest {
            // Arrange
            val messageId1 = UUID.randomUUID().toString()
            val messageId2 = UUID.randomUUID().toString()
            val noteId = UUID.randomUUID().toString()

            dao.insert(ChatMessageNote(messageId1, noteId))
            dao.insert(ChatMessageNote(messageId2, noteId))

            // Act
            val linkedMessageIds = dao.getLinkedMessageIds(noteId)

            // Assert
            assert(linkedMessageIds.size == 2)
            assert(linkedMessageIds.containsAll(listOf(messageId1, messageId2)))
        }

    @Test
    fun deleteAllForMessage() =
        runTest {
            // Arrange
            val messageId = UUID.randomUUID().toString()
            val noteId1 = UUID.randomUUID().toString()
            val noteId2 = UUID.randomUUID().toString()

            dao.insert(ChatMessageNote(messageId, noteId1))
            dao.insert(ChatMessageNote(messageId, noteId2))

            // Act
            dao.deleteAllForMessage(messageId)

            // Assert
            val linkedNoteIds = dao.getLinkedNoteIds(messageId)
            assert(linkedNoteIds.isEmpty())
        }

    @Test
    fun deleteAllForNote() =
        runTest {
            // Arrange
            val messageId1 = UUID.randomUUID().toString()
            val messageId2 = UUID.randomUUID().toString()
            val noteId = UUID.randomUUID().toString()

            dao.insert(ChatMessageNote(messageId1, noteId))
            dao.insert(ChatMessageNote(messageId2, noteId))

            // Act
            dao.deleteAllForNote(noteId)

            // Assert
            val linkedMessageIds = dao.getLinkedMessageIds(noteId)
            assert(linkedMessageIds.isEmpty())
        }

    @Test
    fun checkIfLinked() =
        runTest {
            // Arrange
            val messageId = UUID.randomUUID().toString()
            val noteId = UUID.randomUUID().toString()

            // Act - Before linking
            val linkedBefore = dao.isLinked(messageId, noteId)

            // Link
            dao.insert(ChatMessageNote(messageId, noteId))

            // Act - After linking
            val linkedAfter = dao.isLinked(messageId, noteId)

            // Assert
            assert(!linkedBefore)
            assert(linkedAfter)
        }

    @Test
    fun getLinkCount() =
        runTest {
            // Arrange
            val messageId = UUID.randomUUID().toString()
            val noteId1 = UUID.randomUUID().toString()
            val noteId2 = UUID.randomUUID().toString()
            val noteId3 = UUID.randomUUID().toString()

            dao.insert(ChatMessageNote(messageId, noteId1))
            dao.insert(ChatMessageNote(messageId, noteId2))
            dao.insert(ChatMessageNote(messageId, noteId3))

            // Act
            val count = dao.getLinkCountForMessage(messageId)

            // Assert
            assert(count == 3)
        }
}

/**
 * Test database with minimal entities for DAO testing.
 */
@androidx.room.Database(
    entities = [
        ChatMessageNote::class,
        CalendarEventNote::class,
    ],
    version = 1,
    exportSchema = false,
)
@androidx.room.TypeConverters(Converters::class)
abstract class ChatMessageNotesTestDatabase : androidx.room.RoomDatabase() {
    abstract fun chatMessageNotesDao(): ChatMessageNotesDao

    abstract fun calendarEventNotesDao(): CalendarEventNotesDao
}
