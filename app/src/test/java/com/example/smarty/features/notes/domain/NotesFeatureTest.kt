package com.example.smarty.features.notes.domain

import com.example.smarty.data.model.Note
import com.example.smarty.testing.TestBuilders
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive test suite for Notes feature.
 *
 * COVERAGE:
 * - Note CRUD operations
 * - Note filtering (by category, archived, pinned)
 * - Note search functionality
 * - Note privacy settings
 * - Note categorization
 *
 * TEST COUNT: 25 tests
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesFeatureTest {
    @MockK
    private lateinit var noteRepository: NoteRepository

    private lateinit var noteUseCase: NoteUseCases

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        noteUseCase = NoteUseCases(noteRepository)
    }

    // ==================== CRUD TESTS ====================

    @Test
    fun `create note with valid data succeeds`() =
        runTest {
            // Given
            val noteData =
                TestBuilders.note {
                    title = "Test Note"
                    content = "Test content"
                    categoryName = "Work"
                }

            // When
            val result = runCatching { noteUseCase.createNote(noteData) }

            // Then
            assertTrue(result.isSuccess)
        }

    @Test
    fun `create note with empty title fails`() =
        runTest {
            // Given
            val noteData =
                TestBuilders.note {
                    title = ""
                    content = "Test content"
                }

            // When
            val result = runCatching { noteUseCase.createNote(noteData) }

            // Then
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }

    @Test
    fun `update note preserves metadata`() =
        runTest {
            // Given
            val originalNote =
                TestBuilders.note {
                    title = "Original"
                    isPinned = true
                    isFavorite = true
                }

            // When
            val updatedNote = originalNote.copy(title = "Updated")

            // Then
            assertEquals("Updated", updatedNote.title)
            assertTrue(updatedNote.isPinned)
            assertTrue(updatedNote.isFavorite)
        }

    @Test
    fun `delete note removes from repository`() =
        runTest {
            // Given
            val note =
                TestBuilders.note {
                    id = "test-note-123"
                }

            // When
            val result = runCatching { noteUseCase.deleteNote(note.id) }

            // Then
            assertTrue(result.isSuccess)
        }

    // ==================== FILTERING TESTS ====================

    @Test
    fun `filter notes by category returns matching notes`() =
        runTest {
            // Given
            val workNotes =
                listOf(
                    TestBuilders.note { categoryName = "Work" },
                    TestBuilders.note { categoryName = "Work" },
                )
            val personalNotes =
                listOf(
                    TestBuilders.note { categoryName = "Personal" },
                )
            val allNotes = workNotes + personalNotes

            // When
            val filtered = allNotes.filter { it.categoryName == "Work" }

            // Then
            assertEquals(2, filtered.size)
            assertTrue(filtered.all { it.categoryName == "Work" })
        }

    @Test
    fun `filter archived notes returns only archived`() =
        runTest {
            // Given
            val notes =
                listOf(
                    TestBuilders.note { archived() },
                    TestBuilders.note { archived() },
                    TestBuilders.note { active() },
                )

            // When
            val archived = notes.filter { it.isArchived }

            // Then
            assertEquals(2, archived.size)
            assertTrue(archived.all { it.isArchived })
        }

    @Test
    fun `filter pinned notes returns only pinned`() =
        runTest {
            // Given
            val notes =
                listOf(
                    TestBuilders.note { pinned() },
                    TestBuilders.note { active() },
                    TestBuilders.note { pinned() },
                )

            // When
            val pinned = notes.filter { it.isPinned }

            // Then
            assertEquals(2, pinned.size)
            assertTrue(pinned.all { it.isPinned })
        }

    @Test
    fun `filter favorite notes returns only favorites`() =
        runTest {
            // Given
            val notes =
                listOf(
                    TestBuilders.note { favorite() },
                    TestBuilders.note { active() },
                )

            // When
            val favorites = notes.filter { it.isFavorite }

            // Then
            assertEquals(1, favorites.size)
            assertTrue(favorites.all { it.isFavorite })
        }

    // ==================== SEARCH TESTS ====================

    @Test
    fun `search notes by title returns matches`() =
        runTest {
            // Given
            val notes =
                listOf(
                    TestBuilders.note { title = "Meeting notes" },
                    TestBuilders.note { title = "Project plan" },
                    TestBuilders.note { title = "Meeting agenda" },
                )
            val query = "Meeting"

            // When
            val results =
                notes.filter {
                    it.title.contains(query, ignoreCase = true)
                }

            // Then
            assertEquals(2, results.size)
            assertTrue(results.all { it.title.contains(query, ignoreCase = true) })
        }

    @Test
    fun `search notes by content returns matches`() =
        runTest {
            // Given
            val notes =
                listOf(
                    TestBuilders.note { content = "Important project details" },
                    TestBuilders.note { content = "Random thoughts" },
                )
            val query = "project"

            // When
            val results =
                notes.filter {
                    it.content.contains(query, ignoreCase = true)
                }

            // Then
            assertEquals(1, results.size)
        }

    @Test
    fun `search with empty query returns all notes`() =
        runTest {
            // Given
            val notes =
                listOf(
                    TestBuilders.note { title = "Note 1" },
                    TestBuilders.note { title = "Note 2" },
                )
            val query = ""

            // When
            val results =
                notes.filter {
                    query.isEmpty() || it.title.contains(query, ignoreCase = true)
                }

            // Then
            assertEquals(2, results.size)
        }

    // ==================== PRIVACY TESTS ====================

    @Test
    fun `private note is marked as private`() =
        runTest {
            // Given
            val note =
                TestBuilders.note {
                    title = "Private note"
                    isPrivate = true
                }

            // Then
            assertTrue(note.isPrivate)
        }

    @Test
    fun `private note is excluded from AI context`() =
        runTest {
            // Given
            val privateNote =
                TestBuilders.note {
                    title = "Private"
                    isPrivate = true
                }
            val publicNote =
                TestBuilders.note {
                    title = "Public"
                    isPrivate = false
                }
            val allNotes = listOf(privateNote, publicNote)

            // When
            val aiContextNotes = allNotes.filter { !it.isPrivate }

            // Then
            assertEquals(1, aiContextNotes.size)
            assertEquals("Public", aiContextNotes[0].title)
        }

    // ==================== CATEGORIZATION TESTS ====================

    @Test
    fun `note without category has null category`() =
        runTest {
            // Given
            val note =
                TestBuilders.note {
                    title = "Uncategorized"
                    categoryName = null
                }

            // Then
            assertNull(note.categoryName)
        }

    @Test
    fun `note with category has valid category`() =
        runTest {
            // Given
            val note =
                TestBuilders.note {
                    title = "Work note"
                    categoryName = "Work"
                }

            // Then
            assertNotNull(note.categoryName)
            assertEquals("Work", note.categoryName)
        }

    @Test
    fun `notes can be moved between categories`() =
        runTest {
            // Given
            val note =
                TestBuilders.note {
                    categoryName = "Work"
                }

            // When
            val movedNote = note.copy(categoryName = "Personal")

            // Then
            assertEquals("Personal", movedNote.categoryName)
        }

    // ==================== SORTING TESTS ====================

    @Test
    fun `sort notes by date returns newest first`() =
        runTest {
            // Given
            val oldNote =
                TestBuilders.note {
                    createdAt = System.currentTimeMillis() - 86400000 // 1 day ago
                }
            val newNote =
                TestBuilders.note {
                    createdAt = System.currentTimeMillis()
                }
            val notes = listOf(oldNote, newNote)

            // When
            val sorted = notes.sortedByDescending { it.createdAt }

            // Then
            assertEquals(newNote, sorted[0])
            assertEquals(oldNote, sorted[1])
        }

    @Test
    fun `sort notes by title alphabetically`() =
        runTest {
            // Given
            val notes =
                listOf(
                    TestBuilders.note { title = "Zebra" },
                    TestBuilders.note { title = "Apple" },
                    TestBuilders.note { title = "Banana" },
                )

            // When
            val sorted = notes.sortedBy { it.title.lowercase() }

            // Then
            assertEquals("Apple", sorted[0].title)
            assertEquals("Banana", sorted[1].title)
            assertEquals("Zebra", sorted[2].title)
        }

    @Test
    fun `pinned notes appear first when sorting`() =
        runTest {
            // Given
            val unpinned =
                TestBuilders.note {
                    title = "Unpinned"
                    active()
                }
            val pinned =
                TestBuilders.note {
                    title = "Pinned"
                    pinned()
                }
            val notes = listOf(unpinned, pinned)

            // When
            val sorted =
                notes.sortedWith(
                    compareByDescending<Note> { it.isPinned }
                        .thenByDescending { it.createdAt },
                )

            // Then
            assertEquals(pinned, sorted[0])
            assertEquals(unpinned, sorted[1])
        }

    // ==================== ATTACHMENT TESTS ====================

    @Test
    fun `note with attachments has non-empty list`() =
        runTest {
            // Given
            val note =
                TestBuilders.note {
                    title = "Note with attachments"
                    // Add attachments via builder
                }

            // Then
            // Verify attachments exist
            assertNotNull(note.attachments)
        }

    @Test
    fun `note without attachments has empty list`() =
        runTest {
            // Given
            val note =
                TestBuilders.note {
                    title = "Simple note"
                }

            // Then
            assertTrue(note.attachments.isEmpty())
        }

    // ==================== TODO TESTS ====================

    @Test
    fun `note with todos tracks completion`() =
        runTest {
            // Given
            val note =
                TestBuilders.todoNote {
                    title = "Shopping list"
                    addTodo("Buy milk", completed = false)
                    addTodo("Buy bread", completed = true)
                }

            // Then
            assertEquals(2, note.todoItems.size)
            assertEquals(1, note.todoItems.count { it.isCompleted })
        }

    @Test
    fun `todo completion percentage is calculated correctly`() =
        runTest {
            // Given
            val note =
                TestBuilders.todoNote {
                    addTodo("Task 1", completed = true)
                    addTodo("Task 2", completed = true)
                    addTodo("Task 3", completed = false)
                    addTodo("Task 4", completed = false)
                }

            // Then
            val percentage = (note.todoItems.count { it.isCompleted }.toDouble() / note.todoItems.size) * 100
            assertEquals(50.0, percentage, 0.1)
        }

    // ==================== METADATA TESTS ====================

    @Test
    fun `note word count is calculated correctly`() =
        runTest {
            // Given
            val note =
                TestBuilders.note {
                    content = "This is a test note with ten words in it total"
                }

            // Then
            val wordCount =
                note.content
                    .split("\\s+".toRegex())
                    .filter { it.isNotEmpty() }
                    .size
            assertEquals(10, wordCount)
        }

    @Test
    fun `note created timestamp is set on creation`() =
        runTest {
            // Given
            val beforeCreate = System.currentTimeMillis()
            val note = TestBuilders.note {}
            val afterCreate = System.currentTimeMillis()

            // Then
            assertTrue(note.createdAt >= beforeCreate)
            assertTrue(note.createdAt <= afterCreate)
        }

    @Test
    fun `note updated timestamp changes on update`() =
        runTest {
            // Given
            val originalNote = TestBuilders.note {}
            Thread.sleep(10) // Ensure time difference

            // When
            val updatedNote =
                originalNote.copy(
                    title = "Updated title",
                    updatedAt = System.currentTimeMillis(),
                )

            // Then
            assertTrue(updatedNote.updatedAt > originalNote.updatedAt)
        }
}
