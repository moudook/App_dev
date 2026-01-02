package com.example.smarty.agent.tools

import com.example.smarty.agent.tools.external.PlayAudioArgs
import com.example.smarty.agent.tools.external.PlayAudioTool
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Security tests for PlayAudioTool privacy enforcement.
 *
 * CRITICAL: These tests verify that private notes' audio attachments
 * are NEVER accessible through the PlayAudioTool.
 *
 * Vulnerability addressed: VULN-1 - PlayAudioTool Complete Privacy Bypass
 */
class PlayAudioToolPrivacyTest {

    private var capturedTrack: AudioTrack? = null
    private lateinit var testNotes: List<Note>

    private fun createNote(
        id: String,
        title: String,
        isFullPrivacy: Boolean = false,
        excludeFromAiChat: Boolean = false,
        attachmentsJson: String = "[]"
    ): Note {
        return Note(
            id = id,
            title = title,
            content = "Content for $title",
            type = NoteType.BRAIN_DUMP,
            processingStatus = ProcessingStatus.COMPLETED,
            isFullPrivacy = isFullPrivacy,
            excludeFromAiChat = excludeFromAiChat,
            attachmentsJson = attachmentsJson
        )
    }

    @Before
    fun setup() {
        // Mock Android Log for SemanticSearchEngine and PrivacyGuard
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0

        capturedTrack = null

        // Create test notes with audio attachments
        val audioAttachment = """[{"id":"att1","uri":"content://audio/secret.mp3","fileName":"secret_recording.mp3","mimeType":"audio/mpeg","fileSize":1000}]"""
        val publicAudioAttachment = """[{"id":"att2","uri":"content://audio/public.mp3","fileName":"public_song.mp3","mimeType":"audio/mpeg","fileSize":1000}]"""

        testNotes = listOf(
            // Private note with audio - should NEVER be accessible
            createNote(
                id = "private-note-1",
                title = "My Secret Diary",
                isFullPrivacy = true,
                attachmentsJson = audioAttachment
            ),
            // AI-excluded note with audio - should NEVER be accessible
            createNote(
                id = "private-note-2",
                title = "Confidential Meeting",
                excludeFromAiChat = true,
                attachmentsJson = audioAttachment
            ),
            // Public note with audio - should be accessible
            createNote(
                id = "public-note-1",
                title = "My Favorite Songs",
                attachmentsJson = publicAudioAttachment
            )
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    private fun createTool(): PlayAudioTool {
        return PlayAudioTool(
            getActiveNotes = { testNotes },
            onPlayAudio = { track -> capturedTrack = track }
        )
    }

    // =========================================================================
    // DIRECT ID ACCESS TESTS
    // =========================================================================

    @Test
    fun `cannot play audio from private note by ID`() = runTest {
        val tool = createTool()

        val result = tool.execute(PlayAudioArgs(
            query = "any",
            noteId = "private-note-1"
        ))

        assertFalse("Should fail for private note", result.success)
        assertNull("Should not capture any track", capturedTrack)
        assertEquals("Audio not found", result.message)
    }

    @Test
    fun `cannot play audio from AI-excluded note by ID`() = runTest {
        val tool = createTool()

        val result = tool.execute(PlayAudioArgs(
            query = "any",
            noteId = "private-note-2"
        ))

        assertFalse("Should fail for AI-excluded note", result.success)
        assertNull("Should not capture any track", capturedTrack)
    }

    @Test
    fun `can play audio from public note by ID`() = runTest {
        val tool = createTool()

        val result = tool.execute(PlayAudioArgs(
            query = "any",
            noteId = "public-note-1"
        ))

        assertTrue("Should succeed for public note", result.success)
        assertNotNull("Should capture track", capturedTrack)
        assertEquals("public_song.mp3", capturedTrack?.fileName)
    }

    // =========================================================================
    // SEARCH-BASED ACCESS TESTS
    // =========================================================================

    @Test
    fun `search does not find audio in private notes`() = runTest {
        val tool = createTool()

        // Search for "secret" which is in the private note's audio filename
        val result = tool.execute(PlayAudioArgs(query = "secret"))

        // Key security assertion: Private audio should NEVER be returned
        // Fuzzy search may find public audio, which is acceptable behavior
        if (capturedTrack != null) {
            // If something was found, it must NOT be from the private note
            assertNotEquals(
                "Should never play audio from private note",
                "secret_recording.mp3",
                capturedTrack?.fileName
            )
        }
    }

    @Test
    fun `search does not find audio in AI-excluded notes`() = runTest {
        val tool = createTool()

        // Search for "confidential" which is in the AI-excluded note's title
        val result = tool.execute(PlayAudioArgs(query = "confidential"))

        // Key security assertion: AI-excluded audio should NEVER be returned
        // Fuzzy search may find public audio, which is acceptable behavior
        if (capturedTrack != null) {
            // If something was found, it must NOT be from the AI-excluded note
            assertNotEquals(
                "Should never play audio from AI-excluded note",
                "secret_recording.mp3",
                capturedTrack?.fileName
            )
        }
    }

    @Test
    fun `search finds audio only in public notes`() = runTest {
        val tool = createTool()

        // Search for "song" which is in the public note
        val result = tool.execute(PlayAudioArgs(query = "song"))

        assertTrue("Should find audio from public note", result.success)
        assertNotNull("Should capture track", capturedTrack)
        assertEquals("public_song.mp3", capturedTrack?.fileName)
    }

    // =========================================================================
    // TITLE LEAKAGE TESTS
    // =========================================================================

    @Test
    fun `error message does not reveal private note exists`() = runTest {
        val tool = createTool()

        val result = tool.execute(PlayAudioArgs(
            query = "any",
            noteId = "private-note-1"
        ))

        // Message should be generic, not mention "private" or the note title
        assertFalse("Message should not contain 'private'",
            result.message?.contains("private", ignoreCase = true) ?: false)
        assertFalse("Message should not contain note title",
            result.message?.contains("Secret Diary", ignoreCase = true) ?: false)
    }

    @Test
    fun `success message only shows accessible note titles`() = runTest {
        val tool = createTool()

        val result = tool.execute(PlayAudioArgs(
            query = "song",
            noteId = "public-note-1"
        ))

        assertTrue("Should succeed", result.success)
        // The result should contain track info OR a playing message
        assertTrue("Result should indicate playback",
            result.trackTitle?.contains("public_song") ?: false ||
            result.message?.contains("Playing") ?: false)
    }

    // =========================================================================
    // SECURITY INVARIANT TESTS
    // =========================================================================

    @Test
    fun `private notes are completely invisible to audio search`() = runTest {
        val tool = createTool()

        // Try various search terms that would match private notes
        val searchTerms = listOf(
            "secret",
            "diary",
            "confidential",
            "meeting",
            "private-note"
        )

        for (term in searchTerms) {
            capturedTrack = null
            val result = tool.execute(PlayAudioArgs(query = term))

            // Key security assertion: Private audio should NEVER be returned
            // Fuzzy search may find public audio, which is acceptable
            if (capturedTrack != null) {
                assertNotEquals(
                    "Search for '$term' should not expose private audio",
                    "secret_recording.mp3",
                    capturedTrack?.fileName
                )
            }
        }
    }
}
