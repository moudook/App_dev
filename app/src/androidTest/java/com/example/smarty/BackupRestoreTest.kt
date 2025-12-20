package com.example.smarty

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smarty.data.local.CogniDatabase
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Android instrumented tests for database persistence and backup functionality.
 *
 * These tests verify:
 * - Database operations work correctly
 * - Data persists across operations
 * - Privacy flags are maintained
 * - Categories and notes are properly linked
 *
 * Note: Full backup/restore tests require Google Drive integration
 * which cannot be tested in instrumented tests without authentication.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {

    private lateinit var database: CogniDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Use in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(
            context,
            CogniDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        database.close()
    }

    // =========================================================================
    // DATABASE PERSISTENCE TESTS
    // =========================================================================

    @Test
    fun insertAndRetrieveNote() = runBlocking {
        val note = createTestNote(
            id = "test-note-1",
            title = "Test Note",
            content = "Test content for persistence"
        )

        database.noteDao().insertNote(note)

        val retrieved = database.noteDao().getNoteById("test-note-1")
        assertNotNull("Note should be retrievable after insert", retrieved)
        assertEquals("Test Note", retrieved?.title)
        assertEquals("Test content for persistence", retrieved?.content)
    }

    @Test
    fun insertAndRetrieveCategory() = runBlocking {
        val category = Category(
            id = "cat-1",
            name = "Work",
            colorHex = "#FF0000"
        )

        database.categoryDao().insertCategory(category)

        val retrieved = database.categoryDao().getCategoryById("cat-1")
        assertNotNull("Category should be retrievable", retrieved)
        assertEquals("Work", retrieved?.name)
        assertEquals("#FF0000", retrieved?.colorHex)
    }

    @Test
    fun noteWithCategoryPersists() = runBlocking {
        // Insert category first
        val category = Category(id = "cat-work", name = "Work")
        database.categoryDao().insertCategory(category)

        // Insert note with category reference
        val note = createTestNote(
            id = "note-with-cat",
            title = "Work Note",
            categoryId = "cat-work",
            categoryName = "Work"
        )
        database.noteDao().insertNote(note)

        // Retrieve and verify
        val retrieved = database.noteDao().getNoteById("note-with-cat")
        assertEquals("cat-work", retrieved?.categoryId)
        assertEquals("Work", retrieved?.categoryName)
    }

    // =========================================================================
    // PRIVACY FLAGS PERSISTENCE TESTS
    // =========================================================================

    @Test
    fun privacyFlagsPersist() = runBlocking {
        val privateNote = createTestNote(
            id = "private-note",
            title = "Private",
            isFullPrivacy = true
        )
        database.noteDao().insertNote(privateNote)

        val retrieved = database.noteDao().getNoteById("private-note")
        assertTrue("isFullPrivacy should persist", retrieved?.isFullPrivacy == true)
    }

    @Test
    fun excludeFromAiChatFlagPersists() = runBlocking {
        val excludedNote = createTestNote(
            id = "excluded-note",
            title = "AI Excluded",
            excludeFromAiChat = true
        )
        database.noteDao().insertNote(excludedNote)

        val retrieved = database.noteDao().getNoteById("excluded-note")
        assertTrue("excludeFromAiChat should persist", retrieved?.excludeFromAiChat == true)
    }

    @Test
    fun archivedFlagPersists() = runBlocking {
        val archivedNote = createTestNote(
            id = "archived-note",
            title = "Archived",
            isArchived = true
        )
        database.noteDao().insertNote(archivedNote)

        val retrieved = database.noteDao().getNoteById("archived-note")
        assertTrue("isArchived should persist", retrieved?.isArchived == true)
    }

    // =========================================================================
    // BULK OPERATIONS TESTS
    // =========================================================================

    @Test
    fun multipleNotesInsertAndRetrieve() = runBlocking {
        val notes = (1..10).map { i ->
            createTestNote(
                id = "bulk-note-$i",
                title = "Note $i",
                content = "Content for note $i"
            )
        }

        notes.forEach { database.noteDao().insertNote(it) }

        val allNotes = database.noteDao().getAllNotesOnce()
        assertEquals(10, allNotes.size)
    }

    @Test
    fun deleteAllNotesWorks() = runBlocking {
        // Insert notes
        repeat(5) { i ->
            database.noteDao().insertNote(
                createTestNote(id = "delete-test-$i", title = "To Delete $i")
            )
        }

        // Verify inserted
        assertEquals(5, database.noteDao().getAllNotesOnce().size)

        // Delete all
        database.noteDao().deleteAllNotes()

        // Verify deleted
        assertEquals(0, database.noteDao().getAllNotesOnce().size)
    }

    @Test
    fun deleteAllCategoriesWorks() = runBlocking {
        repeat(3) { i ->
            database.categoryDao().insertCategory(
                Category(id = "del-cat-$i", name = "Category $i")
            )
        }

        assertEquals(3, database.categoryDao().getAllCategoriesOnce().size)

        database.categoryDao().deleteAllCategories()

        assertEquals(0, database.categoryDao().getAllCategoriesOnce().size)
    }

    // =========================================================================
    // UPDATE OPERATIONS TESTS
    // =========================================================================

    @Test
    fun noteUpdatePersists() = runBlocking {
        val note = createTestNote(
            id = "update-test",
            title = "Original Title",
            content = "Original Content"
        )
        database.noteDao().insertNote(note)

        // Update
        val updated = note.copy(
            title = "Updated Title",
            content = "Updated Content"
        )
        database.noteDao().updateNote(updated)

        // Verify
        val retrieved = database.noteDao().getNoteById("update-test")
        assertEquals("Updated Title", retrieved?.title)
        assertEquals("Updated Content", retrieved?.content)
    }

    @Test
    fun privacyFlagCanBeUpdated() = runBlocking {
        val note = createTestNote(
            id = "privacy-update",
            title = "Public Note",
            isFullPrivacy = false
        )
        database.noteDao().insertNote(note)

        // Make it private
        database.noteDao().updateNote(note.copy(isFullPrivacy = true))

        val retrieved = database.noteDao().getNoteById("privacy-update")
        assertTrue("Privacy flag should be updated", retrieved?.isFullPrivacy == true)
    }

    // =========================================================================
    // DATA INTEGRITY TESTS
    // =========================================================================

    @Test
    fun noteTypesPersistCorrectly() = runBlocking {
        val noteTypes = listOf(
            NoteType.BRAIN_DUMP,
            NoteType.LINK,
            NoteType.DOCUMENT,
            NoteType.AUDIO,
            NoteType.CONTACT,
            NoteType.IMAGE,
            NoteType.CALENDAR
        )

        noteTypes.forEachIndexed { index, type ->
            database.noteDao().insertNote(
                createTestNote(
                    id = "type-test-$index",
                    title = "Type $type",
                    type = type
                )
            )
        }

        noteTypes.forEachIndexed { index, type ->
            val retrieved = database.noteDao().getNoteById("type-test-$index")
            assertEquals(type, retrieved?.type)
        }
    }

    @Test
    fun processingStatusPersists() = runBlocking {
        val statuses = listOf(
            ProcessingStatus.PENDING,
            ProcessingStatus.PROCESSING,
            ProcessingStatus.COMPLETED,
            ProcessingStatus.FAILED
        )

        statuses.forEachIndexed { index, status ->
            database.noteDao().insertNote(
                createTestNote(
                    id = "status-test-$index",
                    title = "Status $status",
                    processingStatus = status
                )
            )
        }

        statuses.forEachIndexed { index, status ->
            val retrieved = database.noteDao().getNoteById("status-test-$index")
            assertEquals(status, retrieved?.processingStatus)
        }
    }

    @Test
    fun timestampsPersist() = runBlocking {
        val now = System.currentTimeMillis()
        val note = Note(
            id = "timestamp-test",
            title = "Timestamp Test",
            content = "Content",
            type = NoteType.BRAIN_DUMP,
            processingStatus = ProcessingStatus.COMPLETED,
            createdAt = now,
            updatedAt = now + 1000
        )

        database.noteDao().insertNote(note)

        val retrieved = database.noteDao().getNoteById("timestamp-test")
        assertEquals(now, retrieved?.createdAt)
        assertEquals(now + 1000, retrieved?.updatedAt)
    }

    @Test
    fun summaryAndSuggestionsPersist() = runBlocking {
        val note = createTestNote(
            id = "summary-test",
            title = "Summary Test",
            summary = "This is a summary",
            whySaved = "Because it's important"
        )

        database.noteDao().insertNote(note)

        val retrieved = database.noteDao().getNoteById("summary-test")
        assertEquals("This is a summary", retrieved?.summary)
        assertEquals("Because it's important", retrieved?.whySaved)
    }

    @Test
    fun aiCreatedFlagPersists() = runBlocking {
        val aiNote = createTestNote(
            id = "ai-created",
            title = "AI Created Note",
            isAiCreated = true
        )

        database.noteDao().insertNote(aiNote)

        val retrieved = database.noteDao().getNoteById("ai-created")
        assertTrue("isAiCreated should persist", retrieved?.isAiCreated == true)
    }

    // =========================================================================
    // RESTORE SIMULATION TESTS
    // =========================================================================

    @Test
    fun clearAndRestoreSimulation() = runBlocking {
        // 1. Create initial data
        val originalNotes = (1..5).map { i ->
            createTestNote(id = "original-$i", title = "Original $i")
        }
        val originalCategories = listOf(
            Category(id = "orig-cat-1", name = "Work"),
            Category(id = "orig-cat-2", name = "Personal")
        )

        originalNotes.forEach { database.noteDao().insertNote(it) }
        originalCategories.forEach { database.categoryDao().insertCategory(it) }

        // 2. Simulate backup by storing data
        val backupNotes = database.noteDao().getAllNotesOnce()
        val backupCategories = database.categoryDao().getAllCategoriesOnce()

        // 3. Clear database
        database.noteDao().deleteAllNotes()
        database.categoryDao().deleteAllCategories()

        assertEquals(0, database.noteDao().getAllNotesOnce().size)
        assertEquals(0, database.categoryDao().getAllCategoriesOnce().size)

        // 4. Restore from backup
        backupCategories.forEach { database.categoryDao().insertCategory(it) }
        backupNotes.forEach { database.noteDao().insertNote(it) }

        // 5. Verify restoration
        val restoredNotes = database.noteDao().getAllNotesOnce()
        val restoredCategories = database.categoryDao().getAllCategoriesOnce()

        assertEquals(5, restoredNotes.size)
        assertEquals(2, restoredCategories.size)

        // Verify data integrity
        assertTrue(restoredNotes.any { it.id == "original-1" && it.title == "Original 1" })
        assertTrue(restoredCategories.any { it.name == "Work" })
    }

    @Test
    fun restorePreservesPrivacyFlags() = runBlocking {
        // Create notes with various privacy flags
        val notes = listOf(
            createTestNote(id = "pub", title = "Public"),
            createTestNote(id = "priv", title = "Private", isFullPrivacy = true),
            createTestNote(id = "excluded", title = "Excluded", excludeFromAiChat = true),
            createTestNote(id = "archived", title = "Archived", isArchived = true)
        )

        notes.forEach { database.noteDao().insertNote(it) }

        // Backup
        val backup = database.noteDao().getAllNotesOnce()

        // Clear and restore
        database.noteDao().deleteAllNotes()
        backup.forEach { database.noteDao().insertNote(it) }

        // Verify flags preserved
        val pub = database.noteDao().getNoteById("pub")
        val priv = database.noteDao().getNoteById("priv")
        val excluded = database.noteDao().getNoteById("excluded")
        val archived = database.noteDao().getNoteById("archived")

        assertFalse(pub?.isFullPrivacy ?: true)
        assertTrue(priv?.isFullPrivacy ?: false)
        assertTrue(excluded?.excludeFromAiChat ?: false)
        assertTrue(archived?.isArchived ?: false)
    }

    // =========================================================================
    // HELPER FUNCTIONS
    // =========================================================================

    private fun createTestNote(
        id: String = UUID.randomUUID().toString(),
        title: String = "Test Note",
        content: String = "Test content",
        type: NoteType = NoteType.BRAIN_DUMP,
        processingStatus: ProcessingStatus = ProcessingStatus.COMPLETED,
        categoryId: String? = null,
        categoryName: String? = null,
        summary: String? = null,
        whySaved: String? = null,
        isFullPrivacy: Boolean = false,
        excludeFromAiChat: Boolean = false,
        isArchived: Boolean = false,
        isAiCreated: Boolean = false
    ): Note {
        return Note(
            id = id,
            title = title,
            content = content,
            type = type,
            processingStatus = processingStatus,
            categoryId = categoryId,
            categoryName = categoryName,
            summary = summary,
            whySaved = whySaved,
            isFullPrivacy = isFullPrivacy,
            excludeFromAiChat = excludeFromAiChat,
            isArchived = isArchived,
            isAiCreated = isAiCreated
        )
    }
}
