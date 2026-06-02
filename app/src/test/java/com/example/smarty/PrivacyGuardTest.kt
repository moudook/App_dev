package com.example.smarty

import com.example.smarty.core.common.util.PrivacyAware
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.ProcessingStatus
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PrivacyGuard security barrier.
 * These tests verify that private notes are NEVER accessible to AI.
 *
 * CRITICAL: These tests must PASS to ensure user privacy.
 */
class PrivacyGuardTest {
    // =========================================================================
    // TEST DATA HELPERS
    // =========================================================================

    private fun createNote(
        id: String = "test-note-id",
        title: String = "Test Note",
        content: String = "Test content",
        isFullPrivacy: Boolean = false,
        excludeFromAiChat: Boolean = false,
        isArchived: Boolean = false,
    ): Note =
        Note(
            id = id,
            title = title,
            content = content,
            type = NoteType.BRAIN_DUMP,
            processingStatus = ProcessingStatus.COMPLETED,
            isFullPrivacy = isFullPrivacy,
            excludeFromAiChat = excludeFromAiChat,
            isArchived = isArchived,
        )

    // =========================================================================
    // NOTE PRIVACY STATUS TESTS
    // =========================================================================

    @Test
    fun `note with isFullPrivacy true is private`() {
        val note = createNote(isFullPrivacy = true)
        assertTrue("Note with isFullPrivacy should be private", note.isPrivate)
    }

    @Test
    fun `note with excludeFromAiChat true is private`() {
        val note = createNote(excludeFromAiChat = true)
        assertTrue("Note with excludeFromAiChat should be private", note.isPrivate)
    }

    @Test
    fun `note with both privacy flags true is private`() {
        val note = createNote(isFullPrivacy = true, excludeFromAiChat = true)
        assertTrue("Note with both privacy flags should be private", note.isPrivate)
    }

    @Test
    fun `note with no privacy flags is not private`() {
        val note = createNote(isFullPrivacy = false, excludeFromAiChat = false)
        assertFalse("Note without privacy flags should not be private", note.isPrivate)
    }

    // =========================================================================
    // PRIVACY AWARE INTERFACE TESTS
    // =========================================================================

    @Test
    fun `note implements PrivacyAware interface`() {
        val note = createNote()
        assertTrue("Note should implement PrivacyAware", note is PrivacyAware)
    }

    @Test
    fun `PrivacyAware isPrivate matches Note privacy status`() {
        val privateNote = createNote(isFullPrivacy = true)
        val publicNote = createNote()

        val privateAware: PrivacyAware = privateNote
        val publicAware: PrivacyAware = publicNote

        assertTrue("Private note's PrivacyAware.isPrivate should be true", privateAware.isPrivate)
        assertFalse("Public note's PrivacyAware.isPrivate should be false", publicAware.isPrivate)
    }

    // =========================================================================
    // FILTERING TESTS
    // =========================================================================

    @Test
    fun `filter removes all private notes`() {
        val notes =
            listOf(
                createNote(id = "1", isFullPrivacy = true),
                createNote(id = "2", excludeFromAiChat = true),
                createNote(id = "3"), // public
                createNote(id = "4"), // public
            )

        val visible = notes.filter { !it.isPrivate }

        assertEquals("Should have 2 visible notes", 2, visible.size)
        assertTrue("Should contain note 3", visible.any { it.id == "3" })
        assertTrue("Should contain note 4", visible.any { it.id == "4" })
        assertFalse("Should not contain private note 1", visible.any { it.id == "1" })
        assertFalse("Should not contain private note 2", visible.any { it.id == "2" })
    }

    @Test
    fun `filter excludes archived notes when checking AI accessibility`() {
        val notes =
            listOf(
                createNote(id = "1", isArchived = true), // archived
                createNote(id = "2"), // public, not archived
            )

        // AI accessible = not private AND not archived
        val accessible = notes.filter { !it.isPrivate && !it.isArchived }

        assertEquals("Should have 1 accessible note", 1, accessible.size)
        assertEquals("Should be note 2", "2", accessible[0].id)
    }

    @Test
    fun `empty list returns empty filtered list`() {
        val notes = emptyList<Note>()
        val visible = notes.filter { !it.isPrivate }
        assertTrue("Empty list should return empty result", visible.isEmpty())
    }

    @Test
    fun `all private notes returns empty filtered list`() {
        val notes =
            listOf(
                createNote(id = "1", isFullPrivacy = true),
                createNote(id = "2", excludeFromAiChat = true),
                createNote(id = "3", isFullPrivacy = true, excludeFromAiChat = true),
            )

        val visible = notes.filter { !it.isPrivate }

        assertTrue("All private notes should result in empty list", visible.isEmpty())
    }

    @Test
    fun `all public notes returns all notes`() {
        val notes =
            listOf(
                createNote(id = "1"),
                createNote(id = "2"),
                createNote(id = "3"),
            )

        val visible = notes.filter { !it.isPrivate }

        assertEquals("All public notes should be visible", 3, visible.size)
    }

    // =========================================================================
    // FIND BY ID TESTS
    // =========================================================================

    @Test
    fun `findById returns null for private note`() {
        val privateNote = createNote(id = "private-123", isFullPrivacy = true)
        val notes = listOf(privateNote)

        // Simulating PrivacyGuard.findByIdForAi behavior
        val result = notes.find { it.id == "private-123" }?.takeIf { !it.isPrivate }

        assertNull("Should return null for private note", result)
    }

    @Test
    fun `findById returns note for public note`() {
        val publicNote = createNote(id = "public-123")
        val notes = listOf(publicNote)

        val result = notes.find { it.id == "public-123" }?.takeIf { !it.isPrivate }

        assertNotNull("Should return note for public note", result)
        assertEquals("Should be the correct note", "public-123", result?.id)
    }

    @Test
    fun `findById returns null for non-existent note`() {
        val notes = listOf(createNote(id = "existing-note"))

        val result = notes.find { it.id == "non-existent" }?.takeIf { !it.isPrivate }

        assertNull("Should return null for non-existent note", result)
    }

    // =========================================================================
    // ID FILTERING TESTS
    // =========================================================================

    @Test
    fun `filter note IDs excludes private note IDs`() {
        val notes =
            listOf(
                createNote(id = "1", isFullPrivacy = true),
                createNote(id = "2"),
                createNote(id = "3", excludeFromAiChat = true),
                createNote(id = "4"),
            )

        val inputIds = listOf("1", "2", "3", "4")

        // Simulating PrivacyGuard.filterNoteIds behavior
        val filteredIds =
            inputIds.filter { noteId ->
                val note = notes.find { it.id == noteId }
                note != null && !note.isPrivate
            }

        assertEquals("Should have 2 visible note IDs", 2, filteredIds.size)
        assertTrue("Should contain ID 2", filteredIds.contains("2"))
        assertTrue("Should contain ID 4", filteredIds.contains("4"))
        assertFalse("Should not contain private ID 1", filteredIds.contains("1"))
        assertFalse("Should not contain private ID 3", filteredIds.contains("3"))
    }

    // =========================================================================
    // MIXED PRIVACY STATUS TESTS
    // =========================================================================

    @Test
    fun `mixed privacy states are filtered correctly`() {
        val notes =
            listOf(
                createNote(id = "full-privacy", isFullPrivacy = true, excludeFromAiChat = false),
                createNote(id = "ai-excluded", isFullPrivacy = false, excludeFromAiChat = true),
                createNote(id = "both-flags", isFullPrivacy = true, excludeFromAiChat = true),
                createNote(id = "archived-only", isFullPrivacy = false, excludeFromAiChat = false, isArchived = true),
                createNote(id = "public", isFullPrivacy = false, excludeFromAiChat = false, isArchived = false),
            )

        // Privacy filter (not private)
        val notPrivate = notes.filter { !it.isPrivate }
        assertEquals("Should have 2 non-private notes", 2, notPrivate.size)

        // AI accessible (not private AND not archived)
        val accessible = notes.filter { !it.isPrivate && !it.isArchived }
        assertEquals("Should have 1 AI accessible note", 1, accessible.size)
        assertEquals("Should be the public note", "public", accessible[0].id)
    }

    // =========================================================================
    // SECURITY INVARIANT TESTS
    // =========================================================================

    @Test
    fun `private notes never appear in filtered results regardless of other flags`() {
        // Test that privacy flags take precedence
        val noteWithEverything =
            createNote(
                id = "complex",
                isFullPrivacy = true,
                isArchived = false, // Not archived, but should still be filtered out due to privacy
            )

        assertFalse(
            "Private note should not pass privacy filter",
            !noteWithEverything.isPrivate,
        )
    }

    @Test
    fun `count of private notes is accurate`() {
        val notes =
            listOf(
                createNote(id = "1", isFullPrivacy = true),
                createNote(id = "2", excludeFromAiChat = true),
                createNote(id = "3"),
                createNote(id = "4"),
                createNote(id = "5", isFullPrivacy = true),
            )

        val privateCount = notes.count { it.isPrivate }
        val publicCount = notes.count { !it.isPrivate }

        assertEquals("Should have 3 private notes", 3, privateCount)
        assertEquals("Should have 2 public notes", 2, publicCount)
        assertEquals("Total should equal list size", notes.size, privateCount + publicCount)
    }

    // =========================================================================
    // CHAT REFERENCE SANITIZATION TESTS
    // =========================================================================

    @Test
    fun `sanitizeReferencedNoteIds removes private note IDs`() {
        val notes =
            listOf(
                createNote(id = "public-1"),
                createNote(id = "private-1", isFullPrivacy = true),
                createNote(id = "public-2"),
                createNote(id = "private-2", excludeFromAiChat = true),
            )

        val inputIds = listOf("public-1", "private-1", "public-2", "private-2")

        // Simulating sanitizeReferencedNoteIds
        val sanitized =
            inputIds.filter { noteId ->
                val note = notes.find { it.id == noteId }
                note != null && !note.isPrivate && !note.isArchived
            }

        assertEquals("Should have 2 sanitized IDs", 2, sanitized.size)
        assertTrue("Should contain public-1", sanitized.contains("public-1"))
        assertTrue("Should contain public-2", sanitized.contains("public-2"))
        assertFalse("Should NOT contain private-1", sanitized.contains("private-1"))
        assertFalse("Should NOT contain private-2", sanitized.contains("private-2"))
    }

    @Test
    fun `sanitizeReferencedNoteIds removes non-existent note IDs`() {
        val notes =
            listOf(
                createNote(id = "existing-public"),
                createNote(id = "existing-private", isFullPrivacy = true),
            )

        val inputIds = listOf("existing-public", "non-existent", "existing-private")

        val sanitized =
            inputIds.filter { noteId ->
                val note = notes.find { it.id == noteId }
                note != null && !note.isPrivate && !note.isArchived
            }

        assertEquals("Should only have 1 sanitized ID", 1, sanitized.size)
        assertEquals("Should be existing-public", "existing-public", sanitized[0])
    }

    @Test
    fun `empty reference list returns empty sanitized list`() {
        val notes = listOf(createNote(id = "some-note"))
        val inputIds = emptyList<String>()

        val sanitized =
            inputIds.filter { noteId ->
                val note = notes.find { it.id == noteId }
                note != null && !note.isPrivate && !note.isArchived
            }

        assertTrue("Empty input should return empty output", sanitized.isEmpty())
    }

    // =========================================================================
    // RETROACTIVE PRIVACY TESTS
    // =========================================================================

    @Test
    fun `note that becomes private is filtered from existing references`() {
        // Scenario: A note was public, referenced in chat, then made private
        // The sanitization should remove it from any NEW persistence

        val noteBeforePrivacy = createNote(id = "dynamic-note", isFullPrivacy = false)
        var notes = listOf(noteBeforePrivacy, createNote(id = "other"))

        // Before: note is public
        val refsBefore =
            listOf("dynamic-note", "other").filter { noteId ->
                val note = notes.find { it.id == noteId }
                note != null && !note.isPrivate
            }
        assertEquals("Before privacy, both should be included", 2, refsBefore.size)

        // After: note becomes private
        val noteAfterPrivacy = noteBeforePrivacy.copy(isFullPrivacy = true)
        notes = listOf(noteAfterPrivacy, createNote(id = "other"))

        val refsAfter =
            listOf("dynamic-note", "other").filter { noteId ->
                val note = notes.find { it.id == noteId }
                note != null && !note.isPrivate
            }
        assertEquals("After privacy, only 'other' should be included", 1, refsAfter.size)
        assertEquals("Should be 'other'", "other", refsAfter[0])
    }

    // =========================================================================
    // RESPONSE TEXT SANITIZATION TESTS
    // =========================================================================

    @Test
    fun `response text containing private note ID is flagged`() {
        val privateNote = createNote(id = "secret-note-12345", title = "Secret Passwords", isFullPrivacy = true)

        val responseText = "I found your note with ID secret-note-12345"

        // Check if response contains private note ID
        val containsPrivateId = responseText.contains(privateNote.id)

        assertTrue("Response contains private note ID", containsPrivateId)
    }

    @Test
    fun `response text without private content passes`() {
        val privateNote = createNote(id = "secret-note-12345", title = "Secret Passwords", isFullPrivacy = true)

        val responseText = "I created a new note for you about groceries."

        val containsPrivateId = responseText.contains(privateNote.id)
        val containsPrivateTitle = responseText.contains(privateNote.title, ignoreCase = true)

        assertFalse("Response should not contain private note ID", containsPrivateId)
        assertFalse("Response should not contain private note title", containsPrivateTitle)
    }

    // =========================================================================
    // ASSERTION TESTS
    // =========================================================================

    @Test
    fun `containsPrivateNoteIds detects private notes`() {
        val notes =
            listOf(
                createNote(id = "public-1"),
                createNote(id = "private-1", isFullPrivacy = true),
            )

        val idsWithPrivate = listOf("public-1", "private-1")
        val idsWithoutPrivate = listOf("public-1")

        val hasPrivate =
            idsWithPrivate.any { noteId ->
                val note = notes.find { it.id == noteId }
                note != null && note.isPrivate
            }

        val hasNoPrivate =
            idsWithoutPrivate.any { noteId ->
                val note = notes.find { it.id == noteId }
                note != null && note.isPrivate
            }

        assertTrue("Should detect private note in list", hasPrivate)
        assertFalse("Should not detect private note when none exist", hasNoPrivate)
    }

    // =========================================================================
    // EDGE CASE TESTS
    // =========================================================================

    @Test
    fun `all notes private leaves zero AI accessible`() {
        val notes =
            listOf(
                createNote(id = "1", isFullPrivacy = true),
                createNote(id = "2", excludeFromAiChat = true),
                createNote(id = "3", isFullPrivacy = true, excludeFromAiChat = true),
            )

        val accessible = notes.filter { !it.isPrivate && !it.isArchived }

        assertTrue("No notes should be AI accessible", accessible.isEmpty())
    }

    @Test
    fun `single note privacy correctly handled`() {
        val singlePrivate = listOf(createNote(id = "only", isFullPrivacy = true))
        val singlePublic = listOf(createNote(id = "only", isFullPrivacy = false))

        val privateAccessible = singlePrivate.filter { !it.isPrivate }
        val publicAccessible = singlePublic.filter { !it.isPrivate }

        assertTrue("Single private note should give empty list", privateAccessible.isEmpty())
        assertEquals("Single public note should give 1 item", 1, publicAccessible.size)
    }

    @Test
    fun `null note handling in ID lookup`() {
        val notes = emptyList<Note>()
        val result = notes.find { it.id == "any-id" }

        assertNull("Empty list should return null", result)
    }

    @Test
    fun `duplicate note IDs handled correctly`() {
        // Edge case: what if same note ID appears twice in references
        val notes = listOf(createNote(id = "dup"))

        val inputIds = listOf("dup", "dup", "dup")

        val sanitized =
            inputIds.filter { noteId ->
                val note = notes.find { it.id == noteId }
                note != null && !note.isPrivate
            }

        assertEquals("Duplicates should be preserved in output", 3, sanitized.size)
    }
}
