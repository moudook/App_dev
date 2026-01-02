package com.example.smarty.agent.tools

import com.example.smarty.agent.tools.notes.GetRecentNotesArgs
import com.example.smarty.agent.tools.notes.GetRecentNotesTool
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

/**
 * Unit tests for GetRecentNotesTool.
 * Verifies recent notes retrieval, privacy filtering, type/category filtering,
 * and proper sorting by creation time.
 */
class GetRecentNotesToolTest {

    private lateinit var testNotes: List<Note>
    private lateinit var getRecentNotesTool: GetRecentNotesTool

    @Before
    fun setup() {
        // Mock Android Log for PrivacyGuard
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0

        testNotes = createTestNotes()
        getRecentNotesTool = GetRecentNotesTool(
            getActiveNotes = { testNotes }
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    private fun createTestNotes(): List<Note> {
        val now = System.currentTimeMillis()
        return listOf(
            // Most recent public note
            createNote(
                id = "note-1",
                title = "Latest Note",
                content = "This is the most recent note",
                categoryName = "Work",
                createdAt = now,
                type = NoteType.BRAIN_DUMP
            ),
            // Second most recent
            createNote(
                id = "note-2",
                title = "Audio Recording",
                content = "Audio transcription here",
                categoryName = "Personal",
                createdAt = now - 1000,
                type = NoteType.AUDIO,
                fileMimeType = "audio/mp3"
            ),
            // Third
            createNote(
                id = "note-3",
                title = "Document Note",
                content = "PDF content extracted",
                categoryName = "Work",
                createdAt = now - 2000,
                type = NoteType.DOCUMENT,
                fileMimeType = "application/pdf"
            ),
            // Fourth - older
            createNote(
                id = "note-4",
                title = "Image Note",
                content = "Photo description",
                categoryName = "Personal",
                createdAt = now - 3000,
                type = NoteType.IMAGE,
                fileMimeType = "image/jpeg"
            ),
            // Fifth - oldest public
            createNote(
                id = "note-5",
                title = "Old Note",
                content = "This is an older note",
                categoryName = "Work",
                createdAt = now - 4000,
                type = NoteType.BRAIN_DUMP
            ),
            // Private notes (should be filtered out)
            createNote(
                id = "private-1",
                title = "Private Latest",
                content = "This is private",
                createdAt = now + 1000, // Even newer, but private
                isFullPrivacy = true
            ),
            createNote(
                id = "private-2",
                title = "Excluded from AI",
                content = "AI can't see this",
                createdAt = now + 500,
                excludeFromAiChat = true
            ),
            // Archived note
            createNote(
                id = "archived-1",
                title = "Archived Note",
                content = "This is archived",
                createdAt = now + 2000, // Would be newest, but archived
                isArchived = true
            )
        )
    }

    private fun createNote(
        id: String,
        title: String,
        content: String,
        categoryName: String? = null,
        createdAt: Long = System.currentTimeMillis(),
        type: NoteType = NoteType.BRAIN_DUMP,
        isFullPrivacy: Boolean = false,
        excludeFromAiChat: Boolean = false,
        isArchived: Boolean = false,
        fileMimeType: String? = null
    ): Note {
        return Note(
            id = id,
            title = title,
            content = content,
            type = type,
            processingStatus = ProcessingStatus.COMPLETED,
            categoryName = categoryName,
            createdAt = createdAt,
            isFullPrivacy = isFullPrivacy,
            excludeFromAiChat = excludeFromAiChat,
            isArchived = isArchived,
            fileMimeType = fileMimeType,
            fileUri = if (fileMimeType != null) "content://mock/$id" else null
        )
    }

    // =========================================================================
    // ARGS SERIALIZATION TESTS
    // =========================================================================

    @Test
    fun `Args can be deserialized from JSON with all fields`() {
        val json = """{"limit":3,"noteType":"AUDIO","category":"Work"}"""
        val args = Json.decodeFromString(GetRecentNotesArgs.serializer(), json)

        assertEquals(3, args.limit)
        assertEquals("AUDIO", args.noteType)
        assertEquals("Work", args.category)
    }

    @Test
    fun `Args with defaults deserializes correctly`() {
        val json = """{}"""
        val args = Json.decodeFromString(GetRecentNotesArgs.serializer(), json)

        assertEquals(5, args.limit)
        assertNull(args.noteType)
        assertNull(args.category)
    }

    @Test
    fun `Args with only limit deserializes correctly`() {
        val json = """{"limit":10}"""
        val args = Json.decodeFromString(GetRecentNotesArgs.serializer(), json)

        assertEquals(10, args.limit)
        assertNull(args.noteType)
        assertNull(args.category)
    }

    // =========================================================================
    // PRIVACY FILTERING TESTS
    // =========================================================================

    @Test
    fun `private notes are never returned`() = runTest {
        val args = GetRecentNotesArgs(limit = 20) // Get all

        val result = getRecentNotesTool.execute(args)

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
    fun `archived notes are excluded`() = runTest {
        val args = GetRecentNotesArgs(limit = 20)

        val result = getRecentNotesTool.execute(args)

        assertFalse(
            "Should not contain archived notes",
            result.notes.any { it.id == "archived-1" }
        )
    }

    @Test
    fun `only public non-archived notes are returned`() = runTest {
        val args = GetRecentNotesArgs(limit = 20)

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(5, result.notes.size) // Only 5 public, non-archived notes
    }

    // =========================================================================
    // SORTING TESTS
    // =========================================================================

    @Test
    fun `notes are sorted by creation time descending`() = runTest {
        val args = GetRecentNotesArgs(limit = 5)

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        // First result should be the most recent (note-1)
        assertEquals("note-1", result.notes[0].id)
        assertEquals("Latest Note", result.notes[0].title)

        // Verify descending order
        for (i in 0 until result.notes.size - 1) {
            assertTrue(
                "Notes should be in descending order by createdAt",
                result.notes[i].createdAt >= result.notes[i + 1].createdAt
            )
        }
    }

    @Test
    fun `limit 1 returns only the most recent note`() = runTest {
        val args = GetRecentNotesArgs(limit = 1)

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
        assertEquals("note-1", result.notes[0].id)
        assertTrue(result.message.contains("latest note"))
    }

    // =========================================================================
    // LIMIT TESTS
    // =========================================================================

    @Test
    fun `limit is capped at 20`() = runTest {
        // Create tool with 25 notes
        val manyNotes = (1..25).map { i ->
            createNote(
                id = "note-$i",
                title = "Note $i",
                content = "Content $i",
                createdAt = System.currentTimeMillis() - i * 1000
            )
        }
        val toolWithManyNotes = GetRecentNotesTool { manyNotes }

        val args = GetRecentNotesArgs(limit = 100) // Request more than max

        val result = toolWithManyNotes.execute(args)

        assertTrue(result.success)
        assertEquals(20, result.notes.size) // Capped at 20
    }

    @Test
    fun `limit 0 is coerced to 1`() = runTest {
        val args = GetRecentNotesArgs(limit = 0)

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
    }

    @Test
    fun `negative limit is coerced to 1`() = runTest {
        val args = GetRecentNotesArgs(limit = -5)

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
    }

    @Test
    fun `default limit is 5`() = runTest {
        val args = GetRecentNotesArgs() // Default limit

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(5, result.notes.size)
    }

    // =========================================================================
    // TYPE FILTER TESTS
    // =========================================================================

    @Test
    fun `type filter returns only matching types`() = runTest {
        val args = GetRecentNotesArgs(limit = 10, noteType = "AUDIO")

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
        assertEquals("note-2", result.notes[0].id)
        assertEquals("AUDIO", result.notes[0].type)
    }

    @Test
    fun `type filter is case insensitive`() = runTest {
        val args = GetRecentNotesArgs(limit = 10, noteType = "audio")

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
        assertEquals("AUDIO", result.notes[0].type)
    }

    @Test
    fun `type filter with no matches returns empty`() = runTest {
        val args = GetRecentNotesArgs(limit = 10, noteType = "VIDEO")

        val result = getRecentNotesTool.execute(args)

        // Empty results but still successful operation
        assertFalse(result.success) // success is false when no notes found
        assertTrue(result.notes.isEmpty())
        assertTrue(result.message.contains("No notes found"))
    }

    @Test
    fun `BRAIN_DUMP type filter works`() = runTest {
        val args = GetRecentNotesArgs(limit = 10, noteType = "BRAIN_DUMP")

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(2, result.notes.size) // note-1 and note-5
        assertTrue(result.notes.all { it.type == "BRAIN_DUMP" })
    }

    @Test
    fun `DOCUMENT type filter works`() = runTest {
        val args = GetRecentNotesArgs(limit = 10, noteType = "DOCUMENT")

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
        assertEquals("note-3", result.notes[0].id)
    }

    // =========================================================================
    // CATEGORY FILTER TESTS
    // =========================================================================

    @Test
    fun `category filter returns only matching categories`() = runTest {
        val args = GetRecentNotesArgs(limit = 10, category = "Work")

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(3, result.notes.size) // note-1, note-3, note-5
        assertTrue(result.notes.all { it.category == "Work" })
    }

    @Test
    fun `category filter is case insensitive`() = runTest {
        val args = GetRecentNotesArgs(limit = 10, category = "PERSONAL")

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(2, result.notes.size) // note-2, note-4
    }

    @Test
    fun `category filter with no matches returns empty`() = runTest {
        val args = GetRecentNotesArgs(limit = 10, category = "NonExistent")

        val result = getRecentNotesTool.execute(args)

        assertFalse(result.success)
        assertTrue(result.notes.isEmpty())
    }

    // =========================================================================
    // COMBINED FILTER TESTS
    // =========================================================================

    @Test
    fun `type and category filters combine correctly`() = runTest {
        val args = GetRecentNotesArgs(limit = 10, noteType = "BRAIN_DUMP", category = "Work")

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(2, result.notes.size) // note-1 and note-5
        assertTrue(result.notes.all { it.type == "BRAIN_DUMP" && it.category == "Work" })
    }

    @Test
    fun `combined filters with limit works`() = runTest {
        val args = GetRecentNotesArgs(limit = 1, noteType = "BRAIN_DUMP", category = "Work")

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        assertEquals(1, result.notes.size)
        assertEquals("note-1", result.notes[0].id) // Most recent matching note
    }

    // =========================================================================
    // ATTACHMENT DETECTION TESTS
    // =========================================================================

    @Test
    fun `audio attachment is detected`() = runTest {
        val args = GetRecentNotesArgs(limit = 10, noteType = "AUDIO")

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        val audioNote = result.notes[0]
        assertTrue(audioNote.hasAttachments)
        assertTrue(audioNote.attachmentTypes.contains("audio"))
    }

    @Test
    fun `document attachment is detected`() = runTest {
        val args = GetRecentNotesArgs(limit = 10, noteType = "DOCUMENT")

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        val docNote = result.notes[0]
        assertTrue(docNote.hasAttachments)
        assertTrue(docNote.attachmentTypes.contains("document"))
    }

    @Test
    fun `image attachment is detected`() = runTest {
        val args = GetRecentNotesArgs(limit = 10, noteType = "IMAGE")

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        val imageNote = result.notes[0]
        assertTrue(imageNote.hasAttachments)
        assertTrue(imageNote.attachmentTypes.contains("image"))
    }

    @Test
    fun `notes without attachments have empty attachment list`() = runTest {
        val args = GetRecentNotesArgs(limit = 1) // Gets note-1 which has no attachments

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        val note = result.notes[0]
        assertFalse(note.hasAttachments)
        assertTrue(note.attachmentTypes.isEmpty())
    }

    // =========================================================================
    // TOOL METADATA TESTS
    // =========================================================================

    @Test
    fun `tool name is correct`() {
        assertEquals("get_recent_notes", getRecentNotesTool.name)
    }

    @Test
    fun `tool description mentions latest and recent`() {
        assertTrue(getRecentNotesTool.description.contains("latest"))
        assertTrue(getRecentNotesTool.description.contains("recent"))
    }

    @Test
    fun `tool description warns not to use search_notes`() {
        assertTrue(getRecentNotesTool.description.contains("DO NOT use search_notes"))
    }

    // =========================================================================
    // RESULT STRUCTURE TESTS
    // =========================================================================

    @Test
    fun `RecentNoteInfo contains expected fields`() = runTest {
        val args = GetRecentNotesArgs(limit = 1)

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.success)
        val noteInfo = result.notes[0]
        assertEquals("note-1", noteInfo.id)
        assertEquals("Latest Note", noteInfo.title)
        assertEquals("Work", noteInfo.category)
        assertEquals("BRAIN_DUMP", noteInfo.type)
        assertTrue(noteInfo.createdAt > 0)
    }

    @Test
    fun `summary is truncated to 150 chars`() = runTest {
        val longSummary = "A".repeat(200)
        val notesWithLongSummary = listOf(
            Note(
                id = "long-summary",
                title = "Test",
                content = "Content",
                summary = longSummary,
                type = NoteType.BRAIN_DUMP,
                processingStatus = ProcessingStatus.COMPLETED,
                createdAt = System.currentTimeMillis()
            )
        )
        val tool = GetRecentNotesTool { notesWithLongSummary }

        val result = tool.execute(GetRecentNotesArgs(limit = 1))

        assertTrue(result.success)
        assertNotNull(result.notes[0].summary)
        assertTrue(result.notes[0].summary!!.length <= 150)
    }

    // =========================================================================
    // MESSAGE TESTS
    // =========================================================================

    @Test
    fun `single result message uses singular form`() = runTest {
        val args = GetRecentNotesArgs(limit = 1)

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.message.contains("latest note"))
        assertFalse(result.message.contains("notes"))
    }

    @Test
    fun `multiple results message uses plural form`() = runTest {
        val args = GetRecentNotesArgs(limit = 5)

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.message.contains("recent notes"))
    }

    @Test
    fun `filter info is included in message`() = runTest {
        val args = GetRecentNotesArgs(limit = 5, noteType = "AUDIO", category = "Personal")

        val result = getRecentNotesTool.execute(args)

        assertTrue(result.message.contains("AUDIO") || result.message.contains("type"))
        assertTrue(result.message.contains("Personal") || result.message.contains("category"))
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Test
    fun `empty notes list returns appropriate result`() = runTest {
        val emptyTool = GetRecentNotesTool { emptyList() }
        val args = GetRecentNotesArgs(limit = 5)

        val result = emptyTool.execute(args)

        assertFalse(result.success)
        assertTrue(result.notes.isEmpty())
        assertEquals(0, result.totalCount)
        assertTrue(result.message.contains("No notes found"))
    }

    @Test
    fun `result toString produces valid output`() = runTest {
        val args = GetRecentNotesArgs(limit = 1)

        val result = getRecentNotesTool.execute(args)

        val stringOutput = result.toString()
        assertTrue(stringOutput.isNotBlank())
        // Should contain the note info in some format (TOON or JSON)
        assertTrue(stringOutput.contains("Latest Note") || stringOutput.contains("note-1"))
    }
}
