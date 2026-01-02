package com.example.smarty.agent.tools

import com.example.smarty.agent.tools.notes.CreateNoteArgs
import com.example.smarty.agent.tools.notes.CreateNoteTool
import com.example.smarty.data.model.Note
import com.example.smarty.data.repository.CogniRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

/**
 * Unit tests for CreateNoteTool.
 * Verifies input validation, note creation, and error handling.
 */
class CreateNoteToolTest {

    private lateinit var repository: CogniRepository
    private lateinit var createNoteTool: CreateNoteTool
    private var processedNote: Note? = null

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
        processedNote = null
        createNoteTool = CreateNoteTool(
            repository = repository,
            onProcessNote = { note -> processedNote = note }
        )
    }

    // =========================================================================
    // ARGS SERIALIZATION TESTS
    // =========================================================================

    @Test
    fun `Args can be deserialized from JSON`() {
        val json = """{"content":"Test note content","title":"Test Title","category":"Work"}"""
        val args = Json.decodeFromString(CreateNoteArgs.serializer(), json)

        assertEquals("Test note content", args.content)
        assertEquals("Test Title", args.title)
        assertEquals("Work", args.category)
    }

    @Test
    fun `Args with only required fields deserializes correctly`() {
        val json = """{"content":"Minimal note"}"""
        val args = Json.decodeFromString(CreateNoteArgs.serializer(), json)

        assertEquals("Minimal note", args.content)
        assertNull(args.title)
        assertNull(args.category)
    }

    @Test
    fun `Args serializes correctly`() {
        val args = CreateNoteArgs(
            content = "Test content",
            title = "My Title",
            category = "Ideas"
        )

        val jsonString = Json.encodeToString(CreateNoteArgs.serializer(), args)

        assertTrue(jsonString.contains("\"content\":\"Test content\""))
        assertTrue(jsonString.contains("\"title\":\"My Title\""))
        assertTrue(jsonString.contains("\"category\":\"Ideas\""))
    }

    // =========================================================================
    // INPUT VALIDATION TESTS
    // =========================================================================

    @Test
    fun `empty content returns failure`() = runTest {
        val args = CreateNoteArgs(content = "")

        val result = createNoteTool.execute(args)

        assertFalse("Should fail with empty content", result.success)
        assertEquals("Content cannot be empty", result.message)
        assertEquals("Empty content", result.error)
    }

    @Test
    fun `blank content returns failure`() = runTest {
        val args = CreateNoteArgs(content = "   ")

        val result = createNoteTool.execute(args)

        assertFalse("Should fail with blank content", result.success)
        assertEquals("Content cannot be empty", result.message)
    }

    @Test
    fun `whitespace only content returns failure`() = runTest {
        val args = CreateNoteArgs(content = "\n\t\r ")

        val result = createNoteTool.execute(args)

        assertFalse("Should fail with whitespace only", result.success)
    }

    // =========================================================================
    // SUCCESSFUL CREATION TESTS
    // =========================================================================

    @Test
    fun `valid content creates note successfully`() = runTest {
        val noteSlot = slot<Note>()
        coEvery { repository.insertNote(capture(noteSlot)) } returns Unit

        val args = CreateNoteArgs(content = "This is valid note content")

        val result = createNoteTool.execute(args)

        assertTrue("Should succeed with valid content", result.success)
        assertEquals("Note created successfully", result.message)
        assertNotNull("Should have note ID", result.noteId)
        coVerify(exactly = 1) { repository.insertNote(any()) }
    }

    @Test
    fun `note created with custom title`() = runTest {
        val noteSlot = slot<Note>()
        coEvery { repository.insertNote(capture(noteSlot)) } returns Unit

        val args = CreateNoteArgs(
            content = "Note content here",
            title = "Custom Title"
        )

        val result = createNoteTool.execute(args)

        assertTrue(result.success)
        assertEquals("Custom Title", result.noteTitle)
    }

    @Test
    fun `note created with category`() = runTest {
        val mockCategory = mockk<com.example.smarty.data.model.Category>(relaxed = true) {
            coEvery { id } returns "cat-123"
            coEvery { name } returns "Work"
        }
        coEvery { repository.getOrCreateCategory("Work") } returns mockCategory
        coEvery { repository.updateNote(any()) } returns Unit

        val args = CreateNoteArgs(
            content = "Work related note",
            category = "Work"
        )

        val result = createNoteTool.execute(args)

        assertTrue(result.success)
        coVerify { repository.getOrCreateCategory("Work") }
        coVerify { repository.updateNote(any()) }
    }

    @Test
    fun `note without category triggers AI processing`() = runTest {
        coEvery { repository.insertNote(any()) } returns Unit

        val args = CreateNoteArgs(content = "Note for AI to categorize")

        createNoteTool.execute(args)

        assertNotNull("Should trigger onProcessNote callback", processedNote)
    }

    // =========================================================================
    // TOOL METADATA TESTS
    // =========================================================================

    @Test
    fun `tool name is correct`() {
        assertEquals("create_note", createNoteTool.name)
    }

    @Test
    fun `tool has description`() {
        assertTrue(createNoteTool.description.isNotBlank())
        assertTrue(createNoteTool.description.contains("Creates a new note"))
    }

    // =========================================================================
    // ERROR HANDLING TESTS
    // =========================================================================

    @Test
    fun `repository exception returns failure result`() = runTest {
        coEvery { repository.insertNote(any()) } throws RuntimeException("Database error")

        val args = CreateNoteArgs(content = "Valid content")

        val result = createNoteTool.execute(args)

        assertFalse("Should fail on repository exception", result.success)
        assertEquals("Failed to create note", result.message)
        assertEquals("Database error", result.error)
    }

    @Test
    fun `category creation exception returns failure`() = runTest {
        coEvery { repository.insertNote(any()) } returns Unit
        coEvery { repository.getOrCreateCategory(any()) } throws RuntimeException("Category error")

        val args = CreateNoteArgs(content = "Content", category = "BadCategory")

        val result = createNoteTool.execute(args)

        assertFalse("Should fail on category exception", result.success)
    }

    // =========================================================================
    // RESULT TOSTRING TESTS
    // =========================================================================

    @Test
    fun `success result toString is LLM-friendly`() = runTest {
        coEvery { repository.insertNote(any()) } returns Unit

        val args = CreateNoteArgs(content = "Test content", title = "My Note")
        val result = createNoteTool.execute(args)

        val stringOutput = result.toString()
        // TOON format: {success:true|noteTitle:My Note|...}
        assertTrue("Should contain success:true", stringOutput.contains("success:true") || stringOutput.contains("success: true"))
        assertTrue("Should contain note title", stringOutput.contains("My Note"))
    }

    @Test
    fun `failure result toString is LLM-friendly`() = runTest {
        val args = CreateNoteArgs(content = "")
        val result = createNoteTool.execute(args)

        val stringOutput = result.toString()
        // TOON format: {success:false|...}
        assertTrue("Should contain success:false", stringOutput.contains("success:false") || stringOutput.contains("success: false"))
    }

    // =========================================================================
    // AI CREATED FLAG TESTS
    // =========================================================================

    @Test
    fun `created note has isAiCreated flag set to true`() = runTest {
        val noteSlot = slot<Note>()
        coEvery { repository.insertNote(capture(noteSlot)) } returns Unit

        val args = CreateNoteArgs(content = "AI created this note")
        createNoteTool.execute(args)

        assertTrue("Note should have isAiCreated = true", noteSlot.captured.isAiCreated)
    }
}
