package com.example.smarty.agent.tools

import com.example.smarty.agent.tools.notes.SearchNotesArgs
import com.example.smarty.agent.tools.notes.SearchNotesTool
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

/**
 * Unit tests for SearchNotesTool.
 * Verifies search functionality, privacy filtering, and category filtering.
 */
class SearchNotesToolTest {

    private lateinit var testNotes: List<Note>
    private lateinit var searchNotesTool: SearchNotesTool

    @Before
    fun setup() {
        testNotes = createTestNotes()
        searchNotesTool = SearchNotesTool(
            getActiveNotes = { testNotes }
        )
    }

    private fun createTestNotes(): List<Note> {
        return listOf(
            // Public notes
            createNote(
                id = "note-1",
                title = "Shopping List",
                content = "Buy milk, eggs, bread",
                categoryName = "Personal",
                summary = "Grocery items to purchase"
            ),
            createNote(
                id = "note-2",
                title = "Meeting Notes",
                content = "Discuss project timeline with team",
                categoryName = "Work",
                summary = "Team sync meeting"
            ),
            createNote(
                id = "note-3",
                title = "Recipe Ideas",
                content = "Try making pasta carbonara",
                categoryName = "Personal",
                summary = "Italian dinner recipe"
            ),
            // Private notes (should be filtered out)
            createNote(
                id = "private-1",
                title = "Bank Passwords",
                content = "Secret password: hunter2",
                isFullPrivacy = true
            ),
            createNote(
                id = "private-2",
                title = "Private Diary",
                content = "My secret thoughts",
                excludeFromAiChat = true
            ),
            // Archived note
            createNote(
                id = "archived-1",
                title = "Old Todo",
                content = "This was completed",
                isArchived = true
            )
        )
    }

    private fun createNote(
        id: String,
        title: String,
        content: String,
        categoryName: String? = null,
        summary: String? = null,
        isFullPrivacy: Boolean = false,
        excludeFromAiChat: Boolean = false,
        isArchived: Boolean = false
    ): Note {
        return Note(
            id = id,
            title = title,
            content = content,
            type = NoteType.BRAIN_DUMP,
            processingStatus = ProcessingStatus.COMPLETED,
            categoryName = categoryName,
            summary = summary,
            isFullPrivacy = isFullPrivacy,
            excludeFromAiChat = excludeFromAiChat,
            isArchived = isArchived
        )
    }

    // =========================================================================
    // ARGS SERIALIZATION TESTS
    // =========================================================================

    @Test
    fun `Args can be deserialized from JSON`() {
        val json = """{"query":"meeting","category":"Work"}"""
        val args = Json.decodeFromString(SearchNotesArgs.serializer(), json)

        assertEquals("meeting", args.query)
        assertEquals("Work", args.category)
    }

    @Test
    fun `Args with only query deserializes correctly`() {
        val json = """{"query":"test search"}"""
        val args = Json.decodeFromString(SearchNotesArgs.serializer(), json)

        assertEquals("test search", args.query)
        assertNull(args.category)
    }

    // =========================================================================
    // PRIVACY FILTERING TESTS
    // =========================================================================

    @Test
    fun `private notes are never returned in search results`() = runTest {
        val args = SearchNotesArgs(query = "")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        // Should NOT contain private notes
        assertFalse(
            "Should not contain private note with isFullPrivacy",
            result.notes.any { it.id == "private-1" }
        )
        assertFalse(
            "Should not contain private note with excludeFromAiChat",
            result.notes.any { it.id == "private-2" }
        )
    }

    @Test
    fun `archived notes are excluded from results`() = runTest {
        val args = SearchNotesArgs(query = "")

        val result = searchNotesTool.execute(args)

        assertFalse(
            "Should not contain archived notes",
            result.notes.any { it.id == "archived-1" }
        )
    }

    @Test
    fun `direct search for private note title returns nothing`() = runTest {
        val args = SearchNotesArgs(query = "Bank Passwords")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        assertTrue(
            "Search for private note title should return empty",
            result.notes.isEmpty()
        )
    }

    @Test
    fun `direct search for private note content returns nothing`() = runTest {
        val args = SearchNotesArgs(query = "hunter2")

        val result = searchNotesTool.execute(args)

        assertTrue(result.notes.isEmpty())
    }

    // =========================================================================
    // SEARCH FUNCTIONALITY TESTS
    // =========================================================================

    @Test
    fun `empty query returns all visible notes`() = runTest {
        val args = SearchNotesArgs(query = "")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(3, result.notes.size) // Only public, non-archived notes
    }

    @Test
    fun `blank query returns all visible notes`() = runTest {
        val args = SearchNotesArgs(query = "   ")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(3, result.notes.size)
    }

    @Test
    fun `search by title works`() = runTest {
        val args = SearchNotesArgs(query = "Shopping")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
        assertEquals("note-1", result.notes[0].id)
    }

    @Test
    fun `search by content works`() = runTest {
        val args = SearchNotesArgs(query = "pasta carbonara")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
        assertEquals("note-3", result.notes[0].id)
    }

    @Test
    fun `search by summary works`() = runTest {
        val args = SearchNotesArgs(query = "Italian dinner")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
        assertEquals("note-3", result.notes[0].id)
    }

    @Test
    fun `search is case insensitive`() = runTest {
        val args = SearchNotesArgs(query = "MEETING")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
        assertEquals("note-2", result.notes[0].id)
    }

    @Test
    fun `search with no matches returns empty list`() = runTest {
        val args = SearchNotesArgs(query = "nonexistent query xyz")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        assertTrue(result.notes.isEmpty())
        assertEquals("No notes found", result.message)
    }

    // =========================================================================
    // CATEGORY FILTER TESTS
    // =========================================================================

    @Test
    fun `category filter works`() = runTest {
        val args = SearchNotesArgs(query = "", category = "Work")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
        assertEquals("note-2", result.notes[0].id)
    }

    @Test
    fun `category filter is case insensitive`() = runTest {
        val args = SearchNotesArgs(query = "", category = "PERSONAL")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(2, result.notes.size) // Shopping List and Recipe Ideas
    }

    @Test
    fun `category filter with query combines correctly`() = runTest {
        val args = SearchNotesArgs(query = "Recipe", category = "Personal")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
        assertEquals("note-3", result.notes[0].id)
    }

    @Test
    fun `invalid category returns empty results`() = runTest {
        val args = SearchNotesArgs(query = "", category = "NonExistentCategory")

        val result = searchNotesTool.execute(args)

        assertTrue(result.success)
        assertTrue(result.notes.isEmpty())
    }

    // =========================================================================
    // RESULT LIMIT TESTS
    // =========================================================================

    @Test
    fun `results are limited to 10 notes`() = runTest {
        // Create tool with more than 10 notes
        val manyNotes = (1..20).map { i ->
            createNote(id = "note-$i", title = "Test $i", content = "Content $i")
        }
        val toolWithManyNotes = SearchNotesTool { manyNotes }

        val args = SearchNotesArgs(query = "")
        val result = toolWithManyNotes.execute(args)

        assertTrue(result.success)
        assertEquals(10, result.notes.size)
        assertEquals(10, result.totalCount)
    }

    // =========================================================================
    // TOOL METADATA TESTS
    // =========================================================================

    @Test
    fun `tool name is correct`() {
        assertEquals("search_notes", searchNotesTool.name)
    }

    @Test
    fun `tool description mentions privacy`() {
        assertTrue(searchNotesTool.description.contains("Private notes"))
    }

    // =========================================================================
    // RESULT TOSTRING TESTS
    // =========================================================================

    @Test
    fun `success result toString is LLM-friendly`() = runTest {
        val args = SearchNotesArgs(query = "")
        val result = searchNotesTool.execute(args)

        val stringOutput = result.toString()
        assertTrue("Should contain Found", stringOutput.contains("Found"))
        assertTrue("Should list notes", stringOutput.contains("notes"))
    }

    @Test
    fun `empty result toString is informative`() = runTest {
        val args = SearchNotesArgs(query = "impossible search term xyz123")
        val result = searchNotesTool.execute(args)

        val stringOutput = result.toString()
        assertTrue("Should indicate no notes", stringOutput.contains("No notes found"))
    }

    // =========================================================================
    // NOTE INFO STRUCTURE TESTS
    // =========================================================================

    @Test
    fun `NoteInfo contains expected fields`() = runTest {
        val args = SearchNotesArgs(query = "Shopping")
        val result = searchNotesTool.execute(args)

        val noteInfo = result.notes[0]
        assertEquals("note-1", noteInfo.id)
        assertEquals("Shopping List", noteInfo.title)
        assertEquals("Grocery items to purchase", noteInfo.summary)
        assertEquals("Personal", noteInfo.category)
        assertEquals("BRAIN_DUMP", noteInfo.type)
    }
}
