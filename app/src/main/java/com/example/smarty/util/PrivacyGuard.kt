package com.example.smarty.util

import android.util.Log
import com.example.smarty.data.model.Note

/**
 * ============================================================================
 * PRIVACY GUARD - ABSOLUTE SECURITY BARRIER FOR AI ACCESS
 * ============================================================================
 *
 * This is the ONLY gateway for AI to access notes. Private notes are
 * COMPLETELY INVISIBLE to AI - they do not exist from AI's perspective.
 *
 * STRICT RULES (NEVER MODIFY):
 * 1. AI can NEVER read private notes
 * 2. AI can NEVER write to private notes
 * 3. AI can NEVER search private notes
 * 4. AI can NEVER reference private notes
 * 5. AI can NEVER know private notes exist
 * 6. AI can NEVER modify private notes
 * 7. AI can NEVER delete private notes
 * 8. AI can NEVER archive private notes
 * 9. AI can NEVER get count of private notes
 * 10. AI can NEVER get any metadata about private notes
 *
 * ANY ATTEMPT TO ACCESS PRIVATE NOTES THROUGH AI IS BLOCKED AND LOGGED.
 *
 * This file should NEVER be modified to grant AI access to private notes.
 * ============================================================================
 */
object PrivacyGuard {
    private const val TAG = "PrivacyGuard"

    /**
     * Check if a note is private (blocked from AI)
     * A note is private if EITHER flag is set
     */
    fun isPrivate(note: Note): Boolean {
        return note.isFullPrivacy || note.excludeFromAiChat
    }

    /**
     * Check if a note is accessible to AI
     * Only non-private, non-archived notes are accessible
     */
    fun isAiAccessible(note: Note): Boolean {
        val accessible = !note.isFullPrivacy && !note.excludeFromAiChat && !note.isArchived
        if (!accessible && (note.isFullPrivacy || note.excludeFromAiChat)) {
            Log.d(TAG, "BLOCKED: AI access denied for private note ${note.id.take(8)}...")
        }
        return accessible
    }

    /**
     * THE ONLY WAY FOR AI TO GET NOTES
     *
     * This function filters out ALL private notes.
     * There is NO other way for AI to access notes.
     *
     * @param notes The full list of notes
     * @return Only notes that AI is allowed to see (private notes are invisible)
     */
    fun getAiVisibleNotes(notes: List<Note>): List<Note> {
        val visible = notes.filter { isAiAccessible(it) }
        val blocked = notes.size - visible.size
        if (blocked > 0) {
            Log.d(TAG, "AI ACCESS: $blocked private notes blocked, ${visible.size} notes visible to AI")
        }
        return visible
    }

    /**
     * Filter notes for AI context (chat, search, suggestions)
     * Private notes are COMPLETELY INVISIBLE
     */
    fun filterForAiContext(notes: List<Note>): List<Note> {
        return getAiVisibleNotes(notes)
    }

    /**
     * Filter notes for AI search operations
     * Private notes are COMPLETELY INVISIBLE
     */
    fun filterForAiSearch(notes: List<Note>): List<Note> {
        return getAiVisibleNotes(notes)
    }

    /**
     * Filter notes for AI modifications (delete, archive, update)
     * Private notes CANNOT be modified by AI
     */
    fun filterForAiModification(notes: List<Note>): List<Note> {
        return getAiVisibleNotes(notes)
    }

    /**
     * Find a note by ID - returns null if note is private
     * AI cannot access private notes even if it knows the ID
     */
    fun findByIdForAi(notes: List<Note>, noteId: String): Note? {
        val note = notes.find { it.id == noteId }
        if (note != null && isPrivate(note)) {
            Log.w(TAG, "SECURITY: AI attempted to access private note by ID: ${noteId.take(8)}...")
            return null // Note is invisible to AI
        }
        return note?.takeIf { isAiAccessible(it) }
    }

    /**
     * Validate that a note can be processed by AI
     * Returns false for private notes - they cannot be processed
     */
    fun canAiProcess(note: Note): Boolean {
        if (isPrivate(note)) {
            Log.w(TAG, "SECURITY: AI processing blocked for private note: ${note.id.take(8)}...")
            return false
        }
        return true
    }

    /**
     * Security check before any AI operation on a note
     * Throws SecurityException if note is private
     */
    fun requireAiAccess(note: Note, operation: String) {
        if (isPrivate(note)) {
            val message = "SECURITY VIOLATION: AI attempted $operation on private note ${note.id.take(8)}"
            Log.e(TAG, message)
            throw SecurityException(message)
        }
    }

    /**
     * Log a security event when AI tries to access private content
     */
    fun logSecurityEvent(noteId: String, operation: String) {
        Log.w(TAG, "SECURITY EVENT: Blocked AI $operation for note ${noteId.take(8)}...")
    }

    /**
     * Get count of AI-accessible notes (private notes not counted)
     */
    fun getAiAccessibleCount(notes: List<Note>): Int {
        return notes.count { isAiAccessible(it) }
    }

    /**
     * Check if any notes in list are private (for validation)
     */
    fun containsPrivateNotes(notes: List<Note>): Boolean {
        return notes.any { isPrivate(it) }
    }

    /**
     * Sanitize a list to ensure no private notes leak to AI
     * This is a final safety check before any AI operation
     */
    fun sanitizeForAi(notes: List<Note>): List<Note> {
        val sanitized = getAiVisibleNotes(notes)
        if (containsPrivateNotes(notes)) {
            Log.d(TAG, "Sanitized ${notes.size - sanitized.size} private notes from AI access")
        }
        return sanitized
    }

    /**
     * Filter note IDs to only include AI-accessible notes
     * Used to sanitize references before saving to chat history
     *
     * @param noteIds List of note IDs to filter
     * @param allNotes All notes to check against
     * @return Only IDs of notes that are AI-accessible
     */
    fun filterNoteIds(noteIds: List<String>, allNotes: List<Note>): List<String> {
        return noteIds.filter { noteId ->
            val note = allNotes.find { it.id == noteId }
            note != null && isAiAccessible(note)
        }
    }

    /**
     * Check if a note ID refers to a private note
     *
     * @param noteId The note ID to check
     * @param allNotes All notes to search
     * @return true if the note exists and is private
     */
    fun isPrivateNoteId(noteId: String, allNotes: List<Note>): Boolean {
        val note = allNotes.find { it.id == noteId } ?: return false
        return isPrivate(note)
    }

    /**
     * SECURITY NOTICE: Edge Case
     *
     * If a note was public, discussed with AI, and then marked private:
     * - The chat history from BEFORE may contain information about that note
     * - This is a known limitation - past conversations cannot be retroactively sanitized
     * - Recommendation: Users should delete chat history if they make notes private
     * - Future enhancement: Implement chat history scrubbing when privacy status changes
     */
    fun getSecurityNotice(): String {
        return "Chat history from before a note was marked private may contain references to that note."
    }
}
